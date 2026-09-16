package org.burnerwallet.storage;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JUnit 4 tests for WalletData blob serialization (seed blob v2 with MAC).
 */
public class WalletDataTest {

    private static byte[] filled(int len, int start) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) {
            b[i] = (byte) (i + start);
        }
        return b;
    }

    // ---- Seed blob tests ----

    @Test
    public void serializeDeserializeSeedBlob() {
        byte[] salt = filled(16, 1);
        byte[] iv = filled(16, 0x10);
        byte[] ciphertext = filled(80, 0x20);
        byte[] mac = filled(32, 0x80);
        int iterations = 100000;

        byte[] blob = WalletData.serializeSeedBlob(salt, iv, iterations, ciphertext, mac);
        assertEquals(WalletData.HEADER_SIZE + 80 + WalletData.MAC_SIZE, blob.length);
        assertEquals(WalletData.BLOB_VERSION, blob[0] & 0xFF);

        assertArrayEquals(salt, WalletData.getSalt(blob));
        assertArrayEquals(iv, WalletData.getIv(blob));
        assertEquals(iterations, WalletData.getIterations(blob));
        assertArrayEquals(ciphertext, WalletData.getCiphertext(blob));
        assertArrayEquals(mac, WalletData.getMac(blob));
        assertArrayEquals(
            WalletData.serializeAuthenticatedRegion(salt, iv, iterations, ciphertext),
            WalletData.getAuthenticatedRegion(blob));
        assertTrue(WalletData.isSeedBlobWellFormed(blob));
    }

    @Test
    public void iterationsEncodedBigEndian() {
        byte[] blob = WalletData.serializeSeedBlob(new byte[16], new byte[16],
            0x01020304, new byte[80], new byte[32]);
        // Big-endian at offset 33: 0x01, 0x02, 0x03, 0x04
        assertEquals((byte) 0x01, blob[33]);
        assertEquals((byte) 0x02, blob[34]);
        assertEquals((byte) 0x03, blob[35]);
        assertEquals((byte) 0x04, blob[36]);
        assertEquals(0x01020304, WalletData.getIterations(blob));
    }

    @Test
    public void wellFormedRejectsBadBlobs() {
        byte[] good = WalletData.serializeSeedBlob(new byte[16], new byte[16],
            5000, new byte[80], new byte[32]);
        assertTrue(WalletData.isSeedBlobWellFormed(good));

        // Null / too short
        assertFalse(WalletData.isSeedBlobWellFormed(null));
        assertFalse(WalletData.isSeedBlobWellFormed(new byte[WalletData.SEED_BLOB_MIN_SIZE - 1]));

        // Wrong version byte
        byte[] badVersion = (byte[]) good.clone();
        badVersion[0] = 1;
        assertFalse(WalletData.isSeedBlobWellFormed(badVersion));

        // Iteration count too large (would hang the device) or too small
        assertFalse(WalletData.isSeedBlobWellFormed(WalletData.serializeSeedBlob(
            new byte[16], new byte[16], 0x7F000000, new byte[80], new byte[32])));
        assertFalse(WalletData.isSeedBlobWellFormed(WalletData.serializeSeedBlob(
            new byte[16], new byte[16], 0, new byte[80], new byte[32])));

        // Ciphertext not a multiple of the AES block size
        assertFalse(WalletData.isSeedBlobWellFormed(WalletData.serializeSeedBlob(
            new byte[16], new byte[16], 5000, new byte[81], new byte[32])));
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

    // ---- Attempt counter ----

    @Test
    public void attemptsRoundTrip() {
        assertEquals(7, WalletData.getAttempts(WalletData.serializeAttempts(7)));
        assertEquals(0, WalletData.getAttempts(null));
        assertEquals(0, WalletData.getAttempts(new byte[3]));
    }

    // ---- Plaintext tests ----

    @Test
    public void serializeDeserializePlaintext() {
        byte[] seed = filled(64, 0);
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
