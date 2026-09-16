package org.burnerwallet.storage;

import org.burnerwallet.chains.bitcoin.Bip39Mnemonic;
import org.burnerwallet.chains.bitcoin.BitcoinAddress;
import org.burnerwallet.core.CryptoError;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JUnit 4 tests for WalletStore — PIN-encrypted seed storage.
 */
public class WalletStoreTest {

    private static final String PIN = "1234";
    private static final String WRONG_PIN = "9999";

    /** 64-byte test seed (simple pattern). */
    private byte[] testSeed;

    private WalletStore store;

    @Before
    public void setUp() {
        InMemoryRecordStoreAdapter.resetAll();
        store = new WalletStore(new InMemoryRecordStoreAdapter());

        testSeed = new byte[64];
        for (int i = 0; i < 64; i++) {
            testSeed[i] = (byte) i;
        }
    }

    @Test
    public void walletNotExistsInitially() {
        assertFalse("Fresh store should report no wallet", store.walletExists());
    }

    @Test
    public void createAndVerifyPin() throws Exception {
        store.createWallet(testSeed, "", PIN, false);

        assertTrue("Correct PIN should verify", store.verifyPin(PIN));
        assertFalse("Wrong PIN should not verify", store.verifyPin(WRONG_PIN));
    }

    @Test
    public void unlockRetrievesSeed() throws Exception {
        store.createWallet(testSeed, "", PIN, false);

        byte[] recovered = store.unlock(PIN);
        assertNotNull("Unlock with correct PIN should return seed", recovered);
        assertArrayEquals("Recovered seed must match original", testSeed, recovered);
    }

    @Test
    public void unlockWithPassphrase() throws Exception {
        String passphrase = "my secret passphrase";
        store.createWallet(testSeed, passphrase, PIN, false);

        byte[] recovered = store.unlock(PIN);
        assertNotNull("Unlock with correct PIN should return seed", recovered);
        assertArrayEquals("Recovered seed must match original", testSeed, recovered);

        String recoveredPass = store.getPassphrase(PIN);
        assertEquals("Recovered passphrase must match", passphrase, recoveredPass);
    }

    @Test
    public void unlockWrongPinReturnsNull() throws Exception {
        store.createWallet(testSeed, "", PIN, false);

        byte[] result = store.unlock(WRONG_PIN);
        assertNull("Wrong PIN should return null", result);
    }

    @Test
    public void configDefaults() throws Exception {
        store.createWallet(testSeed, "", PIN, false);

        assertFalse("Default network should be mainnet", store.isTestnet());
        assertEquals("Default address index should be 0", 0, store.getAddressIndex());
    }

    @Test
    public void configTestnet() throws Exception {
        store.createWallet(testSeed, "", PIN, true);

        assertTrue("Network should be testnet", store.isTestnet());
    }

    @Test
    public void setNetworkPersists() throws Exception {
        store.createWallet(testSeed, "", PIN, false);

        assertFalse(store.isTestnet());
        store.setTestnet(true);
        assertTrue("Network should now be testnet", store.isTestnet());
        store.setTestnet(false);
        assertFalse("Network should be back to mainnet", store.isTestnet());
    }

    @Test
    public void setAddressIndexPersists() throws Exception {
        store.createWallet(testSeed, "", PIN, false);

        assertEquals(0, store.getAddressIndex());
        store.setAddressIndex(5);
        assertEquals("Address index should be 5", 5, store.getAddressIndex());
    }

    @Test
    public void wipeDeletesEverything() throws Exception {
        store.createWallet(testSeed, "", PIN, false);
        assertTrue("Wallet should exist after creation", store.walletExists());

        store.wipe();
        assertFalse("Wallet should not exist after wipe", store.walletExists());
    }

    @Test
    public void unlockFullReturnsSeedAndPassphrase() throws Exception {
        store.createWallet(testSeed, "hunter2", PIN, true);
        UnlockResult r = store.unlockFull(PIN);
        assertNotNull(r);
        assertArrayEquals(testSeed, r.seed);
        assertEquals("hunter2", r.passphrase);
        assertNull(store.unlockFull(WRONG_PIN));
    }

    @Test
    public void tamperedCiphertextFailsClosed() throws Exception {
        store.createWallet(testSeed, "", PIN, false);

        // Flip one bit inside the ciphertext (would silently change the seed
        // under plain CBC; the MAC must catch it)
        InMemoryRecordStoreAdapter raw = new InMemoryRecordStoreAdapter();
        raw.open(WalletStore.STORE_NAME, false);
        byte[] blob = raw.getRecord(WalletStore.RECORD_SEED);
        blob[WalletData.HEADER_SIZE + 5] ^= 0x01;
        raw.setRecord(WalletStore.RECORD_SEED, blob);
        raw.close();

        assertNull("tampered blob must not unlock", store.unlock(PIN));
        assertFalse(store.verifyPin(PIN));
    }

    @Test
    public void tamperedMacFailsClosed() throws Exception {
        store.createWallet(testSeed, "", PIN, false);

        InMemoryRecordStoreAdapter raw = new InMemoryRecordStoreAdapter();
        raw.open(WalletStore.STORE_NAME, false);
        byte[] blob = raw.getRecord(WalletStore.RECORD_SEED);
        blob[blob.length - 1] ^= 0x80;
        raw.setRecord(WalletStore.RECORD_SEED, blob);
        raw.close();

        assertNull(store.unlock(PIN));
    }

    @Test
    public void corruptedIterationCountIsReportedNotHung() throws Exception {
        store.createWallet(testSeed, "", PIN, false);

        InMemoryRecordStoreAdapter raw = new InMemoryRecordStoreAdapter();
        raw.open(WalletStore.STORE_NAME, false);
        byte[] blob = raw.getRecord(WalletStore.RECORD_SEED);
        blob[33] = 0x7F; // iterations = 0x7F00xxxx
        raw.setRecord(WalletStore.RECORD_SEED, blob);
        raw.close();

        try {
            store.unlock(PIN);
            fail("corrupted record must throw, not spin for years");
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().indexOf("corrupted") >= 0);
        }
    }

    @Test
    public void saltAndIvAreNotDerivedFromPin() throws Exception {
        store.createWallet(testSeed, "", PIN, false);
        InMemoryRecordStoreAdapter raw = new InMemoryRecordStoreAdapter();
        raw.open(WalletStore.STORE_NAME, false);
        byte[] blob1 = raw.getRecord(WalletStore.RECORD_SEED);
        raw.close();

        Thread.sleep(2);
        WalletStore store2 = new WalletStore(new InMemoryRecordStoreAdapter());
        store2.createWallet(testSeed, "", PIN, false);
        raw.open(WalletStore.STORE_NAME, false);
        byte[] blob2 = raw.getRecord(WalletStore.RECORD_SEED);
        raw.close();

        assertFalse("same seed+PIN must not reuse the salt",
            java.util.Arrays.equals(WalletData.getSalt(blob1), WalletData.getSalt(blob2)));
        assertFalse("same seed+PIN must not reuse the IV",
            java.util.Arrays.equals(WalletData.getIv(blob1), WalletData.getIv(blob2)));
    }

    @Test
    public void createWalletWithEntropyIsDeterministic() throws Exception {
        byte[] salt = new byte[16];
        byte[] iv = new byte[16];
        for (int i = 0; i < 16; i++) {
            salt[i] = (byte) (i * 7);
            iv[i] = (byte) (i * 11);
        }
        store.createWalletWithEntropy(testSeed, "", PIN, false, salt, iv);
        InMemoryRecordStoreAdapter raw = new InMemoryRecordStoreAdapter();
        raw.open(WalletStore.STORE_NAME, false);
        byte[] blob1 = raw.getRecord(WalletStore.RECORD_SEED);
        raw.close();

        store.createWalletWithEntropy(testSeed, "", PIN, false, salt, iv);
        raw.open(WalletStore.STORE_NAME, false);
        byte[] blob2 = raw.getRecord(WalletStore.RECORD_SEED);
        raw.close();

        assertArrayEquals(blob1, blob2);
        assertArrayEquals(salt, WalletData.getSalt(blob1));
        assertArrayEquals(iv, WalletData.getIv(blob1));
        assertArrayEquals(testSeed, store.unlock(PIN));
    }

    @Test
    public void failedAttemptsCountAndResetOnSuccess() throws Exception {
        store.createWallet(testSeed, "", PIN, false);
        assertEquals(0, store.getFailedAttempts());

        assertNull(store.unlock(WRONG_PIN));
        assertNull(store.unlock(WRONG_PIN));
        assertEquals(2, store.getFailedAttempts());

        assertNotNull(store.unlock(PIN));
        assertEquals(0, store.getFailedAttempts());
    }

    @Test
    public void tooManyWrongPinsWipeWallet() throws Exception {
        store.createWallet(testSeed, "", PIN, false);
        for (int i = 0; i < WalletStore.MAX_FAILED_ATTEMPTS - 1; i++) {
            assertNull(store.unlock(WRONG_PIN));
            assertTrue("wallet must survive attempt " + (i + 1), store.walletExists());
        }
        assertNull(store.unlock(WRONG_PIN));
        assertFalse("wallet must be wiped at the attempt limit", store.walletExists());
    }

    @Test
    public void recreateReplacesStaleStore() throws Exception {
        // Simulate a partial earlier creation: a store with a single record
        InMemoryRecordStoreAdapter raw = new InMemoryRecordStoreAdapter();
        raw.open(WalletStore.STORE_NAME, true);
        raw.addRecord(new byte[] { 1, 2, 3 });
        raw.close();
        assertFalse(store.walletExists());

        store.createWallet(testSeed, "", PIN, false);
        assertTrue(store.walletExists());
        assertArrayEquals(testSeed, store.unlock(PIN));
    }

    @Test
    public void crossImplRoundTrip() throws Exception {
        // BIP39 "abandon" mnemonic (first 11 words "abandon", last word "about")
        String mnemonic = "abandon abandon abandon abandon abandon abandon "
                + "abandon abandon abandon abandon abandon about";

        byte[] seed = Bip39Mnemonic.toSeed(mnemonic, "");

        // Store and recover
        store.createWallet(seed, "", PIN, false);
        byte[] recovered = store.unlock(PIN);
        assertNotNull("Should unlock successfully", recovered);
        assertArrayEquals("Round-tripped seed must match", seed, recovered);

        // Derive address from recovered seed — must match cross-impl vector
        String address = BitcoinAddress.deriveP2wpkhAddress(
                recovered, false, 0, false, 0);
        assertEquals("Cross-impl mainnet address must match",
                "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", address);
    }
}
