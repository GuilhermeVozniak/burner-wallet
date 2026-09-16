package org.burnerwallet.storage;

import org.burnerwallet.core.AesUtils;
import org.burnerwallet.core.ByteArrayUtils;
import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HashUtils;

import java.io.UnsupportedEncodingException;

/**
 * Encrypted wallet storage using PIN-derived keys.
 *
 * Stores three records in a RecordStoreAdapter:
 * <ol>
 *   <li>Seed blob: version + salt + IV + iterations + ciphertext + MAC
 *       (see {@link WalletData})</li>
 *   <li>Configuration (network, hasPassphrase, addressIndex)</li>
 *   <li>Consecutive failed PIN attempts</li>
 * </ol>
 *
 * Key derivation: PBKDF2-HMAC-SHA512(pin, salt, iterations, 64 bytes);
 * the first 32 bytes are the AES-256 key, the last 32 the HMAC-SHA256 key.
 * Encryption: AES-256-CBC with PKCS7 padding, encrypt-then-MAC. The MAC is
 * verified in constant time before anything is decrypted; a wrong PIN and
 * a tampered blob are therefore indistinguishable and both fail closed.
 *
 * Salt and IV are fresh random values per wallet creation (never derived
 * from the PIN), so an offline attacker cannot use one precomputed PIN
 * table against every device. After {@link #MAX_FAILED_ATTEMPTS}
 * consecutive wrong PINs the wallet is wiped.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class WalletStore {

    /** Record store name. */
    public static final String STORE_NAME = "bw";

    /** Record ID for the seed blob. */
    public static final int RECORD_SEED = 1;

    /** Record ID for wallet configuration. */
    public static final int RECORD_CONFIG = 2;

    /** Record ID for the failed-attempt counter. */
    public static final int RECORD_ATTEMPTS = 3;

    /** Number of records a complete wallet has. */
    private static final int RECORD_COUNT = 3;

    /** PBKDF2 iteration count for PIN key derivation. */
    public static final int PBKDF2_ITERATIONS = 5000;

    /** AES key length in bytes (256 bits). */
    public static final int AES_KEY_LEN = 32;

    /** HMAC key length in bytes. */
    public static final int MAC_KEY_LEN = 32;

    /** Wrong PINs tolerated before the wallet is wiped. */
    public static final int MAX_FAILED_ATTEMPTS = 10;

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
     * @return true if the store exists and contains all wallet records
     */
    public boolean walletExists() {
        try {
            adapter.open(STORE_NAME, false);
            try {
                int count = adapter.getNumRecords();
                return count >= RECORD_COUNT;
            } finally {
                adapter.close();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Create a new wallet with a fresh random salt and IV.
     *
     * Any existing wallet store is deleted first so a previously failed or
     * partial creation cannot leave stale records behind.
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
        byte[][] saltIv = freshSaltAndIv(seed, passphrase);
        byte[] pinBytes = getUtf8Bytes(pin);
        createWalletInternal(seed, passphrase, pinBytes, testnet, saltIv[0], saltIv[1]);
    }

    /**
     * Create a new wallet with externally provided salt and IV (e.g. from
     * the EntropyCollector, or fixed values in tests).
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
        if (salt == null || salt.length != 16 || iv == null || iv.length != 16) {
            throw new CryptoError(CryptoError.ERR_ENCRYPTION,
                "Salt and IV must be 16 bytes");
        }
        byte[] pinBytes = getUtf8Bytes(pin);
        createWalletInternal(seed, passphrase, pinBytes, testnet, salt, iv);
    }

    /**
     * Verify a PIN against the stored blob (no side effects).
     *
     * @param pin PIN to verify
     * @return true if the PIN matches
     * @throws Exception if storage access fails or the blob is corrupted
     */
    public boolean verifyPin(String pin) throws Exception {
        byte[] plaintext = decryptPlaintext(pin, false);
        if (plaintext == null) {
            return false;
        }
        ByteArrayUtils.zeroFill(plaintext);
        return true;
    }

    /**
     * Verify the PIN and decrypt the stored seed. A wrong PIN increments
     * the failed-attempt counter; reaching {@link #MAX_FAILED_ATTEMPTS}
     * wipes the wallet (check {@link #walletExists()} afterwards).
     *
     * @param pin PIN string
     * @return 64-byte seed, or null if the PIN is wrong
     * @throws Exception if storage access fails or the blob is corrupted
     */
    public byte[] unlock(String pin) throws Exception {
        UnlockResult r = unlockFull(pin);
        return r == null ? null : r.seed;
    }

    /**
     * Verify the PIN and decrypt both the seed and the passphrase with a
     * single PBKDF2 derivation. Same attempt-counting semantics as
     * {@link #unlock}.
     *
     * @param pin PIN string
     * @return seed and passphrase, or null if the PIN is wrong
     * @throws Exception if storage access fails or the blob is corrupted
     */
    public UnlockResult unlockFull(String pin) throws Exception {
        byte[] plaintext = decryptPlaintext(pin, true);
        if (plaintext == null) {
            return null;
        }
        byte[] seed = WalletData.extractSeed(plaintext);
        String passphrase = WalletData.extractPassphrase(plaintext);
        ByteArrayUtils.zeroFill(plaintext);
        return new UnlockResult(seed, passphrase);
    }

    /**
     * Verify the PIN and retrieve the stored passphrase (no attempt counting).
     *
     * @param pin PIN string
     * @return passphrase string, or null if the PIN is wrong
     * @throws Exception if storage access fails or the blob is corrupted
     */
    public String getPassphrase(String pin) throws Exception {
        byte[] plaintext = decryptPlaintext(pin, false);
        if (plaintext == null) {
            return null;
        }
        String passphrase = WalletData.extractPassphrase(plaintext);
        ByteArrayUtils.zeroFill(plaintext);
        return passphrase;
    }

    /**
     * Number of consecutive wrong PINs since the last successful unlock.
     *
     * @return failed attempt count (0 if unavailable)
     */
    public int getFailedAttempts() {
        try {
            adapter.open(STORE_NAME, false);
            try {
                return WalletData.getAttempts(adapter.getRecord(RECORD_ATTEMPTS));
            } finally {
                adapter.close();
            }
        } catch (Exception e) {
            return 0;
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
     * Permanently delete the wallet store and all its records. Every record
     * is overwritten with zeros before deletion so the encrypted blob does
     * not linger in unlinked flash pages.
     *
     * @throws Exception if deletion fails
     */
    public void wipe() throws Exception {
        adapter.open(STORE_NAME, false);
        overwriteAndDelete();
    }

    // ---- Internal helpers ----

    /**
     * Verify the PIN via the MAC and return the decrypted plaintext, or
     * null if the PIN is wrong.
     *
     * @param countAttempts whether a wrong PIN counts toward the wipe limit
     */
    private byte[] decryptPlaintext(String pin, boolean countAttempts) throws Exception {
        adapter.open(STORE_NAME, false);
        boolean storeOpen = true;
        try {
            byte[] blob = adapter.getRecord(RECORD_SEED);
            if (!WalletData.isSeedBlobWellFormed(blob)) {
                throw new Exception("Wallet record corrupted");
            }

            byte[] salt = WalletData.getSalt(blob);
            byte[] iv = WalletData.getIv(blob);
            int iterations = WalletData.getIterations(blob);
            byte[] ciphertext = WalletData.getCiphertext(blob);
            byte[] storedMac = WalletData.getMac(blob);
            byte[] authenticated = WalletData.getAuthenticatedRegion(blob);

            byte[] pinBytes = getUtf8Bytes(pin);
            byte[] keys = HashUtils.pbkdf2HmacSha512(
                    pinBytes, salt, iterations, AES_KEY_LEN + MAC_KEY_LEN);
            ByteArrayUtils.zeroFill(pinBytes);
            byte[] encKey = ByteArrayUtils.copyOfRange(keys, 0, AES_KEY_LEN);
            byte[] macKey = ByteArrayUtils.copyOfRange(keys, AES_KEY_LEN,
                    AES_KEY_LEN + MAC_KEY_LEN);
            ByteArrayUtils.zeroFill(keys);

            byte[] computedMac = HashUtils.hmacSha256(macKey, authenticated);
            ByteArrayUtils.zeroFill(macKey);
            boolean macOk = ByteArrayUtils.constantTimeEquals(computedMac, storedMac);

            if (!macOk) {
                ByteArrayUtils.zeroFill(encKey);
                if (countAttempts && recordFailedAttempt()) {
                    storeOpen = false; // wallet wiped
                }
                return null;
            }

            if (countAttempts) {
                adapter.setRecord(RECORD_ATTEMPTS, WalletData.serializeAttempts(0));
            }

            try {
                return AesUtils.decrypt(ciphertext, encKey, iv);
            } catch (CryptoError e) {
                // MAC verified, so this is not a wrong PIN: the record is broken
                throw new Exception("Wallet record corrupted: " + e.getMessage());
            } finally {
                ByteArrayUtils.zeroFill(encKey);
            }
        } finally {
            if (storeOpen) {
                adapter.close();
            }
        }
    }

    /**
     * Increment the failed-attempt counter; wipe the wallet at the limit.
     * The store must be open.
     *
     * @return true if the wallet was wiped (store no longer open)
     */
    private boolean recordFailedAttempt() throws Exception {
        int attempts = WalletData.getAttempts(adapter.getRecord(RECORD_ATTEMPTS)) + 1;
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            overwriteAndDelete();
            return true;
        }
        adapter.setRecord(RECORD_ATTEMPTS, WalletData.serializeAttempts(attempts));
        return false;
    }

    /**
     * Zero every record, then delete the store. The store must be open;
     * it is closed/deleted on return.
     */
    private void overwriteAndDelete() throws Exception {
        try {
            int count = adapter.getNumRecords();
            for (int id = 1; id <= count; id++) {
                byte[] data = adapter.getRecord(id);
                adapter.setRecord(id, new byte[data.length]);
            }
        } catch (Exception e) {
            // Best-effort overwrite; deletion below still happens
        }
        adapter.deleteStore();
    }

    /**
     * Core wallet creation logic shared by createWallet and createWalletWithEntropy.
     */
    private void createWalletInternal(byte[] seed, String passphrase,
                                      byte[] pinBytes, boolean testnet,
                                      byte[] salt, byte[] iv)
            throws CryptoError, Exception {
        if (seed == null || seed.length != WalletData.SEED_LENGTH) {
            throw new CryptoError(CryptoError.ERR_ENCRYPTION, "Seed must be 64 bytes");
        }

        // Derive encryption + MAC keys from PIN
        byte[] keys = HashUtils.pbkdf2HmacSha512(
                pinBytes, salt, PBKDF2_ITERATIONS, AES_KEY_LEN + MAC_KEY_LEN);
        ByteArrayUtils.zeroFill(pinBytes);
        byte[] encKey = ByteArrayUtils.copyOfRange(keys, 0, AES_KEY_LEN);
        byte[] macKey = ByteArrayUtils.copyOfRange(keys, AES_KEY_LEN,
                AES_KEY_LEN + MAC_KEY_LEN);
        ByteArrayUtils.zeroFill(keys);

        // Build plaintext: seed + passphrase
        byte[] plaintext = WalletData.buildPlaintext(seed, passphrase);

        // Encrypt, then MAC
        byte[] ciphertext;
        try {
            ciphertext = AesUtils.encrypt(plaintext, encKey, iv);
        } finally {
            ByteArrayUtils.zeroFill(plaintext);
            ByteArrayUtils.zeroFill(encKey);
        }
        byte[] authenticated = WalletData.serializeAuthenticatedRegion(
                salt, iv, PBKDF2_ITERATIONS, ciphertext);
        byte[] mac = HashUtils.hmacSha256(macKey, authenticated);
        ByteArrayUtils.zeroFill(macKey);
        byte[] seedBlob = ByteArrayUtils.concat(authenticated, mac);

        // Config
        boolean hasPassphrase = passphrase != null && passphrase.length() > 0;
        byte[] config = WalletData.serializeConfig(testnet, hasPassphrase, 0);

        // Remove any stale store so record IDs start at 1
        try {
            adapter.open(STORE_NAME, false);
            adapter.deleteStore();
        } catch (Exception e) {
            // No existing store
        }

        // Store records
        adapter.open(STORE_NAME, true);
        try {
            int id1 = adapter.addRecord(seedBlob);
            int id2 = adapter.addRecord(config);
            int id3 = adapter.addRecord(WalletData.serializeAttempts(0));
            if (id1 != RECORD_SEED || id2 != RECORD_CONFIG || id3 != RECORD_ATTEMPTS) {
                throw new Exception("Unexpected record layout: "
                        + id1 + "," + id2 + "," + id3);
            }
        } finally {
            adapter.close();
        }
    }

    /**
     * Generate a fresh salt and IV for a new wallet.
     *
     * The device has no CSPRNG API, so the values are derived by hashing
     * the (secret) seed together with the passphrase, the clock, free
     * memory and an object identity hash, with domain separation. Because
     * the seed is unknown to an offline attacker, the salt is unpredictable
     * to them; the clock component makes the IV unique across re-creations
     * with the same seed. Neither value reveals anything about the seed.
     *
     * @return {salt, iv}, 16 bytes each
     */
    private static byte[][] freshSaltAndIv(byte[] seed, String passphrase) {
        byte[] pass = getUtf8Bytes(passphrase == null ? "" : passphrase);
        long now = System.currentTimeMillis();
        long mem = Runtime.getRuntime().freeMemory();
        int ident = new Object().hashCode();

        byte[] material = new byte[1 + seed.length + pass.length + 8 + 8 + 4];
        int off = 1;
        System.arraycopy(seed, 0, material, off, seed.length);
        off += seed.length;
        System.arraycopy(pass, 0, material, off, pass.length);
        off += pass.length;
        for (int i = 7; i >= 0; i--) {
            material[off++] = (byte) (now >>> (i * 8));
        }
        for (int i = 7; i >= 0; i--) {
            material[off++] = (byte) (mem >>> (i * 8));
        }
        for (int i = 3; i >= 0; i--) {
            material[off++] = (byte) (ident >>> (i * 8));
        }

        material[0] = 0x01;
        byte[] salt = ByteArrayUtils.copyOfRange(HashUtils.sha256(material), 0, 16);
        material[0] = 0x02;
        byte[] iv = ByteArrayUtils.copyOfRange(HashUtils.sha256(material), 0, 16);

        ByteArrayUtils.zeroFill(material);
        ByteArrayUtils.zeroFill(pass);
        return new byte[][] { salt, iv };
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
}
