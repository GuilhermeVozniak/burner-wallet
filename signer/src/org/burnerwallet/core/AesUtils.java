package org.burnerwallet.core;

import org.bouncycastle.crypto.BufferedBlockCipher;
import org.bouncycastle.crypto.engines.AESLightEngine;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.paddings.PKCS7Padding;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

/**
 * AES-256-CBC encryption and decryption using Bouncy Castle lightweight API.
 *
 * Uses AESLightEngine (smallest footprint, no static tables) wrapped in
 * CBCBlockCipher with PKCS7 padding.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class AesUtils {

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
        try {
            BufferedBlockCipher cipher = createCipher(true, key, iv);
            byte[] output = new byte[cipher.getOutputSize(plaintext.length)];
            int len = cipher.processBytes(plaintext, 0, plaintext.length, output, 0);
            len += cipher.doFinal(output, len);
            // Trim if getOutputSize over-estimated
            if (len < output.length) {
                return ByteArrayUtils.copyOf(output, len);
            }
            return output;
        } catch (Exception e) {
            throw new CryptoError(CryptoError.ERR_ENCRYPTION,
                "AES encryption failed: " + e.getMessage());
        }
    }

    /**
     * Decrypt ciphertext using AES-256-CBC with PKCS7 padding.
     *
     * @param ciphertext data to decrypt (must be a multiple of 16 bytes)
     * @param key        32-byte AES-256 key
     * @param iv         16-byte initialization vector
     * @return plaintext
     * @throws CryptoError if decryption fails (wrong key, corrupted data, bad padding)
     */
    public static byte[] decrypt(byte[] ciphertext, byte[] key, byte[] iv)
            throws CryptoError {
        try {
            BufferedBlockCipher cipher = createCipher(false, key, iv);
            byte[] output = new byte[cipher.getOutputSize(ciphertext.length)];
            int len = cipher.processBytes(ciphertext, 0, ciphertext.length, output, 0);
            len += cipher.doFinal(output, len);
            // Trim if getOutputSize over-estimated
            if (len < output.length) {
                return ByteArrayUtils.copyOf(output, len);
            }
            return output;
        } catch (Exception e) {
            throw new CryptoError(CryptoError.ERR_DECRYPTION,
                "AES decryption failed: " + e.getMessage());
        }
    }

    /**
     * Create and initialize a PaddedBufferedBlockCipher for AES-CBC with PKCS7.
     *
     * @param encrypt true for encryption, false for decryption
     * @param key     32-byte AES-256 key
     * @param iv      16-byte initialization vector
     * @return initialized cipher ready for processBytes/doFinal
     */
    private static BufferedBlockCipher createCipher(boolean encrypt,
                                                     byte[] key, byte[] iv) {
        PaddedBufferedBlockCipher cipher = new PaddedBufferedBlockCipher(
            new CBCBlockCipher(new AESLightEngine()),
            new PKCS7Padding());
        cipher.init(encrypt, new ParametersWithIV(new KeyParameter(key), iv));
        return cipher;
    }
}
