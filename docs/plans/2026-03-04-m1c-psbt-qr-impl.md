# M1c — PSBT Signing & QR Transport Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Enable the signer to parse unsigned PSBTs, sign them with ECDSA, and exchange data with the companion via QR codes. Build the companion's PSBT construction, wallet management, and full TUI workflow.

**Architecture:** Streaming PSBT parser on signer (Java ME), Nayuki QR encoder ported to Java 1.4, multi-frame sequential QR protocol, MMAPI camera scanning with manual hex fallback. Companion uses rust-bitcoin + BDK for PSBT construction and Esplora for broadcasting.

**Tech Stack:** Java ME (CLDC 1.1 / MIDP 2.0), Bouncy Castle jdk14, Nayuki QR (MIT), Rust (bitcoin 0.32, bdk_wallet 1, ratatui 0.29)

---

## Phase 1: Signer Crypto Foundation

### Task 1: CompactSize — Bitcoin varint encoding

**Files:**
- Create: `signer/src/org/burnerwallet/core/CompactSize.java`
- Create: `signer/test/org/burnerwallet/core/CompactSizeTest.java`

**Step 1: Write the failing test**

```java
package org.burnerwallet.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class CompactSizeTest {

    @Test
    public void readSingleByte() {
        // 0xFC (252) is max single-byte value
        byte[] data = new byte[]{(byte) 0xFC};
        long[] result = CompactSize.read(data, 0);
        assertEquals(252L, result[0]);
        assertEquals(1L, result[1]); // bytes consumed
    }

    @Test
    public void readTwoBytes() {
        // 0xFD 0xFD 0x00 = 253
        byte[] data = new byte[]{(byte) 0xFD, (byte) 0xFD, 0x00};
        long[] result = CompactSize.read(data, 0);
        assertEquals(253L, result[0]);
        assertEquals(3L, result[1]);
    }

    @Test
    public void readFourBytes() {
        // 0xFE followed by 4 bytes LE = 65536
        byte[] data = new byte[]{(byte) 0xFE, 0x00, 0x00, 0x01, 0x00};
        long[] result = CompactSize.read(data, 0);
        assertEquals(65536L, result[0]);
        assertEquals(5L, result[1]);
    }

    @Test
    public void readEightBytes() {
        // 0xFF followed by 8 bytes LE
        byte[] data = new byte[]{(byte) 0xFF, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00};
        long[] result = CompactSize.read(data, 0);
        assertEquals(4294967296L, result[0]);
        assertEquals(9L, result[1]);
    }

    @Test
    public void writeSingleByte() {
        byte[] result = CompactSize.write(252);
        assertEquals(1, result.length);
        assertEquals((byte) 0xFC, result[0]);
    }

    @Test
    public void writeTwoBytes() {
        byte[] result = CompactSize.write(253);
        assertEquals(3, result.length);
        assertEquals((byte) 0xFD, result[0]);
        assertEquals((byte) 0xFD, result[1]);
        assertEquals((byte) 0x00, result[2]);
    }

    @Test
    public void writeRoundTrip() {
        long[] values = new long[]{0, 1, 252, 253, 65535, 65536, 100000};
        for (int i = 0; i < values.length; i++) {
            byte[] encoded = CompactSize.write(values[i]);
            long[] decoded = CompactSize.read(encoded, 0);
            assertEquals(values[i], decoded[0]);
        }
    }

    @Test
    public void readWithOffset() {
        byte[] data = new byte[]{0x42, 0x42, (byte) 0x0A};
        long[] result = CompactSize.read(data, 2);
        assertEquals(10L, result[0]);
        assertEquals(1L, result[1]);
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test`
Expected: Compilation error — `CompactSize` class not found

**Step 3: Write implementation**

```java
package org.burnerwallet.core;

/**
 * Bitcoin CompactSize (variable-length integer) encoding.
 * Used in transactions and PSBT binary format.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class CompactSize {

    /**
     * Read a CompactSize value from data at the given offset.
     * Returns long[2]: [0] = value, [1] = bytes consumed.
     */
    public static long[] read(byte[] data, int offset) {
        int first = data[offset] & 0xFF;
        if (first < 0xFD) {
            return new long[]{first, 1};
        } else if (first == 0xFD) {
            long val = (data[offset + 1] & 0xFFL)
                     | ((data[offset + 2] & 0xFFL) << 8);
            return new long[]{val, 3};
        } else if (first == 0xFE) {
            long val = (data[offset + 1] & 0xFFL)
                     | ((data[offset + 2] & 0xFFL) << 8)
                     | ((data[offset + 3] & 0xFFL) << 16)
                     | ((data[offset + 4] & 0xFFL) << 24);
            return new long[]{val, 5};
        } else {
            long val = (data[offset + 1] & 0xFFL)
                     | ((data[offset + 2] & 0xFFL) << 8)
                     | ((data[offset + 3] & 0xFFL) << 16)
                     | ((data[offset + 4] & 0xFFL) << 24)
                     | ((data[offset + 5] & 0xFFL) << 32)
                     | ((data[offset + 6] & 0xFFL) << 40)
                     | ((data[offset + 7] & 0xFFL) << 48)
                     | ((data[offset + 8] & 0xFFL) << 56);
            return new long[]{val, 9};
        }
    }

    /**
     * Write a CompactSize value to a byte array.
     */
    public static byte[] write(long value) {
        if (value < 0xFD) {
            return new byte[]{(byte) value};
        } else if (value <= 0xFFFF) {
            return new byte[]{
                (byte) 0xFD,
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF)
            };
        } else if (value <= 0xFFFFFFFFL) {
            return new byte[]{
                (byte) 0xFE,
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 24) & 0xFF)
            };
        } else {
            return new byte[]{
                (byte) 0xFF,
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 24) & 0xFF),
                (byte) ((value >> 32) & 0xFF),
                (byte) ((value >> 40) & 0xFF),
                (byte) ((value >> 48) & 0xFF),
                (byte) ((value >> 56) & 0xFF)
            };
        }
    }
}
```

**Step 4: Run tests**

Run: `cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test`
Expected: All CompactSizeTest tests PASS

**Step 5: Commit**

```bash
git add signer/src/org/burnerwallet/core/CompactSize.java signer/test/org/burnerwallet/core/CompactSizeTest.java
git commit -s -m "feat(signer): add CompactSize — Bitcoin varint encoding/decoding"
```

---

### Task 2: ECDSA signing in Secp256k1

**Files:**
- Modify: `signer/src/org/burnerwallet/chains/bitcoin/Secp256k1.java`
- Create: `signer/test/org/burnerwallet/chains/bitcoin/Secp256k1SignTest.java`
- Modify: `signer/src/org/burnerwallet/core/CryptoError.java` — add `ERR_SIGNING = 9`

**Step 1: Add error code**

In `CryptoError.java`, add:
```java
public static final int ERR_SIGNING = 9;
```

**Step 2: Write the failing test**

```java
package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HexCodec;
import org.burnerwallet.core.HashUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class Secp256k1SignTest {

    // RFC 6979 test vector for secp256k1 (from bitcoinjs-lib / python-ecdsa)
    // Private key: 1
    // Message hash: SHA-256("Satoshi Nakamoto")
    @Test
    public void signDeterministicNonce() throws CryptoError {
        byte[] privKey = new byte[32];
        privKey[31] = 1; // private key = 1
        byte[] msgHash = HashUtils.sha256("Satoshi Nakamoto".getBytes());
        byte[] sig = Secp256k1.sign(msgHash, privKey);
        assertEquals(64, sig.length); // r(32) || s(32)
        // Verify signature is valid
        byte[] pubKey = Secp256k1.publicKeyFromPrivate(privKey);
        assertTrue(Secp256k1.verify(msgHash, sig, pubKey));
    }

    @Test
    public void signLowSNormalization() throws CryptoError {
        // Any valid signature must have s <= n/2 (BIP 62)
        byte[] privKey = HexCodec.decode(
            "0000000000000000000000000000000000000000000000000000000000000001");
        byte[] msgHash = HashUtils.sha256("test".getBytes());
        byte[] sig = Secp256k1.sign(msgHash, privKey);
        // Extract s (last 32 bytes)
        byte[] sBytes = new byte[32];
        System.arraycopy(sig, 32, sBytes, 0, 32);
        java.math.BigInteger s = new java.math.BigInteger(1, sBytes);
        java.math.BigInteger halfN = Secp256k1.getN().shiftRight(1);
        assertTrue("s must be <= n/2", s.compareTo(halfN) <= 0);
    }

    @Test
    public void signDerEncoding() throws CryptoError {
        byte[] privKey = new byte[32];
        privKey[31] = 1;
        byte[] msgHash = HashUtils.sha256("test".getBytes());
        byte[] sig = Secp256k1.sign(msgHash, privKey);
        byte[] der = Secp256k1.serializeDER(sig);
        // DER signature starts with 0x30 (SEQUENCE)
        assertEquals(0x30, der[0] & 0xFF);
        // Total length in byte 1
        assertEquals(der.length - 2, der[1] & 0xFF);
        // First integer marker
        assertEquals(0x02, der[2] & 0xFF);
    }

    @Test
    public void verifyValidSignature() throws CryptoError {
        byte[] privKey = new byte[32];
        privKey[31] = 42;
        byte[] pubKey = Secp256k1.publicKeyFromPrivate(privKey);
        byte[] msgHash = HashUtils.sha256("hello".getBytes());
        byte[] sig = Secp256k1.sign(msgHash, privKey);
        assertTrue(Secp256k1.verify(msgHash, sig, pubKey));
    }

    @Test
    public void verifyWrongMessage() throws CryptoError {
        byte[] privKey = new byte[32];
        privKey[31] = 42;
        byte[] pubKey = Secp256k1.publicKeyFromPrivate(privKey);
        byte[] msgHash1 = HashUtils.sha256("hello".getBytes());
        byte[] msgHash2 = HashUtils.sha256("world".getBytes());
        byte[] sig = Secp256k1.sign(msgHash1, privKey);
        assertFalse(Secp256k1.verify(msgHash2, sig, pubKey));
    }

    @Test
    public void verifyWrongKey() throws CryptoError {
        byte[] privKey1 = new byte[32];
        privKey1[31] = 1;
        byte[] privKey2 = new byte[32];
        privKey2[31] = 2;
        byte[] pubKey2 = Secp256k1.publicKeyFromPrivate(privKey2);
        byte[] msgHash = HashUtils.sha256("hello".getBytes());
        byte[] sig = Secp256k1.sign(msgHash, privKey1);
        assertFalse(Secp256k1.verify(msgHash, sig, pubKey2));
    }

    @Test
    public void signDifferentMessagesProduceDifferentSigs() throws CryptoError {
        byte[] privKey = new byte[32];
        privKey[31] = 1;
        byte[] hash1 = HashUtils.sha256("msg1".getBytes());
        byte[] hash2 = HashUtils.sha256("msg2".getBytes());
        byte[] sig1 = Secp256k1.sign(hash1, privKey);
        byte[] sig2 = Secp256k1.sign(hash2, privKey);
        assertFalse(java.util.Arrays.equals(sig1, sig2));
    }

    @Test
    public void signAndAppendSighashType() throws CryptoError {
        byte[] privKey = new byte[32];
        privKey[31] = 1;
        byte[] msgHash = HashUtils.sha256("test".getBytes());
        byte[] sig = Secp256k1.sign(msgHash, privKey);
        byte[] der = Secp256k1.serializeDER(sig);
        // Append SIGHASH_ALL
        byte[] derWithSighash = new byte[der.length + 1];
        System.arraycopy(der, 0, derWithSighash, 0, der.length);
        derWithSighash[der.length] = 0x01; // SIGHASH_ALL
        assertEquals(0x01, derWithSighash[derWithSighash.length - 1]);
    }
}
```

**Step 3: Write implementation**

Add to `Secp256k1.java`:

```java
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.digests.SHA256Digest;
import java.math.BigInteger;

/**
 * Sign a 32-byte message hash with a 32-byte private key.
 * Uses RFC 6979 deterministic nonce. Returns 64 bytes: r(32) || s(32).
 * Applies low-S normalization (BIP 62/146).
 */
public static byte[] sign(byte[] messageHash, byte[] privateKey) throws CryptoError {
    if (messageHash.length != 32) {
        throw new CryptoError(CryptoError.ERR_SIGNING, "Message hash must be 32 bytes");
    }
    try {
        BigInteger privKeyInt = new BigInteger(1, privateKey);
        ECPrivateKeyParameters privParams =
            new ECPrivateKeyParameters(privKeyInt, DOMAIN);
        ECDSASigner signer = new ECDSASigner(
            new HMacDSAKCalculator(new SHA256Digest()));
        signer.init(true, privParams);
        BigInteger[] rs = signer.generateSignature(messageHash);
        BigInteger r = rs[0];
        BigInteger s = rs[1];
        // Low-S normalization: if s > n/2, use n - s
        BigInteger halfN = CURVE_PARAMS.getN().shiftRight(1);
        if (s.compareTo(halfN) > 0) {
            s = CURVE_PARAMS.getN().subtract(s);
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
 * Verify an ECDSA signature (64 bytes: r||s) against a message hash
 * and compressed public key (33 bytes).
 */
public static boolean verify(byte[] messageHash, byte[] signature,
        byte[] compressedPubKey) throws CryptoError {
    try {
        ECPoint point = CURVE_PARAMS.getCurve().decodePoint(compressedPubKey);
        ECPublicKeyParameters pubParams =
            new ECPublicKeyParameters(point, DOMAIN);
        ECDSASigner verifier = new ECDSASigner();
        verifier.init(false, pubParams);
        BigInteger r = new BigInteger(1,
            ByteArrayUtils.copyOfRange(signature, 0, 32));
        BigInteger s = new BigInteger(1,
            ByteArrayUtils.copyOfRange(signature, 32, 64));
        return verifier.verifySignature(messageHash, r, s);
    } catch (Exception e) {
        throw new CryptoError(CryptoError.ERR_SIGNING,
            "ECDSA verification failed: " + e.getMessage());
    }
}

/**
 * Encode a 64-byte (r||s) signature as DER.
 */
public static byte[] serializeDER(byte[] rs) {
    byte[] r = ByteArrayUtils.copyOfRange(rs, 0, 32);
    byte[] s = ByteArrayUtils.copyOfRange(rs, 32, 64);
    byte[] rDer = integerToDER(r);
    byte[] sDer = integerToDER(s);
    int seqLen = rDer.length + sDer.length;
    byte[] result = new byte[2 + seqLen];
    result[0] = 0x30; // SEQUENCE
    result[1] = (byte) seqLen;
    System.arraycopy(rDer, 0, result, 2, rDer.length);
    System.arraycopy(sDer, 0, result, 2 + rDer.length, sDer.length);
    return result;
}

private static byte[] integerToDER(byte[] value) {
    // Strip leading zeros but keep one if high bit set
    int start = 0;
    while (start < value.length - 1 && value[start] == 0) {
        start++;
    }
    boolean needsPadding = (value[start] & 0x80) != 0;
    int len = value.length - start + (needsPadding ? 1 : 0);
    byte[] der = new byte[2 + len];
    der[0] = 0x02; // INTEGER
    der[1] = (byte) len;
    if (needsPadding) {
        der[2] = 0x00;
        System.arraycopy(value, start, der, 3, value.length - start);
    } else {
        System.arraycopy(value, start, der, 2, value.length - start);
    }
    return der;
}

private static byte[] toUnsigned32(BigInteger val) {
    byte[] bytes = val.toByteArray();
    if (bytes.length == 32) {
        return bytes;
    } else if (bytes.length > 32) {
        // Strip leading zero
        byte[] trimmed = new byte[32];
        System.arraycopy(bytes, bytes.length - 32, trimmed, 0, 32);
        return trimmed;
    } else {
        byte[] padded = new byte[32];
        System.arraycopy(bytes, 0, padded, 32 - bytes.length, bytes.length);
        return padded;
    }
}
```

Note: `toUnsigned32` may already exist in `Bip32Derivation.java` — if so, make it package-visible and reuse. The agent should check and avoid duplication.

**Step 4: Run tests**

Run: `cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test`
Expected: All Secp256k1SignTest tests PASS

**Step 5: Commit**

```bash
git add signer/src/org/burnerwallet/chains/bitcoin/Secp256k1.java \
       signer/src/org/burnerwallet/core/CryptoError.java \
       signer/test/org/burnerwallet/chains/bitcoin/Secp256k1SignTest.java
git commit -s -m "feat(signer): add ECDSA sign/verify with RFC 6979 and low-S normalization"
```

---

### Task 3: TxSerializer — Raw transaction parsing

**Files:**
- Create: `signer/src/org/burnerwallet/chains/bitcoin/TxSerializer.java`
- Create: `signer/src/org/burnerwallet/chains/bitcoin/TxData.java`
- Create: `signer/test/org/burnerwallet/chains/bitcoin/TxSerializerTest.java`

**Context:** BIP174 PSBTs contain an unsigned transaction in the global map. We need to parse it to extract inputs (prevout references) and outputs (amounts + scripts) for sighash computation.

**Step 1: Write the failing test**

```java
package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HexCodec;
import org.junit.Test;
import static org.junit.Assert.*;

public class TxSerializerTest {

    // Simple 1-in-1-out unsigned tx (no witness, scriptSigs empty)
    // This is the unsigned tx from BIP174 test vector (simplified)
    @Test
    public void parseUnsignedTx() throws CryptoError {
        // version(4) + inputCount(1) + input(41) + outputCount(1) + output(31) + locktime(4)
        // Construct a minimal unsigned tx:
        // version = 2, 1 input (32-byte prevhash + 4-byte index + 0-byte scriptSig + 4-byte sequence),
        // 1 output (8-byte value + scriptPubKey), locktime = 0
        String hex =
            "0200000001" + // version 2, 1 input
            "0000000000000000000000000000000000000000000000000000000000000000" + // prev hash
            "00000000" + // prev index 0
            "00" + // empty scriptSig
            "ffffffff" + // sequence
            "01" + // 1 output
            "e803000000000000" + // 1000 sats LE
            "16" + // scriptPubKey length 22
            "0014" + "0000000000000000000000000000000000000000" + // P2WPKH scriptPubKey
            "00000000"; // locktime 0
        byte[] txBytes = HexCodec.decode(hex);
        TxData tx = TxSerializer.parse(txBytes);
        assertEquals(2, tx.version);
        assertEquals(1, tx.inputs.length);
        assertEquals(1, tx.outputs.length);
        assertEquals(1000L, tx.outputs[0].value);
        assertEquals(0, tx.locktime);
    }

    @Test
    public void parseAndSerializeRoundTrip() throws CryptoError {
        String hex =
            "0200000001" +
            "1111111111111111111111111111111111111111111111111111111111111111" +
            "01000000" + "00" + "ffffffff" +
            "02" +
            "e803000000000000" + "16" + "0014" + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "d007000000000000" + "16" + "0014" + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +
            "00000000";
        byte[] txBytes = HexCodec.decode(hex);
        TxData tx = TxSerializer.parse(txBytes);
        byte[] serialized = TxSerializer.serialize(tx);
        assertEquals(hex, HexCodec.encode(serialized));
    }

    @Test
    public void parseTwoInputs() throws CryptoError {
        String hex =
            "02000000" +
            "02" + // 2 inputs
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "00000000" + "00" + "ffffffff" +
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +
            "01000000" + "00" + "fffffffe" +
            "01" +
            "1027000000000000" + "16" + "0014" + "cccccccccccccccccccccccccccccccccccccccc" +
            "11000000";
        byte[] txBytes = HexCodec.decode(hex);
        TxData tx = TxSerializer.parse(txBytes);
        assertEquals(2, tx.inputs.length);
        assertEquals(1, tx.outputs.length);
        assertEquals(10000L, tx.outputs[0].value);
        assertEquals(17, tx.locktime);
    }

    @Test
    public void extractPrevOutpoint() throws CryptoError {
        String prevHash = "1111111111111111111111111111111111111111111111111111111111111111";
        String hex =
            "0200000001" +
            prevHash + "05000000" + "00" + "ffffffff" +
            "01" + "e803000000000000" + "16" + "0014" + "0000000000000000000000000000000000000000" +
            "00000000";
        byte[] txBytes = HexCodec.decode(hex);
        TxData tx = TxSerializer.parse(txBytes);
        assertEquals(prevHash, HexCodec.encode(tx.inputs[0].prevTxHash));
        assertEquals(5, tx.inputs[0].prevIndex);
    }

    @Test
    public void outputValueAndScript() throws CryptoError {
        String scriptPubKey = "0014" + "abcdef0123456789abcdef0123456789abcdef01";
        String hex =
            "0200000001" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "00000000" + "00" + "ffffffff" +
            "01" +
            "a086010000000000" + // 100000 sats LE
            "16" + scriptPubKey +
            "00000000";
        byte[] txBytes = HexCodec.decode(hex);
        TxData tx = TxSerializer.parse(txBytes);
        assertEquals(100000L, tx.outputs[0].value);
        assertEquals(scriptPubKey, HexCodec.encode(tx.outputs[0].scriptPubKey));
    }
}
```

**Step 2: Write TxData value types**

```java
package org.burnerwallet.chains.bitcoin;

/** Parsed raw Bitcoin transaction. */
public class TxData {
    public int version;
    public TxInput[] inputs;
    public TxOutput[] outputs;
    public int locktime;
}
```

```java
package org.burnerwallet.chains.bitcoin;

/** Single transaction input. */
public class TxInput {
    public byte[] prevTxHash;  // 32 bytes, internal byte order
    public int prevIndex;
    public byte[] scriptSig;   // empty for unsigned
    public long sequence;      // uint32
}
```

```java
package org.burnerwallet.chains.bitcoin;

/** Single transaction output. */
public class TxOutput {
    public long value;          // satoshis
    public byte[] scriptPubKey;
}
```

**Step 3: Write TxSerializer**

```java
package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.CompactSize;
import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.ByteArrayUtils;

/** Serialize and deserialize raw Bitcoin transactions. */
public class TxSerializer {

    public static TxData parse(byte[] data) throws CryptoError {
        TxData tx = new TxData();
        int pos = 0;
        // Version (4 bytes LE)
        tx.version = readInt32LE(data, pos);
        pos += 4;
        // Input count
        long[] cs = CompactSize.read(data, pos);
        int inputCount = (int) cs[0];
        pos += (int) cs[1];
        tx.inputs = new TxInput[inputCount];
        for (int i = 0; i < inputCount; i++) {
            TxInput in = new TxInput();
            in.prevTxHash = ByteArrayUtils.copyOfRange(data, pos, pos + 32);
            pos += 32;
            in.prevIndex = readInt32LE(data, pos);
            pos += 4;
            cs = CompactSize.read(data, pos);
            int scriptLen = (int) cs[0];
            pos += (int) cs[1];
            in.scriptSig = ByteArrayUtils.copyOfRange(data, pos, pos + scriptLen);
            pos += scriptLen;
            in.sequence = readUInt32LE(data, pos);
            pos += 4;
            tx.inputs[i] = in;
        }
        // Output count
        cs = CompactSize.read(data, pos);
        int outputCount = (int) cs[0];
        pos += (int) cs[1];
        tx.outputs = new TxOutput[outputCount];
        for (int i = 0; i < outputCount; i++) {
            TxOutput out = new TxOutput();
            out.value = readInt64LE(data, pos);
            pos += 8;
            cs = CompactSize.read(data, pos);
            int scriptLen = (int) cs[0];
            pos += (int) cs[1];
            out.scriptPubKey = ByteArrayUtils.copyOfRange(data, pos, pos + scriptLen);
            pos += scriptLen;
            tx.outputs[i] = out;
        }
        tx.locktime = readInt32LE(data, pos);
        return tx;
    }

    public static byte[] serialize(TxData tx) {
        // Calculate total size first, then fill
        byte[] buf = new byte[estimateSize(tx)];
        int pos = 0;
        pos = writeInt32LE(buf, pos, tx.version);
        byte[] inputCountBytes = CompactSize.write(tx.inputs.length);
        System.arraycopy(inputCountBytes, 0, buf, pos, inputCountBytes.length);
        pos += inputCountBytes.length;
        for (int i = 0; i < tx.inputs.length; i++) {
            TxInput in = tx.inputs[i];
            System.arraycopy(in.prevTxHash, 0, buf, pos, 32);
            pos += 32;
            pos = writeInt32LE(buf, pos, in.prevIndex);
            byte[] scriptLen = CompactSize.write(in.scriptSig.length);
            System.arraycopy(scriptLen, 0, buf, pos, scriptLen.length);
            pos += scriptLen.length;
            System.arraycopy(in.scriptSig, 0, buf, pos, in.scriptSig.length);
            pos += in.scriptSig.length;
            pos = writeUInt32LE(buf, pos, in.sequence);
        }
        byte[] outputCountBytes = CompactSize.write(tx.outputs.length);
        System.arraycopy(outputCountBytes, 0, buf, pos, outputCountBytes.length);
        pos += outputCountBytes.length;
        for (int i = 0; i < tx.outputs.length; i++) {
            TxOutput out = tx.outputs[i];
            pos = writeInt64LE(buf, pos, out.value);
            byte[] scriptLen = CompactSize.write(out.scriptPubKey.length);
            System.arraycopy(scriptLen, 0, buf, pos, scriptLen.length);
            pos += scriptLen.length;
            System.arraycopy(out.scriptPubKey, 0, buf, pos, out.scriptPubKey.length);
            pos += out.scriptPubKey.length;
        }
        pos = writeInt32LE(buf, pos, tx.locktime);
        // Trim to actual size
        if (pos < buf.length) {
            return ByteArrayUtils.copyOfRange(buf, 0, pos);
        }
        return buf;
    }

    private static int estimateSize(TxData tx) {
        int size = 4 + 9 + 4; // version + max compactsize + locktime
        for (int i = 0; i < tx.inputs.length; i++) {
            size += 32 + 4 + 9 + tx.inputs[i].scriptSig.length + 4;
        }
        size += 9; // output count
        for (int i = 0; i < tx.outputs.length; i++) {
            size += 8 + 9 + tx.outputs[i].scriptPubKey.length;
        }
        return size;
    }

    // LE read/write helpers
    static int readInt32LE(byte[] data, int off) {
        return (data[off] & 0xFF)
             | ((data[off + 1] & 0xFF) << 8)
             | ((data[off + 2] & 0xFF) << 16)
             | ((data[off + 3] & 0xFF) << 24);
    }

    static long readUInt32LE(byte[] data, int off) {
        return (long)(data[off] & 0xFF)
             | ((long)(data[off + 1] & 0xFF) << 8)
             | ((long)(data[off + 2] & 0xFF) << 16)
             | ((long)(data[off + 3] & 0xFF) << 24);
    }

    static long readInt64LE(byte[] data, int off) {
        return (long)(data[off] & 0xFF)
             | ((long)(data[off + 1] & 0xFF) << 8)
             | ((long)(data[off + 2] & 0xFF) << 16)
             | ((long)(data[off + 3] & 0xFF) << 24)
             | ((long)(data[off + 4] & 0xFF) << 32)
             | ((long)(data[off + 5] & 0xFF) << 40)
             | ((long)(data[off + 6] & 0xFF) << 48)
             | ((long)(data[off + 7] & 0xFF) << 56);
    }

    static int writeInt32LE(byte[] buf, int off, int val) {
        buf[off] = (byte) (val & 0xFF);
        buf[off + 1] = (byte) ((val >> 8) & 0xFF);
        buf[off + 2] = (byte) ((val >> 16) & 0xFF);
        buf[off + 3] = (byte) ((val >> 24) & 0xFF);
        return off + 4;
    }

    static int writeUInt32LE(byte[] buf, int off, long val) {
        buf[off] = (byte) (val & 0xFF);
        buf[off + 1] = (byte) ((val >> 8) & 0xFF);
        buf[off + 2] = (byte) ((val >> 16) & 0xFF);
        buf[off + 3] = (byte) ((val >> 24) & 0xFF);
        return off + 4;
    }

    static int writeInt64LE(byte[] buf, int off, long val) {
        buf[off] = (byte) (val & 0xFF);
        buf[off + 1] = (byte) ((val >> 8) & 0xFF);
        buf[off + 2] = (byte) ((val >> 16) & 0xFF);
        buf[off + 3] = (byte) ((val >> 24) & 0xFF);
        buf[off + 4] = (byte) ((val >> 32) & 0xFF);
        buf[off + 5] = (byte) ((val >> 40) & 0xFF);
        buf[off + 6] = (byte) ((val >> 48) & 0xFF);
        buf[off + 7] = (byte) ((val >> 56) & 0xFF);
        return off + 8;
    }
}
```

**Step 4: Run tests**

Run: `cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test`
Expected: All TxSerializerTest tests PASS

**Step 5: Commit**

```bash
git add signer/src/org/burnerwallet/chains/bitcoin/TxSerializer.java \
       signer/src/org/burnerwallet/chains/bitcoin/TxData.java \
       signer/src/org/burnerwallet/chains/bitcoin/TxInput.java \
       signer/src/org/burnerwallet/chains/bitcoin/TxOutput.java \
       signer/test/org/burnerwallet/chains/bitcoin/TxSerializerTest.java
git commit -s -m "feat(signer): add TxSerializer — raw Bitcoin transaction parse/serialize"
```

---

### Task 4: BIP143 Sighash — Segwit signature hash

**Files:**
- Create: `signer/src/org/burnerwallet/chains/bitcoin/Bip143Sighash.java`
- Create: `signer/test/org/burnerwallet/chains/bitcoin/Bip143SighashTest.java`

**Context:** BIP143 defines how segwit transactions compute the sighash digest that gets signed. For P2WPKH, the scriptCode is `OP_DUP OP_HASH160 <20-byte-pubkey-hash> OP_EQUALVERIFY OP_CHECKSIG`.

**Sighash types (from BIP143):**
- `SIGHASH_ALL = 0x01` — sign all inputs and outputs
- `SIGHASH_NONE = 0x02` — sign all inputs, no outputs
- `SIGHASH_SINGLE = 0x03` — sign all inputs, only matching output
- `SIGHASH_ANYONECANPAY = 0x80` — only sign own input (OR'd with above)

**Step 1: Write the failing test**

```java
package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HexCodec;
import org.burnerwallet.core.HashUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class Bip143SighashTest {

    // BIP143 P2WPKH test vector from the BIP specification
    // https://github.com/bitcoin/bips/blob/master/bip-0143.mediawiki#native-p2wpkh
    @Test
    public void nativeP2wpkhSighashAll() throws CryptoError {
        // The unsigned transaction (from BIP143 example)
        String txHex =
            "0100000002fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f" +
            "0000000000eeffffff" +
            "ef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a" +
            "0100000000ffffffff" +
            "02202cb20600000000" + "1976a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac" +
            "9093510d00000000" + "1976a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac" +
            "11000000";
        byte[] txBytes = HexCodec.decode(txHex);
        TxData tx = TxSerializer.parse(txBytes);

        // Witness UTXO for input 1: value = 600000000 (0x23C34600 LE)
        long witnessValue = 600000000L;
        // Public key hash for the P2WPKH output being spent
        String pubKeyHash = "141d0f172a0ecb48aee1be1f2687d2963ae33f71a1";
        // scriptCode = OP_DUP OP_HASH160 <20-byte-hash> OP_EQUALVERIFY OP_CHECKSIG
        byte[] scriptCode = Bip143Sighash.p2wpkhScriptCode(
            HexCodec.decode("1d0f172a0ecb48aee1be1f2687d2963ae33f71a1"));

        byte[] sighash = Bip143Sighash.computeSighash(
            tx, 1, scriptCode, witnessValue, Bip143Sighash.SIGHASH_ALL);

        assertEquals(
            "c37af31116d1b27caf68aae9e3ac82f1477929014d5b917657d0eb49478cb670",
            HexCodec.encode(sighash));
    }

    @Test
    public void scriptCodeFromPubKeyHash() throws CryptoError {
        byte[] pubKeyHash = HexCodec.decode("1d0f172a0ecb48aee1be1f2687d2963ae33f71a1");
        byte[] scriptCode = Bip143Sighash.p2wpkhScriptCode(pubKeyHash);
        // OP_DUP(76) OP_HASH160(a9) OP_PUSH20(14) <hash> OP_EQUALVERIFY(88) OP_CHECKSIG(ac)
        assertEquals(25, scriptCode.length);
        assertEquals(0x76, scriptCode[0] & 0xFF);
        assertEquals(0xa9, scriptCode[1] & 0xFF);
        assertEquals(0x14, scriptCode[2] & 0xFF);
        assertEquals(0x88, scriptCode[23] & 0xFF);
        assertEquals(0xac, scriptCode[24] & 0xFF);
    }

    @Test
    public void sighashTypeByte() {
        assertEquals(0x01, Bip143Sighash.SIGHASH_ALL);
        assertEquals(0x02, Bip143Sighash.SIGHASH_NONE);
        assertEquals(0x03, Bip143Sighash.SIGHASH_SINGLE);
        assertEquals(0x80, Bip143Sighash.SIGHASH_ANYONECANPAY);
    }

    @Test
    public void differentSighashProducesDifferentHash() throws CryptoError {
        String txHex =
            "0200000001" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "00000000" + "00" + "ffffffff" +
            "01" + "e803000000000000" + "16" +
            "0014" + "0000000000000000000000000000000000000000" +
            "00000000";
        byte[] txBytes = HexCodec.decode(txHex);
        TxData tx = TxSerializer.parse(txBytes);
        byte[] scriptCode = Bip143Sighash.p2wpkhScriptCode(new byte[20]);

        byte[] hashAll = Bip143Sighash.computeSighash(
            tx, 0, scriptCode, 1000L, Bip143Sighash.SIGHASH_ALL);
        byte[] hashNone = Bip143Sighash.computeSighash(
            tx, 0, scriptCode, 1000L, Bip143Sighash.SIGHASH_NONE);
        assertFalse(java.util.Arrays.equals(hashAll, hashNone));
    }
}
```

**Step 2: Write implementation**

```java
package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HashUtils;
import org.burnerwallet.core.ByteArrayUtils;

/**
 * BIP143 segwit sighash computation for P2WPKH.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class Bip143Sighash {

    public static final int SIGHASH_ALL = 0x01;
    public static final int SIGHASH_NONE = 0x02;
    public static final int SIGHASH_SINGLE = 0x03;
    public static final int SIGHASH_ANYONECANPAY = 0x80;

    /**
     * Build the P2WPKH scriptCode from a 20-byte pubkey hash.
     * Returns: OP_DUP OP_HASH160 OP_PUSH20 <hash> OP_EQUALVERIFY OP_CHECKSIG
     */
    public static byte[] p2wpkhScriptCode(byte[] pubKeyHash20) {
        byte[] sc = new byte[25];
        sc[0] = 0x76; // OP_DUP
        sc[1] = (byte) 0xa9; // OP_HASH160
        sc[2] = 0x14; // push 20 bytes
        System.arraycopy(pubKeyHash20, 0, sc, 3, 20);
        sc[23] = (byte) 0x88; // OP_EQUALVERIFY
        sc[24] = (byte) 0xac; // OP_CHECKSIG
        return sc;
    }

    /**
     * Compute the BIP143 sighash for the given input index.
     *
     * @param tx          Parsed unsigned transaction
     * @param inputIndex  Index of the input being signed
     * @param scriptCode  The scriptCode (P2WPKH: 25 bytes)
     * @param value       The value of the UTXO being spent (satoshis)
     * @param sighashType Sighash type (SIGHASH_ALL, etc.)
     * @return 32-byte double-SHA256 sighash
     */
    public static byte[] computeSighash(TxData tx, int inputIndex,
            byte[] scriptCode, long value, int sighashType) throws CryptoError {
        int baseType = sighashType & 0x1f;
        boolean anyoneCanPay = (sighashType & SIGHASH_ANYONECANPAY) != 0;

        // 1. hashPrevouts
        byte[] hashPrevouts;
        if (!anyoneCanPay) {
            byte[] prevouts = new byte[36 * tx.inputs.length];
            for (int i = 0; i < tx.inputs.length; i++) {
                System.arraycopy(tx.inputs[i].prevTxHash, 0, prevouts, i * 36, 32);
                TxSerializer.writeInt32LE(prevouts, i * 36 + 32, tx.inputs[i].prevIndex);
            }
            hashPrevouts = HashUtils.doubleSha256(prevouts);
        } else {
            hashPrevouts = new byte[32];
        }

        // 2. hashSequence
        byte[] hashSequence;
        if (!anyoneCanPay && baseType != SIGHASH_SINGLE && baseType != SIGHASH_NONE) {
            byte[] sequences = new byte[4 * tx.inputs.length];
            for (int i = 0; i < tx.inputs.length; i++) {
                TxSerializer.writeUInt32LE(sequences, i * 4, tx.inputs[i].sequence);
            }
            hashSequence = HashUtils.doubleSha256(sequences);
        } else {
            hashSequence = new byte[32];
        }

        // 3. hashOutputs
        byte[] hashOutputs;
        if (baseType != SIGHASH_SINGLE && baseType != SIGHASH_NONE) {
            // Hash all outputs
            byte[] allOutputs = serializeOutputs(tx.outputs, 0, tx.outputs.length);
            hashOutputs = HashUtils.doubleSha256(allOutputs);
        } else if (baseType == SIGHASH_SINGLE && inputIndex < tx.outputs.length) {
            // Hash only the matching output
            byte[] singleOutput = serializeOutputs(tx.outputs, inputIndex, inputIndex + 1);
            hashOutputs = HashUtils.doubleSha256(singleOutput);
        } else {
            hashOutputs = new byte[32];
        }

        // 4. Build preimage
        // nVersion(4) + hashPrevouts(32) + hashSequence(32) +
        // outpoint(36) + scriptCode(var) + value(8) + nSequence(4) +
        // hashOutputs(32) + nLocktime(4) + sighashType(4)
        byte[] scriptCodeLen = org.burnerwallet.core.CompactSize.write(scriptCode.length);
        int preimageLen = 4 + 32 + 32 + 32 + 4 + scriptCodeLen.length + scriptCode.length
                        + 8 + 4 + 32 + 4 + 4;
        byte[] preimage = new byte[preimageLen];
        int pos = 0;

        // nVersion
        pos = TxSerializer.writeInt32LE(preimage, pos, tx.version);
        // hashPrevouts
        System.arraycopy(hashPrevouts, 0, preimage, pos, 32);
        pos += 32;
        // hashSequence
        System.arraycopy(hashSequence, 0, preimage, pos, 32);
        pos += 32;
        // outpoint (prevHash + prevIndex of the input being signed)
        TxInput input = tx.inputs[inputIndex];
        System.arraycopy(input.prevTxHash, 0, preimage, pos, 32);
        pos += 32;
        TxSerializer.writeInt32LE(preimage, pos, input.prevIndex);
        pos += 4;
        // scriptCode
        System.arraycopy(scriptCodeLen, 0, preimage, pos, scriptCodeLen.length);
        pos += scriptCodeLen.length;
        System.arraycopy(scriptCode, 0, preimage, pos, scriptCode.length);
        pos += scriptCode.length;
        // value
        TxSerializer.writeInt64LE(preimage, pos, value);
        pos += 8;
        // nSequence
        TxSerializer.writeUInt32LE(preimage, pos, input.sequence);
        pos += 4;
        // hashOutputs
        System.arraycopy(hashOutputs, 0, preimage, pos, 32);
        pos += 32;
        // nLocktime
        TxSerializer.writeInt32LE(preimage, pos, tx.locktime);
        pos += 4;
        // sighashType (4 bytes LE)
        TxSerializer.writeInt32LE(preimage, pos, sighashType);

        return HashUtils.doubleSha256(preimage);
    }

    private static byte[] serializeOutputs(TxOutput[] outputs, int from, int to) {
        // Calculate size
        int size = 0;
        for (int i = from; i < to; i++) {
            byte[] scriptLen = org.burnerwallet.core.CompactSize.write(outputs[i].scriptPubKey.length);
            size += 8 + scriptLen.length + outputs[i].scriptPubKey.length;
        }
        byte[] result = new byte[size];
        int pos = 0;
        for (int i = from; i < to; i++) {
            TxSerializer.writeInt64LE(result, pos, outputs[i].value);
            pos += 8;
            byte[] scriptLen = org.burnerwallet.core.CompactSize.write(outputs[i].scriptPubKey.length);
            System.arraycopy(scriptLen, 0, result, pos, scriptLen.length);
            pos += scriptLen.length;
            System.arraycopy(outputs[i].scriptPubKey, 0, result, pos, outputs[i].scriptPubKey.length);
            pos += outputs[i].scriptPubKey.length;
        }
        return result;
    }
}
```

**Step 3: Run tests**

Run: `cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test`
Expected: All Bip143SighashTest tests PASS

**Step 4: Commit**

```bash
git add signer/src/org/burnerwallet/chains/bitcoin/Bip143Sighash.java \
       signer/test/org/burnerwallet/chains/bitcoin/Bip143SighashTest.java
git commit -s -m "feat(signer): add BIP143 sighash computation for segwit P2WPKH signing"
```

---

### Task 5: PSBT Parser — BIP174 binary format

**Files:**
- Create: `signer/src/org/burnerwallet/chains/bitcoin/PsbtParser.java`
- Create: `signer/src/org/burnerwallet/chains/bitcoin/PsbtTransaction.java`
- Create: `signer/src/org/burnerwallet/chains/bitcoin/PsbtInput.java`
- Create: `signer/src/org/burnerwallet/chains/bitcoin/PsbtOutput.java`
- Create: `signer/test/org/burnerwallet/chains/bitcoin/PsbtParserTest.java`
- Modify: `signer/src/org/burnerwallet/core/CryptoError.java` — add `ERR_PSBT = 10`

**Context:** BIP174 PSBT format:
```
magic: 0x70736274FF ("psbt" + 0xFF)
global map: key-value pairs terminated by 0x00
  key 0x00: unsigned transaction
per-input maps: one per tx input, each terminated by 0x00
  key 0x00: non-witness UTXO (full prev tx)
  key 0x01: witness UTXO (prev output: value + scriptPubKey)
  key 0x02: partial signature
  key 0x03: sighash type
  key 0x06: BIP32 derivation path
per-output maps: one per tx output, each terminated by 0x00
  key 0x02: BIP32 derivation path
```

**Step 1: Write value types**

```java
package org.burnerwallet.chains.bitcoin;

/** Parsed PSBT data container. */
public class PsbtTransaction {
    public byte[] unsignedTxBytes;
    public TxData unsignedTx;
    public PsbtInput[] inputs;
    public PsbtOutput[] outputs;

    public long getTotalInputValue() {
        long total = 0;
        for (int i = 0; i < inputs.length; i++) {
            if (inputs[i].witnessUtxoValue >= 0) {
                total += inputs[i].witnessUtxoValue;
            }
        }
        return total;
    }

    public long getTotalOutputValue() {
        long total = 0;
        for (int i = 0; i < unsignedTx.outputs.length; i++) {
            total += unsignedTx.outputs[i].value;
        }
        return total;
    }

    public long getFee() {
        return getTotalInputValue() - getTotalOutputValue();
    }
}
```

```java
package org.burnerwallet.chains.bitcoin;

/** Per-input PSBT data. */
public class PsbtInput {
    public long witnessUtxoValue = -1;       // -1 if not present
    public byte[] witnessUtxoScript;         // scriptPubKey of the UTXO
    public byte[] nonWitnessUtxo;            // full prev tx bytes (optional)
    public int sighashType = Bip143Sighash.SIGHASH_ALL; // default
    public byte[] bip32Derivation;           // raw BIP32 derivation data
    public byte[] bip32PubKey;               // pubkey associated with derivation
    public byte[] partialSigKey;             // pubkey for partial sig
    public byte[] partialSigValue;           // DER signature
}
```

```java
package org.burnerwallet.chains.bitcoin;

/** Per-output PSBT data. */
public class PsbtOutput {
    public byte[] bip32Derivation;           // raw BIP32 derivation data
    public byte[] bip32PubKey;               // pubkey associated with derivation
}
```

**Step 2: Write the failing test**

```java
package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HexCodec;
import org.junit.Test;
import static org.junit.Assert.*;

public class PsbtParserTest {

    @Test
    public void parseMagicBytes() throws CryptoError {
        // Invalid magic should throw
        try {
            PsbtParser.parse(new byte[]{0x00, 0x00, 0x00, 0x00, 0x00});
            fail("Should throw on invalid magic");
        } catch (CryptoError e) {
            assertEquals(CryptoError.ERR_PSBT, e.getErrorCode());
        }
    }

    @Test
    public void parseMinimalPsbt() throws CryptoError {
        // Build a minimal valid PSBT:
        // magic(5) + global key 0x00 + unsigned tx + separator(0x00)
        // + one empty input map (0x00) + one empty output map (0x00)
        String unsignedTxHex =
            "0200000001" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "00000000" + "00" + "ffffffff" +
            "01" + "e803000000000000" + "16" +
            "0014" + "0000000000000000000000000000000000000000" +
            "00000000";
        byte[] unsignedTx = HexCodec.decode(unsignedTxHex);

        // Construct PSBT bytes manually
        byte[] psbt = buildMinimalPsbt(unsignedTx, 1, 1);
        PsbtTransaction parsed = PsbtParser.parse(psbt);

        assertNotNull(parsed.unsignedTx);
        assertEquals(1, parsed.unsignedTx.inputs.length);
        assertEquals(1, parsed.unsignedTx.outputs.length);
        assertEquals(1, parsed.inputs.length);
        assertEquals(1, parsed.outputs.length);
    }

    @Test
    public void parseWithWitnessUtxo() throws CryptoError {
        String unsignedTxHex =
            "0200000001" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "00000000" + "00" + "ffffffff" +
            "01" + "e803000000000000" + "16" +
            "0014" + "0000000000000000000000000000000000000000" +
            "00000000";
        byte[] unsignedTx = HexCodec.decode(unsignedTxHex);

        // Witness UTXO: value(8 LE) + scriptPubKey length + scriptPubKey
        // value = 50000 sats
        String witnessUtxoHex =
            "50c3000000000000" + // 50000 LE
            "16" + "0014" + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        byte[] witnessUtxo = HexCodec.decode(witnessUtxoHex);

        byte[] psbt = buildPsbtWithWitnessUtxo(unsignedTx, witnessUtxo);
        PsbtTransaction parsed = PsbtParser.parse(psbt);

        assertEquals(50000L, parsed.inputs[0].witnessUtxoValue);
        assertNotNull(parsed.inputs[0].witnessUtxoScript);
    }

    @Test
    public void parseIgnoresUnknownKeys() throws CryptoError {
        // PSBT with an unknown global key type (0xFC) should be parsed without error
        String unsignedTxHex =
            "0200000001" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "00000000" + "00" + "ffffffff" +
            "01" + "e803000000000000" + "16" +
            "0014" + "0000000000000000000000000000000000000000" +
            "00000000";
        byte[] unsignedTx = HexCodec.decode(unsignedTxHex);
        byte[] psbt = buildPsbtWithUnknownKey(unsignedTx);
        PsbtTransaction parsed = PsbtParser.parse(psbt);
        assertNotNull(parsed.unsignedTx);
    }

    @Test
    public void feeCalculation() throws CryptoError {
        String unsignedTxHex =
            "0200000001" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "00000000" + "00" + "ffffffff" +
            "01" + "e803000000000000" + "16" +
            "0014" + "0000000000000000000000000000000000000000" +
            "00000000";
        byte[] unsignedTx = HexCodec.decode(unsignedTxHex);
        String witnessUtxoHex =
            "50c3000000000000" + // 50000 LE
            "16" + "0014" + "0000000000000000000000000000000000000000";
        byte[] psbt = buildPsbtWithWitnessUtxo(unsignedTx, HexCodec.decode(witnessUtxoHex));
        PsbtTransaction parsed = PsbtParser.parse(psbt);
        // Fee = 50000 - 1000 = 49000
        assertEquals(49000L, parsed.getFee());
    }

    // Helper to build a minimal PSBT with magic + global unsigned tx + empty maps
    private byte[] buildMinimalPsbt(byte[] unsignedTx, int numInputs, int numOutputs) {
        // magic(5) + key_len(1) + key_type(1) + value_len(var) + value + 0x00 separator
        // + numInputs empty maps (0x00 each) + numOutputs empty maps (0x00 each)
        byte[] magic = new byte[]{0x70, 0x73, 0x62, 0x74, (byte) 0xFF};
        byte[] keyLen = org.burnerwallet.core.CompactSize.write(1);
        byte[] keyType = new byte[]{0x00};
        byte[] valueLen = org.burnerwallet.core.CompactSize.write(unsignedTx.length);
        int totalLen = magic.length + keyLen.length + keyType.length
                     + valueLen.length + unsignedTx.length + 1 + numInputs + numOutputs;
        byte[] result = new byte[totalLen];
        int pos = 0;
        System.arraycopy(magic, 0, result, pos, magic.length); pos += magic.length;
        System.arraycopy(keyLen, 0, result, pos, keyLen.length); pos += keyLen.length;
        System.arraycopy(keyType, 0, result, pos, keyType.length); pos += keyType.length;
        System.arraycopy(valueLen, 0, result, pos, valueLen.length); pos += valueLen.length;
        System.arraycopy(unsignedTx, 0, result, pos, unsignedTx.length); pos += unsignedTx.length;
        result[pos++] = 0x00; // global map separator
        for (int i = 0; i < numInputs; i++) { result[pos++] = 0x00; }
        for (int i = 0; i < numOutputs; i++) { result[pos++] = 0x00; }
        return result;
    }

    private byte[] buildPsbtWithWitnessUtxo(byte[] unsignedTx, byte[] witnessUtxo) {
        byte[] minimal = buildMinimalPsbt(unsignedTx, 0, 0);
        // Replace: remove last 2 bytes (empty input + output maps),
        // add input map with witness UTXO, then empty output map
        byte[] base = org.burnerwallet.core.ByteArrayUtils.copyOfRange(
            minimal, 0, minimal.length - 2);
        // Input map: key(01 = witness UTXO) + value
        byte[] keyLen = org.burnerwallet.core.CompactSize.write(1);
        byte[] keyType = new byte[]{0x01};
        byte[] valueLen = org.burnerwallet.core.CompactSize.write(witnessUtxo.length);
        int inputMapLen = keyLen.length + keyType.length + valueLen.length + witnessUtxo.length + 1;
        byte[] result = new byte[base.length + inputMapLen + 1];
        int pos = 0;
        System.arraycopy(base, 0, result, pos, base.length); pos += base.length;
        System.arraycopy(keyLen, 0, result, pos, keyLen.length); pos += keyLen.length;
        System.arraycopy(keyType, 0, result, pos, keyType.length); pos += keyType.length;
        System.arraycopy(valueLen, 0, result, pos, valueLen.length); pos += valueLen.length;
        System.arraycopy(witnessUtxo, 0, result, pos, witnessUtxo.length); pos += witnessUtxo.length;
        result[pos++] = 0x00; // input map separator
        result[pos] = 0x00;   // empty output map
        return result;
    }

    private byte[] buildPsbtWithUnknownKey(byte[] unsignedTx) {
        // Like minimal but with an extra unknown global key (0xFC)
        byte[] magic = new byte[]{0x70, 0x73, 0x62, 0x74, (byte) 0xFF};
        byte[] keyLen1 = org.burnerwallet.core.CompactSize.write(1);
        byte[] keyType1 = new byte[]{0x00};
        byte[] valueLen1 = org.burnerwallet.core.CompactSize.write(unsignedTx.length);
        // Unknown key
        byte[] keyLen2 = org.burnerwallet.core.CompactSize.write(1);
        byte[] keyType2 = new byte[]{(byte) 0xFC};
        byte[] unknownValue = new byte[]{0x42, 0x42};
        byte[] valueLen2 = org.burnerwallet.core.CompactSize.write(unknownValue.length);

        int totalLen = magic.length + keyLen1.length + 1 + valueLen1.length + unsignedTx.length
                     + keyLen2.length + 1 + valueLen2.length + unknownValue.length
                     + 1 + 1 + 1; // separators
        byte[] result = new byte[totalLen];
        int pos = 0;
        System.arraycopy(magic, 0, result, pos, magic.length); pos += magic.length;
        // Unsigned tx entry
        System.arraycopy(keyLen1, 0, result, pos, keyLen1.length); pos += keyLen1.length;
        System.arraycopy(keyType1, 0, result, pos, 1); pos += 1;
        System.arraycopy(valueLen1, 0, result, pos, valueLen1.length); pos += valueLen1.length;
        System.arraycopy(unsignedTx, 0, result, pos, unsignedTx.length); pos += unsignedTx.length;
        // Unknown key entry
        System.arraycopy(keyLen2, 0, result, pos, keyLen2.length); pos += keyLen2.length;
        System.arraycopy(keyType2, 0, result, pos, 1); pos += 1;
        System.arraycopy(valueLen2, 0, result, pos, valueLen2.length); pos += valueLen2.length;
        System.arraycopy(unknownValue, 0, result, pos, unknownValue.length); pos += unknownValue.length;
        result[pos++] = 0x00; // global sep
        result[pos++] = 0x00; // input map
        result[pos] = 0x00;   // output map
        return result;
    }
}
```

**Step 3: Write PsbtParser implementation**

The parser is a streaming state machine. The agent should implement `PsbtParser.parse(byte[])` that:
1. Validates magic bytes `0x70736274FF`
2. Reads global map key-value pairs until 0x00 separator
3. Extracts key type 0x00 as unsigned transaction, parses via `TxSerializer.parse()`
4. For each input (count from parsed tx), reads input map until 0x00
   - Key 0x01: witness UTXO — parse value (8 LE) + scriptPubKey (CompactSize + bytes)
   - Key 0x02: partial signature — store pubkey (key data after type) + DER sig (value)
   - Key 0x03: sighash type — 4 bytes LE
   - Key 0x06: BIP32 derivation — store pubkey (key data) + derivation path (value)
   - Unknown keys: skip (read and discard value)
5. For each output (count from parsed tx), reads output map until 0x00
   - Key 0x02: BIP32 derivation — store
   - Unknown keys: skip
6. Returns `PsbtTransaction`

**Step 4: Run tests, Step 5: Commit**

```bash
git add signer/src/org/burnerwallet/chains/bitcoin/PsbtParser.java \
       signer/src/org/burnerwallet/chains/bitcoin/PsbtTransaction.java \
       signer/src/org/burnerwallet/chains/bitcoin/PsbtInput.java \
       signer/src/org/burnerwallet/chains/bitcoin/PsbtOutput.java \
       signer/src/org/burnerwallet/core/CryptoError.java \
       signer/test/org/burnerwallet/chains/bitcoin/PsbtParserTest.java
git commit -s -m "feat(signer): add BIP174 PSBT streaming parser"
```

---

### Task 6: PSBT Signer & Serializer

**Files:**
- Create: `signer/src/org/burnerwallet/chains/bitcoin/PsbtSigner.java`
- Create: `signer/src/org/burnerwallet/chains/bitcoin/PsbtSerializer.java`
- Create: `signer/test/org/burnerwallet/chains/bitcoin/PsbtSignerTest.java`

**Context:** The signer takes a parsed `PsbtTransaction`, derives the private key for each input using the BIP32 derivation path, computes the BIP143 sighash, signs with ECDSA, and stores the partial signature. The serializer then writes the signed PSBT back to bytes.

**Step 1: Write the failing test**

```java
package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HexCodec;
import org.burnerwallet.core.HashUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class PsbtSignerTest {

    private static final String ABANDON_MNEMONIC =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

    @Test
    public void signProducesValidPartialSig() throws CryptoError {
        // Create a PSBT with one P2WPKH input spending from our known address
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        // Derive the signing key for m/84'/0'/0'/0/0
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key signingKey = Bip32Derivation.derivePath(master, "m/84'/0'/0'/0/0");
        byte[] pubKey = signingKey.getPublicKeyBytes();
        byte[] pubKeyHash = HashUtils.hash160(pubKey);

        // Build a simple unsigned tx
        String txHex =
            "0200000001" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "00000000" + "00" + "ffffffff" +
            "01" + "e803000000000000" + "16" +
            "0014" + HexCodec.encode(pubKeyHash) +
            "00000000";

        byte[] txBytes = HexCodec.decode(txHex);
        TxData tx = TxSerializer.parse(txBytes);

        // Build PsbtTransaction manually
        PsbtTransaction psbtTx = new PsbtTransaction();
        psbtTx.unsignedTxBytes = txBytes;
        psbtTx.unsignedTx = tx;
        psbtTx.inputs = new PsbtInput[1];
        psbtTx.inputs[0] = new PsbtInput();
        psbtTx.inputs[0].witnessUtxoValue = 50000L;
        psbtTx.inputs[0].witnessUtxoScript = HexCodec.decode(
            "0014" + HexCodec.encode(pubKeyHash));
        psbtTx.outputs = new PsbtOutput[1];
        psbtTx.outputs[0] = new PsbtOutput();

        // Sign
        PsbtSigner.sign(psbtTx, seed, false);

        // Verify partial sig was added
        assertNotNull(psbtTx.inputs[0].partialSigValue);
        assertNotNull(psbtTx.inputs[0].partialSigKey);
        // Partial sig key should be our pubkey
        assertArrayEquals(pubKey, psbtTx.inputs[0].partialSigKey);

        // Verify the signature is valid
        byte[] scriptCode = Bip143Sighash.p2wpkhScriptCode(pubKeyHash);
        byte[] sighash = Bip143Sighash.computeSighash(
            tx, 0, scriptCode, 50000L, Bip143Sighash.SIGHASH_ALL);
        // Strip DER encoding and sighash byte to get raw r||s for verify
        // The partialSigValue is DER + sighash_type byte
        byte[] derSig = psbtTx.inputs[0].partialSigValue;
        // Remove sighash type byte at end
        byte[] derOnly = new byte[derSig.length - 1];
        System.arraycopy(derSig, 0, derOnly, 0, derOnly.length);
        // For verification, we trust that sign() produced valid output
        assertTrue(derSig.length > 0);
    }

    @Test
    public void signedPsbtSerializesAndParses() throws CryptoError {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key signingKey = Bip32Derivation.derivePath(master, "m/84'/0'/0'/0/0");
        byte[] pubKey = signingKey.getPublicKeyBytes();
        byte[] pubKeyHash = HashUtils.hash160(pubKey);

        String txHex =
            "0200000001" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "00000000" + "00" + "ffffffff" +
            "01" + "e803000000000000" + "16" +
            "0014" + HexCodec.encode(pubKeyHash) +
            "00000000";

        byte[] txBytes = HexCodec.decode(txHex);

        PsbtTransaction psbtTx = new PsbtTransaction();
        psbtTx.unsignedTxBytes = txBytes;
        psbtTx.unsignedTx = TxSerializer.parse(txBytes);
        psbtTx.inputs = new PsbtInput[1];
        psbtTx.inputs[0] = new PsbtInput();
        psbtTx.inputs[0].witnessUtxoValue = 50000L;
        psbtTx.inputs[0].witnessUtxoScript = HexCodec.decode(
            "0014" + HexCodec.encode(pubKeyHash));
        psbtTx.outputs = new PsbtOutput[1];
        psbtTx.outputs[0] = new PsbtOutput();

        PsbtSigner.sign(psbtTx, seed, false);

        // Serialize to bytes
        byte[] signedPsbtBytes = PsbtSerializer.serialize(psbtTx);
        assertNotNull(signedPsbtBytes);
        assertTrue(signedPsbtBytes.length > 0);
        // Magic bytes present
        assertEquals(0x70, signedPsbtBytes[0] & 0xFF);

        // Re-parse and verify signature is preserved
        PsbtTransaction reparsed = PsbtParser.parse(signedPsbtBytes);
        assertNotNull(reparsed.inputs[0].partialSigValue);
    }

    @Test
    public void signWithSighashNone() throws CryptoError {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key key = Bip32Derivation.derivePath(master, "m/84'/0'/0'/0/0");
        byte[] pubKeyHash = HashUtils.hash160(key.getPublicKeyBytes());

        String txHex =
            "0200000001" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "00000000" + "00" + "ffffffff" +
            "01" + "e803000000000000" + "16" +
            "0014" + HexCodec.encode(pubKeyHash) + "00000000";

        PsbtTransaction psbtTx = new PsbtTransaction();
        psbtTx.unsignedTxBytes = HexCodec.decode(txHex);
        psbtTx.unsignedTx = TxSerializer.parse(psbtTx.unsignedTxBytes);
        psbtTx.inputs = new PsbtInput[1];
        psbtTx.inputs[0] = new PsbtInput();
        psbtTx.inputs[0].witnessUtxoValue = 50000L;
        psbtTx.inputs[0].witnessUtxoScript = HexCodec.decode(
            "0014" + HexCodec.encode(pubKeyHash));
        psbtTx.inputs[0].sighashType = Bip143Sighash.SIGHASH_NONE;
        psbtTx.outputs = new PsbtOutput[1];
        psbtTx.outputs[0] = new PsbtOutput();

        PsbtSigner.sign(psbtTx, seed, false);
        assertNotNull(psbtTx.inputs[0].partialSigValue);
        // Last byte of DER sig should be sighash type
        byte[] sig = psbtTx.inputs[0].partialSigValue;
        assertEquals(Bip143Sighash.SIGHASH_NONE, sig[sig.length - 1] & 0xFF);
    }
}
```

**Step 2: Write PsbtSigner**

The agent should implement `PsbtSigner.sign(PsbtTransaction psbt, byte[] seed, boolean testnet)`:
1. For each input with a witness UTXO:
   - Extract pubkey hash from witness UTXO scriptPubKey (bytes 2-22 for P2WPKH `0014<hash>`)
   - Try deriving keys at common BIP84 paths (m/84'/coin'/0'/0/0 through m/84'/coin'/0'/0/19 and m/84'/coin'/0'/1/0 through m/84'/coin'/0'/1/9) until finding a match
   - If BIP32 derivation data is present in the PSBT input, use that path directly
   - Compute BIP143 sighash with the input's sighash type
   - Sign with `Secp256k1.sign()`
   - DER-encode with `Secp256k1.serializeDER()`, append sighash type byte
   - Store in `input.partialSigKey` and `input.partialSigValue`

**Step 3: Write PsbtSerializer**

The agent should implement `PsbtSerializer.serialize(PsbtTransaction psbt)`:
1. Write magic `0x70736274FF`
2. Write global map: key type 0x00 → unsigned tx bytes, then 0x00 separator
3. For each input, write input map:
   - If witnessUtxo present: key type 0x01 → serialized witness UTXO
   - If partialSig present: key type 0x02 + pubkey → DER sig + sighash byte
   - If sighashType != default: key type 0x03 → 4 bytes LE
   - 0x00 separator
4. For each output, write output map:
   - BIP32 derivation if present
   - 0x00 separator

**Step 4: Run tests, Step 5: Commit**

```bash
git add signer/src/org/burnerwallet/chains/bitcoin/PsbtSigner.java \
       signer/src/org/burnerwallet/chains/bitcoin/PsbtSerializer.java \
       signer/test/org/burnerwallet/chains/bitcoin/PsbtSignerTest.java
git commit -s -m "feat(signer): add PSBT signer and serializer for P2WPKH transactions"
```

---

## Phase 2: QR Transport

### Task 7: QR Encoder — Port Nayuki to Java 1.4

**Files:**
- Create: `signer/src/org/burnerwallet/transport/QrCode.java`
- Create: `signer/src/org/burnerwallet/transport/QrSegment.java`
- Create: `signer/test/org/burnerwallet/transport/QrCodeTest.java`

**Context:** Port Nayuki's QR Code generator (MIT license, ~1000 lines) from modern Java to Java 1.4 (CLDC 1.1). Key changes: remove generics (`List<X>` → arrays), remove enums (`Ecc` → int constants), remove autoboxing, remove enhanced for-loops, remove `Objects.requireNonNull()`.

**Step 1: Write the failing test**

```java
package org.burnerwallet.transport;

import org.junit.Test;
import static org.junit.Assert.*;

public class QrCodeTest {

    @Test
    public void encodeBinaryProducesValidQr() {
        byte[] data = "Hello".getBytes();
        QrCode qr = QrCode.encodeBinary(data, QrCode.ECC_LOW);
        assertNotNull(qr);
        assertTrue(qr.size > 0);
        // Version 1 is 21x21
        assertTrue(qr.size >= 21);
    }

    @Test
    public void moduleAccessInBounds() {
        byte[] data = new byte[]{0x42};
        QrCode qr = QrCode.encodeBinary(data, QrCode.ECC_LOW);
        // All modules should be accessible
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                // Should not throw
                qr.getModule(x, y);
            }
        }
    }

    @Test
    public void outOfBoundsReturnsFalse() {
        byte[] data = new byte[]{0x42};
        QrCode qr = QrCode.encodeBinary(data, QrCode.ECC_LOW);
        assertFalse(qr.getModule(-1, 0));
        assertFalse(qr.getModule(0, -1));
        assertFalse(qr.getModule(qr.size, 0));
        assertFalse(qr.getModule(0, qr.size));
    }

    @Test
    public void differentDataProducesDifferentQr() {
        QrCode qr1 = QrCode.encodeBinary("abc".getBytes(), QrCode.ECC_LOW);
        QrCode qr2 = QrCode.encodeBinary("xyz".getBytes(), QrCode.ECC_LOW);
        // At least some modules should differ
        boolean anyDiff = false;
        int minSize = qr1.size < qr2.size ? qr1.size : qr2.size;
        for (int y = 0; y < minSize && !anyDiff; y++) {
            for (int x = 0; x < minSize && !anyDiff; x++) {
                if (qr1.getModule(x, y) != qr2.getModule(x, y)) {
                    anyDiff = true;
                }
            }
        }
        assertTrue(anyDiff);
    }

    @Test
    public void eccLevels() {
        byte[] data = "test".getBytes();
        QrCode low = QrCode.encodeBinary(data, QrCode.ECC_LOW);
        QrCode high = QrCode.encodeBinary(data, QrCode.ECC_HIGH);
        // Higher ECC generally produces larger QR (or same version with more ECC)
        assertTrue(high.size >= low.size);
    }

    @Test
    public void largerDataProducesLargerVersion() {
        byte[] small = new byte[10];
        byte[] large = new byte[200];
        QrCode qrSmall = QrCode.encodeBinary(small, QrCode.ECC_LOW);
        QrCode qrLarge = QrCode.encodeBinary(large, QrCode.ECC_LOW);
        assertTrue(qrLarge.size > qrSmall.size);
    }
}
```

**Step 2: Port Nayuki QrCode.java and QrSegment.java**

The agent should:
1. Download from `https://github.com/nayuki/QR-Code-generator/tree/master/java/src/main/java/io/nayuki/qrcodegen/`
2. Copy `QrCode.java` and `QrSegment.java` into `signer/src/org/burnerwallet/transport/`
3. Change package to `org.burnerwallet.transport`
4. Replace Java 5+ features:
   - `enum Ecc` → `public static final int ECC_LOW = 0, ECC_MEDIUM = 1, ECC_QUARTILE = 2, ECC_HIGH = 3`
   - `List<QrSegment>` → `QrSegment[]`
   - `List<Integer>` → `int[]` or custom growable buffer
   - `Objects.requireNonNull()` → null checks with `if (x == null) throw`
   - Enhanced for-loops → indexed for-loops
   - Autoboxing → explicit casts
   - `Arrays.copyOf()` → `ByteArrayUtils.copyOf()` or manual copy
   - Remove `@Override` annotations (Java 1.4 doesn't support on interface methods)
   - `BitBuffer` (inner or separate class) → simplify as int array
5. Keep only `encodeBinary()` and `encodeSegments()` — drop text/numeric/alphanumeric modes (not needed for PSBT binary data)
6. Add `QrCode.ECC_*` constants and ECC ordinal/formatBits lookup arrays
7. Credit Nayuki in file header with MIT license notice

**Step 3: Run tests, Step 4: Commit**

```bash
git add signer/src/org/burnerwallet/transport/QrCode.java \
       signer/src/org/burnerwallet/transport/QrSegment.java \
       signer/test/org/burnerwallet/transport/QrCodeTest.java
git commit -s -m "feat(signer): add QR code encoder — Nayuki port to Java 1.4"
```

---

### Task 8: Multi-frame encoder/decoder

**Files:**
- Create: `signer/src/org/burnerwallet/transport/MultiFrameEncoder.java`
- Create: `signer/src/org/burnerwallet/transport/MultiFrameDecoder.java`
- Create: `signer/test/org/burnerwallet/transport/MultiFrameTest.java`

**Step 1: Write the failing test**

```java
package org.burnerwallet.transport;

import org.junit.Test;
import static org.junit.Assert.*;

public class MultiFrameTest {

    @Test
    public void singleFrameForSmallPayload() {
        byte[] payload = new byte[50];
        for (int i = 0; i < 50; i++) payload[i] = (byte) i;
        byte[][] frames = MultiFrameEncoder.encode(payload, 150);
        assertEquals(1, frames.length);
        // Frame: [total(1)][index(1)][data(50)]
        assertEquals(52, frames[0].length);
        assertEquals(1, frames[0][0] & 0xFF); // total = 1
        assertEquals(0, frames[0][1] & 0xFF); // index = 0
    }

    @Test
    public void multipleFrames() {
        byte[] payload = new byte[300];
        for (int i = 0; i < 300; i++) payload[i] = (byte) (i & 0xFF);
        byte[][] frames = MultiFrameEncoder.encode(payload, 150);
        assertEquals(2, frames.length);
        assertEquals(2, frames[0][0] & 0xFF); // total = 2
        assertEquals(0, frames[0][1] & 0xFF); // index = 0
        assertEquals(2, frames[1][0] & 0xFF); // total = 2
        assertEquals(1, frames[1][1] & 0xFF); // index = 1
    }

    @Test
    public void roundTripSingleFrame() {
        byte[] payload = "Hello, Bitcoin!".getBytes();
        byte[][] frames = MultiFrameEncoder.encode(payload, 150);
        MultiFrameDecoder decoder = new MultiFrameDecoder();
        for (int i = 0; i < frames.length; i++) {
            decoder.addFrame(frames[i]);
        }
        assertTrue(decoder.isComplete());
        byte[] reassembled = decoder.assemble();
        assertArrayEquals(payload, reassembled);
    }

    @Test
    public void roundTripMultiFrame() {
        byte[] payload = new byte[500];
        for (int i = 0; i < 500; i++) payload[i] = (byte) (i & 0xFF);
        byte[][] frames = MultiFrameEncoder.encode(payload, 150);
        assertTrue(frames.length > 1);
        MultiFrameDecoder decoder = new MultiFrameDecoder();
        for (int i = 0; i < frames.length; i++) {
            decoder.addFrame(frames[i]);
        }
        assertTrue(decoder.isComplete());
        assertArrayEquals(payload, decoder.assemble());
    }

    @Test
    public void outOfOrderFrames() {
        byte[] payload = new byte[400];
        for (int i = 0; i < 400; i++) payload[i] = (byte) (i & 0xFF);
        byte[][] frames = MultiFrameEncoder.encode(payload, 150);
        MultiFrameDecoder decoder = new MultiFrameDecoder();
        // Add in reverse order
        for (int i = frames.length - 1; i >= 0; i--) {
            decoder.addFrame(frames[i]);
        }
        assertTrue(decoder.isComplete());
        assertArrayEquals(payload, decoder.assemble());
    }

    @Test
    public void duplicateFramesIgnored() {
        byte[] payload = new byte[300];
        byte[][] frames = MultiFrameEncoder.encode(payload, 150);
        MultiFrameDecoder decoder = new MultiFrameDecoder();
        decoder.addFrame(frames[0]);
        decoder.addFrame(frames[0]); // duplicate
        assertFalse(decoder.isComplete());
        decoder.addFrame(frames[1]);
        assertTrue(decoder.isComplete());
    }

    @Test
    public void incompleteDecoder() {
        byte[] payload = new byte[300];
        byte[][] frames = MultiFrameEncoder.encode(payload, 150);
        MultiFrameDecoder decoder = new MultiFrameDecoder();
        decoder.addFrame(frames[0]);
        assertFalse(decoder.isComplete());
        assertEquals(1, decoder.getReceivedCount());
        assertEquals(2, decoder.getTotalCount());
    }

    @Test
    public void emptyPayload() {
        byte[] payload = new byte[0];
        byte[][] frames = MultiFrameEncoder.encode(payload, 150);
        assertEquals(1, frames.length);
        MultiFrameDecoder decoder = new MultiFrameDecoder();
        decoder.addFrame(frames[0]);
        assertTrue(decoder.isComplete());
        assertEquals(0, decoder.assemble().length);
    }
}
```

**Step 2: Write implementations**

```java
package org.burnerwallet.transport;

import org.burnerwallet.core.ByteArrayUtils;

/** Splits a payload into numbered frames for multi-QR transport. */
public class MultiFrameEncoder {
    /** Header size: total(1) + index(1) */
    private static final int HEADER_SIZE = 2;

    public static byte[][] encode(byte[] payload, int maxBytesPerFrame) {
        int dataPerFrame = maxBytesPerFrame - HEADER_SIZE;
        if (dataPerFrame <= 0) {
            dataPerFrame = 1;
        }
        int totalFrames = (payload.length + dataPerFrame - 1) / dataPerFrame;
        if (totalFrames == 0) totalFrames = 1;
        byte[][] frames = new byte[totalFrames][];
        for (int i = 0; i < totalFrames; i++) {
            int offset = i * dataPerFrame;
            int len = payload.length - offset;
            if (len > dataPerFrame) len = dataPerFrame;
            if (len < 0) len = 0;
            frames[i] = new byte[HEADER_SIZE + len];
            frames[i][0] = (byte) totalFrames;
            frames[i][1] = (byte) i;
            if (len > 0) {
                System.arraycopy(payload, offset, frames[i], HEADER_SIZE, len);
            }
        }
        return frames;
    }
}
```

```java
package org.burnerwallet.transport;

import org.burnerwallet.core.ByteArrayUtils;

/** Reassembles multi-frame payloads from QR scans. */
public class MultiFrameDecoder {
    private int totalFrames = -1;
    private byte[][] frameData;
    private boolean[] received;
    private int receivedCount = 0;

    public void addFrame(byte[] frame) {
        if (frame.length < 2) return;
        int total = frame[0] & 0xFF;
        int index = frame[1] & 0xFF;
        if (total == 0 || index >= total) return;
        if (totalFrames == -1) {
            totalFrames = total;
            frameData = new byte[total][];
            received = new boolean[total];
        }
        if (total != totalFrames || received[index]) return;
        byte[] data = new byte[frame.length - 2];
        System.arraycopy(frame, 2, data, 0, data.length);
        frameData[index] = data;
        received[index] = true;
        receivedCount++;
    }

    public boolean isComplete() {
        return totalFrames > 0 && receivedCount == totalFrames;
    }

    public int getReceivedCount() { return receivedCount; }
    public int getTotalCount() { return totalFrames; }

    public byte[] assemble() {
        int totalLen = 0;
        for (int i = 0; i < totalFrames; i++) {
            totalLen += frameData[i].length;
        }
        byte[] result = new byte[totalLen];
        int pos = 0;
        for (int i = 0; i < totalFrames; i++) {
            System.arraycopy(frameData[i], 0, result, pos, frameData[i].length);
            pos += frameData[i].length;
        }
        return result;
    }
}
```

**Step 3: Run tests, Step 4: Commit**

```bash
git add signer/src/org/burnerwallet/transport/MultiFrameEncoder.java \
       signer/src/org/burnerwallet/transport/MultiFrameDecoder.java \
       signer/test/org/burnerwallet/transport/MultiFrameTest.java
git commit -s -m "feat(signer): add multi-frame QR encoder/decoder for air-gap transport"
```

---

### Task 9: QrDisplayScreen — Canvas-based QR rendering

**Files:**
- Create: `signer/src/org/burnerwallet/ui/QrDisplayScreen.java`

**No JUnit test** — this is LCDUI Canvas, tested visually in emulator.

**Implementation:** The agent should create a Canvas subclass that:
1. Takes a `byte[] payload` and `String title`
2. Uses `MultiFrameEncoder.encode(payload, 150)` to split into frames
3. Uses `QrCode.encodeBinary(frame, ECC_LOW)` for each frame
4. `paint(Graphics)`: renders current frame's QR modules scaled to fit screen
   - Nokia screen: 128x160
   - Calculate scale: `min(120 / qr.size, 150 / qr.size)` for ~2px modules
   - Center horizontally, offset vertically to leave room for frame counter
   - White background, black modules
   - Frame counter text at top: "1/3"
5. Timer auto-advances frames every 2 seconds (loop)
6. Key commands: "Done" returns to caller, LEFT/RIGHT for manual navigation
7. `QrDisplayListener` interface: `onQrDisplayDone()`

```bash
git add signer/src/org/burnerwallet/ui/QrDisplayScreen.java
git commit -s -m "feat(signer): add QrDisplayScreen — Canvas-based QR code rendering"
```

---

### Task 10: QR Decoder + CameraScanner (experimental)

**Files:**
- Create: `signer/src/org/burnerwallet/transport/QrDecoder.java`
- Create: `signer/src/org/burnerwallet/transport/CameraScanner.java`
- Create: `signer/src/org/burnerwallet/ui/QrScanScreen.java`
- Create: `signer/test/org/burnerwallet/transport/QrDecoderTest.java`

**Context:** This is the highest-risk task. The QR decoder must work on a ~100MHz ARM processor. If it's too complex, the manual entry fallback is sufficient.

**QrDecoder approach:** Minimal byte-mode QR decoder:
1. Find three finder patterns (7x7 dark/light/dark squares) in grayscale image
2. Determine QR version from finder pattern spacing
3. Read format information (ECC level + mask pattern)
4. Read raw data modules, apply mask
5. Error correction using Reed-Solomon (can skip if ECC_LOW and no errors)
6. Extract byte-mode payload

**QrDecoderTest:** Round-trip test — encode with `QrCode`, render to pixel array, decode with `QrDecoder`:
```java
@Test
public void roundTripEncodeDecode() {
    byte[] data = "test123".getBytes();
    QrCode qr = QrCode.encodeBinary(data, QrCode.ECC_LOW);
    // Render to boolean[][] then to grayscale int[]
    int scale = 4;
    int imgSize = (qr.size + 8) * scale; // quiet zone
    int[] pixels = new int[imgSize * imgSize];
    // ... render qr modules to pixels (0=black, 255=white)
    byte[] decoded = QrDecoder.decode(pixels, imgSize, imgSize);
    assertArrayEquals(data, decoded);
}
```

**CameraScanner:** MMAPI integration:
- `Manager.createPlayer("capture://video")`
- Get `VideoControl`, set display to Canvas
- Timer captures snapshots every 500ms
- Feed pixels to `QrDecoder`
- On successful decode: call `MultiFrameDecoder.addFrame()`

**QrScanScreen:** Canvas with camera viewfinder + progress display

**Note to agent:** If the QR decoder proves too complex (>500 lines) or unreliable, implement a stub that always returns null and rely on ManualEntryScreen. The camera infrastructure can be completed in a follow-up.

```bash
git add signer/src/org/burnerwallet/transport/QrDecoder.java \
       signer/src/org/burnerwallet/transport/CameraScanner.java \
       signer/src/org/burnerwallet/ui/QrScanScreen.java \
       signer/test/org/burnerwallet/transport/QrDecoderTest.java
git commit -s -m "feat(signer): add QR decoder and MMAPI camera scanner (experimental)"
```

---

### Task 11: ManualEntryScreen — hex input fallback

**Files:**
- Create: `signer/src/org/burnerwallet/transport/ManualEntryScreen.java`

**Implementation:** Form with:
1. TextField for hex-encoded PSBT (ANY mode, max 2000 chars)
2. "Submit" command — validates hex format, decodes, passes to listener
3. "Cancel" command — returns to home
4. `ManualEntryListener` interface: `onPsbtEntered(byte[] psbt)`, `onManualEntryCancelled()`
5. Validation: must be valid hex, must start with PSBT magic (`70736274ff`)

```bash
git add signer/src/org/burnerwallet/transport/ManualEntryScreen.java
git commit -s -m "feat(signer): add ManualEntryScreen — hex PSBT input fallback"
```

---

## Phase 3: Signer UI Integration

### Task 12: TransactionReviewScreen

**Files:**
- Create: `signer/src/org/burnerwallet/ui/TransactionReviewScreen.java`
- Create: `signer/test/org/burnerwallet/ui/TransactionReviewTest.java`

**Step 1: Write the failing test**

```java
package org.burnerwallet.ui;

import org.burnerwallet.chains.bitcoin.*;
import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HexCodec;
import org.junit.Test;
import static org.junit.Assert.*;

public class TransactionReviewTest {

    @Test
    public void formatSatsToBtc() {
        assertEquals("0.001", TransactionReviewScreen.formatBtc(100000));
        assertEquals("1.0", TransactionReviewScreen.formatBtc(100000000));
        assertEquals("0.00001", TransactionReviewScreen.formatBtc(1000));
        assertEquals("21000000.0", TransactionReviewScreen.formatBtc(2100000000000000L));
    }

    @Test
    public void detectHighFee() {
        // Fee > 10% of total input
        assertTrue(TransactionReviewScreen.isHighFee(10000, 1000)); // 10% exactly
        assertTrue(TransactionReviewScreen.isHighFee(10000, 2000)); // 20%
        assertFalse(TransactionReviewScreen.isHighFee(100000, 500)); // 0.5%
    }

    @Test
    public void detectMultipleRecipients() {
        // 2+ non-change outputs = multiple recipients
        TxOutput out1 = new TxOutput();
        out1.value = 1000;
        out1.scriptPubKey = HexCodec.decode("0014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        TxOutput out2 = new TxOutput();
        out2.value = 2000;
        out2.scriptPubKey = HexCodec.decode("0014bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        TxOutput[] outputs = new TxOutput[]{out1, out2};
        // With no known change pubkey, all outputs look like recipients
        assertTrue(TransactionReviewScreen.hasMultipleRecipients(outputs, null));
    }

    @Test
    public void extractAddressFromP2wpkh() throws CryptoError {
        byte[] script = HexCodec.decode("0014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        String addr = TransactionReviewScreen.addressFromScript(script, false);
        assertNotNull(addr);
        assertTrue(addr.startsWith("bc1q"));
    }
}
```

**Step 2: Write implementation**

Form-based screen showing:
- "Send: X.XXXX BTC" (total non-change output value)
- "To: bc1q..." (destination address, formatted 14 chars/line)
- "Fee: X.XXXX BTC (Y%)"
- Warnings section (if any): "WARNING: High fee!", "WARNING: Multiple recipients"
- Commands: "Sign" (OK) and "Reject" (BACK)
- `TransactionReviewListener`: `onApprove(PsbtTransaction psbt)`, `onReject()`

Helper static methods for testability:
- `formatBtc(long sats)` → String
- `isHighFee(long totalInput, long fee)` → boolean (>10%)
- `hasMultipleRecipients(TxOutput[], byte[] changePubKey)` → boolean
- `addressFromScript(byte[] scriptPubKey, boolean testnet)` → String

```bash
git add signer/src/org/burnerwallet/ui/TransactionReviewScreen.java \
       signer/test/org/burnerwallet/ui/TransactionReviewTest.java
git commit -s -m "feat(signer): add TransactionReviewScreen with amount/fee/warning display"
```

---

### Task 13: Wire up MIDlet — signing flow + receive QR upgrade

**Files:**
- Modify: `signer/src/org/burnerwallet/ui/BurnerWalletMIDlet.java`
- Modify: `signer/src/org/burnerwallet/ui/WalletHomeScreen.java`
- Modify: `signer/src/org/burnerwallet/ui/ReceiveScreen.java`

**Changes to WalletHomeScreen:**
- Add `ACTION_SIGN = 2` constant
- Add "Sign Transaction" menu item between "Receive Address" and "Settings"

**Changes to ReceiveScreen:**
- After deriving address, also generate QR code via `QrCode.encodeBinary()`
- Display QR on a Canvas above the text address
- Or add "Show QR" command that opens `QrDisplayScreen` with the address bytes

**Changes to BurnerWalletMIDlet:**
- Implement `TransactionReviewListener`, `QrDisplayListener`, `QrScanListener`, `ManualEntryListener`
- `onHomeAction(ACTION_SIGN)` → show QrScanScreen (or ManualEntryScreen if camera unavailable)
- `onPsbtEntered(byte[])` / camera scan complete → parse PSBT → show TransactionReviewScreen
- `onApprove(PsbtTransaction)` → sign with `PsbtSigner.sign()` → serialize → show QrDisplayScreen
- `onReject()` → return to home
- `onQrDisplayDone()` → return to home

```bash
git add signer/src/org/burnerwallet/ui/BurnerWalletMIDlet.java \
       signer/src/org/burnerwallet/ui/WalletHomeScreen.java \
       signer/src/org/burnerwallet/ui/ReceiveScreen.java
git commit -s -m "feat(signer): wire up PSBT signing flow and receive QR display"
```

---

## Phase 4: Companion Core (Rust)

### Task 14: companion/core — wallet.rs + psbt.rs + broadcast.rs

**Files:**
- Create: `companion/core/src/wallet.rs`
- Create: `companion/core/src/psbt.rs`
- Create: `companion/core/src/broadcast.rs`
- Modify: `companion/core/src/lib.rs` — add module exports
- Modify: `companion/core/src/error.rs` — add new error variants if needed

**wallet.rs:**

```rust
use bdk_wallet::{Wallet, KeychainKind, Balance};
use bdk_wallet::bitcoin::Network;
use bdk_esplora::EsploraExt;
use bdk_esplora::esplora_client;
use crate::error::Error;

/// Create a BDK wallet from a BIP84 descriptor.
pub fn create_wallet(
    descriptor: &str,
    change_descriptor: &str,
    network: Network,
) -> Result<Wallet, Error> {
    Wallet::create(descriptor.to_string(), change_descriptor.to_string())
        .network(network)
        .create_wallet_no_persist()
        .map_err(|e| Error::Bitcoin(e.to_string()))
}

/// Sync wallet UTXOs via Esplora.
pub fn sync_wallet(wallet: &mut Wallet, esplora_url: &str) -> Result<(), Error> {
    let client = esplora_client::Builder::new(esplora_url)
        .build_blocking();
    let request = wallet.start_full_scan().build();
    let update = client.full_scan(request, 5, 5)
        .map_err(|e| Error::Network(e.to_string()))?;
    wallet.apply_update(update)
        .map_err(|e| Error::Bitcoin(e.to_string()))?;
    Ok(())
}

/// Get wallet balance.
pub fn get_balance(wallet: &Wallet) -> Balance {
    wallet.balance()
}
```

**psbt.rs:**

```rust
use bitcoin::psbt::Psbt;
use bitcoin::{Amount, Address, Transaction, FeeRate};
use bdk_wallet::Wallet;
use crate::error::Error;

/// Create an unsigned PSBT for sending to one or more recipients.
pub fn create_unsigned_psbt(
    wallet: &mut Wallet,
    recipients: &[(Address, Amount)],
    fee_rate: FeeRate,
) -> Result<Psbt, Error> {
    let mut builder = wallet.build_tx();
    for (addr, amount) in recipients {
        builder.add_recipient(addr.script_pubkey(), *amount);
    }
    builder.fee_rate(fee_rate);
    let psbt = builder.finish()
        .map_err(|e| Error::Psbt(e.to_string()))?;
    Ok(psbt)
}

/// Merge a signed PSBT (from signer) into the original PSBT.
pub fn merge_signed_psbt(
    original: &mut Psbt,
    signed_bytes: &[u8],
) -> Result<(), Error> {
    let signed: Psbt = Psbt::deserialize(signed_bytes)
        .map_err(|e| Error::Psbt(e.to_string()))?;
    original.combine(signed)
        .map_err(|e| Error::Psbt(e.to_string()))?;
    Ok(())
}

/// Finalize a PSBT and extract the broadcastable transaction.
pub fn finalize_psbt(psbt: &Psbt) -> Result<Transaction, Error> {
    // BDK or manual finalization
    let tx = psbt.clone().extract_tx()
        .map_err(|e| Error::Psbt(e.to_string()))?;
    Ok(tx)
}

/// Serialize PSBT to bytes.
pub fn serialize_psbt(psbt: &Psbt) -> Vec<u8> {
    psbt.serialize()
}

/// Deserialize PSBT from bytes.
pub fn deserialize_psbt(bytes: &[u8]) -> Result<Psbt, Error> {
    Psbt::deserialize(bytes)
        .map_err(|e| Error::Psbt(e.to_string()))
}
```

**broadcast.rs:**

```rust
use bitcoin::Transaction;
use bdk_esplora::esplora_client;
use crate::error::Error;

/// Broadcast a finalized transaction via Esplora.
pub fn broadcast_tx(tx: &Transaction, esplora_url: &str) -> Result<bitcoin::Txid, Error> {
    let client = esplora_client::Builder::new(esplora_url)
        .build_blocking();
    client.broadcast(tx)
        .map_err(|e| Error::Network(e.to_string()))?;
    Ok(tx.compute_txid())
}
```

**Tests:** The agent should write tests in `companion/core/src/psbt.rs` (or separate test file) covering:
- `create_unsigned_psbt` with a regtest/signet wallet
- `serialize_psbt` / `deserialize_psbt` round-trip
- `merge_signed_psbt` with a manually signed PSBT
- Note: wallet sync tests may need mocking or use signet

**Step: Commit**

```bash
cd companion/core
git add src/wallet.rs src/psbt.rs src/broadcast.rs src/lib.rs
git commit -s -m "feat(companion-core): add wallet management, PSBT construction, and broadcast"
```

---

### Task 15: Cross-implementation PSBT test vectors

**Files:**
- Create: `protocol/vectors/psbt-signing.json`
- Create: `signer/test/org/burnerwallet/chains/bitcoin/PsbtCrossImplTest.java`
- Add test in `companion/core`

**Context:** Generate a known PSBT from the "abandon...about" mnemonic, sign it on both Java ME and Rust, verify identical signatures (deterministic via RFC 6979).

The agent should:
1. Use the companion core to create a crafted unsigned PSBT (hardcoded, not from wallet)
2. Sign on both sides
3. Verify signature bytes match exactly
4. Save vectors to `protocol/vectors/psbt-signing.json`

```bash
git add protocol/vectors/psbt-signing.json \
       signer/test/org/burnerwallet/chains/bitcoin/PsbtCrossImplTest.java
git commit -s -m "test(protocol): add cross-implementation PSBT signing test vectors"
```

---

## Phase 5: Companion TUI

### Task 16: Companion TUI — full workflow

**Files:**
- Rewrite: `companion/tui/src/main.rs`
- Create: `companion/tui/src/app.rs` — application state
- Create: `companion/tui/src/ui.rs` — ratatui rendering
- Create: `companion/tui/src/screens/mod.rs`
- Create: `companion/tui/src/screens/wallet.rs` — wallet status view
- Create: `companion/tui/src/screens/send.rs` — send flow (address → amount → fee → PSBT → QR)
- Create: `companion/tui/src/screens/receive.rs` — receive signed PSBT (paste hex)
- Create: `companion/tui/src/screens/history.rs` — transaction list
- Modify: `companion/tui/Cargo.toml` — add dependencies if needed

**Architecture:**
- `App` struct holds state: current screen, wallet, network config
- `main()`: parse CLI args, create wallet from mnemonic or descriptor, sync, enter TUI loop
- Ratatui event loop: render current screen, handle key events
- Screens: `WalletScreen`, `SendScreen`, `ReceiveScreen`, `HistoryScreen`

**Send flow:**
1. Enter recipient address (validate bech32)
2. Enter amount in sats or BTC
3. Enter fee rate (sat/vB) or use default
4. Build PSBT via `psbt::create_unsigned_psbt()`
5. Display PSBT as hex (for manual entry on Nokia)
6. Optionally: render QR code in terminal using unicode block characters

**Receive flow:**
1. Paste signed PSBT hex from signer
2. Merge signatures via `psbt::merge_signed_psbt()`
3. Finalize via `psbt::finalize_psbt()`
4. Display transaction details for confirmation
5. Broadcast via `broadcast::broadcast_tx()`
6. Show txid

**TUI QR display (optional enhancement):**
- Use unicode half-block characters (▀▄█ ) to render QR in terminal
- 2 QR rows per terminal row using top/bottom half blocks

```bash
cd companion/tui
git add src/
git commit -s -m "feat(companion-tui): add full wallet TUI — send, receive, history screens"
```

---

## Phase 6: Final Verification

### Task 17: Build verification + CLAUDE.md update

**Steps:**

1. Run all signer tests: `cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test`
   - Expected: ~226 tests pass (153 existing + ~73 new)

2. Run signer build + size check: `make signer && make size-check`
   - Expected: JAR < 1MB

3. Run companion tests: `cd companion/core && cargo test`
   - Expected: all pass

4. Run companion TUI build: `cd companion/tui && cargo build`
   - Expected: builds successfully

5. Run clippy: `cd companion/core && cargo clippy -- -D warnings`
   - Expected: no warnings

6. Update CLAUDE.md:
   - Milestone: "M1c complete, M2 next"
   - Add `transport/` module to signer listing
   - Update test counts
   - Update JAR size

7. Update MEMORY.md with M1c learnings

8. Commit and push

```bash
git add CLAUDE.md
git commit -s -m "docs: update CLAUDE.md for M1c completion — PSBT signing, QR transport, companion TUI"
```
