package org.burnerwallet.core;

import org.burnerwallet.core.crypto.Hmac;
import org.burnerwallet.core.crypto.Pbkdf2;
import org.burnerwallet.core.crypto.Ripemd160;
import org.burnerwallet.core.crypto.Sha256;
import org.burnerwallet.core.crypto.Sha512;

/**
 * Cryptographic hash utilities backed by the in-house CLDC-safe
 * implementations in {@code org.burnerwallet.core.crypto}.
 *
 * Provides SHA-256, RIPEMD-160, HMAC-SHA256/512, and PBKDF2-HMAC-SHA512
 * for Bitcoin key derivation (BIP32, BIP39, address generation) and
 * wallet storage authentication.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class HashUtils {

    /**
     * Compute SHA-256 hash.
     *
     * @param input data to hash
     * @return 32-byte hash
     */
    public static byte[] sha256(byte[] input) {
        return Sha256.hash(input);
    }

    /**
     * Compute double SHA-256 hash: SHA-256(SHA-256(input)).
     * Used by Bitcoin for transaction hashes and block headers.
     *
     * @param input data to hash
     * @return 32-byte hash
     */
    public static byte[] doubleSha256(byte[] input) {
        return sha256(sha256(input));
    }

    /**
     * Compute RIPEMD-160 hash.
     *
     * @param input data to hash
     * @return 20-byte hash
     */
    public static byte[] ripemd160(byte[] input) {
        return Ripemd160.hash(input);
    }

    /**
     * Compute Hash160: RIPEMD-160(SHA-256(input)).
     * Used by Bitcoin for public-key-to-address hashing.
     *
     * @param input data to hash
     * @return 20-byte hash
     */
    public static byte[] hash160(byte[] input) {
        return ripemd160(sha256(input));
    }

    /**
     * Compute HMAC-SHA256.
     * Used to authenticate the encrypted wallet blob at rest.
     *
     * @param key  HMAC key
     * @param data data to authenticate
     * @return 32-byte MAC
     */
    public static byte[] hmacSha256(byte[] key, byte[] data) {
        Hmac hmac = new Hmac(new Sha256(), key);
        hmac.update(data, 0, data.length);
        byte[] out = new byte[32];
        hmac.doFinal(out, 0);
        hmac.destroy();
        return out;
    }

    /**
     * Compute HMAC-SHA512.
     * Used by BIP32 for master key derivation and child key derivation.
     *
     * @param key  HMAC key
     * @param data data to authenticate
     * @return 64-byte MAC
     */
    public static byte[] hmacSha512(byte[] key, byte[] data) {
        Hmac hmac = new Hmac(new Sha512(), key);
        hmac.update(data, 0, data.length);
        byte[] out = new byte[64];
        hmac.doFinal(out, 0);
        hmac.destroy();
        return out;
    }

    /**
     * Derive a key using PBKDF2 with HMAC-SHA512.
     * Used by BIP39 for mnemonic-to-seed derivation and by the wallet
     * store for PIN stretching.
     *
     * @param password         password bytes (typically UTF-8 encoded mnemonic)
     * @param salt             salt bytes (typically "mnemonic" + optional passphrase)
     * @param iterations       number of iterations (2048 for BIP39)
     * @param derivedKeyLength desired output length in bytes (64 for BIP39)
     * @return derived key bytes
     */
    public static byte[] pbkdf2HmacSha512(byte[] password, byte[] salt,
                                           int iterations, int derivedKeyLength) {
        return Pbkdf2.hmacSha512(password, salt, iterations, derivedKeyLength);
    }
}
