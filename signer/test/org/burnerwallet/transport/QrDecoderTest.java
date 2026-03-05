package org.burnerwallet.transport;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Round-trip tests for QrDecoder — encode with QrCode, decode with QrDecoder,
 * verify the original payload is recovered.
 */
public class QrDecoderTest {

    @Test
    public void decodeSimpleByteMode() {
        byte[] data = "Hello".getBytes();
        QrCode qr = QrCode.encodeBinary(data, QrCode.ECC_LOW);
        boolean[][] modules = extractModules(qr);
        byte[] decoded = QrDecoder.decode(modules, qr.size);
        assertNotNull(decoded);
        assertArrayEquals(data, decoded);
    }

    @Test
    public void decodeLargerPayload() {
        byte[] data = new byte[100];
        for (int i = 0; i < 100; i++) {
            data[i] = (byte) (i & 0xFF);
        }
        QrCode qr = QrCode.encodeBinary(data, QrCode.ECC_LOW);
        boolean[][] modules = extractModules(qr);
        byte[] decoded = QrDecoder.decode(modules, qr.size);
        assertNotNull(decoded);
        assertArrayEquals(data, decoded);
    }

    @Test
    public void decodeWithMediumEcc() {
        byte[] data = "test data".getBytes();
        QrCode qr = QrCode.encodeBinary(data, QrCode.ECC_MEDIUM);
        boolean[][] modules = extractModules(qr);
        byte[] decoded = QrDecoder.decode(modules, qr.size);
        assertNotNull(decoded);
        assertArrayEquals(data, decoded);
    }

    @Test
    public void decodeMultiFramePayload() {
        byte[] payload = "PSBT data here for testing multi-frame".getBytes();
        byte[][] frames = MultiFrameEncoder.encode(payload, 30);
        assertTrue(frames.length > 1);

        MultiFrameDecoder mfd = new MultiFrameDecoder();
        for (int i = 0; i < frames.length; i++) {
            QrCode qr = QrCode.encodeBinary(frames[i], QrCode.ECC_LOW);
            boolean[][] modules = extractModules(qr);
            byte[] decoded = QrDecoder.decode(modules, qr.size);
            assertNotNull("Frame " + i + " should decode", decoded);
            mfd.addFrame(decoded);
        }
        assertTrue(mfd.isComplete());
        assertArrayEquals(payload, mfd.assemble());
    }

    @Test
    public void decodeSingleByte() {
        byte[] data = new byte[]{0x42};
        QrCode qr = QrCode.encodeBinary(data, QrCode.ECC_LOW);
        boolean[][] modules = extractModules(qr);
        byte[] decoded = QrDecoder.decode(modules, qr.size);
        assertNotNull(decoded);
        assertArrayEquals(data, decoded);
    }

    private boolean[][] extractModules(QrCode qr) {
        boolean[][] modules = new boolean[qr.size][qr.size];
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                modules[y][x] = qr.getModule(x, y);
            }
        }
        return modules;
    }
}
