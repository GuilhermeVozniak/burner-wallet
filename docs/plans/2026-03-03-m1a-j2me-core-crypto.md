# M1a — Java ME Core Crypto Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement BIP39/32/44/84/173 crypto modules in Java ME using Bouncy Castle lightweight API, producing identical addresses as the Rust companion core.

**Architecture:** Two packages: `org.burnerwallet.core` (chain-agnostic hashing, encoding) and `org.burnerwallet.chains.bitcoin` (BIP implementations delegating to BC). ProGuard shrinks BC from 4.5MB to ~300KB. JUnit tests run on JDK 8; CryptoTestRunner MIDlet runs on FreeJ2ME-Plus.

**Tech Stack:** Java ME (CLDC 1.1 / MIDP 2.0), Bouncy Castle `bcprov-jdk14` 1.83, Apache Ant, ProGuard 7.x, JUnit 4.

---

## Task 0: Download Bouncy Castle and update build.xml

**Files:**
- Download: `signer/lib/bcprov-jdk14.jar`
- Modify: `signer/build.xml`
- Modify: `.gitignore`

**Step 1: Download BC JAR from Maven Central**

```bash
cd signer
curl -L -o lib/bcprov-jdk14.jar \
  https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk14/1.83/bcprov-jdk14-1.83.jar
```

**Step 2: Add BC to .gitignore (4.5MB, too large to track)**

Append to `.gitignore`:
```
# Bouncy Castle (downloaded, not tracked — use make setup-tools)
signer/lib/bcprov-jdk14.jar
```

**Step 3: Update build.xml — add BC to compile classpath and ProGuard injars**

In the `compile` target, add BC to the classpath:
```xml
<classpath>
    <pathelement location="${midp.jar}"/>
    <pathelement location="${cldc.jar}"/>
    <pathelement location="${lib.dir}/bcprov-jdk14.jar"/>
</classpath>
```

In the `preverify` target, inject BC alongside our code:
```xml
<proguard>
    -injars      ${build.dir}/${midlet.name}-unprocessed.jar
    -injars      ${lib.dir}/bcprov-jdk14.jar(!META-INF/**)
    -outjars     ${dist.dir}/${midlet.name}.jar
    -libraryjars ${cldc.jar}
    -libraryjars ${midp.jar}
    -microedition
    -dontobfuscate
    -optimizationpasses 3
    -allowaccessmodification
    -dontnote
    -dontwarn
    -keep public class * extends javax.microedition.midlet.MIDlet
    -keep class org.burnerwallet.** { *; }
</proguard>
```

**Step 4: Add JUnit test compilation target**

Download JUnit 4 JAR:
```bash
curl -L -o lib/junit-4.13.2.jar \
  https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar
curl -L -o lib/hamcrest-core-1.3.jar \
  https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar
```

Add to build.xml:
```xml
<property name="test.src.dir" value="test"/>
<property name="test.classes.dir" value="${build.dir}/test-classes"/>
<property name="junit.jar" value="${lib.dir}/junit-4.13.2.jar"/>
<property name="hamcrest.jar" value="${lib.dir}/hamcrest-core-1.3.jar"/>

<target name="compile-test" depends="compile" description="Compile JUnit tests">
    <mkdir dir="${test.classes.dir}"/>
    <javac srcdir="${test.src.dir}"
           destdir="${test.classes.dir}"
           source="1.8"
           target="1.8"
           includeantruntime="false"
           debug="true">
        <classpath>
            <pathelement location="${classes.dir}"/>
            <pathelement location="${lib.dir}/bcprov-jdk14.jar"/>
            <pathelement location="${junit.jar}"/>
            <pathelement location="${hamcrest.jar}"/>
        </classpath>
    </javac>
</target>

<target name="test" depends="compile-test" description="Run JUnit tests">
    <junit printsummary="on" haltonfailure="yes" fork="yes">
        <classpath>
            <pathelement location="${classes.dir}"/>
            <pathelement location="${test.classes.dir}"/>
            <pathelement location="${lib.dir}/bcprov-jdk14.jar"/>
            <pathelement location="${junit.jar}"/>
            <pathelement location="${hamcrest.jar}"/>
        </classpath>
        <formatter type="brief" usefile="false"/>
        <batchtest>
            <fileset dir="${test.classes.dir}" includes="**/*Test.class"/>
        </batchtest>
    </junit>
</target>
```

Note: Tests compile with `source="1.8"` (standard JDK) even though the main source uses `source="1.4"` (CLDC). This is intentional — tests run on desktop JDK, not on device. The main source must remain Java 1.4 compatible.

**Step 5: Verify build still works**

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant clean build
```

Expected: BUILD SUCCESSFUL. JAR size will grow significantly due to BC inclusion.

**Step 6: Commit**

```bash
git add signer/build.xml .gitignore
git commit -m "chore(signer): integrate Bouncy Castle and add JUnit test target"
```

---

## Task 1: Core utilities — HexCodec, ByteArrayUtils, CryptoError

**Files:**
- Create: `signer/src/org/burnerwallet/core/CryptoError.java`
- Create: `signer/src/org/burnerwallet/core/HexCodec.java`
- Create: `signer/src/org/burnerwallet/core/ByteArrayUtils.java`
- Create: `signer/test/org/burnerwallet/core/CoreUtilsTest.java`

**Step 1: Write tests**

```java
package org.burnerwallet.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class CoreUtilsTest {
    @Test
    public void hexEncodeEmpty() {
        assertEquals("", HexCodec.encode(new byte[0]));
    }

    @Test
    public void hexEncodeBytes() {
        assertEquals("deadbeef", HexCodec.encode(new byte[]{
            (byte)0xde, (byte)0xad, (byte)0xbe, (byte)0xef}));
    }

    @Test
    public void hexDecodeRoundTrip() throws CryptoError {
        byte[] original = new byte[]{0x00, 0x01, (byte)0xff};
        assertArrayEquals(original, HexCodec.decode(HexCodec.encode(original)));
    }

    @Test(expected = CryptoError.class)
    public void hexDecodeOddLength() throws CryptoError {
        HexCodec.decode("abc");
    }

    @Test
    public void concatBytes() {
        byte[] a = new byte[]{1, 2};
        byte[] b = new byte[]{3, 4, 5};
        byte[] result = ByteArrayUtils.concat(a, b);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, result);
    }

    @Test
    public void constantTimeEqualsTrue() {
        byte[] a = new byte[]{1, 2, 3};
        byte[] b = new byte[]{1, 2, 3};
        assertTrue(ByteArrayUtils.constantTimeEquals(a, b));
    }

    @Test
    public void constantTimeEqualsFalse() {
        byte[] a = new byte[]{1, 2, 3};
        byte[] b = new byte[]{1, 2, 4};
        assertFalse(ByteArrayUtils.constantTimeEquals(a, b));
    }

    @Test
    public void zeroFill() {
        byte[] data = new byte[]{1, 2, 3, 4};
        ByteArrayUtils.zeroFill(data);
        assertArrayEquals(new byte[4], data);
    }
}
```

**Step 2: Run tests — verify they fail**

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test
```

Expected: Compilation fails — classes don't exist.

**Step 3: Implement**

`CryptoError.java`:
```java
package org.burnerwallet.core;

public class CryptoError extends Exception {
    public static final int ERR_INVALID_MNEMONIC = 1;
    public static final int ERR_INVALID_SEED_LENGTH = 2;
    public static final int ERR_DERIVATION_FAILED = 3;
    public static final int ERR_INVALID_KEY = 4;
    public static final int ERR_ENCODING = 5;
    public static final int ERR_CHECKSUM = 6;

    private int errorCode;

    public CryptoError(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public CryptoError(String message) {
        this(0, message);
    }

    public int getErrorCode() {
        return errorCode;
    }
}
```

`HexCodec.java`:
```java
package org.burnerwallet.core;

public final class HexCodec {
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    public static String encode(byte[] data) {
        char[] result = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            result[i * 2] = HEX_CHARS[(data[i] >> 4) & 0x0f];
            result[i * 2 + 1] = HEX_CHARS[data[i] & 0x0f];
        }
        return new String(result);
    }

    public static byte[] decode(String hex) throws CryptoError {
        if (hex.length() % 2 != 0) {
            throw new CryptoError(CryptoError.ERR_ENCODING, "Hex string must have even length");
        }
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new CryptoError(CryptoError.ERR_ENCODING, "Invalid hex char");
            }
            result[i] = (byte) ((hi << 4) | lo);
        }
        return result;
    }
}
```

`ByteArrayUtils.java`:
```java
package org.burnerwallet.core;

public final class ByteArrayUtils {
    public static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    public static byte[] copyOf(byte[] src, int newLength) {
        byte[] result = new byte[newLength];
        System.arraycopy(src, 0, result, 0, Math.min(src.length, newLength));
        return result;
    }

    public static byte[] copyOfRange(byte[] src, int from, int to) {
        int len = to - from;
        byte[] result = new byte[len];
        System.arraycopy(src, from, result, 0, len);
        return result;
    }

    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    public static void zeroFill(byte[] array) {
        for (int i = 0; i < array.length; i++) {
            array[i] = 0;
        }
    }
}
```

**Step 4: Run tests — verify they pass**

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test
```

Expected: All 8 tests pass.

**Step 5: Commit**

```bash
git add signer/src/org/burnerwallet/core/CryptoError.java \
        signer/src/org/burnerwallet/core/HexCodec.java \
        signer/src/org/burnerwallet/core/ByteArrayUtils.java \
        signer/test/org/burnerwallet/core/CoreUtilsTest.java
git commit -m "feat(signer): add core utilities — HexCodec, ByteArrayUtils, CryptoError"
```

---

## Task 2: HashUtils — SHA-256, RIPEMD-160, HMAC-SHA512, PBKDF2

This is the critical task that validates Bouncy Castle integration on CLDC 1.1.

**Files:**
- Create: `signer/src/org/burnerwallet/core/HashUtils.java`
- Create: `signer/test/org/burnerwallet/core/HashUtilsTest.java`

**Step 1: Write tests** (known-answer vectors)

```java
package org.burnerwallet.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class HashUtilsTest {
    @Test
    public void sha256Empty() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        byte[] hash = HashUtils.sha256(new byte[0]);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            HexCodec.encode(hash));
    }

    @Test
    public void sha256Abc() throws CryptoError {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        byte[] hash = HashUtils.sha256("abc".getBytes());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            HexCodec.encode(hash));
    }

    @Test
    public void doubleSha256() throws CryptoError {
        byte[] hash = HashUtils.doubleSha256("abc".getBytes());
        // SHA-256(SHA-256("abc"))
        byte[] expected = HashUtils.sha256(HashUtils.sha256("abc".getBytes()));
        assertArrayEquals(expected, hash);
    }

    @Test
    public void ripemd160() {
        // RIPEMD-160("abc") = 8eb208f7e05d987a9b044a8e98c6b087f15a0bfc
        byte[] hash = HashUtils.ripemd160("abc".getBytes());
        assertEquals("8eb208f7e05d987a9b044a8e98c6b087f15a0bfc", HexCodec.encode(hash));
    }

    @Test
    public void hash160() {
        // Hash160 = RIPEMD-160(SHA-256(input))
        byte[] input = "abc".getBytes();
        byte[] expected = HashUtils.ripemd160(HashUtils.sha256(input));
        assertArrayEquals(expected, HashUtils.hash160(input));
    }

    @Test
    public void hmacSha512() throws CryptoError {
        // BIP32 uses HMAC-SHA512 with key "Bitcoin seed"
        // Known test: HMAC-SHA512(key="Bitcoin seed", data=seed_vector_1)
        byte[] key = "Bitcoin seed".getBytes();
        byte[] data = HexCodec.decode("000102030405060708090a0b0c0d0e0f");
        byte[] hmac = HashUtils.hmacSha512(key, data);
        assertEquals(64, hmac.length);
        // First 32 bytes = master private key, verified against BIP32 vector 1
        String left32 = HexCodec.encode(ByteArrayUtils.copyOfRange(hmac, 0, 32));
        assertEquals("e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35", left32);
    }

    @Test
    public void pbkdf2HmacSha512() throws CryptoError {
        // BIP39 test vector 1: mnemonic "abandon...about" with passphrase ""
        // Salt = "mnemonic" + passphrase = "mnemonic"
        String mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        byte[] password = mnemonic.getBytes();
        byte[] salt = "mnemonic".getBytes();
        byte[] seed = HashUtils.pbkdf2HmacSha512(password, salt, 2048, 64);
        assertEquals("5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            HexCodec.encode(seed));
    }
}
```

**Step 2: Run tests — verify they fail**

**Step 3: Implement**

```java
package org.burnerwallet.core;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;

public final class HashUtils {
    public static byte[] sha256(byte[] input) {
        SHA256Digest digest = new SHA256Digest();
        digest.update(input, 0, input.length);
        byte[] out = new byte[32];
        digest.doFinal(out, 0);
        return out;
    }

    public static byte[] doubleSha256(byte[] input) {
        return sha256(sha256(input));
    }

    public static byte[] ripemd160(byte[] input) {
        RIPEMD160Digest digest = new RIPEMD160Digest();
        digest.update(input, 0, input.length);
        byte[] out = new byte[20];
        digest.doFinal(out, 0);
        return out;
    }

    public static byte[] hash160(byte[] input) {
        return ripemd160(sha256(input));
    }

    public static byte[] hmacSha512(byte[] key, byte[] data) {
        HMac hmac = new HMac(new SHA512Digest());
        hmac.init(new KeyParameter(key));
        hmac.update(data, 0, data.length);
        byte[] out = new byte[64];
        hmac.doFinal(out, 0);
        return out;
    }

    public static byte[] pbkdf2HmacSha512(byte[] password, byte[] salt,
                                            int iterations, int derivedKeyLength) {
        PKCS5S2ParametersGenerator gen = new PKCS5S2ParametersGenerator(new SHA512Digest());
        gen.init(password, salt, iterations);
        KeyParameter dk = (KeyParameter) gen.generateDerivedParameters(derivedKeyLength * 8);
        return dk.getKey();
    }
}
```

**Step 4: Run tests — verify they pass**

**Step 5: Commit**

```bash
git add signer/src/org/burnerwallet/core/HashUtils.java \
        signer/test/org/burnerwallet/core/HashUtilsTest.java
git commit -m "feat(signer): add HashUtils — SHA-256, RIPEMD-160, HMAC-SHA512, PBKDF2 via Bouncy Castle"
```

---

## Task 3: Base58 — Base58Check encode/decode

**Files:**
- Create: `signer/src/org/burnerwallet/core/Base58.java`
- Create: `signer/test/org/burnerwallet/core/Base58Test.java`

**Step 1: Write tests**

```java
package org.burnerwallet.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class Base58Test {
    @Test
    public void encodeEmptyBytes() {
        assertEquals("", Base58.encode(new byte[0]));
    }

    @Test
    public void encodeLeadingZeros() {
        // Leading zero bytes map to '1' characters
        assertEquals("1", Base58.encode(new byte[]{0}));
        assertEquals("11", Base58.encode(new byte[]{0, 0}));
    }

    @Test
    public void encodeDecodeRoundTrip() throws CryptoError {
        byte[] data = HexCodec.decode("0000010203");
        String encoded = Base58.encode(data);
        assertArrayEquals(data, Base58.decode(encoded));
    }

    @Test
    public void encodeCheckDecodeCheck() throws CryptoError {
        byte[] payload = HexCodec.decode("0488ade4");
        String encoded = Base58.encodeCheck(payload);
        assertArrayEquals(payload, Base58.decodeCheck(encoded));
    }

    @Test(expected = CryptoError.class)
    public void decodeCheckBadChecksum() throws CryptoError {
        // Corrupt last character
        String valid = Base58.encodeCheck(new byte[]{1, 2, 3});
        char last = valid.charAt(valid.length() - 1);
        char corrupted = (last == '1') ? '2' : '1';
        String invalid = valid.substring(0, valid.length() - 1) + corrupted;
        Base58.decodeCheck(invalid);
    }

    @Test
    public void knownXprvPrefix() throws CryptoError {
        // xprv version bytes 0x0488ADE4 should Base58Check encode starting with "xprv"
        // Full xprv from BIP32 vector 1:
        String xprv = "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi";
        byte[] decoded = Base58.decodeCheck(xprv);
        assertEquals(78, decoded.length); // 4 version + 1 depth + 4 fingerprint + 4 index + 32 chain + 33 key
        assertEquals("0488ade4", HexCodec.encode(ByteArrayUtils.copyOfRange(decoded, 0, 4)));
    }
}
```

**Step 3: Implement** (uses BigInteger from BC for base conversion — the key BC dependency test)

```java
package org.burnerwallet.core;

import java.math.BigInteger;

public final class Base58 {
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final BigInteger BASE = BigInteger.valueOf(58);

    public static String encode(byte[] input) {
        if (input.length == 0) return "";

        // Count leading zeros
        int leadingZeros = 0;
        while (leadingZeros < input.length && input[leadingZeros] == 0) {
            leadingZeros++;
        }

        // Convert to BigInteger (unsigned)
        BigInteger num = new BigInteger(1, input);
        StringBuffer sb = new StringBuffer();
        while (num.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] divmod = num.divideAndRemainder(BASE);
            num = divmod[0];
            sb.insert(0, ALPHABET.charAt(divmod[1].intValue()));
        }

        // Add leading '1' for each leading zero byte
        for (int i = 0; i < leadingZeros; i++) {
            sb.insert(0, '1');
        }

        return sb.toString();
    }

    public static byte[] decode(String input) throws CryptoError {
        if (input.length() == 0) return new byte[0];

        BigInteger num = BigInteger.ZERO;
        for (int i = 0; i < input.length(); i++) {
            int digit = ALPHABET.indexOf(input.charAt(i));
            if (digit < 0) {
                throw new CryptoError(CryptoError.ERR_ENCODING, "Invalid Base58 character: " + input.charAt(i));
            }
            num = num.multiply(BASE).add(BigInteger.valueOf(digit));
        }

        byte[] bytes = num.toByteArray();
        // Remove leading zero added by BigInteger for positive sign
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = ByteArrayUtils.copyOfRange(bytes, 1, bytes.length);
        }

        // Count leading '1' characters (= leading zero bytes)
        int leadingOnes = 0;
        while (leadingOnes < input.length() && input.charAt(leadingOnes) == '1') {
            leadingOnes++;
        }

        byte[] result = new byte[leadingOnes + bytes.length];
        System.arraycopy(bytes, 0, result, leadingOnes, bytes.length);
        return result;
    }

    public static String encodeCheck(byte[] payload) {
        byte[] checksum = HashUtils.doubleSha256(payload);
        byte[] data = new byte[payload.length + 4];
        System.arraycopy(payload, 0, data, 0, payload.length);
        System.arraycopy(checksum, 0, data, payload.length, 4);
        return encode(data);
    }

    public static byte[] decodeCheck(String input) throws CryptoError {
        byte[] data = decode(input);
        if (data.length < 4) {
            throw new CryptoError(CryptoError.ERR_CHECKSUM, "Input too short for checksum");
        }
        byte[] payload = ByteArrayUtils.copyOfRange(data, 0, data.length - 4);
        byte[] checksum = ByteArrayUtils.copyOfRange(data, data.length - 4, data.length);
        byte[] expectedChecksum = ByteArrayUtils.copyOfRange(HashUtils.doubleSha256(payload), 0, 4);
        if (!ByteArrayUtils.constantTimeEquals(checksum, expectedChecksum)) {
            throw new CryptoError(CryptoError.ERR_CHECKSUM, "Invalid Base58Check checksum");
        }
        return payload;
    }
}
```

**Step 4: Run tests — verify they pass**

**Step 5: Commit**

```bash
git commit -m "feat(signer): add Base58Check encode/decode with BigInteger from Bouncy Castle"
```

---

## Task 4: Bech32 — BIP173 encoding

**Files:**
- Create: `signer/src/org/burnerwallet/core/Bech32.java`
- Create: `signer/test/org/burnerwallet/core/Bech32Test.java`

Tests should use vectors from `protocol/vectors/bip173-bech32.json`. The valid P2WPKH address `BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4` decodes to witness program `751e76e8199196d454941c45d1b3a323f1433bd6`.

Implementation follows BIP173 spec: polymod checksum over 5-bit groups, with HRP expansion.

**Step 5: Commit**

```bash
git commit -m "feat(signer): add BIP173 bech32 encode/decode"
```

---

## Task 5: NetworkParams + Secp256k1

**Files:**
- Create: `signer/src/org/burnerwallet/chains/bitcoin/NetworkParams.java`
- Create: `signer/src/org/burnerwallet/chains/bitcoin/Secp256k1.java`
- Create: `signer/test/org/burnerwallet/chains/bitcoin/Secp256k1Test.java`

`NetworkParams` is constants only (version bytes, HRP, coin type).

`Secp256k1` wraps BC's ECPoint operations:

```java
package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.CryptoError;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.math.ec.ECPoint;
import java.math.BigInteger;

public final class Secp256k1 {
    private static final X9ECParameters CURVE_PARAMS = SECNamedCurves.getByName("secp256k1");
    private static final ECDomainParameters DOMAIN = new ECDomainParameters(
        CURVE_PARAMS.getCurve(), CURVE_PARAMS.getG(), CURVE_PARAMS.getN(), CURVE_PARAMS.getH());

    public static byte[] publicKeyFromPrivate(byte[] privateKey32) throws CryptoError {
        BigInteger privKeyInt = new BigInteger(1, privateKey32);
        if (privKeyInt.compareTo(BigInteger.ZERO) <= 0 || privKeyInt.compareTo(DOMAIN.getN()) >= 0) {
            throw new CryptoError(CryptoError.ERR_INVALID_KEY, "Private key out of range");
        }
        ECPoint point = DOMAIN.getG().multiply(privKeyInt).normalize();
        return point.getEncoded(true); // 33-byte compressed
    }

    public static byte[] pointAdd(byte[] pubKey1, byte[] pubKey2) throws CryptoError {
        ECPoint p1 = CURVE_PARAMS.getCurve().decodePoint(pubKey1);
        ECPoint p2 = CURVE_PARAMS.getCurve().decodePoint(pubKey2);
        ECPoint sum = p1.add(p2).normalize();
        return sum.getEncoded(true);
    }

    public static ECDomainParameters getDomain() { return DOMAIN; }
    public static BigInteger getN() { return DOMAIN.getN(); }
}
```

Tests should verify:
- Private key = 1 produces the generator point G
- Known private/public key pair from BIP32 vector 1
- Invalid keys (0, n) are rejected

**Step 5: Commit**

```bash
git commit -m "feat(signer): add secp256k1 EC operations via Bouncy Castle"
```

---

## Task 6: BIP39 — Wordlist + Mnemonic

**Files:**
- Create: `signer/res/bip39-english.txt`
- Create: `signer/src/org/burnerwallet/chains/bitcoin/Bip39Wordlist.java`
- Create: `signer/src/org/burnerwallet/chains/bitcoin/Bip39Mnemonic.java`
- Create: `signer/test/org/burnerwallet/chains/bitcoin/Bip39Test.java`

Download the official BIP39 English wordlist:
```bash
curl -L -o signer/res/bip39-english.txt \
  https://raw.githubusercontent.com/bitcoin/bips/master/bip-0039/english.txt
```

Tests from `protocol/vectors/bip39-english.json`:
- Vector 1: entropy "00000000000000000000000000000000" -> "abandon...about", seed with "" passphrase
- Vector 2: entropy "7f7f..." -> "legal winner...", seed with "TREZOR"
- Vector 3: entropy "8080..." -> "letter advice...", seed with "TREZOR"
- Vector 4: entropy "ffff..." -> "zoo...wrong", seed with "TREZOR"
- Validation: reject bad checksums, reject unknown words

`Bip39Mnemonic.generate(byte[] entropy)` computes SHA-256 checksum bits, maps 11-bit groups to wordlist indices.
`Bip39Mnemonic.toSeed(String mnemonic, String passphrase)` calls PBKDF2-HMAC-SHA512 with salt "mnemonic" + passphrase.

**Step 5: Commit**

```bash
git commit -m "feat(signer): add BIP39 mnemonic generation and seed derivation"
```

---

## Task 7: BIP32 — Key derivation

The most complex task. Implements `Bip32Key` value type and `Bip32Derivation` with master-from-seed and child derivation.

**Files:**
- Create: `signer/src/org/burnerwallet/chains/bitcoin/Bip32Key.java`
- Create: `signer/src/org/burnerwallet/chains/bitcoin/Bip32Derivation.java`
- Create: `signer/test/org/burnerwallet/chains/bitcoin/Bip32Test.java`

Tests from `protocol/vectors/bip32-derivation.json`:
- Vector 1: seed "000102030405060708090a0b0c0d0e0f"
  - m -> xprv/xpub
  - m/0' -> xprv/xpub
  - m/0'/1 -> xprv/xpub
  - m/0'/1/2' -> xprv/xpub
  - m/0'/1/2'/2 -> xprv/xpub
  - m/0'/1/2'/2/1000000000 -> xprv/xpub
- Vector 2: seed "fffcf9f6..." (3 derivation depths)

Key algorithm for child derivation:
```
if hardened:
    data = 0x00 || parentPrivKey || ser32(index)
else:
    data = parentPubKey || ser32(index)
hmac = HMAC-SHA512(parentChainCode, data)
childKey = (parse256(hmac_left) + parentKey) mod n
childChainCode = hmac_right
```

**Step 5: Commit**

```bash
git commit -m "feat(signer): add BIP32 HD key derivation with xprv/xpub serialization"
```

---

## Task 8: BIP44/84 paths + BitcoinAddress

**Files:**
- Create: `signer/src/org/burnerwallet/chains/bitcoin/Bip44Path.java`
- Create: `signer/src/org/burnerwallet/chains/bitcoin/BitcoinAddress.java`
- Create: `signer/test/org/burnerwallet/chains/bitcoin/AddressTest.java`

Tests must verify cross-implementation compatibility with Rust companion:

```java
@Test
public void crossImplMainnetReceive0() throws CryptoError {
    String mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    byte[] seed = Bip39Mnemonic.toSeed(mnemonic, "");
    String addr = BitcoinAddress.deriveP2wpkhAddress(seed, false, 0, false, 0);
    assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", addr);
}

@Test
public void crossImplTestnetReceive0() throws CryptoError {
    String mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    byte[] seed = Bip39Mnemonic.toSeed(mnemonic, "");
    String addr = BitcoinAddress.deriveP2wpkhAddress(seed, true, 0, false, 0);
    assertEquals("tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl", addr);
}
```

These addresses were generated by the Rust companion core and verified in this planning session.

**Step 5: Commit**

```bash
git commit -m "feat(signer): add BIP44/84 paths and P2WPKH address generation"
```

---

## Task 9: Cross-implementation test vectors file

**Files:**
- Create: `protocol/vectors/cross-impl-wallet.json`

```json
{
  "description": "Cross-implementation test vectors — must produce identical results in Rust and Java ME",
  "mnemonic": "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
  "passphrase": "",
  "seed_hex": "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
  "master_xprv": "xprv9s21ZrQH143K3GJpoapnV8SFfukcVBSfeCficPSGfubmSFDxo1kuHnLisriDvSnRRuL2Qrg5ggqHKNVpxR86QEC8w35uxmGoggxtQTPvfUu",
  "master_xpub": "xpub661MyMwAqRbcFkPHucMnrGNzDwb6teAX1RbKQmqtEF8kK3Z7LZ59qafCjB9eCRLiTVG3uxBxgKvRgbubRhqSKXnGGb1aoaqLrpMBDrVxga8",
  "addresses": {
    "m/84'/0'/0'/0/0": "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
    "m/84'/0'/0'/0/1": "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g",
    "m/84'/0'/0'/0/2": "bc1qp59yckz4ae5c4efgw2s5wfyvrz0ala7rgvuz8z",
    "m/84'/0'/0'/1/0": "bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el",
    "m/84'/1'/0'/0/0": "tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl",
    "m/84'/1'/0'/0/1": "tb1qd7spv5q28348xl4myc8zmh983w5jx32cjhkn97",
    "m/84'/1'/0'/0/2": "tb1qxdyjf6h5d6qxap4n2dap97q4j5ps6ua8sll0ct",
    "m/84'/1'/0'/1/0": "tb1q9u62588spffmq4dzjxsr5l297znf3z6j5p2688"
  }
}
```

**Commit:**

```bash
git commit -m "test(protocol): add cross-implementation wallet test vectors"
```

---

## Task 10: Full build verification + size check

**Step 1: Run full build with BC**

```bash
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant clean build
```

Expected: BUILD SUCCESSFUL. Note the JAR size — must be < 1,048,576 bytes.

**Step 2: Run size check**

```bash
ant size-check
```

**Step 3: Run all JUnit tests**

```bash
ant test
```

Expected: All tests pass (HashUtils, Base58, Bech32, Secp256k1, BIP39, BIP32, Address).

**Step 4: Run in FreeJ2ME-Plus emulator**

```bash
cd .. && make emulator
```

Verify the MIDlet launches (still shows scaffold UI — M1b will add the real screens).

**Step 5: Final commit**

```bash
git commit -m "chore(signer): verify M1a build — all crypto tests pass, JAR within budget"
```

---

## Dependency Graph

```
Task 0: BC + build.xml setup
    |
Task 1: HexCodec + ByteArrayUtils + CryptoError
    |
Task 2: HashUtils (BC integration validation)
    |
    +-- Task 3: Base58 (needs HashUtils)
    |
    +-- Task 4: Bech32 (independent)
    |
Task 5: NetworkParams + Secp256k1 (needs BC)
    |
Task 6: BIP39 (needs HashUtils, wordlist)
    |
Task 7: BIP32 (needs HashUtils, Secp256k1, Base58)
    |
Task 8: BIP44/84 + BitcoinAddress (needs everything)
    |
Task 9: Cross-impl vectors
    |
Task 10: Full build verification
```

Tasks 3 and 4 can run in parallel after Task 2.
