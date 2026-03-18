package org.burnerwallet.chains.bitcoin;

import java.math.BigInteger;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;

import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HexCodec;

/**
 * Secp256k1 elliptic curve operations using Bouncy Castle.
 *
 * Provides public key derivation, point arithmetic, ECDSA signing
 * (RFC 6979 deterministic nonce, BIP 62/146 low-S normalization),
 * signature verification, and DER encoding for Bitcoin.
 * All public keys are returned in compressed format (33 bytes).
 *
 * Curve parameters are hardcoded to avoid the SECNamedCurves
 * registry which depends on ASN1ObjectIdentifier -> java.util.Map
 * (not available in CLDC 1.1).
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public final class Secp256k1 {

    private static final ECCurve CURVE;
    private static final ECDomainParameters DOMAIN;

    static {
        // secp256k1 curve parameters (SEC 2, section 2.7.1)
        try {
            BigInteger p = new BigInteger(1, HexCodec.decode(
                "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F"));
            BigInteger a = BigInteger.valueOf(0);
            BigInteger b = BigInteger.valueOf(7);
            BigInteger n = new BigInteger(1, HexCodec.decode(
                "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141"));
            BigInteger h = BigInteger.valueOf(1);

            CURVE = new ECCurve.Fp(p, a, b);
            byte[] gEnc = HexCodec.decode(
                "04"
                + "79BE667EF9DCBBAC55A06295CE870B07"
                + "029BFCDB2DCE28D959F2815B16F81798"
                + "483ADA7726A3C4655DA4FBFC0E1108A8"
                + "FD17B448A68554199C47D08FFB10D4B8");
            ECPoint g = CURVE.decodePoint(gEnc);
            DOMAIN = new ECDomainParameters(CURVE, g, n, h);
        } catch (Exception e) {
            throw new RuntimeException("secp256k1 init failed");
        }
    }

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
        BigInteger privKeyInt = new BigInteger(1, privateKey32);
        validatePrivateKey(privKeyInt);
        ECPoint point = DOMAIN.getG().multiply(privKeyInt).normalize();
        return point.getEncoded(true);
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
            ECPoint p1 = CURVE.decodePoint(pubKey1);
            ECPoint p2 = CURVE.decodePoint(pubKey2);
            ECPoint sum = p1.add(p2).normalize();
            return sum.getEncoded(true);
        } catch (Exception e) {
            throw new CryptoError(CryptoError.ERR_INVALID_KEY,
                "Point addition failed: " + e.getMessage());
        }
    }

    /**
     * Return the curve order n.
     *
     * @return the order of the secp256k1 generator point
     */
    public static BigInteger getN() {
        return DOMAIN.getN();
    }

    /**
     * Return the EC domain parameters for secp256k1.
     *
     * @return ECDomainParameters instance
     */
    public static ECDomainParameters getDomain() {
        return DOMAIN;
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

        BigInteger privKeyInt = new BigInteger(1, privateKey);
        validatePrivateKey(privKeyInt);

        try {
            ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
            ECPrivateKeyParameters keyParams = new ECPrivateKeyParameters(privKeyInt, DOMAIN);
            signer.init(true, keyParams);

            BigInteger[] components = signer.generateSignature(messageHash);
            BigInteger r = components[0];
            BigInteger s = components[1];

            // Low-S normalization (BIP 62/146)
            BigInteger halfN = DOMAIN.getN().shiftRight(1);
            if (s.compareTo(halfN) > 0) {
                s = DOMAIN.getN().subtract(s);
            }

            byte[] result = new byte[64];
            byte[] rBytes = toUnsigned32(r);
            byte[] sBytes = toUnsigned32(s);
            System.arraycopy(rBytes, 0, result, 0, 32);
            System.arraycopy(sBytes, 0, result, 32, 32);
            return result;
        } catch (Exception e) {
            throw new CryptoError(CryptoError.ERR_SIGNING,
                "ECDSA signing failed: " + e.getMessage());
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

        try {
            byte[] rBytes = new byte[32];
            byte[] sBytes = new byte[32];
            System.arraycopy(signature, 0, rBytes, 0, 32);
            System.arraycopy(signature, 32, sBytes, 0, 32);

            BigInteger r = new BigInteger(1, rBytes);
            BigInteger s = new BigInteger(1, sBytes);

            ECPoint point = CURVE.decodePoint(compressedPubKey);
            ECPublicKeyParameters keyParams = new ECPublicKeyParameters(point, DOMAIN);

            ECDSASigner signer = new ECDSASigner();
            signer.init(false, keyParams);

            return signer.verifySignature(messageHash, r, s);
        } catch (Exception e) {
            throw new CryptoError(CryptoError.ERR_SIGNING,
                "Signature verification failed: " + e.getMessage());
        }
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
     * Convert a BigInteger to a 32-byte unsigned big-endian byte array.
     * Pads with leading zeros or strips the leading sign byte as needed.
     */
    private static byte[] toUnsigned32(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length == 32) {
            return bytes;
        } else if (bytes.length > 32) {
            // Strip leading zero byte (sign byte from BigInteger)
            byte[] result = new byte[32];
            System.arraycopy(bytes, bytes.length - 32, result, 0, 32);
            return result;
        } else {
            // Pad with leading zeros
            byte[] result = new byte[32];
            System.arraycopy(bytes, 0, result, 32 - bytes.length, bytes.length);
            return result;
        }
    }

    /**
     * Validate that a private key scalar is in range (0, n).
     */
    private static void validatePrivateKey(BigInteger privKey) throws CryptoError {
        if (privKey.signum() <= 0) {
            throw new CryptoError(CryptoError.ERR_INVALID_KEY,
                "Private key must be greater than zero");
        }
        if (privKey.compareTo(DOMAIN.getN()) >= 0) {
            throw new CryptoError(CryptoError.ERR_INVALID_KEY,
                "Private key must be less than curve order n");
        }
    }
}
