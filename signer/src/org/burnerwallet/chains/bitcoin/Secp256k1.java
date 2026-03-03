package org.burnerwallet.chains.bitcoin;

import java.math.BigInteger;

import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.math.ec.ECPoint;

import org.burnerwallet.core.CryptoError;

/**
 * Secp256k1 elliptic curve operations using Bouncy Castle.
 *
 * Provides public key derivation and point arithmetic for Bitcoin
 * key management. All public keys are returned in compressed format
 * (33 bytes).
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public final class Secp256k1 {

    private static final X9ECParameters CURVE;
    private static final ECDomainParameters DOMAIN;

    static {
        CURVE = SECNamedCurves.getByName("secp256k1");
        DOMAIN = new ECDomainParameters(
            CURVE.getCurve(),
            CURVE.getG(),
            CURVE.getN(),
            CURVE.getH()
        );
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
            ECPoint p1 = CURVE.getCurve().decodePoint(pubKey1);
            ECPoint p2 = CURVE.getCurve().decodePoint(pubKey2);
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
        return CURVE.getN();
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
     * Validate that a private key scalar is in range (0, n).
     */
    private static void validatePrivateKey(BigInteger privKey) throws CryptoError {
        if (privKey.signum() <= 0) {
            throw new CryptoError(CryptoError.ERR_INVALID_KEY,
                "Private key must be greater than zero");
        }
        if (privKey.compareTo(CURVE.getN()) >= 0) {
            throw new CryptoError(CryptoError.ERR_INVALID_KEY,
                "Private key must be less than curve order n");
        }
    }
}
