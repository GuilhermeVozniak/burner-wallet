package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HashUtils;
import org.burnerwallet.core.HexCodec;
import org.junit.Test;
import static org.junit.Assert.*;

import java.math.BigInteger;

/**
 * JUnit 4 tests for ECDSA signing, verification, and DER encoding
 * in the Secp256k1 class.
 */
public class Secp256k1SignTest {

    /**
     * Sign with private key = 1 and SHA-256("Satoshi Nakamoto").
     * RFC 6979 deterministic nonce should produce a valid, verifiable signature.
     */
    @Test
    public void signDeterministicNonce() throws CryptoError {
        byte[] privKey = new byte[32];
        privKey[31] = 1;

        byte[] msgHash = HashUtils.sha256("Satoshi Nakamoto".getBytes());
        byte[] sig = Secp256k1.sign(msgHash, privKey);

        assertEquals("Signature must be 64 bytes", 64, sig.length);

        // Verify the signature is valid
        byte[] pubKey = Secp256k1.publicKeyFromPrivate(privKey);
        assertTrue("Signature should verify", Secp256k1.verify(msgHash, sig, pubKey));
    }

    /**
     * Every signature produced by sign() must have low-S (s <= n/2),
     * per BIP 62/146.
     */
    @Test
    public void signLowSNormalization() throws CryptoError {
        BigInteger halfN = Secp256k1.getN().shiftRight(1);

        // Test with several different keys
        for (int k = 1; k <= 5; k++) {
            byte[] privKey = new byte[32];
            privKey[31] = (byte) k;

            byte[] msgHash = HashUtils.sha256(("message " + k).getBytes());
            byte[] sig = Secp256k1.sign(msgHash, privKey);

            // Extract s from bytes 32..63
            byte[] sBytes = new byte[32];
            System.arraycopy(sig, 32, sBytes, 0, 32);
            BigInteger s = new BigInteger(1, sBytes);

            assertTrue("s must be <= n/2 for key " + k,
                s.compareTo(halfN) <= 0);
        }
    }

    /**
     * DER encoding should start with 0x30 and contain two INTEGER components
     * (0x02 tag each).
     */
    @Test
    public void signDerEncoding() throws CryptoError {
        byte[] privKey = new byte[32];
        privKey[31] = 1;

        byte[] msgHash = HashUtils.sha256("test DER".getBytes());
        byte[] sig = Secp256k1.sign(msgHash, privKey);
        byte[] der = Secp256k1.serializeDER(sig);

        // Must start with SEQUENCE tag 0x30
        assertEquals("DER must start with 0x30", 0x30, der[0] & 0xFF);

        // Total length byte
        int totalLen = der[1] & 0xFF;
        assertEquals("DER total length must match payload",
            der.length - 2, totalLen);

        // First INTEGER tag
        assertEquals("First component must be INTEGER (0x02)",
            0x02, der[2] & 0xFF);

        int rLen = der[3] & 0xFF;

        // Second INTEGER tag
        assertEquals("Second component must be INTEGER (0x02)",
            0x02, der[4 + rLen] & 0xFF);

        int sLen = der[5 + rLen] & 0xFF;

        // Total structure length check
        assertEquals("DER structure length",
            2 + 2 + rLen + 2 + sLen, der.length);
    }

    /**
     * A signature produced by sign() should verify against the same
     * key and message hash.
     */
    @Test
    public void verifyValidSignature() throws CryptoError {
        byte[] privKey = HexCodec.decode(
            "e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35"
        );
        byte[] pubKey = Secp256k1.publicKeyFromPrivate(privKey);
        byte[] msgHash = HashUtils.sha256("Hello Bitcoin".getBytes());

        byte[] sig = Secp256k1.sign(msgHash, privKey);
        assertTrue("Valid signature must verify",
            Secp256k1.verify(msgHash, sig, pubKey));
    }

    /**
     * A signature for msg1 must NOT verify against msg2.
     */
    @Test
    public void verifyWrongMessage() throws CryptoError {
        byte[] privKey = new byte[32];
        privKey[31] = 42;

        byte[] msg1 = HashUtils.sha256("message one".getBytes());
        byte[] msg2 = HashUtils.sha256("message two".getBytes());

        byte[] pubKey = Secp256k1.publicKeyFromPrivate(privKey);
        byte[] sig = Secp256k1.sign(msg1, privKey);

        assertFalse("Signature for msg1 must not verify against msg2",
            Secp256k1.verify(msg2, sig, pubKey));
    }

    /**
     * A signature made with key1 must NOT verify with key2's public key.
     */
    @Test
    public void verifyWrongKey() throws CryptoError {
        byte[] privKey1 = new byte[32];
        privKey1[31] = 10;
        byte[] privKey2 = new byte[32];
        privKey2[31] = 20;

        byte[] msgHash = HashUtils.sha256("wrong key test".getBytes());

        byte[] sig = Secp256k1.sign(msgHash, privKey1);
        byte[] pubKey2 = Secp256k1.publicKeyFromPrivate(privKey2);

        assertFalse("Signature from key1 must not verify with key2",
            Secp256k1.verify(msgHash, sig, pubKey2));
    }

    /**
     * Different messages must produce different signatures (RFC 6979
     * determinism means same key + same msg = same sig, but different
     * messages should differ).
     */
    @Test
    public void signDifferentMessagesProduceDifferentSigs() throws CryptoError {
        byte[] privKey = new byte[32];
        privKey[31] = 7;

        byte[] msg1 = HashUtils.sha256("alpha".getBytes());
        byte[] msg2 = HashUtils.sha256("beta".getBytes());

        byte[] sig1 = Secp256k1.sign(msg1, privKey);
        byte[] sig2 = Secp256k1.sign(msg2, privKey);

        boolean identical = true;
        for (int i = 0; i < 64; i++) {
            if (sig1[i] != sig2[i]) {
                identical = false;
                break;
            }
        }
        assertFalse("Different messages must produce different signatures",
            identical);
    }

    /**
     * In Bitcoin, a scriptSig signature is DER-encoded followed by the
     * SIGHASH type byte. For SIGHASH_ALL (0x01), append one byte.
     */
    @Test
    public void signAndAppendSighashType() throws CryptoError {
        byte[] privKey = new byte[32];
        privKey[31] = 3;

        byte[] msgHash = HashUtils.sha256("sighash test".getBytes());
        byte[] sig = Secp256k1.sign(msgHash, privKey);
        byte[] der = Secp256k1.serializeDER(sig);

        // Append SIGHASH_ALL = 0x01
        byte[] scriptSig = new byte[der.length + 1];
        System.arraycopy(der, 0, scriptSig, 0, der.length);
        scriptSig[scriptSig.length - 1] = 0x01;

        // Verify structure: starts with 0x30, ends with 0x01
        assertEquals("Must start with 0x30", 0x30, scriptSig[0] & 0xFF);
        assertEquals("Must end with SIGHASH_ALL (0x01)",
            0x01, scriptSig[scriptSig.length - 1] & 0xFF);

        // DER length byte should NOT include the sighash type
        int derLen = scriptSig[1] & 0xFF;
        assertEquals("DER length + 2 (header) + 1 (sighash) = total",
            scriptSig.length, derLen + 2 + 1);
    }
}
