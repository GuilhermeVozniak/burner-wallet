package org.burnerwallet.storage;

import org.burnerwallet.core.AesUtils;
import org.burnerwallet.core.ByteArrayUtils;
import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HashUtils;

import java.io.UnsupportedEncodingException;

/**
 * Encrypted wallet storage using PIN-derived AES-256 keys.
 *
 * Stores three records in a RecordStoreAdapter:
 * <ol>
 *   <li>Encrypted seed blob (salt + IV + iterations + ciphertext)</li>
 *   <li>PIN verification hash (SHA-256 of derived key)</li>
 *   <li>Configuration (network, hasPassphrase, addressIndex)</li>
 * </ol>
 *
 * Key derivation: PBKDF2-HMAC-SHA512(pin, salt, iterations, 32 bytes).
 * Encryption: AES-256-CBC with PKCS7 padding.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class WalletStore {

    /** Record store name. */
    public static final String STORE_NAME = "bw";

    /** Record ID for the encrypted seed blob. */
    public static final int RECORD_SEED = 1;

    /** Record ID for the PIN verification hash. */
    public static final int RECORD_PIN_HASH = 2;

    /** Record ID for wallet configuration. */
    public static final int RECORD_CONFIG = 3;

    /** PBKDF2 iteration count for PIN key derivation. */
    public static final int PBKDF2_ITERATIONS = 5000;

    /** AES key length in bytes (256 bits). */
    public static final int AES_KEY_LEN = 32;

    private final RecordStoreAdapter adapter;

    /**
     * Create a WalletStore backed by the given RecordStoreAdapter.
     *
     * @param adapter record store implementation (production or test double)
     */
    public WalletStore(RecordStoreAdapter adapter) {
        this.adapter = adapter;
    }

    /**
     * Check whether a wallet already exists in storage.
     *
     * @return true if the store exists and contains at least 3 records
     */
    public boolean walletExists() {
        try {
            adapter.open(STORE_NAME, false);
            try {
                int count = adapter.getNumRecords();
                return count >= 3;
            } finally {
                adapter.close();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Create a new wallet with deterministic salt/IV derived from the PIN.
     * Primarily useful for testing with reproducible outputs.
     *
     * @param seed       64-byte BIP39 seed
     * @param passphrase BIP39 passphrase (use "" for none)
     * @param pin        user-chosen PIN string
     * @param testnet    true for testnet, false for mainnet
     * @throws CryptoError if encryption fails
     * @throws Exception   if storage fails
     */
    public void createWallet(byte[] seed, String passphrase, String pin,
                             boolean testnet) throws CryptoError, Exception {
        byte[] pinBytes = getUtf8Bytes(pin);
        byte[] salt = generateBytes(16, pin, 1);
        byte[] iv = generateBytes(16, pin, 2);
        createWalletInternal(seed, passphrase, pinBytes, testnet, salt, iv);
    }

    /**
     * Create a new wallet with externally provided salt and IV.
     * Use this in production with entropy from EntropyCollector.
     *
     * @param seed       64-byte BIP39 seed
     * @param passphrase BIP39 passphrase (use "" for none)
     * @param pin        user-chosen PIN string
     * @param testnet    true for testnet, false for mainnet
     * @param salt       16-byte random salt
     * @param iv         16-byte random IV
     * @throws CryptoError if encryption fails
     * @throws Exception   if storage fails
     */
    public void createWalletWithEntropy(byte[] seed, String passphrase,
                                        String pin, boolean testnet,
                                        byte[] salt, byte[] iv)
            throws CryptoError, Exception {
        byte[] pinBytes = getUtf8Bytes(pin);
        createWalletInternal(seed, passphrase, pinBytes, testnet, salt, iv);
    }

    /**
     * Verify a PIN against the stored hash.
     *
     * @param pin PIN to verify
     * @return true if the PIN matches
     * @throws Exception if storage access fails
     */
    public boolean verifyPin(String pin) throws Exception {
        adapter.open(STORE_NAME, false);
        try {
            byte[] seedBlob = adapter.getRecord(RECORD_SEED);
            byte[] storedHash = adapter.getRecord(RECORD_PIN_HASH);

            byte[] salt = WalletData.getSalt(seedBlob);
            int iterations = WalletData.getIterations(seedBlob);

            byte[] pinBytes = getUtf8Bytes(pin);
            byte[] derivedKey = HashUtils.pbkdf2HmacSha512(
                    pinBytes, salt, iterations, AES_KEY_LEN);
            byte[] keyHash = HashUtils.sha256(derivedKey);

            boolean result = ByteArrayUtils.constantTimeEquals(keyHash, storedHash);

            ByteArrayUtils.zeroFill(derivedKey);
            ByteArrayUtils.zeroFill(pinBytes);

            return result;
        } finally {
            adapter.close();
        }
    }

    /**
     * Verify the PIN and decrypt the stored seed.
     *
     * @param pin PIN string
     * @return 64-byte seed, or null if PIN is wrong or decryption fails
     * @throws Exception if storage access fails
     */
    public byte[] unlock(String pin) throws Exception {
        adapter.open(STORE_NAME, false);
        try {
            byte[] seedBlob = adapter.getRecord(RECORD_SEED);
            byte[] storedHash = adapter.getRecord(RECORD_PIN_HASH);

            byte[] salt = WalletData.getSalt(seedBlob);
            byte[] iv = WalletData.getIv(seedBlob);
            int iterations = WalletData.getIterations(seedBlob);
            byte[] ciphertext = WalletData.getCiphertext(seedBlob);

            byte[] pinBytes = getUtf8Bytes(pin);
            byte[] derivedKey = HashUtils.pbkdf2HmacSha512(
                    pinBytes, salt, iterations, AES_KEY_LEN);
            byte[] keyHash = HashUtils.sha256(derivedKey);

            if (!ByteArrayUtils.constantTimeEquals(keyHash, storedHash)) {
                ByteArrayUtils.zeroFill(derivedKey);
                ByteArrayUtils.zeroFill(pinBytes);
                return null;
            }

            try {
                byte[] plaintext = AesUtils.decrypt(ciphertext, derivedKey, iv);
                byte[] seed = WalletData.extractSeed(plaintext);
                ByteArrayUtils.zeroFill(plaintext);
                ByteArrayUtils.zeroFill(derivedKey);
                ByteArrayUtils.zeroFill(pinBytes);
                return seed;
            } catch (CryptoError e) {
                ByteArrayUtils.zeroFill(derivedKey);
                ByteArrayUtils.zeroFill(pinBytes);
                return null;
            }
        } finally {
            adapter.close();
        }
    }

    /**
     * Verify the PIN and retrieve the stored passphrase.
     *
     * @param pin PIN string
     * @return passphrase string, or null if PIN is wrong or decryption fails
     * @throws Exception if storage access fails
     */
    public String getPassphrase(String pin) throws Exception {
        adapter.open(STORE_NAME, false);
        try {
            byte[] seedBlob = adapter.getRecord(RECORD_SEED);
            byte[] storedHash = adapter.getRecord(RECORD_PIN_HASH);

            byte[] salt = WalletData.getSalt(seedBlob);
            byte[] iv = WalletData.getIv(seedBlob);
            int iterations = WalletData.getIterations(seedBlob);
            byte[] ciphertext = WalletData.getCiphertext(seedBlob);

            byte[] pinBytes = getUtf8Bytes(pin);
            byte[] derivedKey = HashUtils.pbkdf2HmacSha512(
                    pinBytes, salt, iterations, AES_KEY_LEN);
            byte[] keyHash = HashUtils.sha256(derivedKey);

            if (!ByteArrayUtils.constantTimeEquals(keyHash, storedHash)) {
                ByteArrayUtils.zeroFill(derivedKey);
                ByteArrayUtils.zeroFill(pinBytes);
                return null;
            }

            try {
                byte[] plaintext = AesUtils.decrypt(ciphertext, derivedKey, iv);
                String passphrase = WalletData.extractPassphrase(plaintext);
                ByteArrayUtils.zeroFill(plaintext);
                ByteArrayUtils.zeroFill(derivedKey);
                ByteArrayUtils.zeroFill(pinBytes);
                return passphrase;
            } catch (CryptoError e) {
                ByteArrayUtils.zeroFill(derivedKey);
                ByteArrayUtils.zeroFill(pinBytes);
                return null;
            }
        } finally {
            adapter.close();
        }
    }

    /**
     * Read whether the wallet is configured for testnet.
     *
     * @return true if testnet
     * @throws Exception if storage access fails
     */
    public boolean isTestnet() throws Exception {
        adapter.open(STORE_NAME, false);
        try {
            byte[] config = adapter.getRecord(RECORD_CONFIG);
            return WalletData.getNetworkTestnet(config);
        } finally {
            adapter.close();
        }
    }

    /**
     * Update the network setting in config.
     *
     * @param testnet true for testnet, false for mainnet
     * @throws Exception if storage access fails
     */
    public void setTestnet(boolean testnet) throws Exception {
        adapter.open(STORE_NAME, false);
        try {
            byte[] config = adapter.getRecord(RECORD_CONFIG);
            boolean hasPassphrase = WalletData.getHasPassphrase(config);
            int addressIndex = WalletData.getAddressIndex(config);
            byte[] newConfig = WalletData.serializeConfig(testnet, hasPassphrase, addressIndex);
            adapter.setRecord(RECORD_CONFIG, newConfig);
        } finally {
            adapter.close();
        }
    }

    /**
     * Read the current address derivation index from config.
     *
     * @return address index
     * @throws Exception if storage access fails
     */
    public int getAddressIndex() throws Exception {
        adapter.open(STORE_NAME, false);
        try {
            byte[] config = adapter.getRecord(RECORD_CONFIG);
            return WalletData.getAddressIndex(config);
        } finally {
            adapter.close();
        }
    }

    /**
     * Update the address derivation index in config.
     *
     * @param index new address index
     * @throws Exception if storage access fails
     */
    public void setAddressIndex(int index) throws Exception {
        adapter.open(STORE_NAME, false);
        try {
            byte[] config = adapter.getRecord(RECORD_CONFIG);
            boolean testnet = WalletData.getNetworkTestnet(config);
            boolean hasPassphrase = WalletData.getHasPassphrase(config);
            byte[] newConfig = WalletData.serializeConfig(testnet, hasPassphrase, index);
            adapter.setRecord(RECORD_CONFIG, newConfig);
        } finally {
            adapter.close();
        }
    }

    /**
     * Permanently delete the wallet store and all its records.
     *
     * @throws Exception if deletion fails
     */
    public void wipe() throws Exception {
        adapter.open(STORE_NAME, false);
        adapter.deleteStore();
    }

    // ---- Internal helpers ----

    /**
     * Core wallet creation logic shared by createWallet and createWalletWithEntropy.
     */
    private void createWalletInternal(byte[] seed, String passphrase,
                                      byte[] pinBytes, boolean testnet,
                                      byte[] salt, byte[] iv)
            throws CryptoError, Exception {
        // Derive AES key from PIN
        byte[] derivedKey = HashUtils.pbkdf2HmacSha512(
                pinBytes, salt, PBKDF2_ITERATIONS, AES_KEY_LEN);

        // Build plaintext: seed + passphrase
        byte[] plaintext = WalletData.buildPlaintext(seed, passphrase);

        // Encrypt
        byte[] ciphertext = AesUtils.encrypt(plaintext, derivedKey, iv);

        // Build seed blob
        byte[] seedBlob = WalletData.serializeSeedBlob(
                salt, iv, PBKDF2_ITERATIONS, ciphertext);

        // PIN verification hash
        byte[] pinHash = HashUtils.sha256(derivedKey);

        // Config
        boolean hasPassphrase = passphrase != null && passphrase.length() > 0;
        byte[] config = WalletData.serializeConfig(testnet, hasPassphrase, 0);

        // Store records
        adapter.open(STORE_NAME, true);
        try {
            adapter.addRecord(seedBlob);    // record 1
            adapter.addRecord(pinHash);     // record 2
            adapter.addRecord(config);      // record 3
        } finally {
            adapter.close();
        }

        // Wipe sensitive material
        ByteArrayUtils.zeroFill(derivedKey);
        ByteArrayUtils.zeroFill(plaintext);
        ByteArrayUtils.zeroFill(pinBytes);
    }

    /**
     * Convert a string to UTF-8 bytes.
     *
     * @param s input string
     * @return UTF-8 byte array
     */
    private static byte[] getUtf8Bytes(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s.getBytes();
        }
    }

    /**
     * Generate deterministic bytes from a string input and counter.
     * Uses PBKDF2-HMAC-SHA512 with 1 iteration for fast deterministic derivation.
     * Primarily used in {@link #createWallet} to derive salt/IV from PIN for testability.
     *
     * @param length  number of output bytes
     * @param input   input string (e.g. PIN)
     * @param counter counter value to differentiate salt from IV
     * @return derived bytes of the requested length
     */
    private static byte[] generateBytes(int length, String input, int counter) {
        byte[] inputBytes = getUtf8Bytes(input);
        byte[] counterBytes = new byte[] {
            (byte) ((counter >> 24) & 0xFF),
            (byte) ((counter >> 16) & 0xFF),
            (byte) ((counter >> 8) & 0xFF),
            (byte) (counter & 0xFF)
        };
        byte[] salt = ByteArrayUtils.concat(inputBytes, counterBytes);
        return HashUtils.pbkdf2HmacSha512(inputBytes, salt, 1, length);
    }
}
