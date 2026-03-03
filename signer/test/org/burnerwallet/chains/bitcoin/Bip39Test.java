package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HexCodec;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JUnit 4 tests for BIP39 — wordlist loading, mnemonic generation,
 * validation, and seed derivation using official test vectors.
 */
public class Bip39Test {

    // ---- Wordlist tests ----

    @Test
    public void wordlistLoads() {
        Bip39Wordlist.init();
        assertTrue("Wordlist should be loaded", Bip39Wordlist.isLoaded());
        assertEquals("abandon", Bip39Wordlist.getWord(0));
        assertEquals("zoo", Bip39Wordlist.getWord(2047));
    }

    @Test
    public void wordlistIndexOf() {
        Bip39Wordlist.init();
        assertEquals(0, Bip39Wordlist.indexOf("abandon"));
        assertEquals(2047, Bip39Wordlist.indexOf("zoo"));
        assertEquals(-1, Bip39Wordlist.indexOf("notaword"));
    }

    // ---- Mnemonic generation tests ----

    @Test
    public void generateFromEntropy_vector1() throws CryptoError {
        // BIP39 test vector: all-zero 128-bit entropy
        byte[] entropy = HexCodec.decode("00000000000000000000000000000000");
        String mnemonic = Bip39Mnemonic.generate(entropy);
        assertEquals(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            mnemonic);
    }

    @Test
    public void generateFromEntropy_vector4() throws CryptoError {
        // BIP39 test vector: all-ones 128-bit entropy
        byte[] entropy = HexCodec.decode("ffffffffffffffffffffffffffffffff");
        String mnemonic = Bip39Mnemonic.generate(entropy);
        assertEquals(
            "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
            mnemonic);
    }

    // ---- Validation tests ----

    @Test
    public void validateValid() throws CryptoError {
        String mnemonic = "abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon about";
        assertTrue("Valid mnemonic should pass validation",
            Bip39Mnemonic.validate(mnemonic));
    }

    @Test
    public void validateInvalid() throws CryptoError {
        // "abandon" repeated 12 times has wrong checksum
        String mnemonic = "abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon";
        assertFalse("Invalid checksum mnemonic should fail validation",
            Bip39Mnemonic.validate(mnemonic));
    }

    // ---- Seed derivation tests ----

    @Test
    public void toSeed_vector1() throws CryptoError {
        // BIP39 test vector 1: "abandon...about" with empty passphrase
        String mnemonic = "abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon about";
        byte[] seed = Bip39Mnemonic.toSeed(mnemonic, "");
        assertEquals(64, seed.length);
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1" +
            "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            HexCodec.encode(seed));
    }

    @Test
    public void toSeed_vector2_withPassphrase() throws CryptoError {
        // BIP39 test vector: "legal winner..." with passphrase "TREZOR"
        String mnemonic = "legal winner thank year wave sausage worth " +
            "useful legal winner thank yellow";
        byte[] seed = Bip39Mnemonic.toSeed(mnemonic, "TREZOR");
        assertEquals(64, seed.length);
        assertEquals(
            "2e8905819b8723fe2c1d161860e5ee1830318dbf49a83bd451cfb8440c28bd6f" +
            "a457fe1296106559a3c80937a1c1069be3a3a5bd381ee6260e8d9739fce1f607",
            HexCodec.encode(seed));
    }
}
