package org.burnerwallet.core;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JUnit 4 tests for AesUtils — AES-256-CBC encrypt/decrypt
 * using Bouncy Castle AESLightEngine with PKCS7 padding.
 */
public class AesUtilsTest {

    // 32-byte AES-256 key (deterministic for tests)
    private static final byte[] TEST_KEY = new byte[] {
        (byte) 0x00, (byte) 0x01, (byte) 0x02, (byte) 0x03,
        (byte) 0x04, (byte) 0x05, (byte) 0x06, (byte) 0x07,
        (byte) 0x08, (byte) 0x09, (byte) 0x0A, (byte) 0x0B,
        (byte) 0x0C, (byte) 0x0D, (byte) 0x0E, (byte) 0x0F,
        (byte) 0x10, (byte) 0x11, (byte) 0x12, (byte) 0x13,
        (byte) 0x14, (byte) 0x15, (byte) 0x16, (byte) 0x17,
        (byte) 0x18, (byte) 0x19, (byte) 0x1A, (byte) 0x1B,
        (byte) 0x1C, (byte) 0x1D, (byte) 0x1E, (byte) 0x1F
    };

    // 16-byte IV
    private static final byte[] TEST_IV = new byte[] {
        (byte) 0xA0, (byte) 0xA1, (byte) 0xA2, (byte) 0xA3,
        (byte) 0xA4, (byte) 0xA5, (byte) 0xA6, (byte) 0xA7,
        (byte) 0xA8, (byte) 0xA9, (byte) 0xAA, (byte) 0xAB,
        (byte) 0xAC, (byte) 0xAD, (byte) 0xAE, (byte) 0xAF
    };

    // ---- Round-trip tests ----

    @Test
    public void encryptDecryptRoundTrip() throws CryptoError {
        byte[] plaintext = "Hello, Burner Wallet!".getBytes();
        byte[] ciphertext = AesUtils.encrypt(plaintext, TEST_KEY, TEST_IV);
        byte[] decrypted = AesUtils.decrypt(ciphertext, TEST_KEY, TEST_IV);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void encryptDecryptEmptyPlaintext() throws CryptoError {
        byte[] plaintext = new byte[0];
        byte[] ciphertext = AesUtils.encrypt(plaintext, TEST_KEY, TEST_IV);
        // Empty plaintext with PKCS7 produces exactly one padding block (16 bytes)
        assertEquals(16, ciphertext.length);
        byte[] decrypted = AesUtils.decrypt(ciphertext, TEST_KEY, TEST_IV);
        assertEquals(0, decrypted.length);
    }

    @Test
    public void encryptDecryptExactBlockSize() throws CryptoError {
        // 16 bytes = exact block size; PKCS7 must add a full padding block
        byte[] plaintext = new byte[16];
        for (int i = 0; i < 16; i++) {
            plaintext[i] = (byte) i;
        }
        byte[] ciphertext = AesUtils.encrypt(plaintext, TEST_KEY, TEST_IV);
        // 16-byte input + 16-byte padding block = 32-byte ciphertext
        assertEquals(32, ciphertext.length);
        byte[] decrypted = AesUtils.decrypt(ciphertext, TEST_KEY, TEST_IV);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void encryptDecrypt64ByteSeed() throws CryptoError {
        // Simulate a 64-byte BIP39 seed round-trip
        byte[] seed = new byte[64];
        for (int i = 0; i < 64; i++) {
            seed[i] = (byte) (i * 3 + 7);
        }
        byte[] ciphertext = AesUtils.encrypt(seed, TEST_KEY, TEST_IV);
        byte[] decrypted = AesUtils.decrypt(ciphertext, TEST_KEY, TEST_IV);
        assertArrayEquals(seed, decrypted);
    }

    // ---- Failure and edge-case tests ----

    @Test
    public void wrongKeyFailsDecryption() throws CryptoError {
        byte[] plaintext = "secret data".getBytes();
        byte[] ciphertext = AesUtils.encrypt(plaintext, TEST_KEY, TEST_IV);

        // Use a different key for decryption
        byte[] wrongKey = new byte[32];
        for (int i = 0; i < 32; i++) {
            wrongKey[i] = (byte) (TEST_KEY[i] ^ 0xFF);
        }

        // Decryption with wrong key should either throw or produce wrong output
        boolean threwException = false;
        byte[] decrypted = null;
        try {
            decrypted = AesUtils.decrypt(ciphertext, wrongKey, TEST_IV);
        } catch (CryptoError e) {
            threwException = true;
            assertEquals(CryptoError.ERR_DECRYPTION, e.getErrorCode());
        }

        if (!threwException) {
            // If no exception, the output must differ from the original plaintext
            assertFalse("Wrong key must not produce original plaintext",
                ByteArrayUtils.constantTimeEquals(plaintext, decrypted));
        }
    }

    @Test
    public void wrongIvProducesDifferentOutput() throws CryptoError {
        byte[] plaintext = "deterministic test".getBytes();
        byte[] ciphertext1 = AesUtils.encrypt(plaintext, TEST_KEY, TEST_IV);

        // Encrypt with a different IV
        byte[] differentIv = new byte[16];
        for (int i = 0; i < 16; i++) {
            differentIv[i] = (byte) (TEST_IV[i] ^ 0xFF);
        }
        byte[] ciphertext2 = AesUtils.encrypt(plaintext, TEST_KEY, differentIv);

        // Same plaintext, same key, different IV → different ciphertext
        assertFalse("Different IV must produce different ciphertext",
            ByteArrayUtils.constantTimeEquals(ciphertext1, ciphertext2));
    }

    @Test
    public void ciphertextIsMultipleOfBlockSize() throws CryptoError {
        // Every plaintext size from 1 to 80 must produce block-aligned ciphertext
        for (int size = 1; size <= 80; size++) {
            byte[] plaintext = new byte[size];
            for (int i = 0; i < size; i++) {
                plaintext[i] = (byte) (i & 0xFF);
            }
            byte[] ciphertext = AesUtils.encrypt(plaintext, TEST_KEY, TEST_IV);
            assertEquals("Ciphertext for size " + size + " must be block-aligned",
                0, ciphertext.length % 16);
        }
    }

    @Test
    public void seedWithPassphraseRoundTrip() throws CryptoError {
        // Simulate storing seed(64) + len(2) + passphrase bytes
        byte[] seed = new byte[64];
        for (int i = 0; i < 64; i++) {
            seed[i] = (byte) (i + 0x80);
        }
        byte[] passphrase = "my secret passphrase".getBytes();
        int passphraseLen = passphrase.length;

        // Build payload: seed(64) + big-endian length(2) + passphrase
        byte[] lenBytes = new byte[] {
            (byte) ((passphraseLen >> 8) & 0xFF),
            (byte) (passphraseLen & 0xFF)
        };
        byte[] payload = ByteArrayUtils.concat(
            ByteArrayUtils.concat(seed, lenBytes), passphrase);

        byte[] ciphertext = AesUtils.encrypt(payload, TEST_KEY, TEST_IV);
        byte[] decrypted = AesUtils.decrypt(ciphertext, TEST_KEY, TEST_IV);
        assertArrayEquals(payload, decrypted);

        // Verify we can parse the structure back
        byte[] recoveredSeed = ByteArrayUtils.copyOfRange(decrypted, 0, 64);
        assertArrayEquals(seed, recoveredSeed);

        int recoveredLen = ((decrypted[64] & 0xFF) << 8) | (decrypted[65] & 0xFF);
        assertEquals(passphraseLen, recoveredLen);

        byte[] recoveredPassphrase = ByteArrayUtils.copyOfRange(
            decrypted, 66, 66 + recoveredLen);
        assertArrayEquals(passphrase, recoveredPassphrase);
    }
}
