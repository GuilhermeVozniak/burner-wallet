package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.CryptoError;
import org.junit.Test;
import static org.junit.Assert.*;

public class AddressTest {
    private static final String ABANDON_MNEMONIC =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

    @Test
    public void bip84MainnetPath() {
        assertEquals("m/84'/0'/0'", Bip44Path.bip84Account(false, 0));
    }

    @Test
    public void bip84TestnetPath() {
        assertEquals("m/84'/1'/0'", Bip44Path.bip84Account(true, 0));
    }

    @Test
    public void bip84AddressPath() {
        assertEquals("m/84'/0'/0'/0/0", Bip44Path.bip84Address(false, 0, false, 0));
        assertEquals("m/84'/0'/0'/1/0", Bip44Path.bip84Address(false, 0, true, 0));
        assertEquals("m/84'/1'/0'/0/5", Bip44Path.bip84Address(true, 0, false, 5));
    }

    @Test
    public void crossImplMainnetReceive0() throws CryptoError {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        String addr = BitcoinAddress.deriveP2wpkhAddress(seed, false, 0, false, 0);
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", addr);
    }

    @Test
    public void crossImplMainnetReceive1() throws CryptoError {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        String addr = BitcoinAddress.deriveP2wpkhAddress(seed, false, 0, false, 1);
        assertEquals("bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g", addr);
    }

    @Test
    public void crossImplMainnetChange0() throws CryptoError {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        String addr = BitcoinAddress.deriveP2wpkhAddress(seed, false, 0, true, 0);
        assertEquals("bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el", addr);
    }

    @Test
    public void crossImplTestnetReceive0() throws CryptoError {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        String addr = BitcoinAddress.deriveP2wpkhAddress(seed, true, 0, false, 0);
        assertEquals("tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl", addr);
    }

    @Test
    public void differentIndicesProduceDifferentAddresses() throws CryptoError {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        String addr0 = BitcoinAddress.deriveP2wpkhAddress(seed, true, 0, false, 0);
        String addr1 = BitcoinAddress.deriveP2wpkhAddress(seed, true, 0, false, 1);
        assertNotEquals(addr0, addr1);
    }

    @Test
    public void passphraseProducesDifferentAddress() throws CryptoError {
        byte[] seedNoPass = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        byte[] seedWithPass = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "secret");
        String addr1 = BitcoinAddress.deriveP2wpkhAddress(seedNoPass, true, 0, false, 0);
        String addr2 = BitcoinAddress.deriveP2wpkhAddress(seedWithPass, true, 0, false, 0);
        assertNotEquals(addr1, addr2);
    }
}
