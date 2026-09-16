package org.burnerwallet.core;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;

/**
 * Collects entropy from keypad timing on a Nokia C1-01.
 *
 * The user presses random keys. For every press the collector records the
 * millisecond delta since the previous press (the primary, user-driven
 * entropy) and an auxiliary word mixing the absolute timestamp, the key
 * code, free heap size and an object identity hash. All samples are
 * hashed with SHA-256 to produce 32 bytes of entropy suitable for BIP39
 * mnemonic generation.
 *
 * Human key intervals cluster tightly, so a session is only accepted once
 * {@link #REQUIRED_PRESSES} presses have been made <em>and</em> the deltas
 * show at least {@link #MIN_DISTINCT_DELTAS} distinct values; otherwise
 * the user is asked to keep going (up to {@link #MAX_PRESSES}).
 *
 * The static {@link #mixEntropy(long[], long[])} method is unit-testable
 * on desktop JDK. Instance methods depend on MIDP Canvas and are
 * tested manually on the device or emulator.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class EntropyCollector extends Canvas {

    /** Number of keypresses required before entropy can be ready. */
    private static final int REQUIRED_PRESSES = 64;

    /** Minimum number of distinct timing deltas for the session to count. */
    private static final int MIN_DISTINCT_DELTAS = 16;

    /** Hard cap: accept the session at this many presses regardless. */
    private static final int MAX_PRESSES = REQUIRED_PRESSES * 3;

    /** Recorded timing deltas between consecutive keypresses. */
    private long[] timings;

    /** Auxiliary per-press samples (timestamp, key code, heap, identity hash). */
    private long[] extras;

    /** Number of keypresses collected so far. */
    private int count;

    /** Timestamp of the last keypress (milliseconds). */
    private long lastTime;

    /** True when enough keypresses have been collected. */
    private boolean ready;

    /** Optional callback notified when entropy collection is complete. */
    private EntropyListener listener;

    /**
     * Callback interface for entropy readiness notification.
     */
    public interface EntropyListener {
        /**
         * Called when the required number of keypresses has been collected.
         *
         * @param entropy 32 bytes of SHA-256 hashed entropy
         */
        void onEntropyReady(byte[] entropy);
    }

    /**
     * Create a new EntropyCollector.
     * Initializes the sample arrays and records the current time.
     */
    public EntropyCollector() {
        this.timings = new long[MAX_PRESSES];
        this.extras = new long[MAX_PRESSES];
        this.count = 0;
        this.lastTime = System.currentTimeMillis();
        this.ready = false;
    }

    /**
     * Convert timing deltas into 32 bytes of entropy.
     *
     * Equivalent to {@link #mixEntropy(long[], long[])} with no extras.
     *
     * @param timingDeltas array of timing deltas (milliseconds between keypresses)
     * @return 32-byte SHA-256 hash of the serialized timing data
     */
    public static byte[] mixEntropy(long[] timingDeltas) {
        return mixEntropy(timingDeltas, null);
    }

    /**
     * Convert timing deltas and auxiliary samples into 32 bytes of entropy.
     *
     * Each long is serialized as 8 big-endian bytes, deltas first then
     * extras, and the concatenation is hashed with SHA-256.
     *
     * This method is static and Canvas-independent for unit testing.
     *
     * @param timingDeltas array of timing deltas (milliseconds between keypresses)
     * @param extraSamples auxiliary samples, or null
     * @return 32-byte SHA-256 hash of the serialized data
     */
    public static byte[] mixEntropy(long[] timingDeltas, long[] extraSamples) {
        int extraLen = extraSamples == null ? 0 : extraSamples.length;
        byte[] concatenated = new byte[(timingDeltas.length + extraLen) * 8];
        for (int i = 0; i < timingDeltas.length; i++) {
            putLong(concatenated, i * 8, timingDeltas[i]);
        }
        for (int i = 0; i < extraLen; i++) {
            putLong(concatenated, (timingDeltas.length + i) * 8, extraSamples[i]);
        }
        byte[] out = HashUtils.sha256(concatenated);
        ByteArrayUtils.zeroFill(concatenated);
        return out;
    }

    private static void putLong(byte[] buf, int offset, long v) {
        buf[offset]     = (byte) ((v >> 56) & 0xFF);
        buf[offset + 1] = (byte) ((v >> 48) & 0xFF);
        buf[offset + 2] = (byte) ((v >> 40) & 0xFF);
        buf[offset + 3] = (byte) ((v >> 32) & 0xFF);
        buf[offset + 4] = (byte) ((v >> 24) & 0xFF);
        buf[offset + 5] = (byte) ((v >> 16) & 0xFF);
        buf[offset + 6] = (byte) ((v >>  8) & 0xFF);
        buf[offset + 7] = (byte) ( v        & 0xFF);
    }

    /**
     * Count the distinct values among the first {@code n} deltas.
     * Static and Canvas-independent for unit testing.
     *
     * @param deltas timing deltas
     * @param n      number of leading entries to consider
     * @return number of distinct values
     */
    public static int countDistinct(long[] deltas, int n) {
        int distinct = 0;
        for (int i = 0; i < n; i++) {
            boolean seen = false;
            for (int j = 0; j < i; j++) {
                if (deltas[j] == deltas[i]) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                distinct++;
            }
        }
        return distinct;
    }

    /**
     * Whether enough keypresses have been collected.
     *
     * @return true if the session has been accepted
     */
    public boolean isReady() {
        return ready;
    }

    /**
     * Number of keypresses collected so far.
     *
     * @return current count
     */
    public int getCount() {
        return count;
    }

    /**
     * Number of keypresses normally required.
     *
     * @return REQUIRED_PRESSES
     */
    public int getRequired() {
        return REQUIRED_PRESSES;
    }

    /**
     * Get the collected entropy if ready.
     *
     * @return 32-byte entropy array, or null if not enough presses yet
     */
    public byte[] getEntropy() {
        if (!ready) {
            return null;
        }
        long[] t = new long[count];
        long[] e = new long[count];
        System.arraycopy(timings, 0, t, 0, count);
        System.arraycopy(extras, 0, e, 0, count);
        return mixEntropy(t, e);
    }

    /**
     * Set a listener to be notified when entropy collection is complete.
     *
     * @param listener the callback, or null to remove
     */
    public void setEntropyListener(EntropyListener listener) {
        this.listener = listener;
    }

    /**
     * Record a keypress sample.
     * Called by the MIDP framework when the user presses a key.
     *
     * @param keyCode the key that was pressed
     */
    protected void keyPressed(int keyCode) {
        if (ready || count >= MAX_PRESSES) {
            return;
        }

        long now = System.currentTimeMillis();
        timings[count] = now - lastTime;
        extras[count] = now
                ^ ((long) keyCode << 32)
                ^ Runtime.getRuntime().freeMemory()
                ^ ((long) new Object().hashCode() << 16);
        lastTime = now;
        count++;

        if (count >= REQUIRED_PRESSES
                && (countDistinct(timings, count) >= MIN_DISTINCT_DELTAS
                    || count >= MAX_PRESSES)) {
            ready = true;
            if (listener != null) {
                listener.onEntropyReady(getEntropy());
            }
        }

        repaint();
    }

    /**
     * Draw the entropy collection UI.
     * Shows a progress message ("Press random keys: 5/64") or
     * a completion message ("Done!") when ready.
     *
     * @param g the Graphics context to paint on
     */
    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();

        // Clear background
        g.setColor(0xFFFFFF);
        g.fillRect(0, 0, w, h);

        g.setColor(0x000000);

        if (ready) {
            g.drawString("Done!", w / 2, h / 2,
                Graphics.HCENTER | Graphics.BASELINE);
        } else if (count >= REQUIRED_PRESSES) {
            g.drawString("Vary your rhythm!", w / 2, h / 2 - 10,
                Graphics.HCENTER | Graphics.BASELINE);
            g.drawString("Keep pressing keys", w / 2, h / 2 + 10,
                Graphics.HCENTER | Graphics.BASELINE);
        } else {
            g.drawString("Press random keys", w / 2, h / 2 - 10,
                Graphics.HCENTER | Graphics.BASELINE);
            g.drawString(count + " / " + REQUIRED_PRESSES, w / 2, h / 2 + 10,
                Graphics.HCENTER | Graphics.BASELINE);
        }
    }
}
