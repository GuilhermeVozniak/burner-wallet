package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HexCodec;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JUnit 4 tests for BIP32 HD key derivation.
 * Test vectors from BIP32 specification:
 * https://en.bitcoin.it/wiki/BIP_0032_TestVectors
 */
public class Bip32Test {

    // ========== Test Vector 1 ==========
    // Seed: 000102030405060708090a0b0c0d0e0f

    @Test
    public void vector1_masterFromSeed() throws CryptoError {
        byte[] seed = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);

        assertEquals(
            "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi",
            master.toXprv(false));
        assertEquals(
            "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8",
            master.toXpub(false));
    }

    @Test
    public void vector1_m_0h() throws CryptoError {
        byte[] seed = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key child = Bip32Derivation.derivePath(master, "m/0'");

        assertEquals(
            "xprv9uHRZZhk6KAJC1avXpDAp4MDc3sQKNxDiPvvkX8Br5ngLNv1TxvUxt4cV1rGL5hj6KCesnDYUhd7oWgT11eZG7XnxHrnYeSvkzY7d2bhkJ7",
            child.toXprv(false));
        assertEquals(
            "xpub68Gmy5EdvgibQVfPdqkBBCHxA5htiqg55crXYuXoQRKfDBFA1WEjWgP6LHhwBZeNK1VTsfTFUHCdrfp1bgwQ9xv5ski8PX9rL2dZXvgGDnw",
            child.toXpub(false));
    }

    @Test
    public void vector1_m_0h_1() throws CryptoError {
        byte[] seed = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key child = Bip32Derivation.derivePath(master, "m/0'/1");

        assertEquals(
            "xprv9wTYmMFdV23N2TdNG573QoEsfRrWKQgWeibmLntzniatZvR9BmLnvSxqu53Kw1UmYPxLgboyZQaXwTCg8MSY3H2EU4pWcQDnRnrVA1xe8fs",
            child.toXprv(false));
        assertEquals(
            "xpub6ASuArnXKPbfEwhqN6e3mwBcDTgzisQN1wXN9BJcM47sSikHjJf3UFHKkNAWbWMiGj7Wf5uMash7SyYq527Hqck2AxYysAA7xmALppuCkwQ",
            child.toXpub(false));
    }

    @Test
    public void vector1_m_0h_1_2h() throws CryptoError {
        byte[] seed = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key child = Bip32Derivation.derivePath(master, "m/0'/1/2'");

        assertEquals(
            "xprv9z4pot5VBttmtdRTWfWQmoH1taj2axGVzFqSb8C9xaxKymcFzXBDptWmT7FwuEzG3ryjH4ktypQSAewRiNMjANTtpgP4mLTj34bhnZX7UiM",
            child.toXprv(false));
        assertEquals(
            "xpub6D4BDPcP2GT577Vvch3R8wDkScZWzQzMMUm3PWbmWvVJrZwQY4VUNgqFJPMM3No2dFDFGTsxxpG5uJh7n7epu4trkrX7x7DogT5Uv6fcLW5",
            child.toXpub(false));
    }

    @Test
    public void vector1_m_0h_1_2h_2() throws CryptoError {
        byte[] seed = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key child = Bip32Derivation.derivePath(master, "m/0'/1/2'/2");

        assertEquals(
            "xprvA2JDeKCSNNZky6uBCviVfJSKyQ1mDYahRjijr5idH2WwLsEd4Hsb2Tyh8RfQMuPh7f7RtyzTtdrbdqqsunu5Mm3wDvUAKRHSC34sJ7in334",
            child.toXprv(false));
        assertEquals(
            "xpub6FHa3pjLCk84BayeJxFW2SP4XRrFd1JYnxeLeU8EqN3vDfZmbqBqaGJAyiLjTAwm6ZLRQUMv1ZACTj37sR62cfN7fe5JnJ7dh8zL4fiyLHV",
            child.toXpub(false));
    }

    @Test
    public void vector1_m_0h_1_2h_2_1000000000() throws CryptoError {
        byte[] seed = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key child = Bip32Derivation.derivePath(master, "m/0'/1/2'/2/1000000000");

        assertEquals(
            "xprvA41z7zogVVwxVSgdKUHDy1SKmdb533PjDz7J6N6mV6uS3ze1ai8FHa8kmHScGpWmj4WggLyQjgPie1rFSruoUihUZREPSL39UNdE3BBDu76",
            child.toXprv(false));
        assertEquals(
            "xpub6H1LXWLaKsWFhvm6RVpEL9P4KfRZSW7abD2ttkWP3SSQvnyA8FSVqNTEcYFgJS2UaFcxupHiYkro49S8yGasTvXEYBVPamhGW6cFJodrTHy",
            child.toXpub(false));
    }

    // ========== Test Vector 2 ==========
    // Seed: fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a29f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542

    @Test
    public void vector2_masterFromSeed() throws CryptoError {
        byte[] seed = HexCodec.decode(
            "fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a2" +
            "9f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);

        assertEquals(
            "xprv9s21ZrQH143K31xYSDQpPDxsXRTUcvj2iNHm5NUtrGiGG5e2DtALGdso3pGz6ssrdK4PFmM8NSpSBHNqPqm55Qn3LqFtT2emdEXVYsCzC2U",
            master.toXprv(false));
        assertEquals(
            "xpub661MyMwAqRbcFW31YEwpkMuc5THy2PSt5bDMsktWQcFF8syAmRUapSCGu8ED9W6oDMSgv6Zz8idoc4a6mr8BDzTJY47LJhkJ8UB7WEGuduB",
            master.toXpub(false));
    }

    @Test
    public void vector2_m_0() throws CryptoError {
        byte[] seed = HexCodec.decode(
            "fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a2" +
            "9f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key child = Bip32Derivation.derivePath(master, "m/0");

        assertEquals(
            "xprv9vHkqa6EV4sPZHYqZznhT2NPtPCjKuDKGY38FBWLvgaDx45zo9WQRUT3dKYnjwih2yJD9mkrocEZXo1ex8G81dwSM1fwqWpWkeS3v86pgKt",
            child.toXprv(false));
        assertEquals(
            "xpub69H7F5d8KSRgmmdJg2KhpAK8SR3DjMwAdkxj3ZuxV27CprR9LgpeyGmXUbC6wb7ERfvrnKZjXoUmmDznezpbZb7ap6r1D3tgFxHmwMkQTPH",
            child.toXpub(false));
    }

    @Test
    public void vector2_m_0_2147483647h() throws CryptoError {
        byte[] seed = HexCodec.decode(
            "fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a2" +
            "9f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key child = Bip32Derivation.derivePath(master, "m/0/2147483647'");

        assertEquals(
            "xprv9wSp6B7kry3Vj9m1zSnLvN3xH8RdsPP1Mh7fAaR7aRLcQMKTR2vidYEeEg2mUCTAwCd6vnxVrcjfy2kRgVsFawNzmjuHc2YmYRmagcEPdU9",
            child.toXprv(false));
        assertEquals(
            "xpub6ASAVgeehLbnwdqV6UKMHVzgqAG8Gr6riv3Fxxpj8ksbH9ebxaEyBLZ85ySDhKiLDBrQSARLq1uNRts8RuJiHjaDMBU4Zn9h8LZNnBC5y4a",
            child.toXpub(false));
    }

    // ========== Additional Vector 2 chains ==========

    @Test
    public void vector2_m_0_2147483647h_1() throws CryptoError {
        byte[] seed = HexCodec.decode(
            "fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a2" +
            "9f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key child = Bip32Derivation.derivePath(master, "m/0/2147483647'/1");

        assertEquals(
            "xprv9zFnWC6h2cLgpmSA46vutJzBcfJ8yaJGg8cX1e5StJh45BBciYTRXSd25UEPVuesF9yog62tGAQtHjXajPPdbRCHuWS6T8XA2ECKADdw4Ef",
            child.toXprv(false));
        assertEquals(
            "xpub6DF8uhdarytz3FWdA8TvFSvvAh8dP3283MY7p2V4SeE2wyWmG5mg5EwVvmdMVCQcoNJxGoWaU9DCWh89LojfZ537wTfunKau47EL2dhHKon",
            child.toXpub(false));
    }

    @Test
    public void vector2_m_0_2147483647h_1_2147483646h() throws CryptoError {
        byte[] seed = HexCodec.decode(
            "fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a2" +
            "9f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key child = Bip32Derivation.derivePath(master, "m/0/2147483647'/1/2147483646'");

        assertEquals(
            "xprvA1RpRA33e1JQ7ifknakTFpgNXPmW2YvmhqLQYMmrj4xJXXWYpDPS3xz7iAxn8L39njGVyuoseXzU6rcxFLJ8HFsTjSyQbLYnMpCqE2VbFWc",
            child.toXprv(false));
        assertEquals(
            "xpub6ERApfZwUNrhLCkDtcHTcxd75RbzS1ed54G1LkBUHQVHQKqhMkhgbmJbZRkrgZw4koxb5JaHWkY4ALHY2grBGRjaDMzQLcgJvLJuZZvRcEL",
            child.toXpub(false));
    }

    @Test
    public void vector2_m_0_2147483647h_1_2147483646h_2() throws CryptoError {
        byte[] seed = HexCodec.decode(
            "fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a2" +
            "9f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key child = Bip32Derivation.derivePath(master, "m/0/2147483647'/1/2147483646'/2");

        assertEquals(
            "xprvA2nrNbFZABcdryreWet9Ea4LvTJcGsqrMzxHx98MMrotbir7yrKCEXw7nadnHM8Dq38EGfSh6dqA9QWTyefMLEcBYJUuekgW4BYPJcr9E7j",
            child.toXprv(false));
        assertEquals(
            "xpub6FnCn6nSzZAw5Tw7cgR9bi15UV96gLZhjDstkXXxvCLsUXBGXPdSnLFbdpq8p9HmGsApME5hQTZ3emM2rnY5agb9rXpVGyy3bdW6EEgAtqt",
            child.toXpub(false));
    }

    // ========== parsePath tests ==========

    @Test
    public void parsePath_bip84() throws CryptoError {
        int[] indices = Bip32Derivation.parsePath("m/84'/0'/0'");
        assertEquals(3, indices.length);
        assertEquals(84 | Bip32Derivation.HARDENED, indices[0]);
        assertEquals(0 | Bip32Derivation.HARDENED, indices[1]);
        assertEquals(0 | Bip32Derivation.HARDENED, indices[2]);
    }

    @Test
    public void parsePath_normalIndices() throws CryptoError {
        int[] indices = Bip32Derivation.parsePath("m/0/1/2");
        assertEquals(3, indices.length);
        assertEquals(0, indices[0]);
        assertEquals(1, indices[1]);
        assertEquals(2, indices[2]);
    }

    @Test
    public void parsePath_mixedHardenedAndNormal() throws CryptoError {
        int[] indices = Bip32Derivation.parsePath("m/44'/0'/0'/0/0");
        assertEquals(5, indices.length);
        assertEquals(44 | Bip32Derivation.HARDENED, indices[0]);
        assertEquals(0 | Bip32Derivation.HARDENED, indices[1]);
        assertEquals(0 | Bip32Derivation.HARDENED, indices[2]);
        assertEquals(0, indices[3]);
        assertEquals(0, indices[4]);
    }

    @Test
    public void parsePath_masterOnly() throws CryptoError {
        int[] indices = Bip32Derivation.parsePath("m");
        assertEquals(0, indices.length);
    }

    @Test(expected = CryptoError.class)
    public void parsePath_invalidPath() throws CryptoError {
        Bip32Derivation.parsePath("x/0'/1");
    }

    // ========== Full 5-level derivation path test ==========

    @Test
    public void fullFiveLevelDerivation() throws CryptoError {
        // Vector 1: m/0'/1/2'/2/1000000000 — tests full 5-level path
        byte[] seed = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key child = Bip32Derivation.derivePath(master, "m/0'/1/2'/2/1000000000");

        assertEquals(5, child.getDepth());
        assertEquals(
            "xprvA41z7zogVVwxVSgdKUHDy1SKmdb533PjDz7J6N6mV6uS3ze1ai8FHa8kmHScGpWmj4WggLyQjgPie1rFSruoUihUZREPSL39UNdE3BBDu76",
            child.toXprv(false));
    }

    // ========== Bip32Key property tests ==========

    @Test
    public void masterKeyHasDepthZero() throws CryptoError {
        byte[] seed = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        assertEquals(0, master.getDepth());
        assertTrue(master.isPrivate());
    }

    @Test
    public void childKeyDepthIncrements() throws CryptoError {
        byte[] seed = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key child1 = Bip32Derivation.deriveChild(master, Bip32Derivation.HARDENED);
        assertEquals(1, child1.getDepth());
        Bip32Key child2 = Bip32Derivation.deriveChild(child1, 1);
        assertEquals(2, child2.getDepth());
    }

    @Test
    public void toPublicReturnsPublicKey() throws CryptoError {
        byte[] seed = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key pubKey = master.toPublic();
        assertFalse(pubKey.isPrivate());
        assertEquals(33, pubKey.getPublicKeyBytes().length);
    }

    @Test
    public void privateKeyBytesAre32Bytes() throws CryptoError {
        byte[] seed = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        byte[] privBytes = master.getPrivateKeyBytes();
        assertEquals(32, privBytes.length);
    }

    @Test(expected = CryptoError.class)
    public void getPrivateKeyBytesFromPublicThrows() throws CryptoError {
        byte[] seed = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key pubKey = master.toPublic();
        pubKey.getPrivateKeyBytes(); // should throw
    }

    @Test
    public void destroyZerosKeyData() throws CryptoError {
        byte[] seed = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        master.destroy();
        // After destroy, chain code and key data should be zeroed
        byte[] cc = master.getChainCode();
        byte[] kd = master.getKeyData();
        boolean allZero = true;
        for (int i = 0; i < cc.length; i++) {
            if (cc[i] != 0) { allZero = false; break; }
        }
        for (int i = 0; i < kd.length; i++) {
            if (kd[i] != 0) { allZero = false; break; }
        }
        assertTrue("Key data should be zeroed after destroy", allZero);
    }

    // ========== deriveChild with step-by-step verification ==========

    @Test
    public void deriveChildStepByStep_vector1() throws CryptoError {
        byte[] seed = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);

        // m/0' (hardened)
        Bip32Key m0h = Bip32Derivation.deriveChild(master, 0 | Bip32Derivation.HARDENED);
        assertEquals(
            "xprv9uHRZZhk6KAJC1avXpDAp4MDc3sQKNxDiPvvkX8Br5ngLNv1TxvUxt4cV1rGL5hj6KCesnDYUhd7oWgT11eZG7XnxHrnYeSvkzY7d2bhkJ7",
            m0h.toXprv(false));

        // m/0'/1 (normal)
        Bip32Key m0h1 = Bip32Derivation.deriveChild(m0h, 1);
        assertEquals(
            "xprv9wTYmMFdV23N2TdNG573QoEsfRrWKQgWeibmLntzniatZvR9BmLnvSxqu53Kw1UmYPxLgboyZQaXwTCg8MSY3H2EU4pWcQDnRnrVA1xe8fs",
            m0h1.toXprv(false));

        // m/0'/1/2' (hardened)
        Bip32Key m0h1_2h = Bip32Derivation.deriveChild(m0h1, 2 | Bip32Derivation.HARDENED);
        assertEquals(
            "xprv9z4pot5VBttmtdRTWfWQmoH1taj2axGVzFqSb8C9xaxKymcFzXBDptWmT7FwuEzG3ryjH4ktypQSAewRiNMjANTtpgP4mLTj34bhnZX7UiM",
            m0h1_2h.toXprv(false));

        // m/0'/1/2'/2 (normal)
        Bip32Key m0h1_2h_2 = Bip32Derivation.deriveChild(m0h1_2h, 2);
        assertEquals(
            "xprvA2JDeKCSNNZky6uBCviVfJSKyQ1mDYahRjijr5idH2WwLsEd4Hsb2Tyh8RfQMuPh7f7RtyzTtdrbdqqsunu5Mm3wDvUAKRHSC34sJ7in334",
            m0h1_2h_2.toXprv(false));

        // m/0'/1/2'/2/1000000000 (normal)
        Bip32Key m0h1_2h_2_1B = Bip32Derivation.deriveChild(m0h1_2h_2, 1000000000);
        assertEquals(
            "xprvA41z7zogVVwxVSgdKUHDy1SKmdb533PjDz7J6N6mV6uS3ze1ai8FHa8kmHScGpWmj4WggLyQjgPie1rFSruoUihUZREPSL39UNdE3BBDu76",
            m0h1_2h_2_1B.toXprv(false));
    }

    // ========== Parent fingerprint test ==========

    @Test
    public void masterFingerprintIsZero() throws CryptoError {
        byte[] seed = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        byte[] fp = master.getParentFingerprint();
        assertEquals(4, fp.length);
        assertEquals(0, fp[0]);
        assertEquals(0, fp[1]);
        assertEquals(0, fp[2]);
        assertEquals(0, fp[3]);
    }

    @Test
    public void masterChildIndexIsZero() throws CryptoError {
        byte[] seed = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        assertEquals(0, master.getChildIndex());
    }
}
