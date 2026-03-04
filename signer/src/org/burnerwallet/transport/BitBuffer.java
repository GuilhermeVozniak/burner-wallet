/*
 * QR Code generator library — Java 1.4 port for CLDC 1.1.
 *
 * Original by Project Nayuki (MIT License).
 * https://www.nayuki.io/page/qr-code-generator-library
 *
 * Ported to Java 1.4 (no generics, no enums, no BitSet) for the
 * Burner Wallet signer running on Nokia C1-01 (CLDC 1.1 / MIDP 2.0).
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 * - The above copyright notice and this permission notice shall be included in
 *   all copies or substantial portions of the Software.
 * - The Software is provided "as is", without warranty of any kind, express or
 *   implied, including but not limited to the warranties of merchantability,
 *   fitness for a particular purpose and noninfringement. In no event shall the
 *   authors or copyright holders be liable for any claim, damages or other
 *   liability, whether in an action of contract, tort or otherwise, arising from,
 *   out of or in connection with the Software or the use or other dealings in the
 *   Software.
 */

package org.burnerwallet.transport;

/**
 * An appendable sequence of bits (0s and 1s), backed by an int array.
 * Replaces the original Nayuki BitBuffer which used java.util.BitSet
 * (not available in CLDC 1.1).
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
final class BitBuffer {

    private int[] data;   // Packed bits, 32 per element
    private int bitLength; // Number of valid bits

    /** Constructs an empty bit buffer. */
    BitBuffer() {
        data = new int[64]; // Initial capacity: 64 * 32 = 2048 bits
        bitLength = 0;
    }

    /** Returns the number of bits in the buffer. */
    int bitLength() {
        return bitLength;
    }

    /** Returns the bit at the given index (0 or 1). */
    int getBit(int index) {
        if (index < 0 || index >= bitLength) {
            throw new IndexOutOfBoundsException();
        }
        return (data[index >>> 5] >>> (31 - (index & 31))) & 1;
    }

    /**
     * Appends the specified number of low-order bits of val.
     * Requires 0 <= len <= 31 and 0 <= val < 2^len.
     */
    void appendBits(int val, int len) {
        if (len < 0 || len > 31 || (val >>> len) != 0) {
            throw new IllegalArgumentException("Value out of range");
        }
        ensureCapacity(bitLength + len);
        for (int i = len - 1; i >= 0; i--) {
            int bit = (val >>> i) & 1;
            if (bit != 0) {
                data[bitLength >>> 5] |= (1 << (31 - (bitLength & 31)));
            }
            bitLength++;
        }
    }

    /** Appends all bits from another BitBuffer. */
    void appendData(BitBuffer bb) {
        if (bb == null) {
            throw new NullPointerException();
        }
        ensureCapacity(bitLength + bb.bitLength);
        for (int i = 0; i < bb.bitLength; i++) {
            int bit = bb.getBit(i);
            if (bit != 0) {
                data[bitLength >>> 5] |= (1 << (31 - (bitLength & 31)));
            }
            bitLength++;
        }
    }

    /** Returns a deep copy of this buffer. */
    BitBuffer copy() {
        BitBuffer result = new BitBuffer();
        result.data = new int[this.data.length];
        System.arraycopy(this.data, 0, result.data, 0, this.data.length);
        result.bitLength = this.bitLength;
        return result;
    }

    /** Ensures the int[] can hold at least minBits bits. */
    private void ensureCapacity(int minBits) {
        int minInts = (minBits + 31) >>> 5;
        if (minInts > data.length) {
            int newLen = data.length;
            while (newLen < minInts) {
                newLen = newLen * 2;
            }
            int[] newData = new int[newLen];
            System.arraycopy(data, 0, newData, 0, data.length);
            data = newData;
        }
    }
}
