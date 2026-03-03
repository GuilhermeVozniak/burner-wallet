package org.burnerwallet.core;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JUnit 4 tests for HashUtils — known-answer vectors for
 * SHA-256, RIPEMD-160, HMAC-SHA512, and PBKDF2-HMAC-SHA512.
 */
public class HashUtilsTest {

    // ---- SHA-256 tests ----

    @Test
    public void sha256Empty() throws CryptoError {
        byte[] hash = HashUtils.sha256(new byte[0]);
        assertEquals(32, hash.length);
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            HexCodec.encode(hash));
    }

    @Test
    public void sha256Abc() throws CryptoError {
        byte[] hash = HashUtils.sha256("abc".getBytes());
        assertEquals(32, hash.length);
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            HexCodec.encode(hash));
    }

    // ---- Double SHA-256 tests ----

    @Test
    public void doubleSha256Abc() throws CryptoError {
        // doubleSha256("abc") = SHA-256(SHA-256("abc"))
        byte[] inner = HashUtils.sha256("abc".getBytes());
        byte[] expected = HashUtils.sha256(inner);
        byte[] result = HashUtils.doubleSha256("abc".getBytes());
        assertEquals(32, result.length);
        assertArrayEquals(expected, result);
    }

    @Test
    public void doubleSha256Empty() throws CryptoError {
        byte[] inner = HashUtils.sha256(new byte[0]);
        byte[] expected = HashUtils.sha256(inner);
        byte[] result = HashUtils.doubleSha256(new byte[0]);
        assertEquals(32, result.length);
        assertArrayEquals(expected, result);
    }

    // ---- RIPEMD-160 tests ----

    @Test
    public void ripemd160Abc() {
        byte[] hash = HashUtils.ripemd160("abc".getBytes());
        assertEquals(20, hash.length);
        assertEquals(
            "8eb208f7e05d987a9b044a8e98c6b087f15a0bfc",
            HexCodec.encode(hash));
    }

    @Test
    public void ripemd160Empty() {
        byte[] hash = HashUtils.ripemd160(new byte[0]);
        assertEquals(20, hash.length);
        assertEquals(
            "9c1185a5c5e9fc54612808977ee8f548b2258d31",
            HexCodec.encode(hash));
    }

    // ---- Hash160 tests ----

    @Test
    public void hash160Abc() throws CryptoError {
        // hash160("abc") = RIPEMD-160(SHA-256("abc"))
        byte[] sha = HashUtils.sha256("abc".getBytes());
        byte[] expected = HashUtils.ripemd160(sha);
        byte[] result = HashUtils.hash160("abc".getBytes());
        assertEquals(20, result.length);
        assertArrayEquals(expected, result);
    }

    // ---- HMAC-SHA512 tests ----

    @Test
    public void hmacSha512Bip32Vector1() throws CryptoError {
        // BIP32 Test Vector 1: master key derivation
        // Key = "Bitcoin seed" (ASCII)
        // Data = 0x000102030405060708090a0b0c0d0e0f
        byte[] key = "Bitcoin seed".getBytes();
        byte[] data = HexCodec.decode("000102030405060708090a0b0c0d0e0f");

        byte[] hmac = HashUtils.hmacSha512(key, data);
        assertEquals(64, hmac.length);

        // First 32 bytes = master secret key
        String first32 = HexCodec.encode(
            ByteArrayUtils.copyOfRange(hmac, 0, 32));
        assertEquals(
            "e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35",
            first32);

        // Last 32 bytes = master chain code
        String last32 = HexCodec.encode(
            ByteArrayUtils.copyOfRange(hmac, 32, 64));
        assertEquals(
            "873dff81c02f525623fd1fe5167eac3a55a049de3d314bb42ee227ffed37d508",
            last32);
    }

    @Test
    public void hmacSha512Bip32Vector2() throws CryptoError {
        // BIP32 Test Vector 2: master key derivation
        byte[] key = "Bitcoin seed".getBytes();
        byte[] data = HexCodec.decode(
            "fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a2" +
            "9f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542");

        byte[] hmac = HashUtils.hmacSha512(key, data);
        assertEquals(64, hmac.length);

        String first32 = HexCodec.encode(
            ByteArrayUtils.copyOfRange(hmac, 0, 32));
        assertEquals(
            "4b03d6fc340455b363f51020ad3ecca4f0850280cf436c70c727923f6db46c3e",
            first32);
    }

    // ---- PBKDF2-HMAC-SHA512 tests ----

    @Test
    public void pbkdf2HmacSha512Bip39Vector() throws CryptoError {
        // BIP39 test vector: "abandon" x 11 + "about"
        // mnemonic passphrase = "abandon abandon abandon abandon abandon " +
        //                       "abandon abandon abandon abandon abandon " +
        //                       "abandon about"
        // salt = "mnemonic" (no extension passphrase)
        String mnemonic = "abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon about";
        byte[] password = mnemonic.getBytes();
        byte[] salt = "mnemonic".getBytes();

        byte[] derived = HashUtils.pbkdf2HmacSha512(password, salt, 2048, 64);
        assertEquals(64, derived.length);
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1" +
            "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            HexCodec.encode(derived));
    }

    @Test
    public void pbkdf2HmacSha512ShortOutput() throws CryptoError {
        // Test with a shorter derived key length
        String mnemonic = "abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon about";
        byte[] password = mnemonic.getBytes();
        byte[] salt = "mnemonic".getBytes();

        byte[] full = HashUtils.pbkdf2HmacSha512(password, salt, 2048, 64);
        byte[] partial = HashUtils.pbkdf2HmacSha512(password, salt, 2048, 32);
        assertEquals(32, partial.length);

        // First 32 bytes should match the first 32 bytes of the full derivation
        byte[] firstHalf = ByteArrayUtils.copyOfRange(full, 0, 32);
        assertArrayEquals(firstHalf, partial);
    }
}
