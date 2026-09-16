package org.burnerwallet.storage;

import org.burnerwallet.core.ByteArrayUtils;

import java.io.UnsupportedEncodingException;

/**
 * Blob serialization value type for wallet storage.
 * All static methods; no instance state.
 *
 * Formats:
 *
 * <b>Seed blob (version 2):</b>
 * {@code version(1)=0x02 + salt(16) + iv(16) + iterations(4 BE) + ciphertext(N) + mac(32)}
 * where {@code mac = HMAC-SHA256(macKey, everything before the mac)}. The MAC
 * both authenticates the ciphertext (a flipped bit cannot silently change
 * the seed) and verifies the PIN (no separate PIN-hash record).
 *
 * <b>Config (6 bytes):</b>
 * {@code network(1: 0=mainnet,1=testnet) + hasPassphrase(1: 0/1) + addressIndex(4 BE)}
 *
 * <b>Failed attempts (4 bytes BE):</b> consecutive wrong-PIN count.
 *
 * <b>Plaintext (before encryption):</b>
 * {@code seed(64) + passphrase_length(2 BE) + passphrase_utf8(0..N)}
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class WalletData {

    /** Seed blob format version byte. */
    public static final int BLOB_VERSION = 2;

    /** version(1) + salt(16) + iv(16) + iterations(4). */
    public static final int HEADER_SIZE = 37;

    /** HMAC-SHA256 output length. */
    public static final int MAC_SIZE = 32;

    /** Smallest well-formed blob: header + one AES block + MAC. */
    public static final int SEED_BLOB_MIN_SIZE = HEADER_SIZE + 16 + MAC_SIZE;

    /** Lowest PBKDF2 iteration count accepted from a stored blob. */
    public static final int MIN_ITERATIONS = 1000;

    /** Highest PBKDF2 iteration count accepted from a stored blob. */
    public static final int MAX_ITERATIONS = 200000;

    /** Expected total size of a serialized config blob. */
    public static final int CONFIG_SIZE = 6;

    /** BIP39 seed length in bytes. */
    public static final int SEED_LENGTH = 64;

    // ---- Seed blob ----

    /**
     * Serialize the authenticated region of a seed blob (everything except
     * the MAC). This is the exact byte string the MAC is computed over.
     *
     * @param salt       16-byte PBKDF2 salt
     * @param iv         16-byte AES IV
     * @param iterations PBKDF2 iteration count (big-endian)
     * @param ciphertext encrypted payload
     * @return header + ciphertext
     */
    public static byte[] serializeAuthenticatedRegion(byte[] salt, byte[] iv,
                                                      int iterations, byte[] ciphertext) {
        byte[] region = new byte[HEADER_SIZE + ciphertext.length];
        region[0] = (byte) BLOB_VERSION;
        System.arraycopy(salt, 0, region, 1, 16);
        System.arraycopy(iv, 0, region, 17, 16);
        region[33] = (byte) (iterations >>> 24);
        region[34] = (byte) (iterations >>> 16);
        region[35] = (byte) (iterations >>> 8);
        region[36] = (byte) iterations;
        System.arraycopy(ciphertext, 0, region, HEADER_SIZE, ciphertext.length);
        return region;
    }

    /**
     * Serialize a complete seed blob.
     *
     * @param salt       16-byte PBKDF2 salt
     * @param iv         16-byte AES IV
     * @param iterations PBKDF2 iteration count (big-endian)
     * @param ciphertext encrypted payload
     * @param mac        32-byte HMAC over the authenticated region
     * @return blob of length HEADER_SIZE + ciphertext.length + MAC_SIZE
     */
    public static byte[] serializeSeedBlob(byte[] salt, byte[] iv,
                                           int iterations, byte[] ciphertext,
                                           byte[] mac) {
        byte[] region = serializeAuthenticatedRegion(salt, iv, iterations, ciphertext);
        return ByteArrayUtils.concat(region, mac);
    }

    /**
     * Structural validation of a seed blob before any of it is trusted:
     * version byte, minimum length, ciphertext a whole number of AES
     * blocks, iteration count within sane bounds (a corrupted count could
     * otherwise hang the device for years or crash PBKDF2).
     *
     * @param blob the stored record
     * @return true if the blob can be parsed
     */
    public static boolean isSeedBlobWellFormed(byte[] blob) {
        if (blob == null || blob.length < SEED_BLOB_MIN_SIZE) {
            return false;
        }
        if ((blob[0] & 0xFF) != BLOB_VERSION) {
            return false;
        }
        int ciphertextLen = blob.length - HEADER_SIZE - MAC_SIZE;
        if (ciphertextLen % 16 != 0) {
            return false;
        }
        int iterations = getIterations(blob);
        return iterations >= MIN_ITERATIONS && iterations <= MAX_ITERATIONS;
    }

    /**
     * Extract the 16-byte salt from a seed blob.
     */
    public static byte[] getSalt(byte[] blob) {
        return ByteArrayUtils.copyOfRange(blob, 1, 17);
    }

    /**
     * Extract the 16-byte IV from a seed blob.
     */
    public static byte[] getIv(byte[] blob) {
        return ByteArrayUtils.copyOfRange(blob, 17, 33);
    }

    /**
     * Extract the PBKDF2 iteration count (big-endian int) from a seed blob.
     */
    public static int getIterations(byte[] blob) {
        return ((blob[33] & 0xFF) << 24)
             | ((blob[34] & 0xFF) << 16)
             | ((blob[35] & 0xFF) << 8)
             |  (blob[36] & 0xFF);
    }

    /**
     * Extract the ciphertext (between header and MAC) from a seed blob.
     */
    public static byte[] getCiphertext(byte[] blob) {
        return ByteArrayUtils.copyOfRange(blob, HEADER_SIZE, blob.length - MAC_SIZE);
    }

    /**
     * Extract the 32-byte MAC (last bytes) from a seed blob.
     */
    public static byte[] getMac(byte[] blob) {
        return ByteArrayUtils.copyOfRange(blob, blob.length - MAC_SIZE, blob.length);
    }

    /**
     * Extract the authenticated region (everything before the MAC).
     */
    public static byte[] getAuthenticatedRegion(byte[] blob) {
        return ByteArrayUtils.copyOfRange(blob, 0, blob.length - MAC_SIZE);
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

    // ---- Failed attempt counter: 4 bytes BE ----

    /**
     * Serialize the consecutive failed-PIN counter.
     */
    public static byte[] serializeAttempts(int attempts) {
        return new byte[] {
            (byte) (attempts >>> 24),
            (byte) (attempts >>> 16),
            (byte) (attempts >>> 8),
            (byte) attempts
        };
    }

    /**
     * Read the consecutive failed-PIN counter (0 if the record is malformed).
     */
    public static int getAttempts(byte[] record) {
        if (record == null || record.length != 4) {
            return 0;
        }
        return ((record[0] & 0xFF) << 24)
             | ((record[1] & 0xFF) << 16)
             | ((record[2] & 0xFF) << 8)
             |  (record[3] & 0xFF);
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
            // Fallback -- should never happen on any reasonable JVM
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
        ByteArrayUtils.zeroFill(passBytes);
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
