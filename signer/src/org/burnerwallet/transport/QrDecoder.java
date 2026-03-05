package org.burnerwallet.transport;

/**
 * Decodes QR codes produced by {@link QrCode} from a boolean module grid.
 *
 * This decoder is designed for round-trip verification: it accepts the exact
 * boolean[][] grid that QrCode produces (true=dark, false=light) and reverses
 * the encoding process to recover the original byte payload.
 *
 * It does NOT perform image processing, finder pattern detection, or
 * perspective correction. For camera-captured images, those steps must be
 * performed externally before passing a clean grid to this decoder.
 *
 * Only BYTE mode (mode indicator 0x4) is supported since our encoder
 * exclusively uses binary encoding.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public final class QrDecoder {

    /** Reverse lookup: eccFormatBits -> ECC level constant. */
    private static final int[] FORMAT_TO_ECC = new int[4];
    static {
        // ECC_FORMAT_BITS: LOW=1, MEDIUM=0, QUARTILE=3, HIGH=2
        // So reverse: 0->MEDIUM, 1->LOW, 2->HIGH, 3->QUARTILE
        FORMAT_TO_ECC[0] = QrCode.ECC_MEDIUM;
        FORMAT_TO_ECC[1] = QrCode.ECC_LOW;
        FORMAT_TO_ECC[2] = QrCode.ECC_HIGH;
        FORMAT_TO_ECC[3] = QrCode.ECC_QUARTILE;
    }

    /**
     * Decode a QR code from a module grid.
     *
     * @param modules 2D grid where true=dark and false=light
     * @param size    width and height of the grid
     * @return decoded byte[] payload, or null if decoding fails
     */
    public static byte[] decode(boolean[][] modules, int size) {
        try {
            return decodeInternal(modules, size);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] decodeInternal(boolean[][] modules, int size) {
        // 1. Determine version
        if ((size - 17) % 4 != 0) {
            return null;
        }
        int version = (size - 17) / 4;
        if (version < QrCode.MIN_VERSION || version > QrCode.MAX_VERSION) {
            return null;
        }

        // 2. Extract format info
        int formatBits = readFormatBits(modules, size);
        int formatData = formatBits ^ 0x5412;

        // The top 5 bits (bits 14-10) are the data: eccFormatBits(2) + mask(3)
        int dataField = (formatData >>> 10) & 0x1F;
        int eccFormatBits = (dataField >>> 3) & 0x03;
        int maskPattern = dataField & 0x07;

        int ecl = FORMAT_TO_ECC[eccFormatBits];

        // 3. Build function module mask
        boolean[][] isFunction = buildFunctionMask(version, size);

        // 4. Unmask data modules
        boolean[][] unmasked = new boolean[size][size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                unmasked[y][x] = modules[y][x];
            }
        }
        applyMask(unmasked, isFunction, maskPattern, size);

        // 5. Extract data codewords (reverse zigzag)
        int rawCodewords = QrCode.getNumRawDataModules(version) / 8;
        byte[] codewords = new byte[rawCodewords];
        int bitIndex = 0;

        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5;
            }
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && bitIndex < rawCodewords * 8) {
                        if (unmasked[y][x]) {
                            codewords[bitIndex >>> 3] |=
                                (byte) (1 << (7 - (bitIndex & 7)));
                        }
                        bitIndex++;
                    }
                }
            }
        }

        // 6. De-interleave and separate data/ECC
        int numBlocks = QrCode.NUM_ERROR_CORRECTION_BLOCKS[ecl][version];
        int blockEccLen = QrCode.ECC_CODEWORDS_PER_BLOCK[ecl][version];
        int numShortBlocks = numBlocks - rawCodewords % numBlocks;
        int shortBlockLen = rawCodewords / numBlocks;

        // De-interleave into blocks.
        // The encoder creates all blocks as shortBlockLen+1 bytes. Short blocks
        // have a zero byte at position (shortBlockLen - blockEccLen) that is
        // omitted during interleaving. We must allocate the same layout.
        byte[][] blocks = new byte[numBlocks][];
        for (int i = 0; i < numBlocks; i++) {
            blocks[i] = new byte[shortBlockLen + 1];
        }

        int k = 0;
        for (int i = 0; i <= shortBlockLen; i++) {
            for (int j = 0; j < numBlocks; j++) {
                // Short blocks skip the extra data byte position
                if (i == shortBlockLen - blockEccLen && j < numShortBlocks) {
                    continue;
                }
                if (k < rawCodewords) {
                    blocks[j][i] = codewords[k];
                    k++;
                }
            }
        }

        // 7. RS syndrome check (verify no errors in clean round-trip)
        byte[] rsDiv = QrCode.reedSolomonComputeDivisor(blockEccLen);
        for (int i = 0; i < numBlocks; i++) {
            // Data length: short blocks omit the extra byte
            int dataLen = shortBlockLen - blockEccLen
                    + (i < numShortBlocks ? 0 : 1);
            byte[] dataBlock = new byte[dataLen];
            System.arraycopy(blocks[i], 0, dataBlock, 0, dataLen);

            // ECC always occupies the last blockEccLen bytes of the block
            int eccStart = blocks[i].length - blockEccLen;
            byte[] eccBlock = new byte[blockEccLen];
            System.arraycopy(blocks[i], eccStart, eccBlock, 0, blockEccLen);

            // Compute expected ECC and compare
            byte[] expectedEcc =
                    QrCode.reedSolomonComputeRemainder(dataBlock, rsDiv);
            for (int b = 0; b < blockEccLen; b++) {
                if (eccBlock[b] != expectedEcc[b]) {
                    return null; // ECC mismatch — corrupted data
                }
            }
        }

        // 8. Concatenate data blocks (without ECC)
        int numDataCodewords = QrCode.getNumDataCodewords(version, ecl);
        byte[] dataBytes = new byte[numDataCodewords];
        int pos = 0;
        for (int i = 0; i < numBlocks; i++) {
            int dataLen = shortBlockLen - blockEccLen
                    + (i < numShortBlocks ? 0 : 1);
            System.arraycopy(blocks[i], 0, dataBytes, pos, dataLen);
            pos += dataLen;
        }

        // 9. Parse data stream (BYTE mode only)
        return parseDataStream(dataBytes, version);
    }

    /**
     * Read the 15 format bits from the top-left finder pattern area.
     * Positions match those written by QrCode.drawFormatBits().
     */
    private static int readFormatBits(boolean[][] modules, int size) {
        int bits = 0;
        // First copy: same positions as drawFormatBits() first copy
        // Bits 0-5: (8, 0), (8, 1), (8, 2), (8, 3), (8, 4), (8, 5)
        for (int i = 0; i <= 5; i++) {
            bits |= (modules[i][8] ? 1 : 0) << i;
        }
        // Bit 6: (8, 7)
        bits |= (modules[7][8] ? 1 : 0) << 6;
        // Bit 7: (8, 8)
        bits |= (modules[8][8] ? 1 : 0) << 7;
        // Bit 8: (7, 8)
        bits |= (modules[8][7] ? 1 : 0) << 8;
        // Bits 9-14: (5, 8), (4, 8), (3, 8), (2, 8), (1, 8), (0, 8)
        for (int i = 9; i < 15; i++) {
            bits |= (modules[8][14 - i] ? 1 : 0) << i;
        }
        return bits;
    }

    /**
     * Build a mask of function modules (finder patterns, timing patterns,
     * alignment patterns, format/version info areas).
     */
    private static boolean[][] buildFunctionMask(int version, int size) {
        boolean[][] isFunction = new boolean[size][size];

        // Timing patterns
        for (int i = 0; i < size; i++) {
            isFunction[i][6] = true; // column 6
            isFunction[6][i] = true; // row 6
        }

        // Finder patterns (3 corners, 7x7 + 1 cell separator)
        markFinderPattern(isFunction, 3, 3, size);
        markFinderPattern(isFunction, size - 4, 3, size);
        markFinderPattern(isFunction, 3, size - 4, size);

        // Alignment patterns
        int[] alignPatPos = QrCode.getAlignmentPatternPositions(version, size);
        int numAlign = alignPatPos.length;
        for (int i = 0; i < numAlign; i++) {
            for (int j = 0; j < numAlign; j++) {
                if (!(i == 0 && j == 0
                        || i == 0 && j == numAlign - 1
                        || i == numAlign - 1 && j == 0)) {
                    markAlignmentPattern(isFunction, alignPatPos[i],
                            alignPatPos[j]);
                }
            }
        }

        // Format info areas (around top-left finder pattern)
        // Horizontal strip at row 8: columns 0-8
        for (int x = 0; x <= 8; x++) {
            isFunction[8][x] = true;
        }
        // Vertical strip at column 8: rows 0-8
        for (int y = 0; y <= 8; y++) {
            isFunction[y][8] = true;
        }
        // Second copy — horizontal: column 8, rows (size-7) to (size-1)
        for (int y = size - 8; y < size; y++) {
            isFunction[y][8] = true;
        }
        // Second copy — vertical: row 8, columns (size-8) to (size-1)
        for (int x = size - 8; x < size; x++) {
            isFunction[8][x] = true;
        }

        // Version info areas (version >= 7)
        if (version >= 7) {
            for (int i = 0; i < 18; i++) {
                int a = size - 11 + i % 3;
                int b = i / 3;
                isFunction[b][a] = true;
                isFunction[a][b] = true;
            }
        }

        return isFunction;
    }

    /**
     * Mark a 7x7 finder pattern + 1-cell white border as function modules.
     * Center at (x, y).
     */
    private static void markFinderPattern(boolean[][] isFunction,
            int x, int y, int size) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int xx = x + dx;
                int yy = y + dy;
                if (0 <= xx && xx < size && 0 <= yy && yy < size) {
                    isFunction[yy][xx] = true;
                }
            }
        }
    }

    /**
     * Mark a 5x5 alignment pattern as function modules.
     * Center at (x, y).
     */
    private static void markAlignmentPattern(boolean[][] isFunction,
            int x, int y) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                isFunction[y + dy][x + dx] = true;
            }
        }
    }

    /**
     * Apply (or undo) a mask pattern on data modules.
     * Same mask formulas as QrCode.applyMask().
     */
    private static void applyMask(boolean[][] modules, boolean[][] isFunction,
            int msk, int size) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (isFunction[y][x]) {
                    continue;
                }
                boolean invert;
                switch (msk) {
                    case 0:  invert = (x + y) % 2 == 0;                   break;
                    case 1:  invert = y % 2 == 0;                         break;
                    case 2:  invert = x % 3 == 0;                         break;
                    case 3:  invert = (x + y) % 3 == 0;                   break;
                    case 4:  invert = (x / 3 + y / 2) % 2 == 0;           break;
                    case 5:  invert = x * y % 2 + x * y % 3 == 0;         break;
                    case 6:  invert = (x * y % 2 + x * y % 3) % 2 == 0;   break;
                    case 7:  invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                    default: return; // invalid mask
                }
                if (invert) {
                    modules[y][x] = !modules[y][x];
                }
            }
        }
    }

    /**
     * Parse the QR data stream. Expects BYTE mode segments.
     * Returns the concatenated payload bytes, or null on error.
     */
    private static byte[] parseDataStream(byte[] dataBytes, int version) {
        int bitLen = dataBytes.length * 8;
        int bitPos = 0;

        // Accumulate output bytes
        byte[] output = new byte[dataBytes.length]; // upper bound
        int outLen = 0;

        while (bitPos + 4 <= bitLen) {
            int mode = readBits(dataBytes, bitPos, 4);
            bitPos += 4;

            if (mode == 0) {
                // Terminator
                break;
            }

            if (mode != QrSegment.MODE_BYTE) {
                // We only support BYTE mode decoding
                return null;
            }

            // Character count bits for BYTE mode
            int ccBits = QrSegment.numCharCountBits(QrSegment.MODE_BYTE,
                    version);
            if (bitPos + ccBits > bitLen) {
                return null;
            }
            int charCount = readBits(dataBytes, bitPos, ccBits);
            bitPos += ccBits;

            if (bitPos + charCount * 8 > bitLen) {
                return null;
            }

            for (int i = 0; i < charCount; i++) {
                output[outLen] = (byte) readBits(dataBytes, bitPos, 8);
                outLen++;
                bitPos += 8;
            }
        }

        // Trim to actual length
        byte[] result = new byte[outLen];
        System.arraycopy(output, 0, result, 0, outLen);
        return result;
    }

    /**
     * Read {@code count} bits from the byte array starting at {@code bitOffset}.
     * MSB first within each byte.
     */
    private static int readBits(byte[] data, int bitOffset, int count) {
        int value = 0;
        for (int i = 0; i < count; i++) {
            int byteIdx = (bitOffset + i) >>> 3;
            int bitIdx = 7 - ((bitOffset + i) & 7);
            value = (value << 1) | ((data[byteIdx] >>> bitIdx) & 1);
        }
        return value;
    }

    // Prevent instantiation
    private QrDecoder() {
    }
}
