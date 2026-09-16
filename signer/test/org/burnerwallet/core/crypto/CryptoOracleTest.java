package org.burnerwallet.core.crypto;

import java.math.BigInteger;
import java.util.Random;

import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.BufferedBlockCipher;
import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.engines.AESLightEngine;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.paddings.PKCS7Padding;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.math.ec.ECPoint;
import org.burnerwallet.chains.bitcoin.Secp256k1;
import org.burnerwallet.core.AesUtils;
import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HashUtils;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Differential tests: the in-house CLDC-safe primitives must agree
 * byte-for-byte with Bouncy Castle (test-classpath only) on random inputs.
 * Bouncy Castle no longer ships in the signer JAR; it serves as the oracle.
 */
public class CryptoOracleTest {

    private static final Random RNG = new Random(0x5EED1234L);

    private static byte[] randomBytes(int len) {
        byte[] b = new byte[len];
        RNG.nextBytes(b);
        return b;
    }

    private static byte[] bcDigest(org.bouncycastle.crypto.Digest d, byte[] in) {
        d.update(in, 0, in.length);
        byte[] out = new byte[d.getDigestSize()];
        d.doFinal(out, 0);
        return out;
    }

    @Test
    public void digestsMatchBouncyCastle() {
        for (int i = 0; i < 300; i++) {
            byte[] in = randomBytes(RNG.nextInt(300));
            assertArrayEquals(bcDigest(new SHA256Digest(), in), Sha256.hash(in));
            assertArrayEquals(bcDigest(new SHA512Digest(), in), Sha512.hash(in));
            assertArrayEquals(bcDigest(new RIPEMD160Digest(), in), Ripemd160.hash(in));
        }
        // Lengths around every block boundary
        for (int len = 0; len < 260; len++) {
            byte[] in = randomBytes(len);
            assertArrayEquals(bcDigest(new SHA256Digest(), in), Sha256.hash(in));
            assertArrayEquals(bcDigest(new SHA512Digest(), in), Sha512.hash(in));
            assertArrayEquals(bcDigest(new RIPEMD160Digest(), in), Ripemd160.hash(in));
        }
    }

    @Test
    public void hmacMatchesBouncyCastle() {
        for (int i = 0; i < 200; i++) {
            byte[] key = randomBytes(RNG.nextInt(200));
            byte[] data = randomBytes(RNG.nextInt(300));
            HMac h256 = new HMac(new SHA256Digest());
            h256.init(new KeyParameter(key));
            h256.update(data, 0, data.length);
            byte[] exp256 = new byte[32];
            h256.doFinal(exp256, 0);
            assertArrayEquals(exp256, HashUtils.hmacSha256(key, data));

            HMac h512 = new HMac(new SHA512Digest());
            h512.init(new KeyParameter(key));
            h512.update(data, 0, data.length);
            byte[] exp512 = new byte[64];
            h512.doFinal(exp512, 0);
            assertArrayEquals(exp512, HashUtils.hmacSha512(key, data));
        }
    }

    @Test
    public void pbkdf2MatchesBouncyCastle() {
        for (int i = 0; i < 12; i++) {
            byte[] pw = randomBytes(RNG.nextInt(40));
            byte[] salt = randomBytes(RNG.nextInt(40));
            int iterations = 1 + RNG.nextInt(6);
            int dkLen = 1 + RNG.nextInt(140);
            PKCS5S2ParametersGenerator gen = new PKCS5S2ParametersGenerator(new SHA512Digest());
            gen.init(pw, salt, iterations);
            byte[] expected = ((KeyParameter) gen.generateDerivedMacParameters(dkLen * 8)).getKey();
            assertArrayEquals(expected, HashUtils.pbkdf2HmacSha512(pw, salt, iterations, dkLen));
        }
    }

    private static byte[] bcAesCbc(boolean encrypt, byte[] key, byte[] iv, byte[] in) throws Exception {
        BufferedBlockCipher cipher = new PaddedBufferedBlockCipher(
            new CBCBlockCipher(new AESLightEngine()), new PKCS7Padding());
        cipher.init(encrypt, new ParametersWithIV(new KeyParameter(key), iv));
        byte[] out = new byte[cipher.getOutputSize(in.length)];
        int len = cipher.processBytes(in, 0, in.length, out, 0);
        len += cipher.doFinal(out, len);
        byte[] r = new byte[len];
        System.arraycopy(out, 0, r, 0, len);
        return r;
    }

    @Test
    public void aesCbcMatchesBouncyCastle() throws Exception {
        for (int i = 0; i < 100; i++) {
            byte[] key = randomBytes(32);
            byte[] iv = randomBytes(16);
            byte[] pt = randomBytes(RNG.nextInt(120));
            byte[] ct = AesUtils.encrypt(pt, key, iv);
            assertArrayEquals(bcAesCbc(true, key, iv, pt), ct);
            assertArrayEquals(pt, AesUtils.decrypt(ct, key, iv));
            assertArrayEquals(pt, bcAesCbc(false, key, iv, ct));
        }
    }

    @Test
    public void aesBlockMatchesBouncyCastleAllKeySizes() {
        int[] sizes = {16, 24, 32};
        for (int s = 0; s < sizes.length; s++) {
            for (int i = 0; i < 30; i++) {
                byte[] key = randomBytes(sizes[s]);
                byte[] block = randomBytes(16);
                AESLightEngine bc = new AESLightEngine();
                bc.init(true, new KeyParameter(key));
                byte[] expected = new byte[16];
                bc.processBlock(block, 0, expected, 0);
                byte[] actual = new byte[16];
                Aes aes = new Aes(key);
                aes.encryptBlock(block, 0, actual, 0);
                assertArrayEquals(expected, actual);
                byte[] back = new byte[16];
                aes.decryptBlock(actual, 0, back, 0);
                assertArrayEquals(block, back);
            }
        }
    }

    private static final X9ECParameters BC_CURVE = SECNamedCurves.getByName("secp256k1");
    private static final ECDomainParameters BC_DOMAIN = new ECDomainParameters(
        BC_CURVE.getCurve(), BC_CURVE.getG(), BC_CURVE.getN(), BC_CURVE.getH());

    private static byte[] randomPrivateKey() {
        while (true) {
            byte[] k = randomBytes(32);
            if (Secp256k1.isValidPrivateKey(k)) {
                return k;
            }
        }
    }

    @Test
    public void publicKeysMatchBouncyCastle() throws CryptoError {
        for (int i = 0; i < 40; i++) {
            byte[] d = randomPrivateKey();
            ECPoint expected = BC_CURVE.getG().multiply(new BigInteger(1, d)).normalize();
            assertArrayEquals(expected.getEncoded(true), Secp256k1.publicKeyFromPrivate(d));
        }
        // Edge scalars: 1, 2, n-1, and small powers of two
        BigInteger n = BC_CURVE.getN();
        BigInteger[] edges = {BigInteger.ONE, BigInteger.valueOf(2), n.subtract(BigInteger.ONE),
            BigInteger.ONE.shiftLeft(255), BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)};
        for (int i = 0; i < edges.length; i++) {
            byte[] d = to32(edges[i]);
            ECPoint expected = BC_CURVE.getG().multiply(edges[i]).normalize();
            assertArrayEquals(expected.getEncoded(true), Secp256k1.publicKeyFromPrivate(d));
        }
    }

    @Test
    public void pointAdditionMatchesBouncyCastle() throws CryptoError {
        for (int i = 0; i < 25; i++) {
            byte[] a = Secp256k1.publicKeyFromPrivate(randomPrivateKey());
            byte[] b = Secp256k1.publicKeyFromPrivate(randomPrivateKey());
            ECPoint expected = BC_CURVE.getCurve().decodePoint(a)
                .add(BC_CURVE.getCurve().decodePoint(b)).normalize();
            assertArrayEquals(expected.getEncoded(true), Secp256k1.pointAdd(a, b));
        }
    }

    @Test
    public void scalarAdditionMatchesBigInteger() throws CryptoError {
        BigInteger n = BC_CURVE.getN();
        for (int i = 0; i < 50; i++) {
            byte[] a = randomPrivateKey();
            byte[] b = randomPrivateKey();
            BigInteger expected = new BigInteger(1, a).add(new BigInteger(1, b)).mod(n);
            assertArrayEquals(to32(expected), Secp256k1.addScalarsModN(a, b));
        }
        // Wrap-around: (n-1) + 1 == 0, (n-1) + (n-1) == n-2
        byte[] nm1 = to32(n.subtract(BigInteger.ONE));
        byte[] one = to32(BigInteger.ONE);
        assertArrayEquals(new byte[32], Secp256k1.addScalarsModN(nm1, one));
        assertArrayEquals(to32(n.subtract(BigInteger.valueOf(2))), Secp256k1.addScalarsModN(nm1, nm1));
    }

    @Test
    public void signaturesMatchBouncyCastleByteForByte() throws CryptoError {
        BigInteger n = BC_CURVE.getN();
        BigInteger halfN = n.shiftRight(1);
        for (int i = 0; i < 30; i++) {
            byte[] d = randomPrivateKey();
            byte[] hash = randomBytes(32);
            ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
            signer.init(true, new ECPrivateKeyParameters(new BigInteger(1, d), BC_DOMAIN));
            BigInteger[] rs = signer.generateSignature(hash);
            BigInteger s = rs[1];
            if (s.compareTo(halfN) > 0) {
                s = n.subtract(s);
            }
            byte[] expected = new byte[64];
            System.arraycopy(to32(rs[0]), 0, expected, 0, 32);
            System.arraycopy(to32(s), 0, expected, 32, 32);
            byte[] actual = Secp256k1.sign(hash, d);
            assertArrayEquals("signature " + i, expected, actual);

            // Cross verification in both directions
            byte[] pub = Secp256k1.publicKeyFromPrivate(d);
            assertTrue(Secp256k1.verify(hash, actual, pub));
            ECDSASigner verifier = new ECDSASigner();
            verifier.init(false, new ECPublicKeyParameters(
                BC_CURVE.getCurve().decodePoint(pub), BC_DOMAIN));
            assertTrue(verifier.verifySignature(hash,
                new BigInteger(1, slice(actual, 0)), new BigInteger(1, slice(actual, 32))));
            // A flipped hash bit must not verify
            byte[] bad = (byte[]) hash.clone();
            bad[i % 32] ^= 1;
            assertFalse(Secp256k1.verify(bad, actual, pub));
        }
    }

    @Test
    public void hashesAboveCurveOrderAreHandled() throws CryptoError {
        // Message hashes >= n exercise the bits2octets reduction path
        byte[] d = randomPrivateKey();
        byte[] hash = new byte[32];
        for (int i = 0; i < 32; i++) {
            hash[i] = (byte) 0xff;
        }
        ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
        signer.init(true, new ECPrivateKeyParameters(new BigInteger(1, d), BC_DOMAIN));
        BigInteger[] rs = signer.generateSignature(hash);
        BigInteger n = BC_CURVE.getN();
        BigInteger s = rs[1].compareTo(n.shiftRight(1)) > 0 ? n.subtract(rs[1]) : rs[1];
        byte[] expected = new byte[64];
        System.arraycopy(to32(rs[0]), 0, expected, 0, 32);
        System.arraycopy(to32(s), 0, expected, 32, 32);
        assertArrayEquals(expected, Secp256k1.sign(hash, d));
        assertTrue(Secp256k1.verify(hash, expected, Secp256k1.publicKeyFromPrivate(d)));
    }

    private static byte[] to32(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length >= 32) {
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
    }

    private static byte[] slice(byte[] a, int off) {
        byte[] r = new byte[32];
        System.arraycopy(a, off, r, 0, 32);
        return r;
    }
}
