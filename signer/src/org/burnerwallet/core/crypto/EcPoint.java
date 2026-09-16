package org.burnerwallet.core.crypto;

/**
 * A point on secp256k1 (y^2 = x^3 + 7 over F_p) in Jacobian coordinates.
 *
 * Doubling uses the a = 0 formulas (dbl-2009-l) and addition the general
 * add-2007-bl formulas. Scalar multiplication is a Montgomery ladder: one
 * addition and one doubling per bit, independent of the scalar's bits, so a
 * private key does not shape the operation sequence. Points are immutable.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public final class EcPoint {

    /** The point at infinity (Z = 0). */
    public static final EcPoint INFINITY = new EcPoint(Fe.one(), Fe.one(), Fe.zero());

    /** The generator G. */
    public static final EcPoint G = new EcPoint(
        new int[] {0x16F81798, 0x59F2815B, 0x2DCE28D9, 0x029BFCDB,
                   0xCE870B07, 0x55A06295, 0xF9DCBBAC, 0x79BE667E},
        new int[] {0xFB10D4B8, 0x9C47D08F, 0xA6855419, 0xFD17B448,
                   0x0E1108A8, 0x5DA4FBFC, 0x26A3C465, 0x483ADA77},
        Fe.one());

    private static final int[] SEVEN = {7, 0, 0, 0, 0, 0, 0, 0};

    private final int[] x;
    private final int[] y;
    private final int[] z;

    private EcPoint(int[] x, int[] y, int[] z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** @return true for the point at infinity */
    public boolean isInfinity() {
        return Fe.isZero(z);
    }

    /**
     * Double this point.
     *
     * @return 2P
     */
    public EcPoint twice() {
        if (isInfinity() || Fe.isZero(y)) {
            return INFINITY;
        }
        Fe.Modulus p = Fe.P;
        int[] a = Fe.sqr(x, p);
        int[] b = Fe.sqr(y, p);
        int[] c = Fe.sqr(b, p);
        int[] d = Fe.sub(Fe.sub(Fe.sqr(Fe.add(x, b, p), p), a, p), c, p);
        d = Fe.add(d, d, p);
        int[] e = Fe.add(Fe.add(a, a, p), a, p);
        int[] f = Fe.sqr(e, p);
        int[] x3 = Fe.sub(f, Fe.add(d, d, p), p);
        int[] c8 = Fe.add(c, c, p);
        c8 = Fe.add(c8, c8, p);
        c8 = Fe.add(c8, c8, p);
        int[] y3 = Fe.sub(Fe.mul(e, Fe.sub(d, x3, p), p), c8, p);
        int[] z3 = Fe.mul(y, z, p);
        z3 = Fe.add(z3, z3, p);
        return new EcPoint(x3, y3, z3);
    }

    /**
     * Add another point.
     *
     * @param q the other point
     * @return P + Q
     */
    public EcPoint add(EcPoint q) {
        if (isInfinity()) {
            return q;
        }
        if (q.isInfinity()) {
            return this;
        }
        Fe.Modulus p = Fe.P;
        int[] z1z1 = Fe.sqr(z, p);
        int[] z2z2 = Fe.sqr(q.z, p);
        int[] u1 = Fe.mul(x, z2z2, p);
        int[] u2 = Fe.mul(q.x, z1z1, p);
        int[] s1 = Fe.mul(Fe.mul(y, q.z, p), z2z2, p);
        int[] s2 = Fe.mul(Fe.mul(q.y, z, p), z1z1, p);
        int[] h = Fe.sub(u2, u1, p);
        int[] r = Fe.sub(s2, s1, p);
        if (Fe.isZero(h)) {
            if (Fe.isZero(r)) {
                return twice();
            }
            return INFINITY;
        }
        int[] i = Fe.sqr(Fe.add(h, h, p), p);
        int[] j = Fe.mul(h, i, p);
        r = Fe.add(r, r, p);
        int[] v = Fe.mul(u1, i, p);
        int[] x3 = Fe.sub(Fe.sub(Fe.sqr(r, p), j, p), Fe.add(v, v, p), p);
        int[] y3 = Fe.sub(Fe.mul(r, Fe.sub(v, x3, p), p), Fe.mul(Fe.add(s1, s1, p), j, p), p);
        int[] z3 = Fe.mul(Fe.sub(Fe.sub(Fe.sqr(Fe.add(z, q.z, p), p), z1z1, p), z2z2, p), h, p);
        return new EcPoint(x3, y3, z3);
    }

    /**
     * Scalar multiplication by a Montgomery ladder.
     *
     * @param k scalar limbs (any 256-bit value)
     * @return kP
     */
    public EcPoint multiply(int[] k) {
        EcPoint r0 = INFINITY;
        EcPoint r1 = this;
        for (int i = 255; i >= 0; i--) {
            int bit = (k[i >>> 5] >>> (i & 31)) & 1;
            if (bit == 0) {
                r1 = r0.add(r1);
                r0 = r0.twice();
            } else {
                r0 = r0.add(r1);
                r1 = r1.twice();
            }
        }
        return r0;
    }

    /**
     * Convert to affine coordinates (Z = 1).
     *
     * @return the normalized point (or infinity)
     */
    public EcPoint normalize() {
        if (isInfinity()) {
            return INFINITY;
        }
        if (Fe.equals(z, Fe.one())) {
            return this;
        }
        Fe.Modulus p = Fe.P;
        int[] zi = Fe.inv(z, p);
        int[] zi2 = Fe.sqr(zi, p);
        int[] zi3 = Fe.mul(zi2, zi, p);
        return new EcPoint(Fe.mul(x, zi2, p), Fe.mul(y, zi3, p), Fe.one());
    }

    /** @return affine x limbs (the point must be normalized) */
    public int[] getX() {
        return x;
    }

    /** @return affine y limbs (the point must be normalized) */
    public int[] getY() {
        return y;
    }

    /**
     * SEC1 compressed encoding.
     *
     * @return 33 bytes: 0x02/0x03 prefix followed by big-endian x
     */
    public byte[] encodeCompressed() {
        EcPoint a = normalize();
        if (a.isInfinity()) {
            throw new IllegalStateException("cannot encode the point at infinity");
        }
        byte[] out = new byte[33];
        out[0] = (byte) (Fe.isOdd(a.y) ? 0x03 : 0x02);
        System.arraycopy(Fe.toBytes(a.x), 0, out, 1, 32);
        return out;
    }

    /**
     * Decode a SEC1 compressed point and check that it lies on the curve.
     *
     * @param enc 33 bytes
     * @return the point
     * @throws IllegalArgumentException if the encoding is not a valid curve point
     */
    public static EcPoint decodeCompressed(byte[] enc) {
        if (enc == null || enc.length != 33 || (enc[0] != 0x02 && enc[0] != 0x03)) {
            throw new IllegalArgumentException("not a compressed secp256k1 point");
        }
        Fe.Modulus p = Fe.P;
        int[] px = Fe.fromBytes(enc, 1);
        if (Fe.compare(px, p.value) >= 0) {
            throw new IllegalArgumentException("x coordinate out of range");
        }
        int[] rhs = Fe.add(Fe.mul(Fe.sqr(px, p), px, p), SEVEN, p);
        int[] py = Fe.sqrtP(rhs);
        if (!Fe.equals(Fe.sqr(py, p), rhs)) {
            throw new IllegalArgumentException("point is not on the curve");
        }
        boolean wantOdd = enc[0] == 0x03;
        if (Fe.isOdd(py) != wantOdd) {
            py = Fe.neg(py, p);
        }
        return new EcPoint(px, py, Fe.one());
    }

    /**
     * Build a point from affine coordinates (no curve check).
     *
     * @param ax affine x limbs
     * @param ay affine y limbs
     * @return the point
     */
    public static EcPoint fromAffine(int[] ax, int[] ay) {
        return new EcPoint(Fe.copy(ax), Fe.copy(ay), Fe.one());
    }

    /**
     * @param o another point
     * @return true if both represent the same curve point
     */
    public boolean equalsPoint(EcPoint o) {
        EcPoint a = normalize();
        EcPoint b = o.normalize();
        if (a.isInfinity() || b.isInfinity()) {
            return a.isInfinity() && b.isInfinity();
        }
        return Fe.equals(a.x, b.x) && Fe.equals(a.y, b.y);
    }
}
