# M1b — Storage & UI Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Turn the Java ME signer into a usable wallet with PIN-encrypted key storage, onboarding, and address display on the Nokia C1-01.

**Architecture:** Single RecordStore (`"bw"`) with AES-256-CBC encryption (PIN-derived key via PBKDF2). Form-based LCDUI screens for all interaction. Entropy from keypad timing + system time. No Canvas rendering (deferred to M1c).

**Tech Stack:** Java ME (CLDC 1.1 / MIDP 2.0), Bouncy Castle jdk14 (AESLightEngine, CBCBlockCipher), JUnit 4 for testing, Ant + ProGuard for build.

**Constraints reminder:** Java 1.4 source level — no generics, no autoboxing, no enhanced for-loop, no varargs, no `String.split()` with regex. Source files compile with `source="1.4" target="1.4"`. Tests compile with `source="1.8" target="1.8"`. All source files must declare the correct package matching their directory path.

---

## Task 1: AesUtils — AES-256-CBC encrypt/decrypt

Adds AES encryption using Bouncy Castle's `AESLightEngine` (smallest footprint). This is the foundation for PIN-protected storage.

**Files:**
- Create: `signer/src/org/burnerwallet/core/AesUtils.java`
- Create: `signer/test/org/burnerwallet/core/AesUtilsTest.java`

**Step 1: Write AesUtilsTest.java**

```java
package org.burnerwallet.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class AesUtilsTest {

    // AES-256-CBC test vector from NIST SP 800-38A F.2.5/F.2.6
    // Key:        603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4
    // IV:         000102030405060708090a0b0c0d0e0f
    // Plaintext:  6bc1bee22e409f96e93d7e117393172a
    // Ciphertext: f58c4c04d6e5f1ba779eabfb5f7bfbd6

    @Test
    public void encryptDecryptRoundTrip() throws Exception {
        byte[] key = HexCodec.decode("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4");
        byte[] iv = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        byte[] plaintext = "Hello, Burner Wallet!".getBytes("UTF-8");

        byte[] ciphertext = AesUtils.encrypt(plaintext, key, iv);
        assertNotNull(ciphertext);
        assertTrue(ciphertext.length > 0);
        // Ciphertext must differ from plaintext
        assertFalse(java.util.Arrays.equals(plaintext, ciphertext));

        byte[] decrypted = AesUtils.decrypt(ciphertext, key, iv);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void encryptDecryptEmptyPlaintext() throws Exception {
        byte[] key = HexCodec.decode("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4");
        byte[] iv = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        byte[] plaintext = new byte[0];

        byte[] ciphertext = AesUtils.encrypt(plaintext, key, iv);
        // Even empty plaintext produces a ciphertext block (PKCS7 padding)
        assertEquals(16, ciphertext.length);

        byte[] decrypted = AesUtils.decrypt(ciphertext, key, iv);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void encryptDecryptExactBlockSize() throws Exception {
        byte[] key = HexCodec.decode("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4");
        byte[] iv = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        // Exactly 16 bytes (one AES block)
        byte[] plaintext = HexCodec.decode("6bc1bee22e409f96e93d7e117393172a");

        byte[] ciphertext = AesUtils.encrypt(plaintext, key, iv);
        // PKCS7 adds a full padding block when input is block-aligned
        assertEquals(32, ciphertext.length);

        byte[] decrypted = AesUtils.decrypt(ciphertext, key, iv);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void encryptDecrypt64ByteSeed() throws Exception {
        // Simulates encrypting a BIP39 seed (64 bytes)
        byte[] key = HexCodec.decode("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4");
        byte[] iv = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        byte[] seed = HexCodec.decode(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1" +
            "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4");

        byte[] ciphertext = AesUtils.encrypt(seed, key, iv);
        byte[] decrypted = AesUtils.decrypt(ciphertext, key, iv);
        assertArrayEquals(seed, decrypted);
    }

    @Test
    public void wrongKeyFailsDecryption() throws Exception {
        byte[] key = HexCodec.decode("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4");
        byte[] wrongKey = HexCodec.decode("000000000000000000000000000000000000000000000000000000000000dead");
        byte[] iv = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        byte[] plaintext = "secret data".getBytes("UTF-8");

        byte[] ciphertext = AesUtils.encrypt(plaintext, key, iv);

        try {
            byte[] decrypted = AesUtils.decrypt(ciphertext, wrongKey, iv);
            // If decryption "succeeds" (bad padding may still pass rarely),
            // the output must not match the original
            assertFalse(java.util.Arrays.equals(plaintext, decrypted));
        } catch (Exception e) {
            // InvalidCipherTextException is expected — wrong key corrupts padding
            assertTrue(e.getMessage() != null);
        }
    }

    @Test
    public void wrongIvProducesDifferentOutput() throws Exception {
        byte[] key = HexCodec.decode("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4");
        byte[] iv1 = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        byte[] iv2 = HexCodec.decode("ff0102030405060708090a0b0c0d0e0f");
        byte[] plaintext = "same data different iv".getBytes("UTF-8");

        byte[] ct1 = AesUtils.encrypt(plaintext, key, iv1);
        byte[] ct2 = AesUtils.encrypt(plaintext, key, iv2);
        assertFalse(java.util.Arrays.equals(ct1, ct2));
    }

    @Test
    public void ciphertextIsMultipleOfBlockSize() throws Exception {
        byte[] key = HexCodec.decode("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4");
        byte[] iv = HexCodec.decode("000102030405060708090a0b0c0d0e0f");

        // Test various plaintext sizes
        for (int size = 1; size <= 80; size++) {
            byte[] plaintext = new byte[size];
            for (int i = 0; i < size; i++) {
                plaintext[i] = (byte) (i & 0xFF);
            }
            byte[] ciphertext = AesUtils.encrypt(plaintext, key, iv);
            assertEquals("Ciphertext for size " + size + " must be block-aligned",
                0, ciphertext.length % 16);
        }
    }

    @Test
    public void seedWithPassphraseRoundTrip() throws Exception {
        // Simulates the real storage format: seed(64) + passphrase_len(2) + passphrase
        byte[] key = HexCodec.decode("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4");
        byte[] iv = HexCodec.decode("000102030405060708090a0b0c0d0e0f");

        byte[] seed = new byte[64];
        for (int i = 0; i < 64; i++) { seed[i] = (byte) i; }
        byte[] passBytes = "mypassphrase".getBytes("UTF-8");
        // Build plaintext: seed(64) + len(2 BE) + passphrase
        byte[] plaintext = new byte[64 + 2 + passBytes.length];
        System.arraycopy(seed, 0, plaintext, 0, 64);
        plaintext[64] = (byte) ((passBytes.length >> 8) & 0xFF);
        plaintext[65] = (byte) (passBytes.length & 0xFF);
        System.arraycopy(passBytes, 0, plaintext, 66, passBytes.length);

        byte[] ciphertext = AesUtils.encrypt(plaintext, key, iv);
        byte[] decrypted = AesUtils.decrypt(ciphertext, key, iv);
        assertArrayEquals(plaintext, decrypted);
    }
}
```

**Step 2: Run tests to verify they fail**

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test
```

Expected: compilation failure — `AesUtils` class does not exist.

**Step 3: Implement AesUtils.java**

```java
package org.burnerwallet.core;

import org.bouncycastle.crypto.engines.AESLightEngine;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.paddings.PKCS7Padding;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.InvalidCipherTextException;

/**
 * AES-256-CBC encryption and decryption using Bouncy Castle AESLightEngine.
 *
 * AESLightEngine uses no static lookup tables, minimizing RAM usage
 * on the Nokia C1-01 (2 MB Java heap).
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class AesUtils {

    /**
     * Encrypt plaintext with AES-256-CBC and PKCS7 padding.
     *
     * @param plaintext data to encrypt (any length)
     * @param key       32-byte (256-bit) AES key
     * @param iv        16-byte initialization vector
     * @return ciphertext (multiple of 16 bytes)
     * @throws CryptoError if encryption fails
     */
    public static byte[] encrypt(byte[] plaintext, byte[] key, byte[] iv)
            throws CryptoError {
        try {
            PaddedBufferedBlockCipher cipher = createCipher(true, key, iv);

            byte[] output = new byte[cipher.getOutputSize(plaintext.length)];
            int len = cipher.processBytes(plaintext, 0, plaintext.length, output, 0);
            len += cipher.doFinal(output, len);

            // Trim if getOutputSize over-estimated
            if (len < output.length) {
                byte[] trimmed = new byte[len];
                System.arraycopy(output, 0, trimmed, 0, len);
                return trimmed;
            }
            return output;
        } catch (InvalidCipherTextException e) {
            throw new CryptoError(CryptoError.ERR_ENCRYPTION, "Encryption failed: " + e.getMessage());
        }
    }

    /**
     * Decrypt ciphertext with AES-256-CBC and PKCS7 padding.
     *
     * @param ciphertext data to decrypt (must be multiple of 16 bytes)
     * @param key        32-byte (256-bit) AES key
     * @param iv         16-byte initialization vector
     * @return decrypted plaintext
     * @throws CryptoError if decryption or padding validation fails
     */
    public static byte[] decrypt(byte[] ciphertext, byte[] key, byte[] iv)
            throws CryptoError {
        try {
            PaddedBufferedBlockCipher cipher = createCipher(false, key, iv);

            byte[] output = new byte[cipher.getOutputSize(ciphertext.length)];
            int len = cipher.processBytes(ciphertext, 0, ciphertext.length, output, 0);
            len += cipher.doFinal(output, len);

            byte[] result = new byte[len];
            System.arraycopy(output, 0, result, 0, len);
            return result;
        } catch (InvalidCipherTextException e) {
            throw new CryptoError(CryptoError.ERR_DECRYPTION, "Decryption failed: " + e.getMessage());
        }
    }

    private static PaddedBufferedBlockCipher createCipher(boolean encrypt, byte[] key, byte[] iv) {
        PaddedBufferedBlockCipher cipher = new PaddedBufferedBlockCipher(
            new CBCBlockCipher(new AESLightEngine()),
            new PKCS7Padding()
        );
        CipherParameters params = new ParametersWithIV(new KeyParameter(key), iv);
        cipher.init(encrypt, params);
        return cipher;
    }
}
```

**Important:** The `CryptoError` class needs two new error codes. Add these constants to `signer/src/org/burnerwallet/core/CryptoError.java`:

```java
public static final int ERR_ENCRYPTION = 7;
public static final int ERR_DECRYPTION = 8;
```

**Step 4: Run tests to verify they pass**

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test
```

Expected: all tests pass (previous 114 + new AES tests).

**Step 5: Commit**

```bash
git add signer/src/org/burnerwallet/core/AesUtils.java \
       signer/src/org/burnerwallet/core/CryptoError.java \
       signer/test/org/burnerwallet/core/AesUtilsTest.java
git commit -s -m "feat(signer): add AES-256-CBC encrypt/decrypt via BC AESLightEngine"
```

---

## Task 2: WalletData — blob serialization value type

A simple value type that serializes/deserializes the binary record format for storage. No MIDP dependency — pure Java 1.4 byte manipulation.

**Files:**
- Create: `signer/src/org/burnerwallet/storage/WalletData.java`
- Create: `signer/test/org/burnerwallet/storage/WalletDataTest.java`

**Step 1: Write WalletDataTest.java**

```java
package org.burnerwallet.storage;

import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HexCodec;
import org.junit.Test;
import static org.junit.Assert.*;

public class WalletDataTest {

    @Test
    public void serializeDeserializeSeedBlob() throws CryptoError {
        byte[] salt = HexCodec.decode("00112233445566778899aabbccddeeff");
        byte[] iv = HexCodec.decode("ffeeddccbbaa99887766554433221100");
        int iterations = 5000;
        byte[] ciphertext = new byte[80];
        for (int i = 0; i < 80; i++) { ciphertext[i] = (byte) i; }

        byte[] blob = WalletData.serializeSeedBlob(salt, iv, iterations, ciphertext);
        assertEquals(116, blob.length);

        // Deserialize and verify
        assertEquals(16, WalletData.getSalt(blob).length);
        assertArrayEquals(salt, WalletData.getSalt(blob));

        assertEquals(16, WalletData.getIv(blob).length);
        assertArrayEquals(iv, WalletData.getIv(blob));

        assertEquals(5000, WalletData.getIterations(blob));

        assertEquals(80, WalletData.getCiphertext(blob).length);
        assertArrayEquals(ciphertext, WalletData.getCiphertext(blob));
    }

    @Test
    public void iterationsEncodedBigEndian() throws CryptoError {
        byte[] salt = new byte[16];
        byte[] iv = new byte[16];
        byte[] ciphertext = new byte[80];

        byte[] blob = WalletData.serializeSeedBlob(salt, iv, 256, ciphertext);
        // iterations at offset 32, big-endian: 0x00000100
        assertEquals(0, blob[32]);
        assertEquals(0, blob[33]);
        assertEquals(1, blob[34]);
        assertEquals(0, blob[35]);
    }

    @Test
    public void serializeDeserializeConfig() throws CryptoError {
        byte[] config = WalletData.serializeConfig(false, true, 42);
        assertEquals(6, config.length);

        assertFalse(WalletData.getNetworkTestnet(config));
        assertTrue(WalletData.getHasPassphrase(config));
        assertEquals(42, WalletData.getAddressIndex(config));
    }

    @Test
    public void configMainnetNoPassphrase() throws CryptoError {
        byte[] config = WalletData.serializeConfig(false, false, 0);
        assertFalse(WalletData.getNetworkTestnet(config));
        assertFalse(WalletData.getHasPassphrase(config));
        assertEquals(0, WalletData.getAddressIndex(config));
    }

    @Test
    public void configTestnetWithPassphrase() throws CryptoError {
        byte[] config = WalletData.serializeConfig(true, true, 999);
        assertTrue(WalletData.getNetworkTestnet(config));
        assertTrue(WalletData.getHasPassphrase(config));
        assertEquals(999, WalletData.getAddressIndex(config));
    }

    @Test
    public void serializeDeserializePlaintext() throws Exception {
        byte[] seed = new byte[64];
        for (int i = 0; i < 64; i++) { seed[i] = (byte) i; }
        String passphrase = "mypass";

        byte[] plaintext = WalletData.buildPlaintext(seed, passphrase);
        // 64 + 2 + 6 = 72
        assertEquals(72, plaintext.length);

        byte[] extractedSeed = WalletData.extractSeed(plaintext);
        assertArrayEquals(seed, extractedSeed);

        String extractedPass = WalletData.extractPassphrase(plaintext);
        assertEquals("mypass", extractedPass);
    }

    @Test
    public void plaintextWithEmptyPassphrase() throws Exception {
        byte[] seed = new byte[64];
        String passphrase = "";

        byte[] plaintext = WalletData.buildPlaintext(seed, passphrase);
        assertEquals(66, plaintext.length); // 64 + 2 + 0

        byte[] extractedSeed = WalletData.extractSeed(plaintext);
        assertArrayEquals(seed, extractedSeed);

        String extractedPass = WalletData.extractPassphrase(plaintext);
        assertEquals("", extractedPass);
    }

    @Test
    public void plaintextWithLongPassphrase() throws Exception {
        byte[] seed = new byte[64];
        String passphrase = "this is a longer passphrase for testing purposes";

        byte[] plaintext = WalletData.buildPlaintext(seed, passphrase);

        byte[] extractedSeed = WalletData.extractSeed(plaintext);
        assertArrayEquals(seed, extractedSeed);

        String extractedPass = WalletData.extractPassphrase(plaintext);
        assertEquals(passphrase, extractedPass);
    }
}
```

**Step 2: Run tests to verify they fail**

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test
```

Expected: compilation failure — `WalletData` class does not exist.

**Step 3: Implement WalletData.java**

```java
package org.burnerwallet.storage;

import org.burnerwallet.core.ByteArrayUtils;

/**
 * Value type for wallet storage binary formats.
 *
 * Seed blob format (116 bytes):
 *   salt(16) + iv(16) + iterations(4 BE) + ciphertext(80)
 *
 * Config format (6 bytes):
 *   network(1: 0=mainnet, 1=testnet) + hasPassphrase(1: 0/1) + addressIndex(4 BE)
 *
 * Plaintext format (before encryption):
 *   seed(64) + passphrase_length(2 BE) + passphrase_utf8(0-N)
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class WalletData {

    // Seed blob offsets
    private static final int SALT_OFFSET = 0;
    private static final int SALT_LEN = 16;
    private static final int IV_OFFSET = 16;
    private static final int IV_LEN = 16;
    private static final int ITER_OFFSET = 32;
    private static final int ITER_LEN = 4;
    private static final int CT_OFFSET = 36;

    // Config offsets
    private static final int CFG_NETWORK = 0;
    private static final int CFG_HAS_PASS = 1;
    private static final int CFG_ADDR_IDX = 2;

    // Plaintext offsets
    private static final int SEED_LEN = 64;
    private static final int PASS_LEN_OFFSET = 64;

    /** Serialize a seed blob for RecordStore record 1. */
    public static byte[] serializeSeedBlob(byte[] salt, byte[] iv,
                                            int iterations, byte[] ciphertext) {
        byte[] blob = new byte[SALT_LEN + IV_LEN + ITER_LEN + ciphertext.length];
        System.arraycopy(salt, 0, blob, SALT_OFFSET, SALT_LEN);
        System.arraycopy(iv, 0, blob, IV_OFFSET, IV_LEN);
        blob[ITER_OFFSET]     = (byte) ((iterations >> 24) & 0xFF);
        blob[ITER_OFFSET + 1] = (byte) ((iterations >> 16) & 0xFF);
        blob[ITER_OFFSET + 2] = (byte) ((iterations >> 8) & 0xFF);
        blob[ITER_OFFSET + 3] = (byte) (iterations & 0xFF);
        System.arraycopy(ciphertext, 0, blob, CT_OFFSET, ciphertext.length);
        return blob;
    }

    /** Extract salt (16 bytes) from seed blob. */
    public static byte[] getSalt(byte[] blob) {
        return ByteArrayUtils.copyOfRange(blob, SALT_OFFSET, SALT_OFFSET + SALT_LEN);
    }

    /** Extract IV (16 bytes) from seed blob. */
    public static byte[] getIv(byte[] blob) {
        return ByteArrayUtils.copyOfRange(blob, IV_OFFSET, IV_OFFSET + IV_LEN);
    }

    /** Extract iterations (int) from seed blob. */
    public static int getIterations(byte[] blob) {
        return ((blob[ITER_OFFSET] & 0xFF) << 24)
             | ((blob[ITER_OFFSET + 1] & 0xFF) << 16)
             | ((blob[ITER_OFFSET + 2] & 0xFF) << 8)
             | (blob[ITER_OFFSET + 3] & 0xFF);
    }

    /** Extract ciphertext from seed blob (everything after header). */
    public static byte[] getCiphertext(byte[] blob) {
        return ByteArrayUtils.copyOfRange(blob, CT_OFFSET, blob.length);
    }

    /** Serialize config for RecordStore record 3. */
    public static byte[] serializeConfig(boolean testnet, boolean hasPassphrase,
                                          int addressIndex) {
        byte[] config = new byte[6];
        config[CFG_NETWORK] = (byte) (testnet ? 1 : 0);
        config[CFG_HAS_PASS] = (byte) (hasPassphrase ? 1 : 0);
        config[CFG_ADDR_IDX]     = (byte) ((addressIndex >> 24) & 0xFF);
        config[CFG_ADDR_IDX + 1] = (byte) ((addressIndex >> 16) & 0xFF);
        config[CFG_ADDR_IDX + 2] = (byte) ((addressIndex >> 8) & 0xFF);
        config[CFG_ADDR_IDX + 3] = (byte) (addressIndex & 0xFF);
        return config;
    }

    /** Check if config specifies testnet. */
    public static boolean getNetworkTestnet(byte[] config) {
        return config[CFG_NETWORK] == 1;
    }

    /** Check if config indicates a passphrase is stored. */
    public static boolean getHasPassphrase(byte[] config) {
        return config[CFG_HAS_PASS] == 1;
    }

    /** Get address index from config. */
    public static int getAddressIndex(byte[] config) {
        return ((config[CFG_ADDR_IDX] & 0xFF) << 24)
             | ((config[CFG_ADDR_IDX + 1] & 0xFF) << 16)
             | ((config[CFG_ADDR_IDX + 2] & 0xFF) << 8)
             | (config[CFG_ADDR_IDX + 3] & 0xFF);
    }

    /**
     * Build plaintext for encryption: seed(64) + passphrase_length(2 BE) + passphrase_utf8.
     */
    public static byte[] buildPlaintext(byte[] seed, String passphrase) {
        byte[] passBytes;
        try {
            passBytes = passphrase.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            passBytes = passphrase.getBytes();
        }
        byte[] plaintext = new byte[SEED_LEN + 2 + passBytes.length];
        System.arraycopy(seed, 0, plaintext, 0, SEED_LEN);
        plaintext[PASS_LEN_OFFSET]     = (byte) ((passBytes.length >> 8) & 0xFF);
        plaintext[PASS_LEN_OFFSET + 1] = (byte) (passBytes.length & 0xFF);
        if (passBytes.length > 0) {
            System.arraycopy(passBytes, 0, plaintext, PASS_LEN_OFFSET + 2, passBytes.length);
        }
        return plaintext;
    }

    /** Extract seed (first 64 bytes) from decrypted plaintext. */
    public static byte[] extractSeed(byte[] plaintext) {
        return ByteArrayUtils.copyOfRange(plaintext, 0, SEED_LEN);
    }

    /** Extract passphrase from decrypted plaintext. */
    public static String extractPassphrase(byte[] plaintext) {
        int passLen = ((plaintext[PASS_LEN_OFFSET] & 0xFF) << 8)
                    | (plaintext[PASS_LEN_OFFSET + 1] & 0xFF);
        if (passLen == 0) {
            return "";
        }
        byte[] passBytes = ByteArrayUtils.copyOfRange(
            plaintext, PASS_LEN_OFFSET + 2, PASS_LEN_OFFSET + 2 + passLen);
        try {
            return new String(passBytes, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return new String(passBytes);
        }
    }
}
```

**Step 4: Run tests to verify they pass**

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test
```

Expected: all tests pass.

**Step 5: Commit**

```bash
git add signer/src/org/burnerwallet/storage/WalletData.java \
       signer/test/org/burnerwallet/storage/WalletDataTest.java
git commit -s -m "feat(signer): add WalletData blob serialization for encrypted storage"
```

---

## Task 3: RecordStoreAdapter + InMemoryRecordStoreAdapter — testable RMS abstraction

Creates the interface that abstracts MIDP RecordStore, plus an in-memory implementation for JUnit tests. The production MIDP implementation comes in a later task since it can't be tested without a MIDP runtime.

**Files:**
- Create: `signer/src/org/burnerwallet/storage/RecordStoreAdapter.java`
- Create: `signer/test/org/burnerwallet/storage/InMemoryRecordStoreAdapter.java`

**Step 1: Implement RecordStoreAdapter.java (interface)**

```java
package org.burnerwallet.storage;

/**
 * Abstraction over MIDP RecordStore for testability.
 *
 * Production code uses MidpRecordStoreAdapter (real RMS).
 * Tests use InMemoryRecordStoreAdapter (HashMap-based).
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public interface RecordStoreAdapter {

    /**
     * Open a named record store, optionally creating it.
     *
     * @param name   store name (1-32 chars)
     * @param create true to create if not exists
     * @throws Exception if store cannot be opened
     */
    void open(String name, boolean create) throws Exception;

    /**
     * Get a record by ID. Returns a new byte array.
     *
     * @param recordId record ID (starts at 1)
     * @return record data, or null if not found
     * @throws Exception on I/O error
     */
    byte[] getRecord(int recordId) throws Exception;

    /**
     * Add a new record. Returns the assigned record ID.
     *
     * @param data record bytes
     * @return new record ID
     * @throws Exception on I/O error
     */
    int addRecord(byte[] data) throws Exception;

    /**
     * Update an existing record.
     *
     * @param recordId record ID
     * @param data     new data
     * @throws Exception if record doesn't exist or I/O error
     */
    void setRecord(int recordId, byte[] data) throws Exception;

    /**
     * Get the number of records in the store.
     *
     * @return record count
     * @throws Exception on I/O error
     */
    int getNumRecords() throws Exception;

    /**
     * Close the record store.
     *
     * @throws Exception on I/O error
     */
    void close() throws Exception;

    /**
     * Delete the entire record store. Store must be closed first.
     *
     * @throws Exception on I/O error
     */
    void deleteStore() throws Exception;
}
```

**Step 2: Implement InMemoryRecordStoreAdapter.java (test double)**

Place this in the test directory since it's only for testing:

```java
package org.burnerwallet.storage;

import java.util.HashMap;

/**
 * In-memory implementation of RecordStoreAdapter for JUnit tests.
 * Uses a HashMap to simulate MIDP RecordStore behavior.
 */
public class InMemoryRecordStoreAdapter implements RecordStoreAdapter {

    private HashMap records = new HashMap();
    private int nextId = 1;
    private boolean open = false;
    private String storeName;

    // Static registry to simulate persistence across open/close
    private static HashMap storeRegistry = new HashMap();

    public void open(String name, boolean create) throws Exception {
        this.storeName = name;
        HashMap existing = (HashMap) storeRegistry.get(name);
        if (existing != null) {
            this.records = existing;
            // Find the next available ID
            this.nextId = 1;
            java.util.Iterator it = records.keySet().iterator();
            while (it.hasNext()) {
                int id = ((Integer) it.next()).intValue();
                if (id >= nextId) {
                    nextId = id + 1;
                }
            }
        } else if (create) {
            this.records = new HashMap();
            storeRegistry.put(name, this.records);
            this.nextId = 1;
        } else {
            throw new Exception("RecordStore not found: " + name);
        }
        this.open = true;
    }

    public byte[] getRecord(int recordId) throws Exception {
        checkOpen();
        byte[] data = (byte[]) records.get(new Integer(recordId));
        if (data == null) {
            throw new Exception("Record not found: " + recordId);
        }
        // Return a copy like real RMS does
        byte[] copy = new byte[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        return copy;
    }

    public int addRecord(byte[] data) throws Exception {
        checkOpen();
        int id = nextId++;
        byte[] copy = new byte[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        records.put(new Integer(id), copy);
        return id;
    }

    public void setRecord(int recordId, byte[] data) throws Exception {
        checkOpen();
        if (!records.containsKey(new Integer(recordId))) {
            throw new Exception("Record not found: " + recordId);
        }
        byte[] copy = new byte[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        records.put(new Integer(recordId), copy);
    }

    public int getNumRecords() throws Exception {
        checkOpen();
        return records.size();
    }

    public void close() throws Exception {
        open = false;
    }

    public void deleteStore() throws Exception {
        if (storeName != null) {
            storeRegistry.remove(storeName);
        }
        records.clear();
        nextId = 1;
        open = false;
    }

    /** Reset all stores. Call in test setUp/tearDown. */
    public static void resetAll() {
        storeRegistry.clear();
    }

    private void checkOpen() throws Exception {
        if (!open) {
            throw new Exception("RecordStore not open");
        }
    }
}
```

**Step 3: No tests needed for this task** — the adapter is tested via WalletStore tests in Task 4. Just verify the project still compiles:

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test
```

Expected: all existing tests still pass.

**Step 4: Commit**

```bash
git add signer/src/org/burnerwallet/storage/RecordStoreAdapter.java \
       signer/test/org/burnerwallet/storage/InMemoryRecordStoreAdapter.java
git commit -s -m "feat(signer): add RecordStoreAdapter interface and in-memory test double"
```

---

## Task 4: WalletStore — encrypted storage CRUD

The main storage class that ties together AES encryption, PBKDF2, and RecordStore. Handles wallet creation, unlock, PIN verification, and config management.

**Files:**
- Create: `signer/src/org/burnerwallet/storage/WalletStore.java`
- Create: `signer/test/org/burnerwallet/storage/WalletStoreTest.java`

**Step 1: Write WalletStoreTest.java**

```java
package org.burnerwallet.storage;

import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HexCodec;
import org.burnerwallet.core.HashUtils;
import org.burnerwallet.core.ByteArrayUtils;
import org.burnerwallet.chains.bitcoin.Bip39Mnemonic;
import org.burnerwallet.chains.bitcoin.BitcoinAddress;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class WalletStoreTest {

    private WalletStore store;

    @Before
    public void setUp() {
        InMemoryRecordStoreAdapter.resetAll();
        store = new WalletStore(new InMemoryRecordStoreAdapter());
    }

    @Test
    public void walletNotExistsInitially() throws Exception {
        assertFalse(store.walletExists());
    }

    @Test
    public void createAndVerifyPin() throws Exception {
        byte[] seed = new byte[64];
        for (int i = 0; i < 64; i++) { seed[i] = (byte) i; }

        store.createWallet(seed, "", "1234", false);
        assertTrue(store.walletExists());
        assertTrue(store.verifyPin("1234"));
        assertFalse(store.verifyPin("9999"));
        assertFalse(store.verifyPin("12345"));
    }

    @Test
    public void unlockRetrievesSeed() throws Exception {
        byte[] seed = new byte[64];
        for (int i = 0; i < 64; i++) { seed[i] = (byte) i; }

        store.createWallet(seed, "", "5678", false);

        byte[] retrieved = store.unlock("5678");
        assertNotNull(retrieved);
        assertArrayEquals(seed, retrieved);
    }

    @Test
    public void unlockWithPassphrase() throws Exception {
        byte[] seed = new byte[64];
        for (int i = 0; i < 64; i++) { seed[i] = (byte) i; }

        store.createWallet(seed, "mypassphrase", "1234", false);

        byte[] retrieved = store.unlock("1234");
        assertArrayEquals(seed, retrieved);

        String passphrase = store.getPassphrase("1234");
        assertEquals("mypassphrase", passphrase);
    }

    @Test
    public void unlockWrongPinReturnsNull() throws Exception {
        byte[] seed = new byte[64];
        store.createWallet(seed, "", "1234", false);

        assertNull(store.unlock("0000"));
    }

    @Test
    public void configDefaults() throws Exception {
        byte[] seed = new byte[64];
        store.createWallet(seed, "", "1234", false);

        assertFalse(store.isTestnet());
        assertEquals(0, store.getAddressIndex());
    }

    @Test
    public void configTestnet() throws Exception {
        byte[] seed = new byte[64];
        store.createWallet(seed, "", "1234", true);

        assertTrue(store.isTestnet());
    }

    @Test
    public void setNetworkPersists() throws Exception {
        byte[] seed = new byte[64];
        store.createWallet(seed, "", "1234", false);

        store.setTestnet(true);
        assertTrue(store.isTestnet());

        store.setTestnet(false);
        assertFalse(store.isTestnet());
    }

    @Test
    public void setAddressIndexPersists() throws Exception {
        byte[] seed = new byte[64];
        store.createWallet(seed, "", "1234", false);

        store.setAddressIndex(5);
        assertEquals(5, store.getAddressIndex());
    }

    @Test
    public void wipeDeletesEverything() throws Exception {
        byte[] seed = new byte[64];
        store.createWallet(seed, "", "1234", false);
        assertTrue(store.walletExists());

        store.wipe();
        assertFalse(store.walletExists());
    }

    @Test
    public void crossImplRoundTrip() throws Exception {
        // End-to-end: store the "abandon" mnemonic seed, unlock, derive address
        String mnemonic = "abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon about";
        byte[] seed = Bip39Mnemonic.toSeed(mnemonic, "");

        store.createWallet(seed, "", "4321", false);
        byte[] retrieved = store.unlock("4321");

        String addr = BitcoinAddress.deriveP2wpkhAddress(retrieved, false, 0, false, 0);
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", addr);
    }
}
```

**Step 2: Run tests to verify they fail**

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test
```

Expected: compilation failure — `WalletStore` class does not exist.

**Step 3: Implement WalletStore.java**

```java
package org.burnerwallet.storage;

import org.burnerwallet.core.AesUtils;
import org.burnerwallet.core.ByteArrayUtils;
import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HashUtils;

/**
 * Encrypted wallet storage backed by RecordStoreAdapter.
 *
 * Uses a single record store named "bw" with three fixed records:
 *   Record 1: Encrypted seed blob (salt + iv + iterations + ciphertext)
 *   Record 2: PIN verification hash (SHA-256 of derived AES key)
 *   Record 3: Config (network + hasPassphrase + addressIndex)
 *
 * Encryption: AES-256-CBC with PIN-derived key via PBKDF2-HMAC-SHA512.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class WalletStore {

    private static final String STORE_NAME = "bw";
    private static final int RECORD_SEED = 1;
    private static final int RECORD_PIN_HASH = 2;
    private static final int RECORD_CONFIG = 3;
    private static final int PBKDF2_ITERATIONS = 5000;
    private static final int AES_KEY_LEN = 32;

    private final RecordStoreAdapter adapter;

    public WalletStore(RecordStoreAdapter adapter) {
        this.adapter = adapter;
    }

    /**
     * Check if a wallet has been created.
     */
    public boolean walletExists() {
        try {
            adapter.open(STORE_NAME, false);
            int count = adapter.getNumRecords();
            adapter.close();
            return count >= 3;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Create a new wallet: encrypt the seed with a PIN-derived key and store it.
     *
     * @param seed       64-byte BIP39 seed
     * @param passphrase BIP39 passphrase (empty string for none)
     * @param pin        4-8 digit PIN string
     * @param testnet    true for testnet, false for mainnet
     * @throws CryptoError if encryption fails
     * @throws Exception   if storage fails
     */
    public void createWallet(byte[] seed, String passphrase, String pin, boolean testnet)
            throws CryptoError, Exception {
        // Generate salt and IV (in production, EntropyCollector provides these;
        // for tests, we use PBKDF2 with a counter as a deterministic fallback)
        byte[] salt = generateBytes(16, pin, 1);
        byte[] iv = generateBytes(16, pin, 2);

        // Derive AES key from PIN
        byte[] pinBytes = getUtf8Bytes(pin);
        byte[] aesKey = HashUtils.pbkdf2HmacSha512(pinBytes, salt, PBKDF2_ITERATIONS, AES_KEY_LEN);

        // Build plaintext: seed + passphrase
        byte[] plaintext = WalletData.buildPlaintext(seed, passphrase);

        // Encrypt
        byte[] ciphertext = AesUtils.encrypt(plaintext, aesKey, iv);
        ByteArrayUtils.zeroFill(plaintext);

        // PIN verification hash
        byte[] pinHash = HashUtils.sha256(aesKey);

        // Serialize seed blob
        byte[] seedBlob = WalletData.serializeSeedBlob(salt, iv, PBKDF2_ITERATIONS, ciphertext);

        // Config
        boolean hasPassphrase = passphrase.length() > 0;
        byte[] config = WalletData.serializeConfig(testnet, hasPassphrase, 0);

        // Store records
        adapter.open(STORE_NAME, true);
        try {
            adapter.addRecord(seedBlob);   // ID 1
            adapter.addRecord(pinHash);    // ID 2
            adapter.addRecord(config);     // ID 3
        } finally {
            adapter.close();
        }

        ByteArrayUtils.zeroFill(aesKey);
    }

    /**
     * Verify a PIN without decrypting the seed.
     *
     * @param pin PIN to verify
     * @return true if PIN matches
     */
    public boolean verifyPin(String pin) {
        try {
            adapter.open(STORE_NAME, false);
            byte[] seedBlob = adapter.getRecord(RECORD_SEED);
            byte[] storedHash = adapter.getRecord(RECORD_PIN_HASH);
            adapter.close();

            byte[] salt = WalletData.getSalt(seedBlob);
            int iterations = WalletData.getIterations(seedBlob);

            byte[] pinBytes = getUtf8Bytes(pin);
            byte[] aesKey = HashUtils.pbkdf2HmacSha512(pinBytes, salt, iterations, AES_KEY_LEN);
            byte[] pinHash = HashUtils.sha256(aesKey);
            ByteArrayUtils.zeroFill(aesKey);

            return ByteArrayUtils.constantTimeEquals(pinHash, storedHash);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Unlock the wallet: verify PIN and decrypt the seed.
     *
     * @param pin PIN to unlock with
     * @return 64-byte seed, or null if PIN is wrong
     * @throws CryptoError if decryption fails (not due to wrong PIN)
     */
    public byte[] unlock(String pin) throws CryptoError {
        try {
            adapter.open(STORE_NAME, false);
            byte[] seedBlob = adapter.getRecord(RECORD_SEED);
            byte[] storedHash = adapter.getRecord(RECORD_PIN_HASH);
            adapter.close();

            byte[] salt = WalletData.getSalt(seedBlob);
            byte[] iv = WalletData.getIv(seedBlob);
            int iterations = WalletData.getIterations(seedBlob);
            byte[] ciphertext = WalletData.getCiphertext(seedBlob);

            byte[] pinBytes = getUtf8Bytes(pin);
            byte[] aesKey = HashUtils.pbkdf2HmacSha512(pinBytes, salt, iterations, AES_KEY_LEN);
            byte[] pinHash = HashUtils.sha256(aesKey);

            if (!ByteArrayUtils.constantTimeEquals(pinHash, storedHash)) {
                ByteArrayUtils.zeroFill(aesKey);
                return null;
            }

            byte[] plaintext = AesUtils.decrypt(ciphertext, aesKey, iv);
            ByteArrayUtils.zeroFill(aesKey);

            byte[] seed = WalletData.extractSeed(plaintext);
            ByteArrayUtils.zeroFill(plaintext);
            return seed;
        } catch (CryptoError ce) {
            throw ce;
        } catch (Exception e) {
            throw new CryptoError(CryptoError.ERR_DECRYPTION, "Unlock failed: " + e.getMessage());
        }
    }

    /**
     * Get the stored passphrase (requires PIN for decryption).
     *
     * @param pin PIN to decrypt with
     * @return passphrase string, or empty if none
     */
    public String getPassphrase(String pin) throws CryptoError {
        try {
            adapter.open(STORE_NAME, false);
            byte[] seedBlob = adapter.getRecord(RECORD_SEED);
            byte[] storedHash = adapter.getRecord(RECORD_PIN_HASH);
            adapter.close();

            byte[] salt = WalletData.getSalt(seedBlob);
            byte[] iv = WalletData.getIv(seedBlob);
            int iterations = WalletData.getIterations(seedBlob);
            byte[] ciphertext = WalletData.getCiphertext(seedBlob);

            byte[] pinBytes = getUtf8Bytes(pin);
            byte[] aesKey = HashUtils.pbkdf2HmacSha512(pinBytes, salt, iterations, AES_KEY_LEN);
            byte[] pinHash = HashUtils.sha256(aesKey);

            if (!ByteArrayUtils.constantTimeEquals(pinHash, storedHash)) {
                ByteArrayUtils.zeroFill(aesKey);
                return "";
            }

            byte[] plaintext = AesUtils.decrypt(ciphertext, aesKey, iv);
            ByteArrayUtils.zeroFill(aesKey);

            String passphrase = WalletData.extractPassphrase(plaintext);
            ByteArrayUtils.zeroFill(plaintext);
            return passphrase;
        } catch (CryptoError ce) {
            throw ce;
        } catch (Exception e) {
            throw new CryptoError(CryptoError.ERR_DECRYPTION, "Failed to get passphrase: " + e.getMessage());
        }
    }

    /** Check if current config is testnet. */
    public boolean isTestnet() {
        try {
            adapter.open(STORE_NAME, false);
            byte[] config = adapter.getRecord(RECORD_CONFIG);
            adapter.close();
            return WalletData.getNetworkTestnet(config);
        } catch (Exception e) {
            return false;
        }
    }

    /** Set network to testnet or mainnet. */
    public void setTestnet(boolean testnet) throws Exception {
        adapter.open(STORE_NAME, false);
        byte[] config = adapter.getRecord(RECORD_CONFIG);
        boolean hasPass = WalletData.getHasPassphrase(config);
        int addrIdx = WalletData.getAddressIndex(config);
        byte[] newConfig = WalletData.serializeConfig(testnet, hasPass, addrIdx);
        adapter.setRecord(RECORD_CONFIG, newConfig);
        adapter.close();
    }

    /** Get current address index. */
    public int getAddressIndex() {
        try {
            adapter.open(STORE_NAME, false);
            byte[] config = adapter.getRecord(RECORD_CONFIG);
            adapter.close();
            return WalletData.getAddressIndex(config);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Set address index. */
    public void setAddressIndex(int index) throws Exception {
        adapter.open(STORE_NAME, false);
        byte[] config = adapter.getRecord(RECORD_CONFIG);
        boolean testnet = WalletData.getNetworkTestnet(config);
        boolean hasPass = WalletData.getHasPassphrase(config);
        byte[] newConfig = WalletData.serializeConfig(testnet, hasPass, index);
        adapter.setRecord(RECORD_CONFIG, newConfig);
        adapter.close();
    }

    /** Delete the entire wallet (factory reset). */
    public void wipe() throws Exception {
        try {
            adapter.deleteStore();
        } catch (Exception e) {
            // Store may not exist, that's fine
        }
    }

    /**
     * Create a wallet with pre-generated salt and IV (for production use
     * with EntropyCollector).
     */
    public void createWalletWithEntropy(byte[] seed, String passphrase, String pin,
                                         boolean testnet, byte[] salt, byte[] iv)
            throws CryptoError, Exception {
        byte[] pinBytes = getUtf8Bytes(pin);
        byte[] aesKey = HashUtils.pbkdf2HmacSha512(pinBytes, salt, PBKDF2_ITERATIONS, AES_KEY_LEN);

        byte[] plaintext = WalletData.buildPlaintext(seed, passphrase);
        byte[] ciphertext = AesUtils.encrypt(plaintext, aesKey, iv);
        ByteArrayUtils.zeroFill(plaintext);

        byte[] pinHash = HashUtils.sha256(aesKey);
        byte[] seedBlob = WalletData.serializeSeedBlob(salt, iv, PBKDF2_ITERATIONS, ciphertext);

        boolean hasPassphrase = passphrase.length() > 0;
        byte[] config = WalletData.serializeConfig(testnet, hasPassphrase, 0);

        adapter.open(STORE_NAME, true);
        try {
            adapter.addRecord(seedBlob);
            adapter.addRecord(pinHash);
            adapter.addRecord(config);
        } finally {
            adapter.close();
        }

        ByteArrayUtils.zeroFill(aesKey);
    }

    /** Get UTF-8 bytes from a string. */
    private static byte[] getUtf8Bytes(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s.getBytes();
        }
    }

    /**
     * Generate deterministic bytes for salt/IV when no EntropyCollector is available.
     * Uses PBKDF2 with the PIN and a counter as a simple KDF.
     * Only used in tests and as a fallback — production code should use EntropyCollector.
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
        byte[] derived = HashUtils.pbkdf2HmacSha512(inputBytes, salt, 1, length);
        return derived;
    }
}
```

**Step 4: Run tests to verify they pass**

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test
```

Expected: all tests pass.

**Step 5: Commit**

```bash
git add signer/src/org/burnerwallet/storage/WalletStore.java \
       signer/test/org/burnerwallet/storage/WalletStoreTest.java
git commit -s -m "feat(signer): add WalletStore — PIN-encrypted seed storage with PBKDF2 + AES"
```

---

## Task 5: EntropyCollector — keypad timing entropy source

Collects entropy from keypad timing deltas for mnemonic generation and salt/IV. Extends Canvas to capture raw key events. The core entropy mixing logic is testable; the Canvas UI is manual-test only.

**Files:**
- Create: `signer/src/org/burnerwallet/core/EntropyCollector.java`
- Create: `signer/test/org/burnerwallet/core/EntropyCollectorTest.java`

**Step 1: Write EntropyCollectorTest.java**

Since `EntropyCollector` has Canvas-dependent UI code (extends `javax.microedition.lcdui.Canvas`), we need to test the entropy mixing logic separately. Extract a static `mixEntropy()` method that can be unit tested. The Canvas key capture is manual-test only.

```java
package org.burnerwallet.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class EntropyCollectorTest {

    @Test
    public void mixEntropyProduces32Bytes() {
        long[] timings = new long[] {100, 150, 80, 200, 120, 90, 110, 130,
                                      95, 175, 105, 140, 85, 160, 125, 145,
                                      100, 150, 80, 200, 120, 90, 110, 130,
                                      95, 175, 105, 140, 85, 160, 125, 145};
        byte[] entropy = EntropyCollector.mixEntropy(timings);
        assertEquals(32, entropy.length);
    }

    @Test
    public void differentTimingsProduceDifferentEntropy() {
        long[] timings1 = new long[] {100, 150, 80, 200, 120, 90, 110, 130,
                                       95, 175, 105, 140, 85, 160, 125, 145,
                                       100, 150, 80, 200, 120, 90, 110, 130,
                                       95, 175, 105, 140, 85, 160, 125, 145};
        long[] timings2 = new long[] {101, 150, 80, 200, 120, 90, 110, 130,
                                       95, 175, 105, 140, 85, 160, 125, 145,
                                       100, 150, 80, 200, 120, 90, 110, 130,
                                       95, 175, 105, 140, 85, 160, 125, 145};

        byte[] e1 = EntropyCollector.mixEntropy(timings1);
        byte[] e2 = EntropyCollector.mixEntropy(timings2);
        assertFalse(java.util.Arrays.equals(e1, e2));
    }

    @Test
    public void mixEntropyNonZero() {
        long[] timings = new long[] {100, 200, 300, 400, 500, 600, 700, 800,
                                      900, 1000, 1100, 1200, 1300, 1400, 1500, 1600,
                                      100, 200, 300, 400, 500, 600, 700, 800,
                                      900, 1000, 1100, 1200, 1300, 1400, 1500, 1600};
        byte[] entropy = EntropyCollector.mixEntropy(timings);
        boolean allZero = true;
        for (int i = 0; i < entropy.length; i++) {
            if (entropy[i] != 0) { allZero = false; break; }
        }
        assertFalse("Entropy should not be all zeros", allZero);
    }

    @Test
    public void singleTimingDifferenceChangesAllBytes() {
        long[] base = new long[32];
        for (int i = 0; i < 32; i++) { base[i] = 100 + i * 10; }

        long[] modified = new long[32];
        System.arraycopy(base, 0, modified, 0, 32);
        modified[0] = modified[0] + 1;

        byte[] e1 = EntropyCollector.mixEntropy(base);
        byte[] e2 = EntropyCollector.mixEntropy(modified);

        // SHA-256 avalanche: most bytes should differ
        int diffCount = 0;
        for (int i = 0; i < 32; i++) {
            if (e1[i] != e2[i]) { diffCount++; }
        }
        assertTrue("SHA-256 avalanche should change many bytes, got " + diffCount, diffCount > 10);
    }

    @Test
    public void getEntropy16Returns16Bytes() {
        long[] timings = new long[32];
        for (int i = 0; i < 32; i++) { timings[i] = 100 + i * 10; }

        byte[] full = EntropyCollector.mixEntropy(timings);
        byte[] first16 = new byte[16];
        System.arraycopy(full, 0, first16, 0, 16);
        assertEquals(16, first16.length);
    }
}
```

**Step 2: Run tests to verify they fail**

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test
```

Expected: compilation failure — `EntropyCollector` class does not exist.

**Step 3: Implement EntropyCollector.java**

```java
package org.burnerwallet.core;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * Entropy collector using keypad timing deltas.
 *
 * Displays "Press random keys..." on a Canvas and records
 * System.currentTimeMillis() deltas between keypresses.
 * After enough presses, mixes all timing data through SHA-256
 * to produce 32 bytes of entropy.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class EntropyCollector extends Canvas {

    private static final int REQUIRED_PRESSES = 32;

    private final long[] timings;
    private int count;
    private long lastTime;
    private boolean ready;
    private EntropyListener listener;

    public EntropyCollector() {
        timings = new long[REQUIRED_PRESSES];
        count = 0;
        lastTime = System.currentTimeMillis();
        ready = false;
    }

    /**
     * Mix timing data through SHA-256 to produce 32 bytes of entropy.
     * This is a static method so it can be unit tested without Canvas.
     *
     * @param timingDeltas array of timing deltas in milliseconds
     * @return 32-byte SHA-256 hash of the timing data
     */
    public static byte[] mixEntropy(long[] timingDeltas) {
        // Convert longs to bytes: each long becomes 8 bytes big-endian
        byte[] raw = new byte[timingDeltas.length * 8];
        for (int i = 0; i < timingDeltas.length; i++) {
            long v = timingDeltas[i];
            int offset = i * 8;
            raw[offset]     = (byte) ((v >> 56) & 0xFF);
            raw[offset + 1] = (byte) ((v >> 48) & 0xFF);
            raw[offset + 2] = (byte) ((v >> 40) & 0xFF);
            raw[offset + 3] = (byte) ((v >> 32) & 0xFF);
            raw[offset + 4] = (byte) ((v >> 24) & 0xFF);
            raw[offset + 5] = (byte) ((v >> 16) & 0xFF);
            raw[offset + 6] = (byte) ((v >> 8) & 0xFF);
            raw[offset + 7] = (byte) (v & 0xFF);
        }
        return HashUtils.sha256(raw);
    }

    /** Check if enough keypresses have been collected. */
    public boolean isReady() {
        return ready;
    }

    /** Get the number of keypresses collected so far. */
    public int getCount() {
        return count;
    }

    /** Get required number of keypresses. */
    public int getRequired() {
        return REQUIRED_PRESSES;
    }

    /**
     * Get the collected entropy (32 bytes).
     * Only valid after isReady() returns true.
     *
     * @return 32 bytes of entropy, or null if not ready
     */
    public byte[] getEntropy() {
        if (!ready) {
            return null;
        }
        return mixEntropy(timings);
    }

    /** Set a listener to be notified when entropy collection is complete. */
    public void setEntropyListener(EntropyListener listener) {
        this.listener = listener;
    }

    protected void keyPressed(int keyCode) {
        if (ready) {
            return;
        }
        long now = System.currentTimeMillis();
        timings[count] = now - lastTime;
        lastTime = now;
        count++;
        if (count >= REQUIRED_PRESSES) {
            ready = true;
            if (listener != null) {
                listener.onEntropyReady(getEntropy());
            }
        }
        repaint();
    }

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();

        // Clear background
        g.setColor(0xFFFFFF);
        g.fillRect(0, 0, w, h);
        g.setColor(0x000000);

        Font font = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
        g.setFont(font);

        if (ready) {
            g.drawString("Done!", w / 2, h / 2, Graphics.HCENTER | Graphics.BASELINE);
        } else {
            g.drawString("Press random keys", w / 2, h / 3, Graphics.HCENTER | Graphics.BASELINE);
            g.drawString(count + " / " + REQUIRED_PRESSES, w / 2, h / 2, Graphics.HCENTER | Graphics.BASELINE);
        }
    }

    /**
     * Callback interface for entropy collection completion.
     */
    public interface EntropyListener {
        void onEntropyReady(byte[] entropy);
    }
}
```

**Step 4: Run tests to verify they pass**

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test
```

Expected: all tests pass.

**Step 5: Commit**

```bash
git add signer/src/org/burnerwallet/core/EntropyCollector.java \
       signer/test/org/burnerwallet/core/EntropyCollectorTest.java
git commit -s -m "feat(signer): add EntropyCollector — keypad timing entropy for mnemonic generation"
```

---

## Task 6: MidpRecordStoreAdapter — production RMS implementation

The real MIDP RecordStore adapter. Cannot be unit tested (requires MIDP runtime), but compiles against MIDP stubs and is manually tested in the emulator.

**Files:**
- Create: `signer/src/org/burnerwallet/storage/MidpRecordStoreAdapter.java`

**Step 1: Implement MidpRecordStoreAdapter.java**

```java
package org.burnerwallet.storage;

import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;
import javax.microedition.rms.RecordStoreNotFoundException;

/**
 * Production RecordStoreAdapter using MIDP javax.microedition.rms.RecordStore.
 *
 * Java 1.4 compatible (CLDC 1.1 / MIDP 2.0).
 */
public class MidpRecordStoreAdapter implements RecordStoreAdapter {

    private RecordStore rs;
    private String storeName;

    public void open(String name, boolean create) throws Exception {
        this.storeName = name;
        try {
            rs = RecordStore.openRecordStore(name, create);
        } catch (RecordStoreNotFoundException e) {
            throw new Exception("RecordStore not found: " + name);
        } catch (RecordStoreException e) {
            throw new Exception("Failed to open RecordStore: " + e.getMessage());
        }
    }

    public byte[] getRecord(int recordId) throws Exception {
        try {
            return rs.getRecord(recordId);
        } catch (Exception e) {
            throw new Exception("Failed to get record " + recordId + ": " + e.getMessage());
        }
    }

    public int addRecord(byte[] data) throws Exception {
        try {
            return rs.addRecord(data, 0, data.length);
        } catch (Exception e) {
            throw new Exception("Failed to add record: " + e.getMessage());
        }
    }

    public void setRecord(int recordId, byte[] data) throws Exception {
        try {
            rs.setRecord(recordId, data, 0, data.length);
        } catch (Exception e) {
            throw new Exception("Failed to set record " + recordId + ": " + e.getMessage());
        }
    }

    public int getNumRecords() throws Exception {
        try {
            return rs.getNumRecords();
        } catch (Exception e) {
            throw new Exception("Failed to get record count: " + e.getMessage());
        }
    }

    public void close() throws Exception {
        if (rs != null) {
            try {
                rs.closeRecordStore();
            } catch (Exception e) {
                throw new Exception("Failed to close RecordStore: " + e.getMessage());
            }
            rs = null;
        }
    }

    public void deleteStore() throws Exception {
        close();
        if (storeName != null) {
            try {
                RecordStore.deleteRecordStore(storeName);
            } catch (RecordStoreNotFoundException e) {
                // Already deleted, fine
            } catch (RecordStoreException e) {
                throw new Exception("Failed to delete RecordStore: " + e.getMessage());
            }
        }
    }
}
```

**Step 2: Verify the project compiles and existing tests pass**

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test
```

Expected: all tests pass (MidpRecordStoreAdapter compiles against MIDP stubs but isn't covered by JUnit).

**Step 3: Commit**

```bash
git add signer/src/org/burnerwallet/storage/MidpRecordStoreAdapter.java
git commit -s -m "feat(signer): add MidpRecordStoreAdapter — production MIDP RecordStore implementation"
```

---

## Task 7: ScreenManager — display controller and navigation helper

Central class that holds the Display reference, manages screen transitions, and provides alert helpers. All UI screens will use this.

**Files:**
- Create: `signer/src/org/burnerwallet/ui/ScreenManager.java`

**Step 1: Implement ScreenManager.java**

```java
package org.burnerwallet.ui;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.midlet.MIDlet;

/**
 * Screen manager for LCDUI navigation.
 *
 * Provides screen switching and alert helpers. Holds the Display
 * singleton and references to key application state.
 *
 * Java 1.4 compatible (CLDC 1.1 / MIDP 2.0).
 */
public class ScreenManager {

    private final MIDlet midlet;
    private final Display display;

    public ScreenManager(MIDlet midlet) {
        this.midlet = midlet;
        this.display = Display.getDisplay(midlet);
    }

    /** Switch to a new screen. */
    public void showScreen(Displayable screen) {
        display.setCurrent(screen);
    }

    /** Show a timed alert, then return to the given screen. */
    public void showAlert(String title, String message, AlertType type,
                          Displayable next, int timeoutMs) {
        Alert alert = new Alert(title, message, null, type);
        alert.setTimeout(timeoutMs);
        display.setCurrent(alert, next);
    }

    /** Show an error alert for 3 seconds, then return to the given screen. */
    public void showError(String message, Displayable returnTo) {
        showAlert("Error", message, AlertType.ERROR, returnTo, 3000);
    }

    /** Show an info alert for 2 seconds, then go to next screen. */
    public void showInfo(String message, Displayable next) {
        showAlert("Info", message, AlertType.INFO, next, 2000);
    }

    /** Show a modal alert (no timeout — user must dismiss). */
    public void showModalAlert(String title, String message, AlertType type,
                                Displayable next) {
        Alert alert = new Alert(title, message, null, type);
        alert.setTimeout(Alert.FOREVER);
        display.setCurrent(alert, next);
    }

    /** Get the Display instance. */
    public Display getDisplay() {
        return display;
    }

    /** Get the MIDlet instance. */
    public MIDlet getMidlet() {
        return midlet;
    }

    /** Exit the application. */
    public void exit() {
        midlet.notifyDestroyed();
    }
}
```

**Step 2: Verify the project compiles**

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test
```

Expected: all tests pass (ScreenManager has no JUnit-testable logic — it's a thin MIDP wrapper).

**Step 3: Commit**

```bash
git add signer/src/org/burnerwallet/ui/ScreenManager.java
git commit -s -m "feat(signer): add ScreenManager — LCDUI display controller and navigation"
```

---

## Task 8: PinScreen — PIN entry, creation, and confirmation

Handles three modes: ENTER (unlock), CREATE (new wallet), CONFIRM (verify new PIN). Uses Form with TextField in NUMERIC|PASSWORD mode.

**Files:**
- Create: `signer/src/org/burnerwallet/ui/PinScreen.java`

**Step 1: Implement PinScreen.java**

```java
package org.burnerwallet.ui;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.TextField;

/**
 * PIN entry screen with three modes:
 *   ENTER   — unlock existing wallet
 *   CREATE  — choose a new PIN
 *   CONFIRM — re-enter PIN to confirm
 *
 * Java 1.4 compatible (CLDC 1.1 / MIDP 2.0).
 */
public class PinScreen implements CommandListener {

    public static final int MODE_ENTER = 0;
    public static final int MODE_CREATE = 1;
    public static final int MODE_CONFIRM = 2;

    private static final int MIN_PIN_LENGTH = 4;
    private static final int MAX_PIN_LENGTH = 8;

    private final ScreenManager screens;
    private final PinListener listener;
    private final int mode;

    private final Form form;
    private final TextField pinField;
    private final Command cmdOk;
    private final Command cmdBack;

    private String firstPin; // Used in CONFIRM mode to compare

    /**
     * Create a PIN screen.
     *
     * @param screens  screen manager
     * @param listener callback for PIN events
     * @param mode     MODE_ENTER, MODE_CREATE, or MODE_CONFIRM
     */
    public PinScreen(ScreenManager screens, PinListener listener, int mode) {
        this.screens = screens;
        this.listener = listener;
        this.mode = mode;

        String title;
        String label;
        switch (mode) {
            case MODE_CREATE:
                title = "Set PIN";
                label = "Choose PIN (4-8 digits):";
                break;
            case MODE_CONFIRM:
                title = "Confirm PIN";
                label = "Re-enter PIN:";
                break;
            default:
                title = "Unlock";
                label = "Enter PIN:";
                break;
        }

        form = new Form(title);
        pinField = new TextField(label, "", MAX_PIN_LENGTH,
            TextField.NUMERIC | TextField.PASSWORD);
        form.append(pinField);

        cmdOk = new Command("OK", Command.OK, 1);
        form.addCommand(cmdOk);

        if (mode == MODE_ENTER) {
            cmdBack = new Command("Exit", Command.EXIT, 2);
        } else {
            cmdBack = new Command("Back", Command.BACK, 2);
        }
        form.addCommand(cmdBack);
        form.setCommandListener(this);
    }

    /** Get the Form displayable for this screen. */
    public Form getForm() {
        return form;
    }

    /** Set the first PIN (for CONFIRM mode comparison). */
    public void setFirstPin(String pin) {
        this.firstPin = pin;
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cmdOk) {
            String pin = pinField.getString();

            if (pin.length() < MIN_PIN_LENGTH) {
                screens.showError("PIN must be at least " + MIN_PIN_LENGTH + " digits", form);
                pinField.setString("");
                return;
            }

            if (mode == MODE_CONFIRM) {
                if (pin.equals(firstPin)) {
                    listener.onPinConfirmed(pin);
                } else {
                    screens.showError("PINs don't match", form);
                    pinField.setString("");
                }
            } else if (mode == MODE_CREATE) {
                listener.onPinCreated(pin);
            } else {
                listener.onPinEntered(pin);
            }
        } else if (c == cmdBack) {
            listener.onPinCancelled();
        }
    }

    /**
     * Callback interface for PIN events.
     */
    public interface PinListener {
        /** Called when user enters PIN in ENTER mode. */
        void onPinEntered(String pin);

        /** Called when user creates PIN in CREATE mode. */
        void onPinCreated(String pin);

        /** Called when user confirms PIN in CONFIRM mode. */
        void onPinConfirmed(String pin);

        /** Called when user cancels/exits. */
        void onPinCancelled();
    }
}
```

**Step 2: Verify compilation**

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test
```

Expected: all tests pass.

**Step 3: Commit**

```bash
git add signer/src/org/burnerwallet/ui/PinScreen.java
git commit -s -m "feat(signer): add PinScreen — PIN entry, creation, and confirmation"
```

---

## Task 9: OnboardingScreen — welcome, generate, import, passphrase

The onboarding flow orchestrator. Handles welcome menu, mnemonic generation (with EntropyCollector), mnemonic import (word-by-word), word verification, and optional passphrase entry.

**Files:**
- Create: `signer/src/org/burnerwallet/ui/OnboardingScreen.java`

**Step 1: Implement OnboardingScreen.java**

```java
package org.burnerwallet.ui;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextField;

import org.burnerwallet.chains.bitcoin.Bip39Mnemonic;
import org.burnerwallet.core.ByteArrayUtils;
import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.EntropyCollector;

/**
 * Onboarding flow for wallet creation and import.
 *
 * Flow: Welcome -> [Generate | Import] -> Passphrase -> (returns mnemonic + passphrase)
 *
 * Generate: EntropyCollector -> show words (3 screens of 4) -> verify one word
 * Import: choose 12/24 -> enter words one by one -> validate checksum
 *
 * Java 1.4 compatible (CLDC 1.1 / MIDP 2.0).
 */
public class OnboardingScreen implements CommandListener, EntropyCollector.EntropyListener {

    private final ScreenManager screens;
    private final OnboardingListener listener;

    // Screens
    private final List welcomeList;
    private final Command cmdExit;

    // Generate flow state
    private EntropyCollector entropyCollector;
    private String generatedMnemonic;
    private String[] mnemonicWords;
    private int verifyWordIndex;

    // Import flow state
    private int importWordCount;
    private String[] importedWords;
    private int currentImportWord;

    // Passphrase state
    private String mnemonic; // final mnemonic (generate or import)

    public OnboardingScreen(ScreenManager screens, OnboardingListener listener) {
        this.screens = screens;
        this.listener = listener;

        welcomeList = new List("Burner Wallet", Choice.IMPLICIT);
        welcomeList.append("Create New Wallet", null);
        welcomeList.append("Import Existing", null);
        cmdExit = new Command("Exit", Command.EXIT, 2);
        welcomeList.addCommand(cmdExit);
        welcomeList.setCommandListener(this);
    }

    /** Get the welcome screen displayable. */
    public Displayable getWelcomeScreen() {
        return welcomeList;
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cmdExit) {
            listener.onOnboardingCancelled();
            return;
        }

        if (d == welcomeList) {
            int sel = welcomeList.getSelectedIndex();
            if (sel == 0) {
                startGenerate();
            } else if (sel == 1) {
                startImport();
            }
            return;
        }

        // Handle other screens via tag stored in screen title
        String title = "";
        if (d instanceof Form) {
            title = ((Form) d).getTitle();
        } else if (d instanceof List) {
            title = ((List) d).getTitle();
        }

        if (title.startsWith("Words ")) {
            handleWordDisplay(c, d);
        } else if ("Verify Word".equals(title)) {
            handleVerifyWord(c, (Form) d);
        } else if ("Word Count".equals(title)) {
            handleWordCountSelection(c, (List) d);
        } else if (title.startsWith("Word ")) {
            handleImportWord(c, (Form) d);
        } else if ("Passphrase".equals(title)) {
            handlePassphrase(c, (Form) d);
        }
    }

    // ---- Generate flow ----

    private void startGenerate() {
        entropyCollector = new EntropyCollector();
        entropyCollector.setEntropyListener(this);
        screens.showScreen(entropyCollector);
    }

    public void onEntropyReady(byte[] entropy) {
        // Use first 16 bytes for mnemonic (128-bit = 12 words)
        byte[] mnemonicEntropy = ByteArrayUtils.copyOfRange(entropy, 0, 16);
        try {
            generatedMnemonic = Bip39Mnemonic.generate(mnemonicEntropy);
        } catch (CryptoError e) {
            screens.showError("Failed to generate mnemonic", welcomeList);
            return;
        }
        mnemonicWords = splitWords(generatedMnemonic);
        showWords(0);
    }

    private void showWords(int startIndex) {
        int end = startIndex + 4;
        if (end > mnemonicWords.length) {
            end = mnemonicWords.length;
        }

        Form form = new Form("Words " + (startIndex + 1) + "-" + end);
        form.append(new StringItem(null, "Write these down!\n\n"));
        for (int i = startIndex; i < end; i++) {
            form.append(new StringItem(null, (i + 1) + ". " + mnemonicWords[i] + "\n"));
        }

        Command cmdNext;
        if (end < mnemonicWords.length) {
            cmdNext = new Command("Next", Command.OK, 1);
        } else {
            cmdNext = new Command("Done", Command.OK, 1);
        }
        form.addCommand(cmdNext);
        form.setCommandListener(this);
        screens.showScreen(form);
    }

    private void handleWordDisplay(Command c, Displayable d) {
        String title = ((Form) d).getTitle();
        // Parse "Words X-Y" to find current range
        int dashIdx = title.indexOf('-');
        int spaceIdx = title.indexOf(' ');
        if (dashIdx > 0 && spaceIdx > 0) {
            int endWord = 0;
            try {
                endWord = Integer.parseInt(title.substring(dashIdx + 1));
            } catch (NumberFormatException e) {
                endWord = mnemonicWords.length;
            }
            if (endWord < mnemonicWords.length) {
                showWords(endWord);
            } else {
                // All words shown — verify one
                showVerifyWord();
            }
        }
    }

    private void showVerifyWord() {
        // Pick a random-ish word to verify (use system time as entropy)
        verifyWordIndex = (int) (System.currentTimeMillis() % mnemonicWords.length);

        Form form = new Form("Verify Word");
        form.append(new StringItem(null, "Enter word #" + (verifyWordIndex + 1) + ":\n"));
        TextField wordField = new TextField("Word:", "", 20, TextField.ANY);
        form.append(wordField);

        Command cmdOk = new Command("OK", Command.OK, 1);
        form.addCommand(cmdOk);
        form.setCommandListener(this);
        screens.showScreen(form);
    }

    private void handleVerifyWord(Command c, Form form) {
        TextField wordField = (TextField) form.get(1); // second item
        String entered = wordField.getString().trim().toLowerCase();

        if (entered.equals(mnemonicWords[verifyWordIndex])) {
            mnemonic = generatedMnemonic;
            showPassphraseScreen();
        } else {
            screens.showError("Wrong word. Try again.", form);
            wordField.setString("");
        }
    }

    // ---- Import flow ----

    private void startImport() {
        List wordCountList = new List("Word Count", Choice.IMPLICIT);
        wordCountList.append("12 words", null);
        wordCountList.append("24 words", null);
        Command cmdBack = new Command("Back", Command.BACK, 2);
        wordCountList.addCommand(cmdBack);
        wordCountList.setCommandListener(this);
        screens.showScreen(wordCountList);
    }

    private void handleWordCountSelection(Command c, List list) {
        if (c.getCommandType() == Command.BACK) {
            screens.showScreen(welcomeList);
            return;
        }
        int sel = list.getSelectedIndex();
        importWordCount = (sel == 0) ? 12 : 24;
        importedWords = new String[importWordCount];
        currentImportWord = 0;
        showImportWordForm();
    }

    private void showImportWordForm() {
        Form form = new Form("Word " + (currentImportWord + 1) + "/" + importWordCount);
        TextField wordField = new TextField("Enter word:", "", 20, TextField.ANY);
        form.append(wordField);

        Command cmdOk = new Command("OK", Command.OK, 1);
        Command cmdBack = new Command("Back", Command.BACK, 2);
        form.addCommand(cmdOk);
        form.addCommand(cmdBack);
        form.setCommandListener(this);
        screens.showScreen(form);
    }

    private void handleImportWord(Command c, Form form) {
        if (c.getCommandType() == Command.BACK) {
            if (currentImportWord > 0) {
                currentImportWord--;
                showImportWordForm();
            } else {
                screens.showScreen(welcomeList);
            }
            return;
        }

        TextField wordField = (TextField) form.get(0);
        String word = wordField.getString().trim().toLowerCase();
        if (word.length() == 0) {
            screens.showError("Enter a word", form);
            return;
        }

        importedWords[currentImportWord] = word;
        currentImportWord++;

        if (currentImportWord < importWordCount) {
            showImportWordForm();
        } else {
            // Validate the complete mnemonic
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < importedWords.length; i++) {
                if (i > 0) { sb.append(' '); }
                sb.append(importedWords[i]);
            }
            String importedMnemonic = sb.toString();

            if (Bip39Mnemonic.validate(importedMnemonic)) {
                mnemonic = importedMnemonic;
                showPassphraseScreen();
            } else {
                screens.showError("Invalid mnemonic checksum", welcomeList);
            }
        }
    }

    // ---- Passphrase ----

    private void showPassphraseScreen() {
        Form form = new Form("Passphrase");
        form.append(new StringItem(null, "Optional BIP39 passphrase:\n"));
        TextField passField = new TextField("Passphrase:", "", 64, TextField.ANY | TextField.SENSITIVE);
        form.append(passField);

        Command cmdOk = new Command("OK", Command.OK, 1);
        Command cmdSkip = new Command("Skip", Command.BACK, 2);
        form.addCommand(cmdOk);
        form.addCommand(cmdSkip);
        form.setCommandListener(this);
        screens.showScreen(form);
    }

    private void handlePassphrase(Command c, Form form) {
        String passphrase = "";
        if (c.getCommandType() != Command.BACK) {
            // OK pressed — get passphrase
            TextField passField = (TextField) form.get(1); // second item
            passphrase = passField.getString();
        }
        // Report back to the MIDlet
        listener.onOnboardingComplete(mnemonic, passphrase);
    }

    // ---- Utility ----

    private static String[] splitWords(String text) {
        String trimmed = text.trim();
        int count = 1;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == ' ') { count++; }
        }
        String[] result = new String[count];
        int idx = 0;
        int start = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == ' ') {
                result[idx++] = trimmed.substring(start, i);
                start = i + 1;
            }
        }
        result[idx] = trimmed.substring(start);
        return result;
    }

    /**
     * Callback interface for onboarding completion.
     */
    public interface OnboardingListener {
        /** Called with the final mnemonic and passphrase. */
        void onOnboardingComplete(String mnemonic, String passphrase);

        /** Called when user cancels onboarding. */
        void onOnboardingCancelled();
    }
}
```

**Step 2: Verify compilation**

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test
```

Expected: all tests pass.

**Step 3: Commit**

```bash
git add signer/src/org/burnerwallet/ui/OnboardingScreen.java
git commit -s -m "feat(signer): add OnboardingScreen — wallet generation and import flows"
```

---

## Task 10: WalletHomeScreen + ReceiveScreen + SettingsScreen — main wallet UI

The three remaining UI screens: main menu, address display, and settings.

**Files:**
- Create: `signer/src/org/burnerwallet/ui/WalletHomeScreen.java`
- Create: `signer/src/org/burnerwallet/ui/ReceiveScreen.java`
- Create: `signer/src/org/burnerwallet/ui/SettingsScreen.java`

**Step 1: Implement WalletHomeScreen.java**

```java
package org.burnerwallet.ui;

import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.List;

/**
 * Main wallet menu after unlock.
 *
 * Options: Receive Address, Settings.
 *
 * Java 1.4 compatible (CLDC 1.1 / MIDP 2.0).
 */
public class WalletHomeScreen implements CommandListener {

    public static final int ACTION_RECEIVE = 0;
    public static final int ACTION_SETTINGS = 1;

    private final ScreenManager screens;
    private final HomeListener listener;
    private final List menuList;
    private final Command cmdExit;

    public WalletHomeScreen(ScreenManager screens, HomeListener listener) {
        this.screens = screens;
        this.listener = listener;

        menuList = new List("Burner Wallet", Choice.IMPLICIT);
        menuList.append("Receive Address", null);
        menuList.append("Settings", null);

        cmdExit = new Command("Lock", Command.EXIT, 2);
        menuList.addCommand(cmdExit);
        menuList.setCommandListener(this);
    }

    public Displayable getScreen() {
        return menuList;
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cmdExit) {
            listener.onHomeAction(-1); // Lock/exit
            return;
        }
        if (c == List.SELECT_COMMAND) {
            int sel = menuList.getSelectedIndex();
            listener.onHomeAction(sel);
        }
    }

    public interface HomeListener {
        void onHomeAction(int action);
    }
}
```

**Step 2: Implement ReceiveScreen.java**

```java
package org.burnerwallet.ui;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.StringItem;

import org.burnerwallet.chains.bitcoin.BitcoinAddress;
import org.burnerwallet.core.CryptoError;

/**
 * Receive address display screen.
 *
 * Shows the bech32 address and derivation path.
 * "Next" increments the address index.
 *
 * Java 1.4 compatible (CLDC 1.1 / MIDP 2.0).
 */
public class ReceiveScreen implements CommandListener {

    private final ScreenManager screens;
    private final ReceiveListener listener;
    private final byte[] seed;
    private final boolean testnet;
    private int addressIndex;

    private Form form;
    private final Command cmdNext;
    private final Command cmdBack;

    public ReceiveScreen(ScreenManager screens, ReceiveListener listener,
                          byte[] seed, boolean testnet, int startIndex) {
        this.screens = screens;
        this.listener = listener;
        this.seed = seed;
        this.testnet = testnet;
        this.addressIndex = startIndex;

        cmdNext = new Command("Next", Command.OK, 1);
        cmdBack = new Command("Back", Command.BACK, 2);

        buildForm();
    }

    public Displayable getScreen() {
        return form;
    }

    public int getAddressIndex() {
        return addressIndex;
    }

    private void buildForm() {
        form = new Form("Receive");
        try {
            String address = BitcoinAddress.deriveP2wpkhAddress(
                seed, testnet, 0, false, addressIndex);
            String path = "m/84'/" + (testnet ? "1" : "0") + "'/0'/0/" + addressIndex;

            // Split address across lines for 128px screen
            form.append(new StringItem("Address:", "\n" + formatAddress(address)));
            form.append(new StringItem("Path:", path));
            form.append(new StringItem("Index:", String.valueOf(addressIndex)));
        } catch (CryptoError e) {
            form.append(new StringItem("Error:", e.getMessage()));
        }

        form.addCommand(cmdNext);
        form.addCommand(cmdBack);
        form.setCommandListener(this);
    }

    /** Format a bech32 address for the narrow Nokia screen (split every 14 chars). */
    private static String formatAddress(String address) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < address.length(); i += 14) {
            if (i > 0) { sb.append('\n'); }
            int end = i + 14;
            if (end > address.length()) { end = address.length(); }
            sb.append(address.substring(i, end));
        }
        return sb.toString();
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cmdNext) {
            addressIndex++;
            buildForm();
            screens.showScreen(form);
            listener.onAddressIndexChanged(addressIndex);
        } else if (c == cmdBack) {
            listener.onReceiveBack();
        }
    }

    public interface ReceiveListener {
        void onAddressIndexChanged(int newIndex);
        void onReceiveBack();
    }
}
```

**Step 3: Implement SettingsScreen.java**

```java
package org.burnerwallet.ui;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.List;

/**
 * Settings screen: network toggle, wipe wallet, about.
 *
 * Java 1.4 compatible (CLDC 1.1 / MIDP 2.0).
 */
public class SettingsScreen implements CommandListener {

    public static final int ACTION_TOGGLE_NETWORK = 0;
    public static final int ACTION_WIPE = 1;
    public static final int ACTION_ABOUT = 2;

    private final ScreenManager screens;
    private final SettingsListener listener;
    private List menuList;
    private boolean testnet;
    private final Command cmdBack;

    public SettingsScreen(ScreenManager screens, SettingsListener listener, boolean testnet) {
        this.screens = screens;
        this.listener = listener;
        this.testnet = testnet;
        this.cmdBack = new Command("Back", Command.BACK, 2);

        buildList();
    }

    private void buildList() {
        menuList = new List("Settings", Choice.IMPLICIT);
        menuList.append("Network: " + (testnet ? "Testnet" : "Mainnet"), null);
        menuList.append("Wipe Wallet", null);
        menuList.append("About", null);
        menuList.addCommand(cmdBack);
        menuList.setCommandListener(this);
    }

    public Displayable getScreen() {
        return menuList;
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cmdBack) {
            listener.onSettingsBack();
            return;
        }

        if (c == List.SELECT_COMMAND) {
            int sel = menuList.getSelectedIndex();
            switch (sel) {
                case ACTION_TOGGLE_NETWORK:
                    testnet = !testnet;
                    buildList();
                    screens.showScreen(menuList);
                    listener.onNetworkChanged(testnet);
                    break;
                case ACTION_WIPE:
                    showWipeConfirmation();
                    break;
                case ACTION_ABOUT:
                    screens.showModalAlert("About",
                        "Burner Wallet v0.1.0\n\nAir-gapped Bitcoin signer\nfor Nokia C1-01",
                        AlertType.INFO, menuList);
                    break;
            }
        }
    }

    private void showWipeConfirmation() {
        screens.showModalAlert("Wipe Wallet",
            "This will DELETE all wallet data!\n\nAre you sure?\n\nPress OK to confirm.",
            AlertType.WARNING, new WipeConfirmScreen());
    }

    /**
     * Inner class for wipe confirmation. When dismissed, triggers actual wipe.
     * Using a Form with Yes/No commands instead of a simple Alert.
     */
    private class WipeConfirmScreen extends javax.microedition.lcdui.Form
            implements CommandListener {
        private Command cmdYes;
        private Command cmdNo;

        WipeConfirmScreen() {
            super("Confirm Wipe");
            append(new javax.microedition.lcdui.StringItem(null,
                "ALL wallet data will be\npermanently deleted.\n\nThis cannot be undone!"));
            cmdYes = new Command("Wipe", Command.OK, 1);
            cmdNo = new Command("Cancel", Command.BACK, 2);
            addCommand(cmdYes);
            addCommand(cmdNo);
            setCommandListener(this);
        }

        public void commandAction(Command c, Displayable d) {
            if (c == cmdYes) {
                listener.onWipeConfirmed();
            } else {
                screens.showScreen(menuList);
            }
        }
    }

    public interface SettingsListener {
        void onNetworkChanged(boolean testnet);
        void onWipeConfirmed();
        void onSettingsBack();
    }
}
```

**Step 4: Verify compilation**

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test
```

Expected: all tests pass.

**Step 5: Commit**

```bash
git add signer/src/org/burnerwallet/ui/WalletHomeScreen.java \
       signer/src/org/burnerwallet/ui/ReceiveScreen.java \
       signer/src/org/burnerwallet/ui/SettingsScreen.java
git commit -s -m "feat(signer): add wallet home, receive address, and settings screens"
```

---

## Task 11: BurnerWalletMIDlet — wire up the complete lifecycle

Replace the scaffold MIDlet with the real implementation that orchestrates all screens, storage, and crypto. This is the integration point.

**Files:**
- Modify: `signer/src/org/burnerwallet/ui/BurnerWalletMIDlet.java`

**Step 1: Rewrite BurnerWalletMIDlet.java**

```java
package org.burnerwallet.ui;

import javax.microedition.lcdui.AlertType;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;

import org.burnerwallet.chains.bitcoin.Bip39Mnemonic;
import org.burnerwallet.core.ByteArrayUtils;
import org.burnerwallet.core.CryptoError;
import org.burnerwallet.storage.MidpRecordStoreAdapter;
import org.burnerwallet.storage.WalletStore;

/**
 * Main MIDlet entry point for the Burner Wallet signer.
 *
 * Orchestrates the complete lifecycle:
 *   - First launch: onboarding → set PIN → encrypted storage
 *   - Subsequent launches: PIN unlock → wallet home
 *   - Wallet home: receive address, settings
 *
 * Java 1.4 compatible (CLDC 1.1 / MIDP 2.0).
 */
public class BurnerWalletMIDlet extends MIDlet
        implements PinScreen.PinListener,
                   OnboardingScreen.OnboardingListener,
                   WalletHomeScreen.HomeListener,
                   ReceiveScreen.ReceiveListener,
                   SettingsScreen.SettingsListener {

    private ScreenManager screens;
    private WalletStore walletStore;

    // Active state
    private byte[] currentSeed;
    private String currentPassphrase;
    private String pendingPin;

    // Screen instances (created on demand)
    private OnboardingScreen onboardingScreen;
    private WalletHomeScreen homeScreen;

    protected void startApp() throws MIDletStateChangeException {
        if (screens == null) {
            screens = new ScreenManager(this);
            walletStore = new WalletStore(new MidpRecordStoreAdapter());
        }

        if (walletStore.walletExists()) {
            showPinEntry();
        } else {
            showOnboarding();
        }
    }

    protected void pauseApp() {
        // Zero seed on pause for security
        wipeSensitiveData();
    }

    protected void destroyApp(boolean unconditional) throws MIDletStateChangeException {
        wipeSensitiveData();
    }

    // ---- Screen launchers ----

    private void showOnboarding() {
        onboardingScreen = new OnboardingScreen(screens, this);
        screens.showScreen(onboardingScreen.getWelcomeScreen());
    }

    private void showPinEntry() {
        PinScreen pinScreen = new PinScreen(screens, this, PinScreen.MODE_ENTER);
        screens.showScreen(pinScreen.getForm());
    }

    private void showPinCreate() {
        PinScreen pinScreen = new PinScreen(screens, this, PinScreen.MODE_CREATE);
        screens.showScreen(pinScreen.getForm());
    }

    private void showPinConfirm(String pin) {
        pendingPin = pin;
        PinScreen pinScreen = new PinScreen(screens, this, PinScreen.MODE_CONFIRM);
        pinScreen.setFirstPin(pin);
        screens.showScreen(pinScreen.getForm());
    }

    private void showHome() {
        homeScreen = new WalletHomeScreen(screens, this);
        screens.showScreen(homeScreen.getScreen());
    }

    // ---- OnboardingListener ----

    public void onOnboardingComplete(String mnemonic, String passphrase) {
        // Derive seed from mnemonic + passphrase
        currentSeed = Bip39Mnemonic.toSeed(mnemonic, passphrase);
        currentPassphrase = passphrase;
        // Now ask user to set a PIN
        showPinCreate();
    }

    public void onOnboardingCancelled() {
        screens.exit();
    }

    // ---- PinListener ----

    public void onPinEntered(String pin) {
        // Unlock existing wallet
        try {
            byte[] seed = walletStore.unlock(pin);
            if (seed != null) {
                currentSeed = seed;
                currentPassphrase = walletStore.getPassphrase(pin);
                showHome();
            } else {
                screens.showError("Wrong PIN", null);
                showPinEntry();
            }
        } catch (CryptoError e) {
            screens.showError("Unlock failed: " + e.getMessage(), null);
            showPinEntry();
        }
    }

    public void onPinCreated(String pin) {
        showPinConfirm(pin);
    }

    public void onPinConfirmed(String pin) {
        // Store the wallet with encryption
        try {
            boolean testnet = false; // Default to mainnet
            walletStore.createWallet(currentSeed, currentPassphrase, pin, testnet);
            screens.showInfo("Wallet created!", null);
            showHome();
        } catch (Exception e) {
            screens.showError("Failed to save wallet: " + e.getMessage(), null);
            showOnboarding();
        }
    }

    public void onPinCancelled() {
        if (walletStore.walletExists()) {
            screens.exit();
        } else {
            showOnboarding();
        }
    }

    // ---- HomeListener ----

    public void onHomeAction(int action) {
        if (action == WalletHomeScreen.ACTION_RECEIVE) {
            boolean testnet = walletStore.isTestnet();
            int startIndex = walletStore.getAddressIndex();
            ReceiveScreen receiveScreen = new ReceiveScreen(
                screens, this, currentSeed, testnet, startIndex);
            screens.showScreen(receiveScreen.getScreen());
        } else if (action == WalletHomeScreen.ACTION_SETTINGS) {
            boolean testnet = walletStore.isTestnet();
            SettingsScreen settingsScreen = new SettingsScreen(screens, this, testnet);
            screens.showScreen(settingsScreen.getScreen());
        } else {
            // Lock
            wipeSensitiveData();
            showPinEntry();
        }
    }

    // ---- ReceiveListener ----

    public void onAddressIndexChanged(int newIndex) {
        try {
            walletStore.setAddressIndex(newIndex);
        } catch (Exception e) {
            // Non-critical, continue
        }
    }

    public void onReceiveBack() {
        showHome();
    }

    // ---- SettingsListener ----

    public void onNetworkChanged(boolean testnet) {
        try {
            walletStore.setTestnet(testnet);
        } catch (Exception e) {
            screens.showError("Failed to save setting", homeScreen.getScreen());
        }
    }

    public void onWipeConfirmed() {
        try {
            walletStore.wipe();
            wipeSensitiveData();
            screens.showInfo("Wallet wiped", null);
            showOnboarding();
        } catch (Exception e) {
            screens.showError("Wipe failed: " + e.getMessage(), homeScreen.getScreen());
        }
    }

    public void onSettingsBack() {
        showHome();
    }

    // ---- Cleanup ----

    private void wipeSensitiveData() {
        if (currentSeed != null) {
            ByteArrayUtils.zeroFill(currentSeed);
            currentSeed = null;
        }
        currentPassphrase = null;
        pendingPin = null;
    }
}
```

**Step 2: Verify build and tests**

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test
```

Expected: all tests pass.

**Step 3: Build full JAR and size check**

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant clean build size-check
```

Expected: build succeeds, JAR < 1MB.

**Step 4: Commit**

```bash
git add signer/src/org/burnerwallet/ui/BurnerWalletMIDlet.java
git commit -s -m "feat(signer): wire up complete MIDlet lifecycle — onboarding, PIN, storage, navigation"
```

---

## Task 12: Integration test — full wallet round-trip

End-to-end test: create wallet with known mnemonic → store encrypted → unlock → derive address → verify matches cross-impl vector.

**Files:**
- Create: `signer/test/org/burnerwallet/storage/WalletIntegrationTest.java`

**Step 1: Write WalletIntegrationTest.java**

```java
package org.burnerwallet.storage;

import org.burnerwallet.chains.bitcoin.Bip39Mnemonic;
import org.burnerwallet.chains.bitcoin.BitcoinAddress;
import org.burnerwallet.core.ByteArrayUtils;
import org.burnerwallet.core.CryptoError;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * End-to-end integration tests for the wallet storage flow.
 * Verifies: mnemonic → seed → encrypt → store → unlock → decrypt → derive address.
 */
public class WalletIntegrationTest {

    private static final String ABANDON_MNEMONIC =
        "abandon abandon abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon about";

    private WalletStore store;

    @Before
    public void setUp() {
        InMemoryRecordStoreAdapter.resetAll();
        store = new WalletStore(new InMemoryRecordStoreAdapter());
    }

    @Test
    public void fullRoundTripNoPassphrase() throws Exception {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        store.createWallet(seed, "", "1234", false);

        byte[] recovered = store.unlock("1234");
        assertNotNull(recovered);

        String addr = BitcoinAddress.deriveP2wpkhAddress(recovered, false, 0, false, 0);
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", addr);
        ByteArrayUtils.zeroFill(recovered);
    }

    @Test
    public void fullRoundTripWithPassphrase() throws Exception {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "mysecret");
        store.createWallet(seed, "mysecret", "5678", false);

        byte[] recovered = store.unlock("5678");
        assertNotNull(recovered);
        assertArrayEquals(seed, recovered);

        String passphrase = store.getPassphrase("5678");
        assertEquals("mysecret", passphrase);

        // With passphrase, address differs from no-passphrase version
        String addr = BitcoinAddress.deriveP2wpkhAddress(recovered, false, 0, false, 0);
        assertNotEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", addr);
        ByteArrayUtils.zeroFill(recovered);
    }

    @Test
    public void testnetAddress() throws Exception {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        store.createWallet(seed, "", "1234", true);
        assertTrue(store.isTestnet());

        byte[] recovered = store.unlock("1234");
        String addr = BitcoinAddress.deriveP2wpkhAddress(recovered, true, 0, false, 0);
        assertEquals("tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl", addr);
        ByteArrayUtils.zeroFill(recovered);
    }

    @Test
    public void wrongPinCannotRecover() throws Exception {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        store.createWallet(seed, "", "1234", false);

        assertNull(store.unlock("0000"));
        assertNull(store.unlock("12345"));
        assertNull(store.unlock("4321"));

        // Correct PIN still works
        assertNotNull(store.unlock("1234"));
    }

    @Test
    public void wipeAndRecreate() throws Exception {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        store.createWallet(seed, "", "1234", false);
        assertTrue(store.walletExists());

        store.wipe();
        assertFalse(store.walletExists());

        // Re-create with different PIN
        store.createWallet(seed, "", "9999", true);
        assertTrue(store.walletExists());
        assertNull(store.unlock("1234")); // Old PIN doesn't work
        assertNotNull(store.unlock("9999")); // New PIN works
    }

    @Test
    public void addressIndexPersists() throws Exception {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        store.createWallet(seed, "", "1234", false);

        assertEquals(0, store.getAddressIndex());
        store.setAddressIndex(3);
        assertEquals(3, store.getAddressIndex());

        byte[] recovered = store.unlock("1234");
        String addr3 = BitcoinAddress.deriveP2wpkhAddress(recovered, false, 0, false, 3);
        // Verify it's the expected address at index 3
        assertNotNull(addr3);
        assertTrue(addr3.startsWith("bc1q"));
        ByteArrayUtils.zeroFill(recovered);
    }

    @Test
    public void multipleAddressesFromSameWallet() throws Exception {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        store.createWallet(seed, "", "1234", false);

        byte[] recovered = store.unlock("1234");
        String addr0 = BitcoinAddress.deriveP2wpkhAddress(recovered, false, 0, false, 0);
        String addr1 = BitcoinAddress.deriveP2wpkhAddress(recovered, false, 0, false, 1);
        String addr2 = BitcoinAddress.deriveP2wpkhAddress(recovered, false, 0, false, 2);

        // All addresses should be different
        assertNotEquals(addr0, addr1);
        assertNotEquals(addr1, addr2);
        assertNotEquals(addr0, addr2);

        // Cross-impl verification for first two
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", addr0);
        assertEquals("bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g", addr1);
        ByteArrayUtils.zeroFill(recovered);
    }
}
```

**Step 2: Run all tests**

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test
```

Expected: all tests pass.

**Step 3: Commit**

```bash
git add signer/test/org/burnerwallet/storage/WalletIntegrationTest.java
git commit -s -m "test(signer): add end-to-end wallet integration tests with cross-impl verification"
```

---

## Task 13: Final verification — build, size check, test count

Run full build pipeline and verify everything.

**Step 1: Clean build + size check**

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant clean build size-check
```

Expected: BUILD SUCCESSFUL, JAR < 1MB (should be ~250-260KB with AES added).

**Step 2: Run full test suite**

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test
```

Expected: all tests pass (should be 114 M1a tests + new AES/WalletData/WalletStore/Entropy/Integration tests).

**Step 3: Verify total test count is reasonable**

The new tests should add approximately:
- AesUtilsTest: ~8 tests
- WalletDataTest: ~8 tests
- WalletStoreTest: ~11 tests
- EntropyCollectorTest: ~5 tests
- WalletIntegrationTest: ~7 tests

Total new: ~39 tests. Grand total: ~153 tests.

**Step 4: Update CLAUDE.md with M1b status**

In the project root `CLAUDE.md`, update the milestone status and test count. Change `M1a complete, M1b next` to `M1b complete, M1c next`, update the signer test count, and add the new `storage/` package to the module list.

**Step 5: Final commit**

```bash
git add CLAUDE.md
git commit -s -m "docs: update CLAUDE.md for M1b completion — storage, PIN, LCDUI screens"
```
