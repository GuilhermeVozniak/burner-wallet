package org.burnerwallet.core.crypto;

/**
 * Fixed-size (256-bit) modular arithmetic for the secp256k1 field prime p
 * and group order n, without java.math.BigInteger (absent on CLDC 1.1).
 *
 * Values are unsigned 256-bit integers stored as eight little-endian 32-bit
 * limbs ({@code int[8]}, limb 0 least significant). Both moduli have the
 * form {@code 2^256 - c} with a small {@code c}, so products are reduced with
 * the identity {@code 2^256 = c (mod m)}. Inversion uses Fermat's theorem
 * ({@code a^(m-2)}), which keeps the code small and free of data-dependent
 * branches on the secret value.
 *
 * All inputs to the modular operations must already be smaller than the
 * modulus; callers reduce raw bytes with {@link #fromBytesMod(byte[], Modulus)}.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public final class Fe {

    /** Number of 32-bit limbs in a value. */
    public static final int LIMBS = 8;

    private static final long MASK = 0xffffffffL;

    /** A modulus of the form 2^256 - c. */
    public static final class Modulus {
        final int[] value;
        final int[] c;
        final int[] expInverse;

        Modulus(int[] value, int[] c) {
            this.value = value;
            this.c = c;
            this.expInverse = subSmall(value, 2);
        }

        /** @return the modulus as 32 big-endian bytes */
        public byte[] toBytes() {
            return Fe.toBytes(value);
        }
    }

    /** The field prime p = 2^256 - 2^32 - 977. */
    public static final Modulus P = new Modulus(
        new int[] {0xFFFFFC2F, 0xFFFFFFFE, 0xFFFFFFFF, 0xFFFFFFFF,
                   0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF},
        new int[] {0x000003D1, 0x00000001});

    /** The group order n. */
    public static final Modulus N = new Modulus(
        new int[] {0xD0364141, 0xBFD25E8C, 0xAF48A03B, 0xBAAEDCE6,
                   0xFFFFFFFE, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF},
        new int[] {0x2FC9BEBF, 0x402DA173, 0x50B75FC4, 0x45512319, 0x00000001});

    /** (p + 1) / 4, the exponent that computes square roots mod p. */
    private static final int[] SQRT_EXP = {
        0xBFFFFF0C, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
        0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x3FFFFFFF
    };

    /** (n - 1) / 2, the low-S threshold. */
    private static final int[] HALF_N = shiftRight1(N.value);

    private Fe() {
        // static only
    }

    // ---- construction and conversion ----

    /** @return a fresh zero */
    public static int[] zero() {
        return new int[LIMBS];
    }

    /** @return a fresh one */
    public static int[] one() {
        int[] r = new int[LIMBS];
        r[0] = 1;
        return r;
    }

    /**
     * Parse 32 big-endian bytes without reduction.
     *
     * @param b   buffer
     * @param off offset of the first (most significant) byte
     * @return limbs
     */
    public static int[] fromBytes(byte[] b, int off) {
        int[] r = new int[LIMBS];
        for (int i = 0; i < LIMBS; i++) {
            int j = off + 28 - 4 * i;
            r[i] = ((b[j] & 0xff) << 24) | ((b[j + 1] & 0xff) << 16)
                 | ((b[j + 2] & 0xff) << 8) | (b[j + 3] & 0xff);
        }
        return r;
    }

    /**
     * Parse 32 big-endian bytes and reduce once modulo {@code m}
     * (a 256-bit value is always below 2m for both moduli).
     *
     * @param b32 exactly 32 bytes
     * @param m   modulus
     * @return limbs below m
     */
    public static int[] fromBytesMod(byte[] b32, Modulus m) {
        int[] r = fromBytes(b32, 0);
        if (compare(r, m.value) >= 0) {
            subRaw(r, m.value, r);
        }
        return r;
    }

    /**
     * @param a limbs
     * @return 32 big-endian bytes
     */
    public static byte[] toBytes(int[] a) {
        byte[] out = new byte[32];
        for (int i = 0; i < LIMBS; i++) {
            int j = 28 - 4 * i;
            out[j] = (byte) (a[i] >>> 24);
            out[j + 1] = (byte) (a[i] >>> 16);
            out[j + 2] = (byte) (a[i] >>> 8);
            out[j + 3] = (byte) a[i];
        }
        return out;
    }

    /**
     * @param a limbs
     * @return a copy
     */
    public static int[] copy(int[] a) {
        int[] r = new int[LIMBS];
        System.arraycopy(a, 0, r, 0, LIMBS);
        return r;
    }

    /**
     * Zero a value in place.
     *
     * @param a limbs to wipe
     */
    public static void wipe(int[] a) {
        if (a != null) {
            for (int i = 0; i < a.length; i++) {
                a[i] = 0;
            }
        }
    }

    // ---- comparison ----

    /**
     * @param a limbs
     * @return true if every limb is zero
     */
    public static boolean isZero(int[] a) {
        int acc = 0;
        for (int i = 0; i < LIMBS; i++) {
            acc |= a[i];
        }
        return acc == 0;
    }

    /**
     * @param a limbs
     * @return true if the value is odd
     */
    public static boolean isOdd(int[] a) {
        return (a[0] & 1) != 0;
    }

    /**
     * Unsigned comparison.
     *
     * @param a limbs
     * @param b limbs
     * @return negative, zero or positive as a is below, equal to or above b
     */
    public static int compare(int[] a, int[] b) {
        for (int i = LIMBS - 1; i >= 0; i--) {
            long x = a[i] & MASK;
            long y = b[i] & MASK;
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        return 0;
    }

    /**
     * @param a limbs
     * @param b limbs
     * @return true if equal
     */
    public static boolean equals(int[] a, int[] b) {
        return compare(a, b) == 0;
    }

    /**
     * @param a limbs
     * @param m modulus
     * @return true if a is strictly below m
     */
    public static boolean isBelow(int[] a, Modulus m) {
        return compare(a, m.value) < 0;
    }

    /**
     * @param s a scalar below n
     * @return true if s is greater than (n - 1) / 2
     */
    public static boolean isHighS(int[] s) {
        return compare(s, HALF_N) > 0;
    }

    // ---- modular arithmetic (inputs below the modulus) ----

    /**
     * @param a limbs below m
     * @param b limbs below m
     * @param m modulus
     * @return (a + b) mod m
     */
    public static int[] add(int[] a, int[] b, Modulus m) {
        int[] r = new int[LIMBS];
        int carry = addRaw(a, b, r);
        if (carry != 0 || compare(r, m.value) >= 0) {
            subRaw(r, m.value, r);
        }
        return r;
    }

    /**
     * @param a limbs below m
     * @param b limbs below m
     * @param m modulus
     * @return (a - b) mod m
     */
    public static int[] sub(int[] a, int[] b, Modulus m) {
        int[] r = new int[LIMBS];
        int borrow = subRaw(a, b, r);
        if (borrow != 0) {
            addRaw(r, m.value, r);
        }
        return r;
    }

    /**
     * @param a limbs below m
     * @param m modulus
     * @return (-a) mod m
     */
    public static int[] neg(int[] a, Modulus m) {
        return sub(zero(), a, m);
    }

    /**
     * @param a limbs below m
     * @param b limbs below m
     * @param m modulus
     * @return (a * b) mod m
     */
    public static int[] mul(int[] a, int[] b, Modulus m) {
        int[] t = new int[2 * LIMBS];
        for (int i = 0; i < LIMBS; i++) {
            long ai = a[i] & MASK;
            long carry = 0;
            for (int j = 0; j < LIMBS; j++) {
                long p = ai * (b[j] & MASK) + (t[i + j] & MASK) + carry;
                t[i + j] = (int) p;
                carry = p >>> 32;
            }
            t[i + LIMBS] = (int) carry;
        }
        return reduce(t, m);
    }

    /**
     * @param a limbs below m
     * @param m modulus
     * @return a^2 mod m
     */
    public static int[] sqr(int[] a, Modulus m) {
        return mul(a, a, m);
    }

    /**
     * Square-and-multiply exponentiation, most significant bit first.
     *
     * @param base limbs below m
     * @param exp  256-bit exponent (limbs)
     * @param m    modulus
     * @return base^exp mod m
     */
    public static int[] pow(int[] base, int[] exp, Modulus m) {
        int[] result = one();
        for (int i = 255; i >= 0; i--) {
            result = mul(result, result, m);
            if (((exp[i >>> 5] >>> (i & 31)) & 1) != 0) {
                result = mul(result, base, m);
            }
        }
        return result;
    }

    /**
     * @param a non-zero limbs below m
     * @param m modulus (prime)
     * @return a^-1 mod m
     */
    public static int[] inv(int[] a, Modulus m) {
        return pow(a, m.expInverse, m);
    }

    /**
     * Square root modulo p (p = 3 mod 4). The caller must verify the result
     * by squaring it, since not every element is a quadratic residue.
     *
     * @param a limbs below p
     * @return a^((p+1)/4) mod p
     */
    public static int[] sqrtP(int[] a) {
        return pow(a, SQRT_EXP, P);
    }

    // ---- raw multi-limb helpers ----

    /** a + b -> r (8 limbs); returns the carry out. r may alias a. */
    static int addRaw(int[] a, int[] b, int[] r) {
        long carry = 0;
        for (int i = 0; i < LIMBS; i++) {
            long s = (a[i] & MASK) + (b[i] & MASK) + carry;
            r[i] = (int) s;
            carry = s >>> 32;
        }
        return (int) carry;
    }

    /** a - b -> r (8 limbs); returns the borrow out. r may alias a. */
    static int subRaw(int[] a, int[] b, int[] r) {
        long borrow = 0;
        for (int i = 0; i < LIMBS; i++) {
            long d = (a[i] & MASK) - (b[i] & MASK) - borrow;
            r[i] = (int) d;
            borrow = (d >> 63) & 1;
        }
        return (int) borrow;
    }

    private static int[] subSmall(int[] a, int k) {
        int[] r = new int[LIMBS];
        long borrow = k;
        for (int i = 0; i < LIMBS; i++) {
            long d = (a[i] & MASK) - borrow;
            r[i] = (int) d;
            borrow = (d >> 63) & 1;
        }
        return r;
    }

    private static int[] shiftRight1(int[] a) {
        int[] r = new int[LIMBS];
        for (int i = 0; i < LIMBS; i++) {
            int hi = i + 1 < LIMBS ? a[i + 1] << 31 : 0;
            r[i] = (a[i] >>> 1) | hi;
        }
        return r;
    }

    /** Schoolbook product of two little-endian limb arrays. */
    private static int[] mulLimbs(int[] a, int aLen, int[] b, int bLen) {
        int[] t = new int[aLen + bLen];
        for (int i = 0; i < aLen; i++) {
            long ai = a[i] & MASK;
            long carry = 0;
            for (int j = 0; j < bLen; j++) {
                long p = ai * (b[j] & MASK) + (t[i + j] & MASK) + carry;
                t[i + j] = (int) p;
                carry = p >>> 32;
            }
            t[i + bLen] = (int) carry;
        }
        return t;
    }

    /** Sum of two little-endian limb arrays, one limb longer than the longest input. */
    private static int[] addLimbs(int[] a, int aLen, int[] b, int bLen) {
        int n = aLen > bLen ? aLen : bLen;
        int[] r = new int[n + 1];
        long carry = 0;
        for (int i = 0; i < n; i++) {
            long s = carry;
            if (i < aLen) {
                s += a[i] & MASK;
            }
            if (i < bLen) {
                s += b[i] & MASK;
            }
            r[i] = (int) s;
            carry = s >>> 32;
        }
        r[n] = (int) carry;
        return r;
    }

    /**
     * Reduce a 16-limb product modulo m using 2^256 = c (mod m), then
     * conditional subtraction.
     */
    private static int[] reduce(int[] t, Modulus m) {
        int[] cur = t;
        int curLen = t.length;
        while (true) {
            int hiLen = curLen - LIMBS;
            while (hiLen > 0 && cur[LIMBS + hiLen - 1] == 0) {
                hiLen--;
            }
            if (hiLen <= 0) {
                break;
            }
            int[] hi = new int[hiLen];
            System.arraycopy(cur, LIMBS, hi, 0, hiLen);
            int[] prod = mulLimbs(hi, hiLen, m.c, m.c.length);
            cur = addLimbs(cur, LIMBS, prod, prod.length);
            curLen = cur.length;
        }
        int[] r = new int[LIMBS];
        System.arraycopy(cur, 0, r, 0, LIMBS);
        while (compare(r, m.value) >= 0) {
            subRaw(r, m.value, r);
        }
        return r;
    }
}
