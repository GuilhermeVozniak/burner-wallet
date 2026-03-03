package org.burnerwallet.core;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JUnit 4 tests for EntropyCollector.mixEntropy — the static,
 * Canvas-independent method that converts timing deltas into
 * 32 bytes of SHA-256 hashed entropy.
 *
 * Instance methods (keyPressed, paint) depend on MIDP Canvas
 * and are tested manually on the device/emulator.
 */
public class EntropyCollectorTest {

    /**
     * mixEntropy with 32 timing values must produce exactly 32 bytes
     * (one SHA-256 digest).
     */
    @Test
    public void mixEntropyProduces32Bytes() {
        long[] timings = new long[32];
        for (int i = 0; i < 32; i++) {
            timings[i] = (long) (i * 37 + 100);
        }
        byte[] entropy = EntropyCollector.mixEntropy(timings);
        assertNotNull(entropy);
        assertEquals(32, entropy.length);
    }

    /**
     * Changing one timing value must produce a completely different
     * entropy output (SHA-256 is collision-resistant).
     */
    @Test
    public void differentTimingsProduceDifferentEntropy() {
        long[] timings1 = new long[32];
        long[] timings2 = new long[32];
        for (int i = 0; i < 32; i++) {
            timings1[i] = (long) (i * 37 + 100);
            timings2[i] = (long) (i * 37 + 100);
        }
        // Change just one timing value
        timings2[15] = timings2[15] + 1;

        byte[] entropy1 = EntropyCollector.mixEntropy(timings1);
        byte[] entropy2 = EntropyCollector.mixEntropy(timings2);

        assertFalse("Different timings must produce different entropy",
            ByteArrayUtils.constantTimeEquals(entropy1, entropy2));
    }

    /**
     * Output must not be all zeros (SHA-256 of any non-trivial input
     * is astronomically unlikely to be all zeros).
     */
    @Test
    public void mixEntropyNonZero() {
        long[] timings = new long[32];
        for (int i = 0; i < 32; i++) {
            timings[i] = (long) (i * 13 + 42);
        }
        byte[] entropy = EntropyCollector.mixEntropy(timings);

        boolean allZero = true;
        for (int i = 0; i < entropy.length; i++) {
            if (entropy[i] != 0) {
                allZero = false;
                break;
            }
        }
        assertFalse("Entropy output must not be all zeros", allZero);
    }

    /**
     * SHA-256 avalanche effect: changing a single timing value by 1
     * should cause more than 10 of the 32 output bytes to differ.
     */
    @Test
    public void singleTimingDifferenceChangesAllBytes() {
        long[] timings1 = new long[32];
        long[] timings2 = new long[32];
        for (int i = 0; i < 32; i++) {
            timings1[i] = (long) (i * 97 + 500);
            timings2[i] = (long) (i * 97 + 500);
        }
        // Flip just one bit in one timing
        timings2[0] = timings2[0] + 1;

        byte[] entropy1 = EntropyCollector.mixEntropy(timings1);
        byte[] entropy2 = EntropyCollector.mixEntropy(timings2);

        int diffCount = 0;
        for (int i = 0; i < 32; i++) {
            if (entropy1[i] != entropy2[i]) {
                diffCount++;
            }
        }
        assertTrue("SHA-256 avalanche: expected >10 differing bytes, got " + diffCount,
            diffCount > 10);
    }

    /**
     * The first 16 bytes of mixEntropy output can be extracted
     * as a sub-array (useful for 128-bit entropy for 12-word mnemonic).
     */
    @Test
    public void getEntropy16Returns16Bytes() {
        long[] timings = new long[32];
        for (int i = 0; i < 32; i++) {
            timings[i] = (long) (i * 53 + 7);
        }
        byte[] full = EntropyCollector.mixEntropy(timings);
        byte[] first16 = ByteArrayUtils.copyOfRange(full, 0, 16);

        assertEquals(16, first16.length);

        // Verify it matches the first 16 bytes of the full output
        for (int i = 0; i < 16; i++) {
            assertEquals("Byte " + i + " must match", full[i], first16[i]);
        }
    }
}
