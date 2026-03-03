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
