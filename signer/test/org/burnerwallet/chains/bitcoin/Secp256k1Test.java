package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HexCodec;
import org.junit.Test;
import static org.junit.Assert.*;

import java.math.BigInteger;

/**
 * JUnit 4 tests for Secp256k1 elliptic curve operations.
 */
public class Secp256k1Test {

    /**
     * Private key = 1 should produce the secp256k1 generator point G.
     * G compressed = 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798
     */
    @Test
    public void privateKeyOne() throws CryptoError {
        byte[] privKey = new byte[32];
        privKey[31] = 1; // big-endian 1
        byte[] pubKey = Secp256k1.publicKeyFromPrivate(privKey);
        String pubHex = HexCodec.encode(pubKey);
        assertEquals(
            "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            pubHex
        );
    }

    /**
     * BIP32 test vector 1 master private key should derive a valid 33-byte
     * compressed public key starting with 02 or 03.
     */
    @Test
    public void privateKeyFromBip32Vector1() throws CryptoError {
        byte[] privKey = HexCodec.decode(
            "e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35"
        );
        byte[] pubKey = Secp256k1.publicKeyFromPrivate(privKey);
        assertEquals(33, pubKey.length);
        assertTrue(
            "Public key must start with 0x02 or 0x03",
            pubKey[0] == (byte) 0x02 || pubKey[0] == (byte) 0x03
        );
    }

    /**
     * Private key of all zeros is invalid and should throw CryptoError.
     */
    @Test(expected = CryptoError.class)
    public void invalidKeyZero() throws CryptoError {
        byte[] privKey = new byte[32]; // all zeros
        Secp256k1.publicKeyFromPrivate(privKey);
    }

    /**
     * Private key equal to the curve order n is invalid and should throw CryptoError.
     */
    @Test(expected = CryptoError.class)
    public void invalidKeyCurveOrder() throws CryptoError {
        BigInteger n = Secp256k1.getN();
        byte[] nBytes = n.toByteArray();
        // BigInteger.toByteArray() may have a leading zero byte; we need exactly 32 bytes
        byte[] privKey = new byte[32];
        if (nBytes.length > 32) {
            // strip leading zero
            System.arraycopy(nBytes, nBytes.length - 32, privKey, 0, 32);
        } else {
            System.arraycopy(nBytes, 0, privKey, 32 - nBytes.length, nBytes.length);
        }
        Secp256k1.publicKeyFromPrivate(privKey);
    }

    /**
     * Public key should always be exactly 33 bytes (compressed format).
     */
    @Test
    public void publicKeyLength() throws CryptoError {
        byte[] privKey = new byte[32];
        privKey[31] = 42; // arbitrary valid key
        byte[] pubKey = Secp256k1.publicKeyFromPrivate(privKey);
        assertEquals(33, pubKey.length);
    }

    /**
     * Point addition: pubKey(1) + pubKey(1) should equal pubKey(2).
     */
    @Test
    public void pointAddBasic() throws CryptoError {
        // privKey = 1
        byte[] privKey1 = new byte[32];
        privKey1[31] = 1;
        byte[] pub1 = Secp256k1.publicKeyFromPrivate(privKey1);

        // privKey = 2
        byte[] privKey2 = new byte[32];
        privKey2[31] = 2;
        byte[] pub2 = Secp256k1.publicKeyFromPrivate(privKey2);

        // pub1 + pub1 should equal pub2
        byte[] sum = Secp256k1.pointAdd(pub1, pub1);
        assertArrayEquals(pub2, sum);
    }
}
