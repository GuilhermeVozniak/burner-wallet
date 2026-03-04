package org.burnerwallet.transport;

/**
 * Splits a byte-array payload into numbered QR-sized frames.
 *
 * Frame format: [total_frames (1 byte)] [frame_index (1 byte)] [payload_chunk (N bytes)]
 *
 * Supports up to 255 frames (single-byte counters), which at typical
 * QR binary capacity (~150 bytes per frame) allows payloads up to ~37 KB
 * -- more than enough for PSBTs on the Nokia C1-01.
 */
public class MultiFrameEncoder {

    private static final int HEADER_SIZE = 2;

    /**
     * Split payload into frames with 2-byte header.
     *
     * @param payload          data to split
     * @param maxBytesPerFrame max total frame size including header
     * @return array of frames, each containing header + data chunk
     */
    public static byte[][] encode(byte[] payload, int maxBytesPerFrame) {
        int dataPerFrame = maxBytesPerFrame - HEADER_SIZE;
        if (dataPerFrame <= 0) {
            dataPerFrame = 1;
        }

        int totalFrames = (payload.length + dataPerFrame - 1) / dataPerFrame;
        if (totalFrames == 0) {
            totalFrames = 1;
        }

        byte[][] frames = new byte[totalFrames][];
        for (int i = 0; i < totalFrames; i++) {
            int offset = i * dataPerFrame;
            int len = payload.length - offset;
            if (len > dataPerFrame) {
                len = dataPerFrame;
            }
            if (len < 0) {
                len = 0;
            }

            frames[i] = new byte[HEADER_SIZE + len];
            frames[i][0] = (byte) totalFrames;
            frames[i][1] = (byte) i;

            if (len > 0) {
                System.arraycopy(payload, offset, frames[i], HEADER_SIZE, len);
            }
        }
        return frames;
    }
}
