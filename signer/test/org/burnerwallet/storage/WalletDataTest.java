package org.burnerwallet.storage;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JUnit 4 tests for WalletData blob serialization.
 */
public class WalletDataTest {

    // ---- Seed blob tests ----

    @Test
    public void serializeDeserializeSeedBlob() {
        byte[] salt = new byte[16];
        byte[] iv = new byte[16];
        byte[] ciphertext = new byte[80];
        for (int i = 0; i < 16; i++) {
            salt[i] = (byte) (i + 1);
            iv[i] = (byte) (i + 0x10);
        }
        for (int i = 0; i < 80; i++) {
            ciphertext[i] = (byte) (i + 0x20);
        }
        int iterations = 100000;

        byte[] blob = WalletData.serializeSeedBlob(salt, iv, iterations, ciphertext);
        assertEquals(WalletData.SEED_BLOB_SIZE, blob.length);

        assertArrayEquals(salt, WalletData.getSalt(blob));
        assertArrayEquals(iv, WalletData.getIv(blob));
        assertEquals(iterations, WalletData.getIterations(blob));
        assertArrayEquals(ciphertext, WalletData.getCiphertext(blob));
    }

    @Test
    public void iterationsEncodedBigEndian() {
        byte[] salt = new byte[16];
        byte[] iv = new byte[16];
        byte[] ciphertext = new byte[80];
        int iterations = 0x01020304;

        byte[] blob = WalletData.serializeSeedBlob(salt, iv, iterations, ciphertext);
        // Big-endian at offset 32: 0x01, 0x02, 0x03, 0x04
        assertEquals((byte) 0x01, blob[32]);
        assertEquals((byte) 0x02, blob[33]);
        assertEquals((byte) 0x03, blob[34]);
        assertEquals((byte) 0x04, blob[35]);

        assertEquals(iterations, WalletData.getIterations(blob));
    }

    // ---- Config tests ----

    @Test
    public void serializeDeserializeConfig() {
        byte[] config = WalletData.serializeConfig(false, true, 42);
        assertEquals(WalletData.CONFIG_SIZE, config.length);

        assertFalse(WalletData.getNetworkTestnet(config));
        assertTrue(WalletData.getHasPassphrase(config));
        assertEquals(42, WalletData.getAddressIndex(config));
    }

    @Test
    public void configMainnetNoPassphrase() {
        byte[] config = WalletData.serializeConfig(false, false, 0);
        assertFalse(WalletData.getNetworkTestnet(config));
        assertFalse(WalletData.getHasPassphrase(config));
        assertEquals(0, WalletData.getAddressIndex(config));
    }

    @Test
    public void configTestnetWithPassphrase() {
        byte[] config = WalletData.serializeConfig(true, true, 999);
        assertTrue(WalletData.getNetworkTestnet(config));
        assertTrue(WalletData.getHasPassphrase(config));
        assertEquals(999, WalletData.getAddressIndex(config));
    }

    // ---- Plaintext tests ----

    @Test
    public void serializeDeserializePlaintext() {
        byte[] seed = new byte[64];
        for (int i = 0; i < 64; i++) {
            seed[i] = (byte) i;
        }
        String passphrase = "mypass";

        byte[] plaintext = WalletData.buildPlaintext(seed, passphrase);
        // 64 (seed) + 2 (length) + 6 ("mypass" UTF-8) = 72
        assertEquals(72, plaintext.length);

        assertArrayEquals(seed, WalletData.extractSeed(plaintext));
        assertEquals(passphrase, WalletData.extractPassphrase(plaintext));
    }

    @Test
    public void plaintextWithEmptyPassphrase() {
        byte[] seed = new byte[64];
        for (int i = 0; i < 64; i++) {
            seed[i] = (byte) (0xFF - i);
        }

        byte[] plaintext = WalletData.buildPlaintext(seed, "");
        // 64 (seed) + 2 (length=0) = 66
        assertEquals(66, plaintext.length);

        assertArrayEquals(seed, WalletData.extractSeed(plaintext));
        assertEquals("", WalletData.extractPassphrase(plaintext));
    }

    @Test
    public void plaintextWithLongPassphrase() {
        byte[] seed = new byte[64];
        for (int i = 0; i < 64; i++) {
            seed[i] = (byte) (i * 3);
        }
        String passphrase = "this is a longer passphrase with spaces and symbols !@#$%";

        byte[] plaintext = WalletData.buildPlaintext(seed, passphrase);
        // 64 + 2 + passphrase.length()
        assertEquals(64 + 2 + passphrase.length(), plaintext.length);

        assertArrayEquals(seed, WalletData.extractSeed(plaintext));
        assertEquals(passphrase, WalletData.extractPassphrase(plaintext));
    }
}
