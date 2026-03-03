package org.burnerwallet.storage;

import org.burnerwallet.chains.bitcoin.Bip39Mnemonic;
import org.burnerwallet.chains.bitcoin.BitcoinAddress;
import org.burnerwallet.core.CryptoError;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * End-to-end wallet integration tests.
 *
 * Exercises the full round-trip: BIP39 seed derivation -> encrypted storage ->
 * PIN-based unlock -> BIP84 address derivation. Verifies cross-implementation
 * address vectors against the companion Rust library.
 */
public class WalletIntegrationTest {

    private static final String ABANDON_MNEMONIC =
        "abandon abandon abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon about";

    private static final String DEFAULT_PIN = "1234";

    private WalletStore store;

    @Before
    public void setUp() {
        InMemoryRecordStoreAdapter.resetAll();
        store = new WalletStore(new InMemoryRecordStoreAdapter());
    }

    /**
     * Full round-trip with no passphrase: generate seed, store it encrypted,
     * unlock with correct PIN, derive mainnet address, verify cross-impl vector.
     */
    @Test
    public void fullRoundTripNoPassphrase() throws Exception {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        store.createWallet(seed, "", DEFAULT_PIN, false);

        byte[] recovered = store.unlock(DEFAULT_PIN);
        assertNotNull("Unlock with correct PIN should return seed", recovered);
        assertArrayEquals("Recovered seed must match original", seed, recovered);

        String address = BitcoinAddress.deriveP2wpkhAddress(
                recovered, false, 0, false, 0);
        assertEquals("Mainnet address must match cross-impl vector",
                "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", address);
    }

    /**
     * Full round-trip with a passphrase: seed differs from no-passphrase version,
     * passphrase is stored and recoverable, derived address differs.
     */
    @Test
    public void fullRoundTripWithPassphrase() throws Exception {
        String passphrase = "mysecret";
        byte[] seedWithPass = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, passphrase);
        byte[] seedNoPass = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");

        // Seeds must differ when passphrase is used
        assertFalse("Passphrase seed must differ from no-passphrase seed",
                java.util.Arrays.equals(seedWithPass, seedNoPass));

        store.createWallet(seedWithPass, passphrase, DEFAULT_PIN, false);

        byte[] recovered = store.unlock(DEFAULT_PIN);
        assertNotNull("Unlock should succeed", recovered);
        assertArrayEquals("Recovered seed must match passphrase seed",
                seedWithPass, recovered);

        String recoveredPass = store.getPassphrase(DEFAULT_PIN);
        assertEquals("Recovered passphrase must match",
                passphrase, recoveredPass);

        // Address derived from passphrase seed must differ from no-passphrase address
        String addrWithPass = BitcoinAddress.deriveP2wpkhAddress(
                recovered, false, 0, false, 0);
        String addrNoPass = BitcoinAddress.deriveP2wpkhAddress(
                seedNoPass, false, 0, false, 0);
        assertFalse("Passphrase address must differ from no-passphrase address",
                addrWithPass.equals(addrNoPass));
    }

    /**
     * Testnet address derivation: create wallet with testnet=true, verify
     * network flag, derive testnet address, verify cross-impl vector.
     */
    @Test
    public void testnetAddress() throws Exception {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        store.createWallet(seed, "", DEFAULT_PIN, true);

        assertTrue("Wallet should report testnet", store.isTestnet());

        byte[] recovered = store.unlock(DEFAULT_PIN);
        assertNotNull("Unlock should succeed", recovered);

        String address = BitcoinAddress.deriveP2wpkhAddress(
                recovered, true, 0, false, 0);
        assertEquals("Testnet address must match cross-impl vector",
                "tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl", address);
    }

    /**
     * Wrong PIN attempts cannot recover the seed; only the correct PIN works.
     */
    @Test
    public void wrongPinCannotRecover() throws Exception {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        store.createWallet(seed, "", DEFAULT_PIN, false);

        assertNull("Wrong PIN '0000' should return null",
                store.unlock("0000"));
        assertNull("Wrong PIN '12345' should return null",
                store.unlock("12345"));
        assertNull("Wrong PIN '4321' should return null",
                store.unlock("4321"));

        byte[] recovered = store.unlock(DEFAULT_PIN);
        assertNotNull("Correct PIN should still unlock after failed attempts",
                recovered);
        assertArrayEquals("Seed must match after failed attempts",
                seed, recovered);
    }

    /**
     * Wipe destroys the wallet; a new wallet can be created with a different PIN,
     * and the old PIN no longer works.
     */
    @Test
    public void wipeAndRecreate() throws Exception {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        store.createWallet(seed, "", DEFAULT_PIN, false);
        assertTrue("Wallet should exist after creation", store.walletExists());

        store.wipe();
        assertFalse("Wallet should not exist after wipe", store.walletExists());

        // Recreate with a different PIN
        String newPin = "5678";
        store.createWallet(seed, "", newPin, false);
        assertTrue("Wallet should exist after recreation", store.walletExists());

        assertNull("Old PIN should not unlock recreated wallet",
                store.unlock(DEFAULT_PIN));

        byte[] recovered = store.unlock(newPin);
        assertNotNull("New PIN should unlock recreated wallet", recovered);
        assertArrayEquals("Recovered seed must match", seed, recovered);
    }

    /**
     * Address index persists in config and can be used for derivation.
     */
    @Test
    public void addressIndexPersists() throws Exception {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        store.createWallet(seed, "", DEFAULT_PIN, false);

        store.setAddressIndex(3);
        assertEquals("Address index should be 3", 3, store.getAddressIndex());

        byte[] recovered = store.unlock(DEFAULT_PIN);
        assertNotNull("Unlock should succeed", recovered);

        // Derive address at the persisted index — should be a valid bech32 address
        String address = BitcoinAddress.deriveP2wpkhAddress(
                recovered, false, 0, false, 3);
        assertNotNull("Address at index 3 should not be null", address);
        assertTrue("Address should start with bc1q",
                address.startsWith("bc1q"));
    }

    /**
     * Multiple addresses derived from the same wallet are all distinct,
     * and indices 0 and 1 match known cross-impl vectors.
     */
    @Test
    public void multipleAddressesFromSameWallet() throws Exception {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        store.createWallet(seed, "", DEFAULT_PIN, false);

        byte[] recovered = store.unlock(DEFAULT_PIN);
        assertNotNull("Unlock should succeed", recovered);

        String addr0 = BitcoinAddress.deriveP2wpkhAddress(
                recovered, false, 0, false, 0);
        String addr1 = BitcoinAddress.deriveP2wpkhAddress(
                recovered, false, 0, false, 1);
        String addr2 = BitcoinAddress.deriveP2wpkhAddress(
                recovered, false, 0, false, 2);

        // All three addresses must be distinct
        assertFalse("addr0 and addr1 must differ", addr0.equals(addr1));
        assertFalse("addr1 and addr2 must differ", addr1.equals(addr2));
        assertFalse("addr0 and addr2 must differ", addr0.equals(addr2));

        // Verify known cross-impl vectors
        assertEquals("Index 0 must match known vector",
                "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", addr0);
        assertEquals("Index 1 must match known vector",
                "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g", addr1);
    }
}
