package org.burnerwallet.storage;

import org.burnerwallet.core.ByteArrayUtils;

import java.io.UnsupportedEncodingException;

/**
 * Blob serialization value type for wallet storage.
 * All static methods; no instance state.
 *
 * Three formats:
 *
 * <b>Seed blob (116 bytes):</b>
 * {@code salt(16) + iv(16) + iterations(4 BE) + ciphertext(80)}
 *
 * <b>Config (6 bytes):</b>
 * {@code network(1: 0=mainnet,1=testnet) + hasPassphrase(1: 0/1) + addressIndex(4 BE)}
 *
 * <b>Plaintext (before encryption):</b>
 * {@code seed(64) + passphrase_length(2 BE) + passphrase_utf8(0..N)}
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class WalletData {

    /** Expected total size of a serialized seed blob. */
    public static final int SEED_BLOB_SIZE = 116;

    /** Expected total size of a serialized config blob. */
    public static final int CONFIG_SIZE = 6;

    /** BIP39 seed length in bytes. */
    public static final int SEED_LENGTH = 64;

    // ---- Seed blob: salt(16) + iv(16) + iterations(4) + ciphertext(80) ----

    /**
     * Serialize the components of an encrypted seed into a single blob.
     *
     * @param salt       16-byte PBKDF2 salt
     * @param iv         16-byte AES IV
     * @param iterations PBKDF2 iteration count (big-endian)
     * @param ciphertext encrypted payload (80 bytes for empty passphrase, longer with passphrase)
     * @return blob of length 36 + ciphertext.length
     */
    public static byte[] serializeSeedBlob(byte[] salt, byte[] iv,
                                           int iterations, byte[] ciphertext) {
        byte[] blob = new byte[36 + ciphertext.length];
        System.arraycopy(salt, 0, blob, 0, 16);
        System.arraycopy(iv, 0, blob, 16, 16);
        blob[32] = (byte) (iterations >>> 24);
        blob[33] = (byte) (iterations >>> 16);
        blob[34] = (byte) (iterations >>> 8);
        blob[35] = (byte) iterations;
        System.arraycopy(ciphertext, 0, blob, 36, ciphertext.length);
        return blob;
    }

    /**
     * Extract the 16-byte salt from a seed blob.
     */
    public static byte[] getSalt(byte[] blob) {
        return ByteArrayUtils.copyOfRange(blob, 0, 16);
    }

    /**
     * Extract the 16-byte IV from a seed blob.
     */
    public static byte[] getIv(byte[] blob) {
        return ByteArrayUtils.copyOfRange(blob, 16, 32);
    }

    /**
     * Extract the PBKDF2 iteration count (big-endian int) from a seed blob.
     */
    public static int getIterations(byte[] blob) {
        return ((blob[32] & 0xFF) << 24)
             | ((blob[33] & 0xFF) << 16)
             | ((blob[34] & 0xFF) << 8)
             |  (blob[35] & 0xFF);
    }

    /**
     * Extract the ciphertext (bytes 36..end) from a seed blob.
     */
    public static byte[] getCiphertext(byte[] blob) {
        return ByteArrayUtils.copyOfRange(blob, 36, blob.length);
    }

    // ---- Config: network(1) + hasPassphrase(1) + addressIndex(4 BE) ----

    /**
     * Serialize wallet configuration into a 6-byte blob.
     *
     * @param testnet       true for testnet, false for mainnet
     * @param hasPassphrase true if a BIP39 passphrase is in use
     * @param addressIndex  current address derivation index (big-endian)
     * @return 6-byte config blob
     */
    public static byte[] serializeConfig(boolean testnet, boolean hasPassphrase,
                                         int addressIndex) {
        byte[] config = new byte[CONFIG_SIZE];
        config[0] = (byte) (testnet ? 1 : 0);
        config[1] = (byte) (hasPassphrase ? 1 : 0);
        config[2] = (byte) (addressIndex >>> 24);
        config[3] = (byte) (addressIndex >>> 16);
        config[4] = (byte) (addressIndex >>> 8);
        config[5] = (byte) addressIndex;
        return config;
    }

    /**
     * Read the network flag from a config blob.
     *
     * @return true if testnet
     */
    public static boolean getNetworkTestnet(byte[] config) {
        return config[0] != 0;
    }

    /**
     * Read the hasPassphrase flag from a config blob.
     */
    public static boolean getHasPassphrase(byte[] config) {
        return config[1] != 0;
    }

    /**
     * Read the address derivation index from a config blob.
     */
    public static int getAddressIndex(byte[] config) {
        return ((config[2] & 0xFF) << 24)
             | ((config[3] & 0xFF) << 16)
             | ((config[4] & 0xFF) << 8)
             |  (config[5] & 0xFF);
    }

    // ---- Plaintext: seed(64) + passphrase_length(2 BE) + passphrase_utf8 ----

    /**
     * Build a plaintext blob to be encrypted: seed + passphrase length + passphrase UTF-8 bytes.
     *
     * @param seed       64-byte BIP39 seed
     * @param passphrase BIP39 passphrase (may be empty)
     * @return plaintext blob
     */
    public static byte[] buildPlaintext(byte[] seed, String passphrase) {
        byte[] passBytes;
        try {
            passBytes = passphrase.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            // Fallback — should never happen on any reasonable JVM
            passBytes = passphrase.getBytes();
        }
        int len = passBytes.length;
        byte[] result = new byte[SEED_LENGTH + 2 + len];
        System.arraycopy(seed, 0, result, 0, SEED_LENGTH);
        result[SEED_LENGTH] = (byte) (len >>> 8);
        result[SEED_LENGTH + 1] = (byte) len;
        if (len > 0) {
            System.arraycopy(passBytes, 0, result, SEED_LENGTH + 2, len);
        }
        return result;
    }

    /**
     * Extract the 64-byte seed from a plaintext blob.
     */
    public static byte[] extractSeed(byte[] plaintext) {
        return ByteArrayUtils.copyOfRange(plaintext, 0, SEED_LENGTH);
    }

    /**
     * Extract the passphrase string from a plaintext blob.
     */
    public static String extractPassphrase(byte[] plaintext) {
        int len = ((plaintext[SEED_LENGTH] & 0xFF) << 8)
                | (plaintext[SEED_LENGTH + 1] & 0xFF);
        if (len == 0) {
            return "";
        }
        byte[] passBytes = ByteArrayUtils.copyOfRange(
                plaintext, SEED_LENGTH + 2, SEED_LENGTH + 2 + len);
        try {
            return new String(passBytes, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return new String(passBytes);
        }
    }
}
