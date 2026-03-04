package org.burnerwallet.transport;

import org.junit.Test;
import static org.junit.Assert.*;

public class QrCodeTest {

    @Test
    public void encodeBinaryProducesValidQr() {
        byte[] data = "Hello".getBytes();
        QrCode qr = QrCode.encodeBinary(data, QrCode.ECC_LOW);
        assertNotNull(qr);
        assertTrue(qr.size > 0);
        assertTrue(qr.size >= 21); // version 1 minimum
    }

    @Test
    public void moduleAccessInBounds() {
        byte[] data = new byte[]{0x42};
        QrCode qr = QrCode.encodeBinary(data, QrCode.ECC_LOW);
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                qr.getModule(x, y); // should not throw
            }
        }
    }

    @Test
    public void outOfBoundsReturnsFalse() {
        QrCode qr = QrCode.encodeBinary(new byte[]{0x42}, QrCode.ECC_LOW);
        assertFalse(qr.getModule(-1, 0));
        assertFalse(qr.getModule(0, -1));
        assertFalse(qr.getModule(qr.size, 0));
        assertFalse(qr.getModule(0, qr.size));
    }

    @Test
    public void differentDataProducesDifferentQr() {
        QrCode qr1 = QrCode.encodeBinary("abc".getBytes(), QrCode.ECC_LOW);
        QrCode qr2 = QrCode.encodeBinary("xyz".getBytes(), QrCode.ECC_LOW);
        boolean anyDiff = false;
        int minSize = qr1.size < qr2.size ? qr1.size : qr2.size;
        for (int y = 0; y < minSize && !anyDiff; y++) {
            for (int x = 0; x < minSize && !anyDiff; x++) {
                if (qr1.getModule(x, y) != qr2.getModule(x, y)) {
                    anyDiff = true;
                }
            }
        }
        assertTrue(anyDiff);
    }

    @Test
    public void eccLevels() {
        byte[] data = "test".getBytes();
        QrCode low = QrCode.encodeBinary(data, QrCode.ECC_LOW);
        QrCode high = QrCode.encodeBinary(data, QrCode.ECC_HIGH);
        assertTrue(high.size >= low.size);
    }

    @Test
    public void largerDataProducesLargerVersion() {
        byte[] small = new byte[10];
        byte[] large = new byte[200];
        QrCode qrSmall = QrCode.encodeBinary(small, QrCode.ECC_LOW);
        QrCode qrLarge = QrCode.encodeBinary(large, QrCode.ECC_LOW);
        assertTrue(qrLarge.size > qrSmall.size);
    }
}
