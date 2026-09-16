package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.ByteArrayUtils;
import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.crypto.EcPoint;
import org.burnerwallet.core.crypto.Fe;
import org.burnerwallet.core.crypto.Hmac;
import org.burnerwallet.core.crypto.Sha256;

/**
 * Secp256k1 elliptic curve operations on the in-house CLDC-safe
 * implementation ({@link EcPoint}, {@link Fe}); no Bouncy Castle and no
 * java.math.BigInteger, neither of which exists on the target phone.
 *
 * Provides public key derivation, point addition, scalar arithmetic for
 * BIP32, ECDSA signing (RFC 6979 deterministic nonce, BIP 62/146 low-S
 * normalization), signature verification, and DER encoding.
 * All public keys are returned in compressed format (33 bytes).
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public final class Secp256k1 {

    /** Upper bound on RFC 6979 candidate nonces before giving up. */
    private static final int MAX_NONCE_TRIES = 1000;

    private Secp256k1() {
        // prevent instantiation
    }

    /**
     * Derive the compressed public key from a 32-byte private key.
     *
     * @param privateKey32 the 32-byte private key (big-endian, unsigned)
     * @return 33-byte compressed public key
     * @throws CryptoError if the private key is invalid (zero or >= curve order)
     */
    public static byte[] publicKeyFromPrivate(byte[] privateKey32) throws CryptoError {
        int[] d = parsePrivateKey(privateKey32);
        try {
            return EcPoint.G.multiply(d).encodeCompressed();
        } finally {
            Fe.wipe(d);
        }
    }

    /**
     * Add two compressed public key points together.
     *
     * @param pubKey1 first 33-byte compressed public key
     * @param pubKey2 second 33-byte compressed public key
     * @return 33-byte compressed public key of the sum
     * @throws CryptoError if either key cannot be decoded as a curve point
     */
    public static byte[] pointAdd(byte[] pubKey1, byte[] pubKey2) throws CryptoError {
        try {
            EcPoint p1 = EcPoint.decodeCompressed(pubKey1);
            EcPoint p2 = EcPoint.decodeCompressed(pubKey2);
            EcPoint sum = p1.add(p2);
            if (sum.isInfinity()) {
                throw new CryptoError(CryptoError.ERR_INVALID_KEY,
                    "Point addition failed: result is the point at infinity");
            }
            return sum.encodeCompressed();
        } catch (IllegalArgumentException e) {
            throw new CryptoError(CryptoError.ERR_INVALID_KEY,
                "Point addition failed: " + e.getMessage());
        }
    }

    /**
     * @return the curve order n as 32 big-endian bytes
     */
    public static byte[] getNBytes() {
        return Fe.N.toBytes();
    }

    /**
     * @param key candidate private key
     * @return true if key is 32 bytes and 0 &lt; key &lt; n
     */
    public static boolean isValidPrivateKey(byte[] key) {
        if (key == null || key.length != 32) {
            return false;
        }
        int[] v = Fe.fromBytes(key, 0);
        boolean ok = !Fe.isZero(v) && Fe.isBelow(v, Fe.N);
        Fe.wipe(v);
        return ok;
    }

    /**
     * @param scalar 32-byte big-endian scalar
     * @return true if scalar &lt; n (zero allowed)
     */
    public static boolean isScalarBelowN(byte[] scalar) {
        if (scalar == null || scalar.length != 32) {
            return false;
        }
        int[] v = Fe.fromBytes(scalar, 0);
        boolean ok = Fe.isBelow(v, Fe.N);
        Fe.wipe(v);
        return ok;
    }

    /**
     * Compute (a + b) mod n for BIP32 child key derivation.
     *
     * @param a 32-byte scalar below n
     * @param b 32-byte scalar below n
     * @return 32-byte big-endian result (may be zero; callers must check)
     * @throws CryptoError if either input is not a scalar below n
     */
    public static byte[] addScalarsModN(byte[] a, byte[] b) throws CryptoError {
        if (!isScalarBelowN(a) || !isScalarBelowN(b)) {
            throw new CryptoError(CryptoError.ERR_INVALID_KEY, "Scalar out of range");
        }
        int[] fa = Fe.fromBytes(a, 0);
        int[] fb = Fe.fromBytes(b, 0);
        int[] r = Fe.add(fa, fb, Fe.N);
        byte[] out = Fe.toBytes(r);
        Fe.wipe(fa);
        Fe.wipe(fb);
        Fe.wipe(r);
        return out;
    }

    /**
     * Sign a 32-byte message hash using ECDSA with RFC 6979 deterministic nonce.
     * Applies BIP 62/146 low-S normalization: if s > n/2, replace with n - s.
     *
     * @param messageHash 32-byte hash to sign (e.g. SHA-256 of a message)
     * @param privateKey  32-byte private key
     * @return 64-byte signature: r(32) || s(32), each zero-padded big-endian
     * @throws CryptoError if messageHash is not 32 bytes or signing fails
     */
    public static byte[] sign(byte[] messageHash, byte[] privateKey) throws CryptoError {
        if (messageHash == null || messageHash.length != 32) {
            throw new CryptoError(CryptoError.ERR_SIGNING,
                "Message hash must be exactly 32 bytes");
        }
        int[] d = parsePrivateKey(privateKey);
        int[] z = Fe.fromBytesMod(messageHash, Fe.N);
        byte[] x = ByteArrayUtils.copyOf(privateKey, 32);
        byte[] h1 = Fe.toBytes(z);
        byte[] v = new byte[32];
        byte[] k = new byte[32];
        for (int i = 0; i < 32; i++) {
            v[i] = 0x01;
        }
        // RFC 6979 section 3.2 steps b-g
        Hmac hmac = new Hmac(new Sha256(), k);
        hmac.update(v, 0, 32);
        hmac.update((byte) 0x00);
        hmac.update(x, 0, 32);
        hmac.update(h1, 0, 32);
        hmac.doFinal(k, 0);
        hmac.destroy();
        hmac = new Hmac(new Sha256(), k);
        hmac.update(v, 0, 32);
        hmac.doFinal(v, 0);
        hmac.update(v, 0, 32);
        hmac.update((byte) 0x01);
        hmac.update(x, 0, 32);
        hmac.update(h1, 0, 32);
        hmac.doFinal(k, 0);
        hmac.destroy();
        hmac = new Hmac(new Sha256(), k);
        hmac.update(v, 0, 32);
        hmac.doFinal(v, 0);
        try {
            for (int attempt = 0; attempt < MAX_NONCE_TRIES; attempt++) {
                // step h: T = HMAC_K(V); k = bits2int(T)
                hmac.update(v, 0, 32);
                hmac.doFinal(v, 0);
                int[] kk = Fe.fromBytes(v, 0);
                if (!Fe.isZero(kk) && Fe.isBelow(kk, Fe.N)) {
                    EcPoint rp = EcPoint.G.multiply(kk).normalize();
                    int[] r = Fe.fromBytesMod(Fe.toBytes(rp.getX()), Fe.N);
                    if (!Fe.isZero(r)) {
                        int[] kinv = Fe.inv(kk, Fe.N);
                        int[] rd = Fe.mul(r, d, Fe.N);
                        int[] s = Fe.mul(kinv, Fe.add(z, rd, Fe.N), Fe.N);
                        Fe.wipe(kinv);
                        Fe.wipe(rd);
                        if (!Fe.isZero(s)) {
                            if (Fe.isHighS(s)) {
                                s = Fe.neg(s, Fe.N);
                            }
                            byte[] result = new byte[64];
                            System.arraycopy(Fe.toBytes(r), 0, result, 0, 32);
                            System.arraycopy(Fe.toBytes(s), 0, result, 32, 32);
                            Fe.wipe(kk);
                            return result;
                        }
                    }
                }
                Fe.wipe(kk);
                // retry: K = HMAC_K(V || 0x00); V = HMAC_K(V)
                hmac.update(v, 0, 32);
                hmac.update((byte) 0x00);
                hmac.doFinal(k, 0);
                hmac.destroy();
                hmac = new Hmac(new Sha256(), k);
                hmac.update(v, 0, 32);
                hmac.doFinal(v, 0);
            }
            throw new CryptoError(CryptoError.ERR_SIGNING, "ECDSA signing failed: no valid nonce");
        } finally {
            Fe.wipe(d);
            Fe.wipe(z);
            ByteArrayUtils.zeroFill(x);
            ByteArrayUtils.zeroFill(v);
            ByteArrayUtils.zeroFill(k);
            hmac.destroy();
        }
    }

    /**
     * Verify an ECDSA signature against a message hash and compressed public key.
     *
     * @param messageHash    32-byte hash that was signed
     * @param signature      64-byte signature: r(32) || s(32)
     * @param compressedPubKey 33-byte compressed public key
     * @return true if the signature is valid, false otherwise
     * @throws CryptoError if inputs have wrong lengths or decoding fails
     */
    public static boolean verify(byte[] messageHash, byte[] signature,
                                 byte[] compressedPubKey) throws CryptoError {
        if (messageHash == null || messageHash.length != 32) {
            throw new CryptoError(CryptoError.ERR_SIGNING,
                "Message hash must be exactly 32 bytes");
        }
        if (signature == null || signature.length != 64) {
            throw new CryptoError(CryptoError.ERR_SIGNING,
                "Signature must be exactly 64 bytes");
        }
        if (compressedPubKey == null || compressedPubKey.length != 33) {
            throw new CryptoError(CryptoError.ERR_SIGNING,
                "Compressed public key must be exactly 33 bytes");
        }
        EcPoint q;
        try {
            q = EcPoint.decodeCompressed(compressedPubKey);
        } catch (IllegalArgumentException e) {
            throw new CryptoError(CryptoError.ERR_SIGNING,
                "Signature verification failed: " + e.getMessage());
        }
        int[] r = Fe.fromBytes(signature, 0);
        int[] s = Fe.fromBytes(signature, 32);
        if (Fe.isZero(r) || !Fe.isBelow(r, Fe.N) || Fe.isZero(s) || !Fe.isBelow(s, Fe.N)) {
            return false;
        }
        int[] z = Fe.fromBytesMod(messageHash, Fe.N);
        int[] w = Fe.inv(s, Fe.N);
        int[] u1 = Fe.mul(z, w, Fe.N);
        int[] u2 = Fe.mul(r, w, Fe.N);
        EcPoint rp = EcPoint.G.multiply(u1).add(q.multiply(u2));
        if (rp.isInfinity()) {
            return false;
        }
        rp = rp.normalize();
        int[] v = Fe.fromBytesMod(Fe.toBytes(rp.getX()), Fe.N);
        return Fe.equals(v, r);
    }

    /**
     * Serialize a 64-byte raw signature (r||s) to DER format.
     *
     * DER encoding: 0x30 &lt;total_len&gt; 0x02 &lt;r_len&gt; &lt;r&gt; 0x02 &lt;s_len&gt; &lt;s&gt;
     *
     * @param rs 64-byte raw signature: r(32) || s(32)
     * @return DER-encoded signature
     * @throws CryptoError if rs is not exactly 64 bytes
     */
    public static byte[] serializeDER(byte[] rs) throws CryptoError {
        if (rs == null || rs.length != 64) {
            throw new CryptoError(CryptoError.ERR_SIGNING,
                "Raw signature must be exactly 64 bytes");
        }

        byte[] rRaw = new byte[32];
        byte[] sRaw = new byte[32];
        System.arraycopy(rs, 0, rRaw, 0, 32);
        System.arraycopy(rs, 32, sRaw, 0, 32);

        byte[] rDer = integerToDER(rRaw);
        byte[] sDer = integerToDER(sRaw);

        int totalLen = rDer.length + sDer.length;
        byte[] result = new byte[2 + totalLen];
        result[0] = 0x30;
        result[1] = (byte) totalLen;
        System.arraycopy(rDer, 0, result, 2, rDer.length);
        System.arraycopy(sDer, 0, result, 2 + rDer.length, sDer.length);
        return result;
    }

    /**
     * Convert a 32-byte unsigned big-endian integer to DER INTEGER encoding.
     * Strips leading zeros and prepends 0x00 if the high bit is set.
     */
    private static byte[] integerToDER(byte[] value) {
        // Strip leading zeros
        int start = 0;
        while (start < value.length - 1 && value[start] == 0) {
            start++;
        }

        // If high bit is set, prepend 0x00 to keep it positive
        boolean needsPadding = (value[start] & 0x80) != 0;
        int len = value.length - start;
        if (needsPadding) {
            len++;
        }

        byte[] der = new byte[2 + len];
        der[0] = 0x02;
        der[1] = (byte) len;
        if (needsPadding) {
            der[2] = 0x00;
            System.arraycopy(value, start, der, 3, value.length - start);
        } else {
            System.arraycopy(value, start, der, 2, value.length - start);
        }
        return der;
    }

    /**
     * Parse and validate a private key scalar in range (0, n).
     */
    private static int[] parsePrivateKey(byte[] privateKey) throws CryptoError {
        if (privateKey == null || privateKey.length != 32) {
            throw new CryptoError(CryptoError.ERR_INVALID_KEY,
                "Private key must be exactly 32 bytes");
        }
        int[] d = Fe.fromBytes(privateKey, 0);
        if (Fe.isZero(d)) {
            throw new CryptoError(CryptoError.ERR_INVALID_KEY,
                "Private key must be greater than zero");
        }
        if (!Fe.isBelow(d, Fe.N)) {
            Fe.wipe(d);
            throw new CryptoError(CryptoError.ERR_INVALID_KEY,
                "Private key must be less than curve order n");
        }
        return d;
    }
}
