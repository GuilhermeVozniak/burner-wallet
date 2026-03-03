package org.burnerwallet.core;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;

/**
 * Collects entropy from keypad timing on a Nokia C1-01.
 *
 * The user presses random keys; the millisecond deltas between
 * consecutive presses are recorded. When enough presses have been
 * collected, the timing array is hashed with SHA-256 to produce
 * 32 bytes of entropy suitable for BIP39 mnemonic generation.
 *
 * The static {@link #mixEntropy(long[])} method is unit-testable
 * on desktop JDK. Instance methods depend on MIDP Canvas and are
 * tested manually on the device or emulator.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class EntropyCollector extends Canvas {

    /** Number of keypresses required before entropy is ready. */
    private static final int REQUIRED_PRESSES = 32;

    /** Recorded timing deltas between consecutive keypresses. */
    private long[] timings;

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
         * @param entropy 32 bytes of SHA-256 hashed timing entropy
         */
        void onEntropyReady(byte[] entropy);
    }

    /**
     * Create a new EntropyCollector.
     * Initializes the timing array and records the current time.
     */
    public EntropyCollector() {
        this.timings = new long[REQUIRED_PRESSES];
        this.count = 0;
        this.lastTime = System.currentTimeMillis();
        this.ready = false;
    }

    /**
     * Convert timing deltas into 32 bytes of entropy.
     *
     * Each long is serialized as 8 big-endian bytes, all are concatenated,
     * and the result is hashed with SHA-256 to produce a uniform 32-byte output.
     *
     * This method is static and Canvas-independent for unit testing.
     *
     * @param timingDeltas array of timing deltas (milliseconds between keypresses)
     * @return 32-byte SHA-256 hash of the serialized timing data
     */
    public static byte[] mixEntropy(long[] timingDeltas) {
        byte[] concatenated = new byte[timingDeltas.length * 8];
        for (int i = 0; i < timingDeltas.length; i++) {
            long v = timingDeltas[i];
            int offset = i * 8;
            concatenated[offset]     = (byte) ((v >> 56) & 0xFF);
            concatenated[offset + 1] = (byte) ((v >> 48) & 0xFF);
            concatenated[offset + 2] = (byte) ((v >> 40) & 0xFF);
            concatenated[offset + 3] = (byte) ((v >> 32) & 0xFF);
            concatenated[offset + 4] = (byte) ((v >> 24) & 0xFF);
            concatenated[offset + 5] = (byte) ((v >> 16) & 0xFF);
            concatenated[offset + 6] = (byte) ((v >>  8) & 0xFF);
            concatenated[offset + 7] = (byte) ( v        & 0xFF);
        }
        return HashUtils.sha256(concatenated);
    }

    /**
     * Whether enough keypresses have been collected.
     *
     * @return true if count >= REQUIRED_PRESSES
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
     * Total number of keypresses required.
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
        return mixEntropy(timings);
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
     * Record a keypress timing delta.
     * Called by the MIDP framework when the user presses a key.
     *
     * @param keyCode the key that was pressed
     */
    protected void keyPressed(int keyCode) {
        if (ready) {
            return;
        }

        long now = System.currentTimeMillis();
        timings[count] = now - lastTime;
        lastTime = now;
        count++;

        if (count >= REQUIRED_PRESSES) {
            ready = true;
            if (listener != null) {
                listener.onEntropyReady(getEntropy());
            }
        }

        repaint();
    }

    /**
     * Draw the entropy collection UI.
     * Shows a progress message ("Press random keys: 5/32") or
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
        } else {
            g.drawString("Press random keys", w / 2, h / 2 - 10,
                Graphics.HCENTER | Graphics.BASELINE);
            g.drawString(count + " / " + REQUIRED_PRESSES, w / 2, h / 2 + 10,
                Graphics.HCENTER | Graphics.BASELINE);
        }
    }
}
