/*
 * QR Code generator library — Java 1.4 port for CLDC 1.1.
 *
 * Original by Project Nayuki (MIT License).
 * https://www.nayuki.io/page/qr-code-generator-library
 *
 * Ported to Java 1.4 (no generics, no enums, no assert, no Arrays,
 * no Objects, no String.format) for the Burner Wallet signer running
 * on Nokia C1-01 (CLDC 1.1 / MIDP 2.0).
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
 * A QR Code symbol, which is a type of two-dimension barcode.
 * Invented by Denso Wave and described in the ISO/IEC 18004 standard.
 *
 * <p>Instances of this class represent an immutable square grid of dark and light cells.
 * The class provides static factory functions to create a QR Code from text or binary data.</p>
 *
 * <p>Java 1.4 compatible (CLDC 1.1). Enums replaced with static final int
 * constants; generics, assert, and Java 5+ APIs removed.</p>
 */
public final class QrCode {

    /*---- ECC level constants (replaces Ecc enum) ----*/

    /** Error correction level: ~7% recovery. */
    public static final int ECC_LOW      = 0;
    /** Error correction level: ~15% recovery. */
    public static final int ECC_MEDIUM   = 1;
    /** Error correction level: ~25% recovery. */
    public static final int ECC_QUARTILE = 2;
    /** Error correction level: ~30% recovery. */
    public static final int ECC_HIGH     = 3;

    /**
     * Format bits for each ECC level.
     * Index by ECC_LOW..ECC_HIGH.
     */
    static final int[] ECC_FORMAT_BITS = {1, 0, 3, 2};

    // Number of ECC levels
    private static final int NUM_ECC_LEVELS = 4;


    /*---- Static factory functions (high level) ----*/

    /**
     * Returns a QR Code representing the specified Unicode text string
     * at the specified error correction level.
     *
     * @param text the text to encode (not null)
     * @param ecl the error correction level (ECC_LOW..ECC_HIGH)
     * @return a QR Code representing the text
     */
    public static QrCode encodeText(String text, int ecl) {
        if (text == null) {
            throw new NullPointerException();
        }
        validateEcl(ecl);
        QrSegment[] segs = QrSegment.makeSegments(text);
        return encodeSegments(segs, ecl);
    }

    /**
     * Returns a QR Code representing the specified binary data
     * at the specified error correction level.
     *
     * @param data the binary data to encode (not null)
     * @param ecl the error correction level (ECC_LOW..ECC_HIGH)
     * @return a QR Code representing the data
     */
    public static QrCode encodeBinary(byte[] data, int ecl) {
        if (data == null) {
            throw new NullPointerException();
        }
        validateEcl(ecl);
        QrSegment seg = QrSegment.makeBytes(data);
        return encodeSegments(new QrSegment[]{ seg }, ecl);
    }


    /*---- Static factory functions (mid level) ----*/

    /**
     * Returns a QR Code representing the specified segments
     * at the specified error correction level.
     */
    public static QrCode encodeSegments(QrSegment[] segs, int ecl) {
        return encodeSegments(segs, ecl, MIN_VERSION, MAX_VERSION, -1, true);
    }

    /**
     * Returns a QR Code representing the specified segments with the
     * specified encoding parameters.
     *
     * @param segs the segments to encode
     * @param ecl the error correction level (ECC_LOW..ECC_HIGH)
     * @param minVersion minimum QR version (1..40)
     * @param maxVersion maximum QR version (1..40)
     * @param mask mask pattern (-1 for auto, 0..7 for fixed)
     * @param boostEcl whether to boost ECC level if data fits
     * @return a QR Code representing the segments
     */
    public static QrCode encodeSegments(QrSegment[] segs, int ecl,
            int minVersion, int maxVersion, int mask, boolean boostEcl) {
        if (segs == null) {
            throw new NullPointerException();
        }
        validateEcl(ecl);
        if (!(MIN_VERSION <= minVersion && minVersion <= maxVersion
                && maxVersion <= MAX_VERSION) || mask < -1 || mask > 7) {
            throw new IllegalArgumentException("Invalid value");
        }

        // Find the minimal version number to use
        int version, dataUsedBits;
        for (version = minVersion; ; version++) {
            int dataCapacityBits = getNumDataCodewords(version, ecl) * 8;
            dataUsedBits = QrSegment.getTotalBits(segs, version);
            if (dataUsedBits != -1 && dataUsedBits <= dataCapacityBits) {
                break;
            }
            if (version >= maxVersion) {
                String msg = "Segment too long";
                if (dataUsedBits != -1) {
                    msg = "Data length = " + dataUsedBits + " bits, Max capacity = "
                        + dataCapacityBits + " bits";
                }
                throw new IllegalArgumentException(msg);
            }
        }

        // Increase the error correction level while the data still fits
        for (int newEcl = 0; newEcl < NUM_ECC_LEVELS; newEcl++) {
            if (boostEcl && dataUsedBits <= getNumDataCodewords(version, newEcl) * 8) {
                ecl = newEcl;
            }
        }

        // Concatenate all segments to create the data bit string
        BitBuffer bb = new BitBuffer();
        for (int i = 0; i < segs.length; i++) {
            QrSegment seg = segs[i];
            bb.appendBits(seg.mode, 4);
            bb.appendBits(seg.numChars, QrSegment.numCharCountBits(seg.mode, version));
            bb.appendData(seg.data);
        }

        // Add terminator and pad up to a byte if applicable
        int dataCapacityBits = getNumDataCodewords(version, ecl) * 8;
        int termLen = dataCapacityBits - bb.bitLength();
        if (termLen > 4) {
            termLen = 4;
        }
        bb.appendBits(0, termLen);
        bb.appendBits(0, (8 - bb.bitLength() % 8) % 8);

        // Pad with alternating bytes until data capacity is reached
        for (int padByte = 0xEC; bb.bitLength() < dataCapacityBits; padByte ^= (0xEC ^ 0x11)) {
            bb.appendBits(padByte, 8);
        }

        // Pack bits into bytes in big endian
        byte[] dataCodewords = new byte[bb.bitLength() / 8];
        for (int i = 0; i < bb.bitLength(); i++) {
            dataCodewords[i >>> 3] |= (byte)(bb.getBit(i) << (7 - (i & 7)));
        }

        // Create the QR Code object
        return new QrCode(version, ecl, dataCodewords, mask);
    }


    /*---- Instance fields ----*/

    /** The version number of this QR Code (1..40). */
    public final int version;

    /** The width and height of this QR Code in modules (21..177). */
    public final int size;

    /** The error correction level used (ECC_LOW..ECC_HIGH). */
    public final int errorCorrectionLevel;

    /** The mask pattern used (0..7). */
    public final int mask;

    // The modules of this QR Code (false = light, true = dark).
    private boolean[][] modules;

    // Indicates function modules that are not subjected to masking.
    private boolean[][] isFunction;


    /*---- Constructor (low level) ----*/

    /**
     * Constructs a QR Code with the specified version number,
     * error correction level, data codeword bytes, and mask number.
     */
    public QrCode(int ver, int ecl, byte[] dataCodewords, int msk) {
        if (ver < MIN_VERSION || ver > MAX_VERSION) {
            throw new IllegalArgumentException("Version value out of range");
        }
        if (msk < -1 || msk > 7) {
            throw new IllegalArgumentException("Mask value out of range");
        }
        validateEcl(ecl);
        if (dataCodewords == null) {
            throw new NullPointerException();
        }
        version = ver;
        size = ver * 4 + 17;
        errorCorrectionLevel = ecl;
        modules    = new boolean[size][size];
        isFunction = new boolean[size][size];

        // Compute ECC, draw modules, do masking
        drawFunctionPatterns();
        byte[] allCodewords = addEccAndInterleave(dataCodewords);
        drawCodewords(allCodewords);

        // Do masking
        if (msk == -1) {
            int minPenalty = Integer.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                applyMask(i);
                drawFormatBits(i);
                int penalty = getPenaltyScore();
                if (penalty < minPenalty) {
                    msk = i;
                    minPenalty = penalty;
                }
                applyMask(i);  // Undoes the mask due to XOR
            }
        }
        mask = msk;
        applyMask(msk);
        drawFormatBits(msk);

        isFunction = null;
    }


    /*---- Public instance methods ----*/

    /**
     * Returns the color of the module (pixel) at the specified coordinates.
     * Returns false (light) for out-of-bounds coordinates.
     *
     * @param x the x coordinate (0 = left edge)
     * @param y the y coordinate (0 = top edge)
     * @return true if the module is dark, false if light or out of bounds
     */
    public boolean getModule(int x, int y) {
        return 0 <= x && x < size && 0 <= y && y < size && modules[y][x];
    }


    /*---- Private helper methods for constructor: Drawing function modules ----*/

    private void drawFunctionPatterns() {
        // Draw horizontal and vertical timing patterns
        for (int i = 0; i < size; i++) {
            setFunctionModule(6, i, i % 2 == 0);
            setFunctionModule(i, 6, i % 2 == 0);
        }

        // Draw 3 finder patterns (all corners except bottom right)
        drawFinderPattern(3, 3);
        drawFinderPattern(size - 4, 3);
        drawFinderPattern(3, size - 4);

        // Draw alignment patterns
        int[] alignPatPos = getAlignmentPatternPositions();
        int numAlign = alignPatPos.length;
        for (int i = 0; i < numAlign; i++) {
            for (int j = 0; j < numAlign; j++) {
                if (!(i == 0 && j == 0 || i == 0 && j == numAlign - 1
                        || i == numAlign - 1 && j == 0)) {
                    drawAlignmentPattern(alignPatPos[i], alignPatPos[j]);
                }
            }
        }

        // Draw configuration data
        drawFormatBits(0);  // Dummy mask value; overwritten later
        drawVersion();
    }


    private void drawFormatBits(int msk) {
        int data = ECC_FORMAT_BITS[errorCorrectionLevel] << 3 | msk;
        int rem = data;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        }
        int bits = (data << 10 | rem) ^ 0x5412;  // uint15

        // Draw first copy
        for (int i = 0; i <= 5; i++) {
            setFunctionModule(8, i, getBit(bits, i));
        }
        setFunctionModule(8, 7, getBit(bits, 6));
        setFunctionModule(8, 8, getBit(bits, 7));
        setFunctionModule(7, 8, getBit(bits, 8));
        for (int i = 9; i < 15; i++) {
            setFunctionModule(14 - i, 8, getBit(bits, i));
        }

        // Draw second copy
        for (int i = 0; i < 8; i++) {
            setFunctionModule(size - 1 - i, 8, getBit(bits, i));
        }
        for (int i = 8; i < 15; i++) {
            setFunctionModule(8, size - 15 + i, getBit(bits, i));
        }
        setFunctionModule(8, size - 8, true);  // Always dark
    }


    private void drawVersion() {
        if (version < 7) {
            return;
        }

        int rem = version;
        for (int i = 0; i < 12; i++) {
            rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        }
        int bits = version << 12 | rem;  // uint18

        for (int i = 0; i < 18; i++) {
            boolean bit = getBit(bits, i);
            int a = size - 11 + i % 3;
            int b = i / 3;
            setFunctionModule(a, b, bit);
            setFunctionModule(b, a, bit);
        }
    }


    private void drawFinderPattern(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int adx = dx < 0 ? -dx : dx;
                int ady = dy < 0 ? -dy : dy;
                int dist = adx > ady ? adx : ady;  // Chebyshev norm
                int xx = x + dx, yy = y + dy;
                if (0 <= xx && xx < size && 0 <= yy && yy < size) {
                    setFunctionModule(xx, yy, dist != 2 && dist != 4);
                }
            }
        }
    }


    private void drawAlignmentPattern(int x, int y) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int adx = dx < 0 ? -dx : dx;
                int ady = dy < 0 ? -dy : dy;
                int dist = adx > ady ? adx : ady;
                setFunctionModule(x + dx, y + dy, dist != 1);
            }
        }
    }


    private void setFunctionModule(int x, int y, boolean isDark) {
        modules[y][x] = isDark;
        isFunction[y][x] = true;
    }


    /*---- Private helper methods for constructor: Codewords and masking ----*/

    private byte[] addEccAndInterleave(byte[] data) {
        if (data.length != getNumDataCodewords(version, errorCorrectionLevel)) {
            throw new IllegalArgumentException();
        }

        int numBlocks = NUM_ERROR_CORRECTION_BLOCKS[errorCorrectionLevel][version];
        int blockEccLen = ECC_CODEWORDS_PER_BLOCK[errorCorrectionLevel][version];
        int rawCodewords = getNumRawDataModules(version) / 8;
        int numShortBlocks = numBlocks - rawCodewords % numBlocks;
        int shortBlockLen = rawCodewords / numBlocks;

        // Split data into blocks and append ECC to each block
        byte[][] blocks = new byte[numBlocks][];
        byte[] rsDiv = reedSolomonComputeDivisor(blockEccLen);
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int datLen = shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1);
            byte[] dat = new byte[datLen];
            System.arraycopy(data, k, dat, 0, datLen);
            k += datLen;
            byte[] block = new byte[shortBlockLen + 1];
            System.arraycopy(dat, 0, block, 0, datLen);
            byte[] ecc = reedSolomonComputeRemainder(dat, rsDiv);
            System.arraycopy(ecc, 0, block, block.length - blockEccLen, ecc.length);
            blocks[i] = block;
        }

        // Interleave the bytes from every block into a single sequence
        byte[] result = new byte[rawCodewords];
        for (int i = 0, k = 0; i < blocks[0].length; i++) {
            for (int j = 0; j < blocks.length; j++) {
                if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
                    result[k] = blocks[j][i];
                    k++;
                }
            }
        }
        return result;
    }


    private void drawCodewords(byte[] data) {
        if (data.length != getNumRawDataModules(version) / 8) {
            throw new IllegalArgumentException();
        }

        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5;
            }
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = getBit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }


    private void applyMask(int msk) {
        if (msk < 0 || msk > 7) {
            throw new IllegalArgumentException("Mask value out of range");
        }
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean invert;
                switch (msk) {
                    case 0:  invert = (x + y) % 2 == 0;                    break;
                    case 1:  invert = y % 2 == 0;                          break;
                    case 2:  invert = x % 3 == 0;                          break;
                    case 3:  invert = (x + y) % 3 == 0;                    break;
                    case 4:  invert = (x / 3 + y / 2) % 2 == 0;            break;
                    case 5:  invert = x * y % 2 + x * y % 3 == 0;          break;
                    case 6:  invert = (x * y % 2 + x * y % 3) % 2 == 0;    break;
                    case 7:  invert = ((x + y) % 2 + x * y % 3) % 2 == 0;  break;
                    default: throw new IllegalArgumentException("Bad mask");
                }
                if (invert && !isFunction[y][x]) {
                    modules[y][x] = !modules[y][x];
                }
            }
        }
    }


    private int getPenaltyScore() {
        int result = 0;

        // Adjacent modules in row having same color, and finder-like patterns
        for (int y = 0; y < size; y++) {
            boolean runColor = false;
            int runX = 0;
            int[] runHistory = new int[7];
            for (int x = 0; x < size; x++) {
                if (modules[y][x] == runColor) {
                    runX++;
                    if (runX == 5) {
                        result += PENALTY_N1;
                    } else if (runX > 5) {
                        result++;
                    }
                } else {
                    finderPenaltyAddHistory(runX, runHistory);
                    if (!runColor) {
                        result += finderPenaltyCountPatterns(runHistory) * PENALTY_N3;
                    }
                    runColor = modules[y][x];
                    runX = 1;
                }
            }
            result += finderPenaltyTerminateAndCount(runColor, runX, runHistory) * PENALTY_N3;
        }

        // Adjacent modules in column having same color, and finder-like patterns
        for (int x = 0; x < size; x++) {
            boolean runColor = false;
            int runY = 0;
            int[] runHistory = new int[7];
            for (int y = 0; y < size; y++) {
                if (modules[y][x] == runColor) {
                    runY++;
                    if (runY == 5) {
                        result += PENALTY_N1;
                    } else if (runY > 5) {
                        result++;
                    }
                } else {
                    finderPenaltyAddHistory(runY, runHistory);
                    if (!runColor) {
                        result += finderPenaltyCountPatterns(runHistory) * PENALTY_N3;
                    }
                    runColor = modules[y][x];
                    runY = 1;
                }
            }
            result += finderPenaltyTerminateAndCount(runColor, runY, runHistory) * PENALTY_N3;
        }

        // 2*2 blocks of modules having same color
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean color = modules[y][x];
                if (color == modules[y][x + 1]
                        && color == modules[y + 1][x]
                        && color == modules[y + 1][x + 1]) {
                    result += PENALTY_N2;
                }
            }
        }

        // Balance of dark and light modules
        int dark = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (modules[y][x]) {
                    dark++;
                }
            }
        }
        int total = size * size;
        int k = (abs(dark * 20 - total * 10) + total - 1) / total - 1;
        result += k * PENALTY_N4;
        return result;
    }


    /*---- Private helper functions ----*/

    private int[] getAlignmentPatternPositions() {
        return getAlignmentPatternPositions(version, size);
    }

    /**
     * Returns alignment pattern center positions for the given version.
     * Package-visible so QrDecoder can reuse this logic.
     */
    static int[] getAlignmentPatternPositions(int ver, int sz) {
        if (ver == 1) {
            return new int[0];
        } else {
            int numAlign = ver / 7 + 2;
            int step = (ver * 8 + numAlign * 3 + 5) / (numAlign * 4 - 4) * 2;
            int[] result = new int[numAlign];
            result[0] = 6;
            for (int i = result.length - 1, pos = sz - 7; i >= 1; i--, pos -= step) {
                result[i] = pos;
            }
            return result;
        }
    }


    static int getNumRawDataModules(int ver) {
        if (ver < MIN_VERSION || ver > MAX_VERSION) {
            throw new IllegalArgumentException("Version number out of range");
        }

        int sz = ver * 4 + 17;
        int result = sz * sz;
        result -= 8 * 8 * 3;
        result -= 15 * 2 + 1;
        result -= (sz - 16) * 2;
        if (ver >= 2) {
            int numAlign = ver / 7 + 2;
            result -= (numAlign - 1) * (numAlign - 1) * 25;
            result -= (numAlign - 2) * 2 * 20;
            if (ver >= 7) {
                result -= 6 * 3 * 2;
            }
        }
        return result;
    }


    static byte[] reedSolomonComputeDivisor(int degree) {
        if (degree < 1 || degree > 255) {
            throw new IllegalArgumentException("Degree out of range");
        }
        byte[] result = new byte[degree];
        result[degree - 1] = 1;

        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < result.length; j++) {
                result[j] = (byte) reedSolomonMultiply(result[j] & 0xFF, root);
                if (j + 1 < result.length) {
                    result[j] ^= result[j + 1];
                }
            }
            root = reedSolomonMultiply(root, 0x02);
        }
        return result;
    }


    static byte[] reedSolomonComputeRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (int b = 0; b < data.length; b++) {
            int factor = (data[b] ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) {
                result[i] ^= (byte) reedSolomonMultiply(divisor[i] & 0xFF, factor);
            }
        }
        return result;
    }


    static int reedSolomonMultiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }


    static int getNumDataCodewords(int ver, int ecl) {
        return getNumRawDataModules(ver) / 8
            - ECC_CODEWORDS_PER_BLOCK[ecl][ver]
            * NUM_ERROR_CORRECTION_BLOCKS[ecl][ver];
    }


    private int finderPenaltyCountPatterns(int[] runHistory) {
        int n = runHistory[1];
        boolean core = n > 0 && runHistory[2] == n && runHistory[3] == n * 3
            && runHistory[4] == n && runHistory[5] == n;
        return (core && runHistory[0] >= n * 4 && runHistory[6] >= n ? 1 : 0)
             + (core && runHistory[6] >= n * 4 && runHistory[0] >= n ? 1 : 0);
    }


    private int finderPenaltyTerminateAndCount(boolean currentRunColor,
            int currentRunLength, int[] runHistory) {
        if (currentRunColor) {
            finderPenaltyAddHistory(currentRunLength, runHistory);
            currentRunLength = 0;
        }
        currentRunLength += size;
        finderPenaltyAddHistory(currentRunLength, runHistory);
        return finderPenaltyCountPatterns(runHistory);
    }


    private void finderPenaltyAddHistory(int currentRunLength, int[] runHistory) {
        if (runHistory[0] == 0) {
            currentRunLength += size;
        }
        System.arraycopy(runHistory, 0, runHistory, 1, runHistory.length - 1);
        runHistory[0] = currentRunLength;
    }


    /**
     * Returns true iff the i'th bit of x is set to 1.
     */
    static boolean getBit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    /** Absolute value (replaces Math.abs which may not be available). */
    private static int abs(int x) {
        return x < 0 ? -x : x;
    }

    /** Validates an ECC level constant. */
    private static void validateEcl(int ecl) {
        if (ecl < ECC_LOW || ecl > ECC_HIGH) {
            throw new IllegalArgumentException("Invalid ECC level: " + ecl);
        }
    }


    /*---- Constants and tables ----*/

    /** The minimum version number (1) supported. */
    public static final int MIN_VERSION =  1;

    /** The maximum version number (40) supported. */
    public static final int MAX_VERSION = 40;

    private static final int PENALTY_N1 =  3;
    private static final int PENALTY_N2 =  3;
    private static final int PENALTY_N3 = 40;
    private static final int PENALTY_N4 = 10;


    static final byte[][] ECC_CODEWORDS_PER_BLOCK = {
        // Version: (index 0 is padding, set to illegal value)
        //0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40    Error correction level
        {-1,  7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},  // Low
        {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28},  // Medium
        {-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},  // Quartile
        {-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},  // High
    };

    static final byte[][] NUM_ERROR_CORRECTION_BLOCKS = {
        // Version: (index 0 is padding, set to illegal value)
        //0, 1, 2, 3, 4, 5, 6, 7, 8, 9,10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40    Error correction level
        {-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4,  4,  4,  4,  4,  6,  6,  6,  6,  7,  8,  8,  9,  9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25},  // Low
        {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5,  5,  8,  9,  9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49},  // Medium
        {-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8,  8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68},  // Quartile
        {-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81},  // High
    };
}
