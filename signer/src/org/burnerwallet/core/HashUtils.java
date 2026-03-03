package org.burnerwallet.core;

import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * Cryptographic hash utilities using Bouncy Castle lightweight API.
 *
 * Provides SHA-256, RIPEMD-160, HMAC-SHA512, and PBKDF2-HMAC-SHA512
 * for Bitcoin key derivation (BIP32, BIP39, address generation).
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
        SHA256Digest digest = new SHA256Digest();
        digest.update(input, 0, input.length);
        byte[] out = new byte[32];
        digest.doFinal(out, 0);
        return out;
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
        RIPEMD160Digest digest = new RIPEMD160Digest();
        digest.update(input, 0, input.length);
        byte[] out = new byte[20];
        digest.doFinal(out, 0);
        return out;
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
     * Compute HMAC-SHA512.
     * Used by BIP32 for master key derivation and child key derivation.
     *
     * @param key  HMAC key
     * @param data data to authenticate
     * @return 64-byte MAC
     */
    public static byte[] hmacSha512(byte[] key, byte[] data) {
        HMac hmac = new HMac(new SHA512Digest());
        hmac.init(new KeyParameter(key));
        hmac.update(data, 0, data.length);
        byte[] out = new byte[64];
        hmac.doFinal(out, 0);
        return out;
    }

    /**
     * Derive a key using PBKDF2 with HMAC-SHA512.
     * Used by BIP39 for mnemonic-to-seed derivation.
     *
     * @param password         password bytes (typically UTF-8 encoded mnemonic)
     * @param salt             salt bytes (typically "mnemonic" + optional passphrase)
     * @param iterations       number of iterations (2048 for BIP39)
     * @param derivedKeyLength desired output length in bytes (64 for BIP39)
     * @return derived key bytes
     */
    public static byte[] pbkdf2HmacSha512(byte[] password, byte[] salt,
                                           int iterations, int derivedKeyLength) {
        PKCS5S2ParametersGenerator gen = new PKCS5S2ParametersGenerator(new SHA512Digest());
        gen.init(password, salt, iterations);
        KeyParameter params = (KeyParameter) gen.generateDerivedMacParameters(derivedKeyLength * 8);
        return params.getKey();
    }
}
