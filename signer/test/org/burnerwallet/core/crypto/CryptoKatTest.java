package org.burnerwallet.core.crypto;

import org.burnerwallet.core.AesUtils;
import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HashUtils;
import org.burnerwallet.core.HexCodec;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Known-answer tests for the in-house CLDC-safe primitives: FIPS 180-4,
 * RIPEMD-160, RFC 4231, FIPS 197, BIP39 and secp256k1 published values.
 */
public class CryptoKatTest {

    private static byte[] ascii(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) s.charAt(i);
        }
        return b;
    }

    @Test
    public void sha256KnownAnswers() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            HexCodec.encode(Sha256.hash(new byte[0])));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            HexCodec.encode(Sha256.hash(ascii("abc"))));
        // Two-block message (56 bytes forces the padding into a second block)
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            HexCodec.encode(Sha256.hash(ascii(
                "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))));
    }

    @Test
    public void sha256Streaming() {
        byte[] msg = ascii("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq");
        Sha256 d = new Sha256();
        d.update(msg, 0, 10);
        d.update(msg, 10, msg.length - 10);
        byte[] out = new byte[32];
        d.doFinal(out, 0);
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            HexCodec.encode(out));
    }

    @Test
    public void sha512KnownAnswers() {
        assertEquals("ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
            + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            HexCodec.encode(Sha512.hash(ascii("abc"))));
        assertEquals("cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce"
            + "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
            HexCodec.encode(Sha512.hash(new byte[0])));
    }

    @Test
    public void ripemd160KnownAnswers() {
        assertEquals("9c1185a5c5e9fc54612808977ee8f548b2258d31",
            HexCodec.encode(Ripemd160.hash(new byte[0])));
        assertEquals("8eb208f7e05d987a9b044a8e98c6b087f15a0bfc",
            HexCodec.encode(Ripemd160.hash(ascii("abc"))));
        assertEquals("12a053384a9c0c88e405a06c27dcf49ada62eb2b",
            HexCodec.encode(Ripemd160.hash(ascii(
                "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))));
    }

    @Test
    public void hmacRfc4231Case2() {
        byte[] key = ascii("Jefe");
        byte[] data = ascii("what do ya want for nothing?");
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            HexCodec.encode(HashUtils.hmacSha256(key, data)));
        assertEquals("164b7a7bfcf819e2e395fbe73b56e0a387bd64222e831fd610270cd7ea250554"
            + "9758bf75c05a994a6d034f65f8f0e6fdcaeab1a34d4a6b4b636e070a38bce737",
            HexCodec.encode(HashUtils.hmacSha512(key, data)));
    }

    @Test
    public void hmacLongKeyIsHashed() {
        // RFC 4231 case 6: 131-byte key
        byte[] key = new byte[131];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) 0xaa;
        }
        byte[] data = ascii("Test Using Larger Than Block-Size Key - Hash Key First");
        assertEquals("60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            HexCodec.encode(HashUtils.hmacSha256(key, data)));
    }

    @Test
    public void pbkdf2Bip39Seed() {
        byte[] seed = HashUtils.pbkdf2HmacSha512(
            ascii("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"),
            ascii("mnemonic"), 2048, 64);
        assertEquals("5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1"
            + "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            HexCodec.encode(seed));
    }

    @Test
    public void aesFips197Vectors() throws CryptoError {
        byte[] pt = HexCodec.decode("00112233445566778899aabbccddeeff");
        byte[] out = new byte[16];
        Aes aes256 = new Aes(HexCodec.decode(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"));
        aes256.encryptBlock(pt, 0, out, 0);
        assertEquals("8ea2b7ca516745bfeafc49904b496089", HexCodec.encode(out));
        byte[] back = new byte[16];
        aes256.decryptBlock(out, 0, back, 0);
        assertArrayEquals(pt, back);

        Aes aes128 = new Aes(HexCodec.decode("000102030405060708090a0b0c0d0e0f"));
        aes128.encryptBlock(pt, 0, out, 0);
        assertEquals("69c4e0d86a7b0430d8cdb78070b4c55a", HexCodec.encode(out));
    }

    @Test
    public void aesCbcRoundTripAllLengths() throws CryptoError {
        byte[] key = HexCodec.decode(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        byte[] iv = HexCodec.decode("0f0e0d0c0b0a09080706050403020100");
        for (int len = 0; len <= 40; len++) {
            byte[] pt = new byte[len];
            for (int i = 0; i < len; i++) {
                pt[i] = (byte) (i * 7 + len);
            }
            byte[] ct = AesUtils.encrypt(pt, key, iv);
            assertEquals("padded length for " + len, ((len / 16) + 1) * 16, ct.length);
            assertArrayEquals(pt, AesUtils.decrypt(ct, key, iv));
        }
    }

    @Test(expected = CryptoError.class)
    public void aesCbcRejectsBadPadding() throws CryptoError {
        byte[] key = new byte[32];
        byte[] iv = new byte[16];
        byte[] ct = AesUtils.encrypt(new byte[5], key, iv);
        ct[ct.length - 1] ^= 0x40;
        AesUtils.decrypt(ct, key, iv);
    }

    @Test
    public void curvePointsKnownAnswers() {
        int[] two = Fe.zero();
        two[0] = 2;
        EcPoint g2 = EcPoint.G.multiply(two).normalize();
        assertEquals("c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5",
            HexCodec.encode(Fe.toBytes(g2.getX())));
        assertEquals("1ae168fea63dc339a3c58419466ceaeef7f632653266d0e1236431a950cfe52a",
            HexCodec.encode(Fe.toBytes(g2.getY())));
        int[] three = Fe.zero();
        three[0] = 3;
        EcPoint g3 = EcPoint.G.multiply(three).normalize();
        assertEquals("f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9",
            HexCodec.encode(Fe.toBytes(g3.getX())));
        // 3G must equal G + 2G and 2G + G, with mixed doubling paths
        assertTrue(g3.equalsPoint(EcPoint.G.add(g2)));
        assertTrue(g3.equalsPoint(g2.add(EcPoint.G)));
        assertTrue(g2.equalsPoint(EcPoint.G.twice()));
        assertTrue(g2.equalsPoint(EcPoint.G.add(EcPoint.G)));
        // G + (-G) is the point at infinity
        EcPoint minusG = EcPoint.fromAffine(EcPoint.G.getX(), Fe.neg(EcPoint.G.getY(), Fe.P));
        assertTrue(EcPoint.G.add(minusG).isInfinity());
        // n * G is the point at infinity
        assertTrue(EcPoint.G.multiply(Fe.fromBytes(Fe.N.toBytes(), 0)).isInfinity());
    }

    @Test
    public void compressedEncodingRoundTrip() {
        byte[] enc = EcPoint.G.encodeCompressed();
        assertEquals("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            HexCodec.encode(enc));
        assertTrue(EcPoint.decodeCompressed(enc).equalsPoint(EcPoint.G));
        // Flipping the parity prefix must decode to the negated point
        enc[0] = 0x03;
        EcPoint neg = EcPoint.decodeCompressed(enc);
        assertTrue(EcPoint.G.add(neg).isInfinity());
    }

    @Test(expected = IllegalArgumentException.class)
    public void decodeRejectsPointOffCurve() {
        byte[] enc = EcPoint.G.encodeCompressed();
        // x = 5 has no square root for x^3 + 7 on secp256k1
        for (int i = 1; i < 33; i++) {
            enc[i] = 0;
        }
        enc[32] = 5;
        EcPoint.decodeCompressed(enc);
    }

    @Test
    public void fieldArithmeticIdentities() {
        int[] a = Fe.fromBytesMod(Sha256.hash(ascii("a")), Fe.P);
        int[] b = Fe.fromBytesMod(Sha256.hash(ascii("b")), Fe.P);
        // (a + b) - b == a, (a * b) * b^-1 == a, a * a^-1 == 1
        assertTrue(Fe.equals(a, Fe.sub(Fe.add(a, b, Fe.P), b, Fe.P)));
        assertTrue(Fe.equals(a, Fe.mul(Fe.mul(a, b, Fe.P), Fe.inv(b, Fe.P), Fe.P)));
        assertTrue(Fe.equals(Fe.one(), Fe.mul(a, Fe.inv(a, Fe.P), Fe.P)));
        // Same identities modulo n
        int[] c = Fe.fromBytesMod(Sha256.hash(ascii("c")), Fe.N);
        int[] d = Fe.fromBytesMod(Sha256.hash(ascii("d")), Fe.N);
        assertTrue(Fe.equals(c, Fe.sub(Fe.add(c, d, Fe.N), d, Fe.N)));
        assertTrue(Fe.equals(Fe.one(), Fe.mul(c, Fe.inv(c, Fe.N), Fe.N)));
        // (p - 1) * (p - 1) == 1 mod p, and (p - 1) + 1 == 0
        int[] pm1 = Fe.neg(Fe.one(), Fe.P);
        assertTrue(Fe.equals(Fe.one(), Fe.mul(pm1, pm1, Fe.P)));
        assertTrue(Fe.isZero(Fe.add(pm1, Fe.one(), Fe.P)));
        // sqrt(4) == 2 or p - 2
        int[] four = Fe.zero();
        four[0] = 4;
        int[] r = Fe.sqrtP(four);
        assertTrue(Fe.equals(four, Fe.sqr(r, Fe.P)));
    }
}
