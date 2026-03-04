/*
 * QR Code generator library — Java 1.4 port for CLDC 1.1.
 *
 * Original by Project Nayuki (MIT License).
 * https://www.nayuki.io/page/qr-code-generator-library
 *
 * Ported to Java 1.4 (no generics, no enums, no regex, no CharSequence)
 * for the Burner Wallet signer running on Nokia C1-01 (CLDC 1.1 / MIDP 2.0).
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
 * A segment of character/binary/control data in a QR Code symbol.
 * Instances of this class are immutable.
 *
 * <p>Java 1.4 compatible (CLDC 1.1). Enums replaced with static final int
 * constants; generics and regex removed.</p>
 */
public final class QrSegment {

    /*---- Mode constants (replaces Mode enum) ----*/

    /** Numeric mode indicator. */
    public static final int MODE_NUMERIC      = 0x1;
    /** Alphanumeric mode indicator. */
    public static final int MODE_ALPHANUMERIC = 0x2;
    /** Byte mode indicator. */
    public static final int MODE_BYTE         = 0x4;
    /** Kanji mode indicator. */
    public static final int MODE_KANJI        = 0x8;
    /** ECI mode indicator. */
    public static final int MODE_ECI          = 0x7;

    // Character count bit widths for each mode, indexed by mode-to-index mapping.
    // Each row has 3 values for version ranges: [1-9], [10-26], [27-40].
    private static final int[][] MODE_CC_BITS = {
        {10, 12, 14}, // NUMERIC      (index 0)
        { 9, 11, 13}, // ALPHANUMERIC (index 1)
        { 8, 16, 16}, // BYTE         (index 2)
        { 8, 10, 12}, // KANJI        (index 3)
        { 0,  0,  0}, // ECI          (index 4)
    };

    /** Maps a mode constant to an internal index for MODE_CC_BITS lookup. */
    static int modeIndex(int mode) {
        switch (mode) {
            case MODE_NUMERIC:      return 0;
            case MODE_ALPHANUMERIC: return 1;
            case MODE_BYTE:         return 2;
            case MODE_KANJI:        return 3;
            case MODE_ECI:          return 4;
            default: throw new IllegalArgumentException("Invalid mode");
        }
    }

    /**
     * Returns the bit width of the character count field for a segment
     * in the given mode at the given QR Code version.
     */
    static int numCharCountBits(int mode, int ver) {
        int idx = modeIndex(mode);
        int rangeIdx = (ver + 7) / 17;
        return MODE_CC_BITS[idx][rangeIdx];
    }

    // The set of all legal characters in alphanumeric mode.
    static final String ALPHANUMERIC_CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";


    /*---- Static factory functions ----*/

    /**
     * Returns a segment representing the specified binary data
     * encoded in byte mode. All input byte arrays are acceptable.
     */
    public static QrSegment makeBytes(byte[] data) {
        if (data == null) {
            throw new NullPointerException();
        }
        BitBuffer bb = new BitBuffer();
        for (int i = 0; i < data.length; i++) {
            bb.appendBits(data[i] & 0xFF, 8);
        }
        return new QrSegment(MODE_BYTE, data.length, bb);
    }

    /**
     * Returns a segment representing the specified string of decimal digits
     * encoded in numeric mode.
     */
    public static QrSegment makeNumeric(String digits) {
        if (digits == null) {
            throw new NullPointerException();
        }
        if (!isNumeric(digits)) {
            throw new IllegalArgumentException("String contains non-numeric characters");
        }
        BitBuffer bb = new BitBuffer();
        int i = 0;
        while (i < digits.length()) {
            int n = digits.length() - i;
            if (n > 3) {
                n = 3;
            }
            int val = Integer.parseInt(digits.substring(i, i + n));
            bb.appendBits(val, n * 3 + 1);
            i += n;
        }
        return new QrSegment(MODE_NUMERIC, digits.length(), bb);
    }

    /**
     * Returns a segment representing the specified text string
     * encoded in alphanumeric mode.
     */
    public static QrSegment makeAlphanumeric(String text) {
        if (text == null) {
            throw new NullPointerException();
        }
        if (!isAlphanumeric(text)) {
            throw new IllegalArgumentException("String contains unencodable characters in alphanumeric mode");
        }
        BitBuffer bb = new BitBuffer();
        int i;
        for (i = 0; i <= text.length() - 2; i += 2) {
            int temp = ALPHANUMERIC_CHARSET.indexOf(text.charAt(i)) * 45;
            temp += ALPHANUMERIC_CHARSET.indexOf(text.charAt(i + 1));
            bb.appendBits(temp, 11);
        }
        if (i < text.length()) {
            bb.appendBits(ALPHANUMERIC_CHARSET.indexOf(text.charAt(i)), 6);
        }
        return new QrSegment(MODE_ALPHANUMERIC, text.length(), bb);
    }

    /**
     * Returns an array of segments to represent the specified text string.
     * Selects the most efficient encoding mode automatically.
     */
    public static QrSegment[] makeSegments(String text) {
        if (text == null) {
            throw new NullPointerException();
        }
        if (text.length() == 0) {
            return new QrSegment[0];
        } else if (isNumeric(text)) {
            return new QrSegment[]{ makeNumeric(text) };
        } else if (isAlphanumeric(text)) {
            return new QrSegment[]{ makeAlphanumeric(text) };
        } else {
            byte[] bytes;
            try {
                bytes = text.getBytes("UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                // UTF-8 is always available; fallback to platform default
                bytes = text.getBytes();
            }
            return new QrSegment[]{ makeBytes(bytes) };
        }
    }

    /** Tests whether the specified string can be encoded in numeric mode. */
    public static boolean isNumeric(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** Tests whether the specified string can be encoded in alphanumeric mode. */
    public static boolean isAlphanumeric(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (ALPHANUMERIC_CHARSET.indexOf(text.charAt(i)) == -1) {
                return false;
            }
        }
        return true;
    }


    /*---- Instance fields ----*/

    /** The mode indicator of this segment. */
    public final int mode;

    /** The length of this segment's unencoded data. */
    public final int numChars;

    /** The data bits of this segment (package-private). */
    final BitBuffer data;


    /*---- Constructor ----*/

    /**
     * Constructs a QR Code segment with the specified attributes and data.
     *
     * @param md the mode constant
     * @param numCh the data length in characters or bytes (non-negative)
     * @param dat the data bits (not null; defensively copied)
     */
    public QrSegment(int md, int numCh, BitBuffer dat) {
        if (dat == null) {
            throw new NullPointerException();
        }
        // Validate mode
        modeIndex(md);
        if (numCh < 0) {
            throw new IllegalArgumentException("Invalid value");
        }
        mode = md;
        numChars = numCh;
        data = dat.copy();
    }


    /*---- Package methods ----*/

    /**
     * Calculates the number of bits needed to encode the given segments
     * at the given version. Returns -1 if a segment has too many characters
     * to fit its length field, or the total bits would overflow.
     */
    static int getTotalBits(QrSegment[] segs, int version) {
        if (segs == null) {
            throw new NullPointerException();
        }
        long result = 0;
        for (int i = 0; i < segs.length; i++) {
            QrSegment seg = segs[i];
            if (seg == null) {
                throw new NullPointerException();
            }
            int ccbits = numCharCountBits(seg.mode, version);
            if (seg.numChars >= (1 << ccbits)) {
                return -1;
            }
            result += 4L + ccbits + seg.data.bitLength();
            if (result > Integer.MAX_VALUE) {
                return -1;
            }
        }
        return (int) result;
    }
}
