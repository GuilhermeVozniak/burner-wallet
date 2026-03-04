package org.burnerwallet.transport;

/**
 * Reassembles a payload from numbered QR frames produced by {@link MultiFrameEncoder}.
 *
 * Frames may arrive in any order and duplicates are silently ignored.
 * Call {@link #isComplete()} after each {@link #addFrame(byte[])} to check
 * whether the full payload can be assembled.
 */
public class MultiFrameDecoder {

    private int totalFrames = -1;
    private byte[][] frameData;
    private boolean[] received;
    private int receivedCount = 0;

    /**
     * Feed a raw frame (header + data) into the decoder.
     * Frames shorter than 2 bytes, with mismatched totals, or
     * out-of-range indices are silently dropped.
     *
     * @param frame raw frame bytes as produced by {@link MultiFrameEncoder}
     */
    public void addFrame(byte[] frame) {
        if (frame.length < 2) {
            return;
        }

        int total = frame[0] & 0xFF;
        int index = frame[1] & 0xFF;

        if (total == 0 || index >= total) {
            return;
        }

        if (totalFrames == -1) {
            totalFrames = total;
            frameData = new byte[total][];
            received = new boolean[total];
        }

        if (total != totalFrames || received[index]) {
            return;
        }

        byte[] data = new byte[frame.length - 2];
        System.arraycopy(frame, 2, data, 0, data.length);
        frameData[index] = data;
        received[index] = true;
        receivedCount++;
    }

    /**
     * @return true when every frame has been received
     */
    public boolean isComplete() {
        return totalFrames > 0 && receivedCount == totalFrames;
    }

    /**
     * @return number of unique frames received so far
     */
    public int getReceivedCount() {
        return receivedCount;
    }

    /**
     * @return expected total number of frames, or -1 if no frame has been added yet
     */
    public int getTotalCount() {
        return totalFrames;
    }

    /**
     * Reassemble the original payload from all received frames.
     * Must only be called after {@link #isComplete()} returns true.
     *
     * @return the original payload byte array
     */
    public byte[] assemble() {
        int totalLen = 0;
        for (int i = 0; i < totalFrames; i++) {
            totalLen += frameData[i].length;
        }

        byte[] result = new byte[totalLen];
        int pos = 0;
        for (int i = 0; i < totalFrames; i++) {
            System.arraycopy(frameData[i], 0, result, pos, frameData[i].length);
            pos += frameData[i].length;
        }
        return result;
    }
}
