package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.ByteArrayUtils;
import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HashUtils;

/**
 * Signs parsed BIP174 PSBTs for P2WPKH inputs.
 *
 * For each input with a witness UTXO, the signer:
 * 1. Extracts the pubkey hash from the witnessUtxoScript
 * 2. Searches BIP84 derivation paths to find the matching key
 * 3. Computes a BIP143 sighash
 * 4. Signs with ECDSA (RFC 6979, low-S)
 * 5. DER-encodes the signature and stores it as a partial sig
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class PsbtSigner {

    /** Maximum receive address index to search (0..19). */
    private static final int MAX_RECEIVE_INDEX = 19;

    /** Maximum change address index to search (0..9). */
    private static final int MAX_CHANGE_INDEX = 9;

    private PsbtSigner() {
        // prevent instantiation
    }

    /**
     * Sign all signable P2WPKH inputs in the PSBT.
     *
     * For each input that has a witnessUtxo, derives the signing key
     * by scanning BIP84 paths, computes the BIP143 sighash, and
     * produces an ECDSA partial signature.
     *
     * @param psbt    the parsed PSBT to sign (modified in place)
     * @param seed    the BIP39 seed bytes (64 bytes)
     * @param testnet true for testnet (coin type 1), false for mainnet (coin type 0)
     * @throws CryptoError if signing fails
     */
    public static void sign(PsbtTransaction psbt, byte[] seed, boolean testnet)
            throws CryptoError {

        Bip32Key master = Bip32Derivation.masterFromSeed(seed);

        for (int i = 0; i < psbt.inputs.length; i++) {
            PsbtInput input = psbt.inputs[i];

            // Only sign inputs that have witness UTXO data
            if (input.witnessUtxoScript == null) {
                continue;
            }

            // Extract pubkey hash from P2WPKH script: 0x0014<20-byte-hash>
            byte[] targetPubKeyHash = extractP2wpkhHash(input.witnessUtxoScript);
            if (targetPubKeyHash == null) {
                continue; // Not a P2WPKH script, skip
            }

            // Find the matching signing key
            Bip32Key signingKey = findSigningKey(master, testnet, targetPubKeyHash);
            if (signingKey == null) {
                continue; // No matching key found in our wallet
            }

            byte[] pubKey = signingKey.getPublicKeyBytes();
            byte[] privKey = signingKey.getPrivateKeyBytes();

            // Build scriptCode for P2WPKH
            byte[] scriptCode = Bip143Sighash.p2wpkhScriptCode(targetPubKeyHash);

            // Determine sighash type
            int sighashType = input.sighashType;

            // Compute BIP143 sighash
            byte[] sighash = Bip143Sighash.computeSighash(
                psbt.unsignedTx, i, scriptCode,
                input.witnessUtxoValue, sighashType);

            // Sign: produces 64-byte r||s
            byte[] rawSig = Secp256k1.sign(sighash, privKey);

            // DER encode
            byte[] derSig = Secp256k1.serializeDER(rawSig);

            // Append sighash type byte
            byte[] sigWithHashType = new byte[derSig.length + 1];
            System.arraycopy(derSig, 0, sigWithHashType, 0, derSig.length);
            sigWithHashType[derSig.length] = (byte) (sighashType & 0xFF);

            // Store partial signature
            input.partialSigKey = pubKey;
            input.partialSigValue = sigWithHashType;

            // Wipe private key material
            ByteArrayUtils.zeroFill(privKey);
        }
    }

    /**
     * Extract the 20-byte pubkey hash from a P2WPKH scriptPubKey.
     *
     * P2WPKH format: OP_0 OP_PUSH20 <20-byte-hash>
     * Encoded as: 0x00 0x14 <20 bytes> (total 22 bytes)
     *
     * @param script the scriptPubKey bytes
     * @return 20-byte pubkey hash, or null if not P2WPKH
     */
    private static byte[] extractP2wpkhHash(byte[] script) {
        if (script == null || script.length != 22) {
            return null;
        }
        if ((script[0] & 0xFF) != 0x00 || (script[1] & 0xFF) != 0x14) {
            return null;
        }
        return ByteArrayUtils.copyOfRange(script, 2, 22);
    }

    /**
     * Search BIP84 derivation paths to find a key matching the given pubkey hash.
     *
     * Tries receive addresses (m/84'/coin'/0'/0/0 through 0/19) and
     * change addresses (m/84'/coin'/0'/1/0 through 1/9).
     *
     * @param master           the master key derived from seed
     * @param testnet          true for testnet
     * @param targetPubKeyHash the 20-byte hash to match
     * @return the matching Bip32Key, or null if no match found
     * @throws CryptoError if derivation fails
     */
    private static Bip32Key findSigningKey(Bip32Key master, boolean testnet,
            byte[] targetPubKeyHash) throws CryptoError {

        // Derive the account key once: m/84'/coin'/0'
        String accountPath = Bip44Path.bip84Account(testnet, 0);
        Bip32Key accountKey = Bip32Derivation.derivePath(master, accountPath);

        // Search receive chain (external): m/84'/coin'/0'/0/index
        Bip32Key receiveChain = Bip32Derivation.deriveChild(accountKey, 0);
        for (int index = 0; index <= MAX_RECEIVE_INDEX; index++) {
            Bip32Key candidate = Bip32Derivation.deriveChild(receiveChain, index);
            byte[] pubKey = candidate.getPublicKeyBytes();
            byte[] pubKeyHash = HashUtils.hash160(pubKey);
            if (ByteArrayUtils.constantTimeEquals(pubKeyHash, targetPubKeyHash)) {
                return candidate;
            }
        }

        // Search change chain (internal): m/84'/coin'/0'/1/index
        Bip32Key changeChain = Bip32Derivation.deriveChild(accountKey, 1);
        for (int index = 0; index <= MAX_CHANGE_INDEX; index++) {
            Bip32Key candidate = Bip32Derivation.deriveChild(changeChain, index);
            byte[] pubKey = candidate.getPublicKeyBytes();
            byte[] pubKeyHash = HashUtils.hash160(pubKey);
            if (ByteArrayUtils.constantTimeEquals(pubKeyHash, targetPubKeyHash)) {
                return candidate;
            }
        }

        return null;
    }
}
