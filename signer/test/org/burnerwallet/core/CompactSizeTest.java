package org.burnerwallet.core;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JUnit 4 tests for CompactSize (Bitcoin variable-length integer encoding).
 */
public class CompactSizeTest {

    @Test
    public void readSingleByte() {
        // 0xFC = 252, single byte encoding
        byte[] data = new byte[] { (byte) 0xFC };
        long[] result = CompactSize.read(data, 0);
        assertEquals(252L, result[0]);
        assertEquals(1L, result[1]);
    }

    @Test
    public void readTwoBytes() {
        // 0xFD prefix + 2-byte little-endian: 0xFD 0x00 = 253
        byte[] data = new byte[] { (byte) 0xFD, (byte) 0xFD, (byte) 0x00 };
        long[] result = CompactSize.read(data, 0);
        assertEquals(253L, result[0]);
        assertEquals(3L, result[1]);
    }

    @Test
    public void readFourBytes() {
        // 0xFE prefix + 4-byte little-endian: 65536 = 0x00010000 -> LE: 00 00 01 00
        byte[] data = new byte[] {
            (byte) 0xFE,
            (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00
        };
        long[] result = CompactSize.read(data, 0);
        assertEquals(65536L, result[0]);
        assertEquals(5L, result[1]);
    }

    @Test
    public void readEightBytes() {
        // 0xFF prefix + 8-byte little-endian: 4294967296 = 0x0000000100000000
        // LE: 00 00 00 00 01 00 00 00
        byte[] data = new byte[] {
            (byte) 0xFF,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00
        };
        long[] result = CompactSize.read(data, 0);
        assertEquals(4294967296L, result[0]);
        assertEquals(9L, result[1]);
    }

    @Test
    public void writeSingleByte() {
        byte[] result = CompactSize.write(252);
        assertArrayEquals(new byte[] { (byte) 0xFC }, result);
    }

    @Test
    public void writeTwoBytes() {
        byte[] result = CompactSize.write(253);
        assertArrayEquals(
            new byte[] { (byte) 0xFD, (byte) 0xFD, (byte) 0x00 },
            result
        );
    }

    @Test
    public void writeRoundTrip() {
        long[] testValues = new long[] { 0, 1, 252, 253, 65535, 65536, 100000 };
        for (int i = 0; i < testValues.length; i++) {
            byte[] encoded = CompactSize.write(testValues[i]);
            long[] decoded = CompactSize.read(encoded, 0);
            assertEquals("round-trip failed for value " + testValues[i],
                testValues[i], decoded[0]);
        }
    }

    @Test
    public void readWithOffset() {
        // Two padding bytes, then 0xFD 0xFD 0x00 = 253 at offset 2
        byte[] data = new byte[] {
            (byte) 0x00, (byte) 0x00,
            (byte) 0xFD, (byte) 0xFD, (byte) 0x00
        };
        long[] result = CompactSize.read(data, 2);
        assertEquals(253L, result[0]);
        assertEquals(3L, result[1]);
    }
}
