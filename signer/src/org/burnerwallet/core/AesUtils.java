package org.burnerwallet.core;

import org.burnerwallet.core.crypto.Aes;

/**
 * AES-256-CBC encryption and decryption with PKCS7 padding, backed by the
 * in-house CLDC-safe {@link Aes} block cipher.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class AesUtils {

    private static final int BLOCK = Aes.BLOCK_SIZE;

    /**
     * Encrypt plaintext using AES-256-CBC with PKCS7 padding.
     *
     * @param plaintext data to encrypt
     * @param key       32-byte AES-256 key
     * @param iv        16-byte initialization vector
     * @return ciphertext (always a multiple of 16 bytes)
     * @throws CryptoError if encryption fails
     */
    public static byte[] encrypt(byte[] plaintext, byte[] key, byte[] iv)
            throws CryptoError {
        checkKeyAndIv(key, iv, CryptoError.ERR_ENCRYPTION);
        if (plaintext == null) {
            throw new CryptoError(CryptoError.ERR_ENCRYPTION, "AES encryption failed: no input");
        }
        Aes aes = new Aes(key);
        try {
            int padLen = BLOCK - (plaintext.length % BLOCK);
            byte[] out = new byte[plaintext.length + padLen];
            byte[] block = new byte[BLOCK];
            byte[] prev = ByteArrayUtils.copyOf(iv, BLOCK);
            for (int off = 0; off < out.length; off += BLOCK) {
                for (int i = 0; i < BLOCK; i++) {
                    int idx = off + i;
                    byte pb = idx < plaintext.length ? plaintext[idx] : (byte) padLen;
                    block[i] = (byte) (pb ^ prev[i]);
                }
                aes.encryptBlock(block, 0, out, off);
                System.arraycopy(out, off, prev, 0, BLOCK);
            }
            ByteArrayUtils.zeroFill(block);
            return out;
        } finally {
            aes.destroy();
        }
    }

    /**
     * Decrypt ciphertext using AES-256-CBC with PKCS7 padding.
     *
     * @param ciphertext data to decrypt (must be a non-empty multiple of 16 bytes)
     * @param key        32-byte AES-256 key
     * @param iv         16-byte initialization vector
     * @return plaintext
     * @throws CryptoError if decryption fails (wrong key, corrupted data, bad padding)
     */
    public static byte[] decrypt(byte[] ciphertext, byte[] key, byte[] iv)
            throws CryptoError {
        checkKeyAndIv(key, iv, CryptoError.ERR_DECRYPTION);
        if (ciphertext == null || ciphertext.length == 0 || ciphertext.length % BLOCK != 0) {
            throw new CryptoError(CryptoError.ERR_DECRYPTION,
                "AES decryption failed: ciphertext length is not a multiple of 16");
        }
        Aes aes = new Aes(key);
        byte[] padded = new byte[ciphertext.length];
        try {
            byte[] prev = iv;
            for (int off = 0; off < ciphertext.length; off += BLOCK) {
                aes.decryptBlock(ciphertext, off, padded, off);
                for (int i = 0; i < BLOCK; i++) {
                    padded[off + i] ^= prev[i];
                }
                prev = ByteArrayUtils.copyOfRange(ciphertext, off, off + BLOCK);
            }
            int padLen = padded[padded.length - 1] & 0xff;
            boolean valid = padLen >= 1 && padLen <= BLOCK;
            if (valid) {
                // Constant-time-ish check over all padding bytes
                int diff = 0;
                for (int i = 0; i < padLen; i++) {
                    diff |= (padded[padded.length - 1 - i] & 0xff) ^ padLen;
                }
                valid = diff == 0;
            }
            if (!valid) {
                throw new CryptoError(CryptoError.ERR_DECRYPTION,
                    "AES decryption failed: pad block corrupted");
            }
            return ByteArrayUtils.copyOf(padded, padded.length - padLen);
        } finally {
            ByteArrayUtils.zeroFill(padded);
            aes.destroy();
        }
    }

    private static void checkKeyAndIv(byte[] key, byte[] iv, int errorCode) throws CryptoError {
        if (key == null || key.length != 32) {
            throw new CryptoError(errorCode, "AES key must be 32 bytes");
        }
        if (iv == null || iv.length != BLOCK) {
            throw new CryptoError(errorCode, "AES IV must be 16 bytes");
        }
    }
}
