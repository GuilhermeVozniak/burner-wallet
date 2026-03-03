package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.Bech32;
import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HashUtils;

/**
 * Bitcoin address derivation from BIP39 seeds.
 *
 * Currently supports P2WPKH (native SegWit, bech32) addresses
 * using BIP84 derivation paths.
 *
 * Full pipeline: seed -> BIP32 master -> BIP84 path -> pubkey -> Hash160 -> bech32
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public final class BitcoinAddress {

    private BitcoinAddress() {
        // prevent instantiation
    }

    /**
     * Derive a P2WPKH (native SegWit, bech32) address from a BIP39 seed.
     *
     * Uses BIP84 derivation path: m/84'/coin'/account'/change/index
     *
     * @param seed    64-byte seed from BIP39 mnemonic
     * @param testnet true for testnet (tb1...), false for mainnet (bc1...)
     * @param account account index (typically 0)
     * @param change  true for change addresses, false for receive addresses
     * @param index   address index
     * @return bech32 P2WPKH address string
     * @throws CryptoError if derivation fails
     */
    public static String deriveP2wpkhAddress(byte[] seed, boolean testnet,
                                              int account, boolean change, int index)
            throws CryptoError {
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        String path = Bip44Path.bip84Address(testnet, account, change, index);
        Bip32Key leaf = Bip32Derivation.derivePath(master, path);
        byte[] pubKey = leaf.getPublicKeyBytes();
        return p2wpkhFromPubKey(pubKey, testnet);
    }

    /**
     * Generate a P2WPKH address directly from a 33-byte compressed public key.
     *
     * Steps: Hash160(pubkey) -> bech32 encode with witness version 0.
     *
     * @param compressedPubKey33 33-byte compressed public key
     * @param testnet            true for testnet, false for mainnet
     * @return bech32 P2WPKH address string
     * @throws CryptoError if encoding fails
     */
    public static String p2wpkhFromPubKey(byte[] compressedPubKey33, boolean testnet)
            throws CryptoError {
        byte[] witnessProgram = HashUtils.hash160(compressedPubKey33);
        String hrp = testnet ? NetworkParams.TESTNET_BECH32_HRP : NetworkParams.MAINNET_BECH32_HRP;
        return Bech32.encode(hrp, 0, witnessProgram);
    }
}
