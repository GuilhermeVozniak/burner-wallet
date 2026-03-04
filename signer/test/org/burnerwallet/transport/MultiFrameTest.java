package org.burnerwallet.transport;

import org.junit.Test;
import static org.junit.Assert.*;

public class MultiFrameTest {

    @Test
    public void singleFrameForSmallPayload() {
        byte[] payload = new byte[50];
        for (int i = 0; i < 50; i++) payload[i] = (byte) i;
        byte[][] frames = MultiFrameEncoder.encode(payload, 150);
        assertEquals(1, frames.length);
        assertEquals(52, frames[0].length); // header(2) + data(50)
        assertEquals(1, frames[0][0] & 0xFF); // total = 1
        assertEquals(0, frames[0][1] & 0xFF); // index = 0
    }

    @Test
    public void multipleFrames() {
        byte[] payload = new byte[300];
        for (int i = 0; i < 300; i++) payload[i] = (byte) (i & 0xFF);
        byte[][] frames = MultiFrameEncoder.encode(payload, 150);
        assertEquals(3, frames.length); // 300 / (150-2) = 2.027... -> 3
        assertEquals(3, frames[0][0] & 0xFF);
        assertEquals(0, frames[0][1] & 0xFF);
        assertEquals(3, frames[1][0] & 0xFF);
        assertEquals(1, frames[1][1] & 0xFF);
    }

    @Test
    public void roundTripSingleFrame() {
        byte[] payload = "Hello, Bitcoin!".getBytes();
        byte[][] frames = MultiFrameEncoder.encode(payload, 150);
        MultiFrameDecoder decoder = new MultiFrameDecoder();
        for (int i = 0; i < frames.length; i++) {
            decoder.addFrame(frames[i]);
        }
        assertTrue(decoder.isComplete());
        assertArrayEquals(payload, decoder.assemble());
    }

    @Test
    public void roundTripMultiFrame() {
        byte[] payload = new byte[500];
        for (int i = 0; i < 500; i++) payload[i] = (byte) (i & 0xFF);
        byte[][] frames = MultiFrameEncoder.encode(payload, 150);
        assertTrue(frames.length > 1);
        MultiFrameDecoder decoder = new MultiFrameDecoder();
        for (int i = 0; i < frames.length; i++) {
            decoder.addFrame(frames[i]);
        }
        assertTrue(decoder.isComplete());
        assertArrayEquals(payload, decoder.assemble());
    }

    @Test
    public void outOfOrderFrames() {
        byte[] payload = new byte[400];
        for (int i = 0; i < 400; i++) payload[i] = (byte) (i & 0xFF);
        byte[][] frames = MultiFrameEncoder.encode(payload, 150);
        MultiFrameDecoder decoder = new MultiFrameDecoder();
        for (int i = frames.length - 1; i >= 0; i--) {
            decoder.addFrame(frames[i]);
        }
        assertTrue(decoder.isComplete());
        assertArrayEquals(payload, decoder.assemble());
    }

    @Test
    public void duplicateFramesIgnored() {
        byte[] payload = new byte[300];
        byte[][] frames = MultiFrameEncoder.encode(payload, 150);
        MultiFrameDecoder decoder = new MultiFrameDecoder();
        decoder.addFrame(frames[0]);
        decoder.addFrame(frames[0]); // duplicate
        assertFalse(decoder.isComplete());
        for (int i = 1; i < frames.length; i++) {
            decoder.addFrame(frames[i]);
        }
        assertTrue(decoder.isComplete());
    }

    @Test
    public void incompleteDecoder() {
        byte[] payload = new byte[300];
        byte[][] frames = MultiFrameEncoder.encode(payload, 150);
        MultiFrameDecoder decoder = new MultiFrameDecoder();
        decoder.addFrame(frames[0]);
        assertFalse(decoder.isComplete());
        assertEquals(1, decoder.getReceivedCount());
        assertTrue(decoder.getTotalCount() > 1);
    }

    @Test
    public void emptyPayload() {
        byte[] payload = new byte[0];
        byte[][] frames = MultiFrameEncoder.encode(payload, 150);
        assertEquals(1, frames.length);
        MultiFrameDecoder decoder = new MultiFrameDecoder();
        decoder.addFrame(frames[0]);
        assertTrue(decoder.isComplete());
        assertEquals(0, decoder.assemble().length);
    }
}
