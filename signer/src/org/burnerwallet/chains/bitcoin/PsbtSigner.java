package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.ByteArrayUtils;
import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HashUtils;

/**
 * Signs parsed BIP174 PSBTs for P2WPKH inputs.
 *
 * Signing policy (enforced by {@link #verifyInputs} before any signature
 * is produced):
 * <ul>
 *   <li>Every input must request SIGHASH_ALL. Other sighash types would let
 *       a compromised companion swap the outputs after the user approved
 *       them on the review screen.</li>
 *   <li>Every input must carry both {@code witness_utxo} and
 *       {@code non_witness_utxo}. The previous transaction is parsed, its
 *       txid checked against the input's outpoint, and the amount and
 *       script compared with {@code witness_utxo}. Trusting the amount in
 *       {@code witness_utxo} alone enables the multi-round fee attack
 *       (the signer would commit to whatever amount the companion claims
 *       and display a fee that is not the real one).</li>
 *   <li>Every input must be a P2WPKH output controlled by this wallet
 *       (receive indices 0..{@link #MAX_RECEIVE_INDEX}, change indices
 *       0..{@link #MAX_CHANGE_INDEX}).</li>
 * </ul>
 *
 * For each input the signer computes a BIP143 sighash, signs with ECDSA
 * (RFC 6979, low-S), DER-encodes the signature and stores it as a partial
 * signature.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class PsbtSigner {

    /** Maximum receive address index to search (0..19). */
    public static final int MAX_RECEIVE_INDEX = 19;

    /** Maximum change address index to search (0..9). */
    public static final int MAX_CHANGE_INDEX = 9;

    private PsbtSigner() {
        // prevent instantiation
    }

    /**
     * The set of keys this wallet can sign with, derived once per signing
     * session. Holds private key material: call {@link #destroy()} as soon
     * as signing is done.
     */
    public static final class WalletKeys {
        private final Bip32Key[] keys;
        private final byte[][] hashes;
        private final boolean[] change;
        private final int[] indexes;
        private boolean destroyed;

        WalletKeys(Bip32Key[] keys, byte[][] hashes, boolean[] change, int[] indexes) {
            this.keys = keys;
            this.hashes = hashes;
            this.change = change;
            this.indexes = indexes;
        }

        /**
         * Find the key whose hash160 matches.
         *
         * @return index into the key set, or -1
         */
        int find(byte[] pubKeyHash) {
            for (int i = 0; i < hashes.length; i++) {
                if (ByteArrayUtils.constantTimeEquals(hashes[i], pubKeyHash)) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * Whether a scriptPubKey pays to one of this wallet's P2WPKH keys.
         *
         * @param scriptPubKey the output script
         * @return true if the script belongs to this wallet
         */
        public boolean ownsScript(byte[] scriptPubKey) {
            byte[] hash = extractP2wpkhHash(scriptPubKey);
            return hash != null && find(hash) >= 0;
        }

        /**
         * Flag which outputs of a PSBT pay back to this wallet (change or
         * self-transfer), for the review screen.
         *
         * @param psbt the parsed PSBT
         * @return one flag per output
         */
        public boolean[] ownedOutputs(PsbtTransaction psbt) {
            TxOutput[] outputs = psbt.unsignedTx.outputs;
            boolean[] owned = new boolean[outputs.length];
            for (int i = 0; i < outputs.length; i++) {
                owned[i] = ownsScript(outputs[i].scriptPubKey);
            }
            return owned;
        }

        /**
         * Whether the key at {@code i} is on the change chain.
         */
        boolean isChange(int i) {
            return change[i];
        }

        /**
         * Address index of the key at {@code i}.
         */
        int indexOf(int i) {
            return indexes[i];
        }

        /**
         * Zero all private key material.
         */
        public void destroy() {
            if (destroyed) {
                return;
            }
            destroyed = true;
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] != null) {
                    keys[i].destroy();
                }
                if (hashes[i] != null) {
                    ByteArrayUtils.zeroFill(hashes[i]);
                }
            }
        }

        private void checkUsable() throws CryptoError {
            if (destroyed) {
                throw new CryptoError(CryptoError.ERR_PSBT,
                    "Wallet keys have been destroyed");
            }
        }
    }

    /**
     * Derive every key this wallet signs with: receive chain
     * m/84'/coin'/0'/0/0..MAX_RECEIVE_INDEX and change chain
     * m/84'/coin'/0'/1/0..MAX_CHANGE_INDEX. Intermediate keys are wiped.
     *
     * @param seed    the BIP39 seed bytes (64 bytes)
     * @param testnet true for testnet (coin type 1), false for mainnet
     * @return the wallet's signing keys
     * @throws CryptoError if derivation fails
     */
    public static WalletKeys deriveWalletKeys(byte[] seed, boolean testnet)
            throws CryptoError {
        int total = (MAX_RECEIVE_INDEX + 1) + (MAX_CHANGE_INDEX + 1);
        Bip32Key[] keys = new Bip32Key[total];
        byte[][] hashes = new byte[total][];
        boolean[] change = new boolean[total];
        int[] indexes = new int[total];

        Bip32Key master = null;
        Bip32Key accountKey = null;
        Bip32Key receiveChain = null;
        Bip32Key changeChain = null;
        try {
            master = Bip32Derivation.masterFromSeed(seed);
            String accountPath = Bip44Path.bip84Account(testnet, 0);
            accountKey = Bip32Derivation.derivePath(master, accountPath);
            receiveChain = Bip32Derivation.deriveChild(accountKey, 0);
            changeChain = Bip32Derivation.deriveChild(accountKey, 1);

            int n = 0;
            for (int index = 0; index <= MAX_RECEIVE_INDEX; index++) {
                keys[n] = Bip32Derivation.deriveChild(receiveChain, index);
                hashes[n] = HashUtils.hash160(keys[n].getPublicKeyBytes());
                change[n] = false;
                indexes[n] = index;
                n++;
            }
            for (int index = 0; index <= MAX_CHANGE_INDEX; index++) {
                keys[n] = Bip32Derivation.deriveChild(changeChain, index);
                hashes[n] = HashUtils.hash160(keys[n].getPublicKeyBytes());
                change[n] = true;
                indexes[n] = index;
                n++;
            }
        } finally {
            if (master != null) {
                master.destroy();
            }
            if (accountKey != null) {
                accountKey.destroy();
            }
            if (receiveChain != null) {
                receiveChain.destroy();
            }
            if (changeChain != null) {
                changeChain.destroy();
            }
        }

        return new WalletKeys(keys, hashes, change, indexes);
    }

    /**
     * Enforce the signing policy on every input of the PSBT without
     * producing any signature. Call this before showing the transaction to
     * the user so the displayed amounts are the verified ones.
     *
     * @param psbt the parsed PSBT
     * @throws CryptoError describing the first violation found
     */
    public static void verifyInputs(PsbtTransaction psbt) throws CryptoError {
        if (psbt.inputs.length == 0) {
            throw new CryptoError(CryptoError.ERR_PSBT, "Transaction has no inputs");
        }
        for (int i = 0; i < psbt.inputs.length; i++) {
            PsbtInput input = psbt.inputs[i];
            TxInput txIn = psbt.unsignedTx.inputs[i];

            if (input.sighashType != Bip143Sighash.SIGHASH_ALL) {
                throw new CryptoError(CryptoError.ERR_PSBT,
                    "Input " + i + " requests sighash 0x"
                    + Integer.toHexString(input.sighashType)
                    + "; only SIGHASH_ALL is allowed");
            }

            if (input.witnessUtxoScript == null || input.witnessUtxoValue < 0) {
                throw new CryptoError(CryptoError.ERR_PSBT,
                    "Input " + i + " has no witness_utxo");
            }

            if (extractP2wpkhHash(input.witnessUtxoScript) == null) {
                throw new CryptoError(CryptoError.ERR_PSBT,
                    "Input " + i + " is not P2WPKH (unsupported)");
            }

            if (input.nonWitnessUtxo == null) {
                throw new CryptoError(CryptoError.ERR_PSBT,
                    "Input " + i + " has no non_witness_utxo; cannot verify amount");
            }

            TxData prev = TxSerializer.parseAllowWitness(input.nonWitnessUtxo);
            byte[] txid = TxSerializer.computeTxid(prev);
            if (!ByteArrayUtils.constantTimeEquals(txid, txIn.prevTxHash)) {
                throw new CryptoError(CryptoError.ERR_PSBT,
                    "Input " + i + ": previous transaction does not match outpoint");
            }
            if (txIn.prevIndex < 0 || txIn.prevIndex >= prev.outputs.length) {
                throw new CryptoError(CryptoError.ERR_PSBT,
                    "Input " + i + ": previous output index out of range");
            }
            TxOutput prevOut = prev.outputs[txIn.prevIndex];
            if (prevOut.value != input.witnessUtxoValue) {
                throw new CryptoError(CryptoError.ERR_PSBT,
                    "Input " + i + ": witness_utxo amount does not match previous transaction");
            }
            if (!ByteArrayUtils.constantTimeEquals(prevOut.scriptPubKey,
                    input.witnessUtxoScript)) {
                throw new CryptoError(CryptoError.ERR_PSBT,
                    "Input " + i + ": witness_utxo script does not match previous transaction");
            }
        }
    }

    /**
     * Sign every input of the PSBT with the given wallet keys.
     *
     * Runs {@link #verifyInputs} first. Fails (without signing anything)
     * if any input is not controlled by this wallet.
     *
     * @param psbt the parsed PSBT to sign (modified in place)
     * @param keys the wallet's keys from {@link #deriveWalletKeys}
     * @return the number of inputs signed (equals the input count)
     * @throws CryptoError if the policy is violated or signing fails
     */
    public static int sign(PsbtTransaction psbt, WalletKeys keys) throws CryptoError {
        keys.checkUsable();
        verifyInputs(psbt);

        // Resolve every signing key before producing any signature so a
        // foreign input aborts the whole operation.
        int[] keyIndex = new int[psbt.inputs.length];
        for (int i = 0; i < psbt.inputs.length; i++) {
            byte[] hash = extractP2wpkhHash(psbt.inputs[i].witnessUtxoScript);
            keyIndex[i] = keys.find(hash);
            if (keyIndex[i] < 0) {
                throw new CryptoError(CryptoError.ERR_PSBT,
                    "Input " + i + " is not controlled by this wallet"
                    + " (receive 0-" + MAX_RECEIVE_INDEX
                    + ", change 0-" + MAX_CHANGE_INDEX + ")");
            }
        }

        for (int i = 0; i < psbt.inputs.length; i++) {
            PsbtInput input = psbt.inputs[i];
            Bip32Key signingKey = keys.keys[keyIndex[i]];
            byte[] targetPubKeyHash = keys.hashes[keyIndex[i]];

            byte[] pubKey = signingKey.getPublicKeyBytes();
            byte[] privKey = signingKey.getPrivateKeyBytes();
            byte[] rawSig = null;
            try {
                // Build scriptCode for P2WPKH
                byte[] scriptCode = Bip143Sighash.p2wpkhScriptCode(targetPubKeyHash);

                // Compute BIP143 sighash (SIGHASH_ALL enforced above)
                byte[] sighash = Bip143Sighash.computeSighash(
                    psbt.unsignedTx, i, scriptCode,
                    input.witnessUtxoValue, Bip143Sighash.SIGHASH_ALL);

                // Sign: produces 64-byte r||s
                rawSig = Secp256k1.sign(sighash, privKey);
            } finally {
                // Wipe private key material
                ByteArrayUtils.zeroFill(privKey);
            }

            // DER encode
            byte[] derSig = Secp256k1.serializeDER(rawSig);

            // Append sighash type byte
            byte[] sigWithHashType = new byte[derSig.length + 1];
            System.arraycopy(derSig, 0, sigWithHashType, 0, derSig.length);
            sigWithHashType[derSig.length] = (byte) Bip143Sighash.SIGHASH_ALL;

            // Preserve any pre-existing partial signature for a different key
            if (input.partialSigKey != null
                    && !ByteArrayUtils.constantTimeEquals(input.partialSigKey, pubKey)) {
                byte[] oldKey = new byte[1 + input.partialSigKey.length];
                oldKey[0] = 0x02;
                System.arraycopy(input.partialSigKey, 0, oldKey, 1,
                    input.partialSigKey.length);
                input.unknown.addElement(new byte[][] { oldKey, input.partialSigValue });
            }

            // Store partial signature
            input.partialSigKey = pubKey;
            input.partialSigValue = sigWithHashType;
        }

        return psbt.inputs.length;
    }

    /**
     * Convenience: derive the wallet keys, sign, and wipe the keys.
     *
     * @param psbt    the parsed PSBT to sign (modified in place)
     * @param seed    the BIP39 seed bytes (64 bytes)
     * @param testnet true for testnet (coin type 1), false for mainnet
     * @return the number of inputs signed
     * @throws CryptoError if the policy is violated or signing fails
     */
    public static int sign(PsbtTransaction psbt, byte[] seed, boolean testnet)
            throws CryptoError {
        WalletKeys keys = deriveWalletKeys(seed, testnet);
        try {
            return sign(psbt, keys);
        } finally {
            keys.destroy();
        }
    }

    /**
     * Extract the 20-byte pubkey hash from a P2WPKH scriptPubKey.
     *
     * P2WPKH format: OP_0 OP_PUSH20 &lt;20-byte-hash&gt;
     * Encoded as: 0x00 0x14 &lt;20 bytes&gt; (total 22 bytes)
     *
     * @param script the scriptPubKey bytes
     * @return 20-byte pubkey hash, or null if not P2WPKH
     */
    static byte[] extractP2wpkhHash(byte[] script) {
        if (script == null || script.length != 22) {
            return null;
        }
        if ((script[0] & 0xFF) != 0x00 || (script[1] & 0xFF) != 0x14) {
            return null;
        }
        return ByteArrayUtils.copyOfRange(script, 2, 22);
    }
}
