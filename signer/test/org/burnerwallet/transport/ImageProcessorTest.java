package org.burnerwallet.transport;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for ImageProcessor: grayscale conversion and adaptive thresholding.
 *
 * These tests use synthetic pixel data (not actual MIDP Images) to verify
 * the algorithms work correctly in desktop JUnit.
 */
public class ImageProcessorTest {

    @Test
    public void toGrayscaleWhitePixel() {
        int[] argb = new int[] { 0xFFFFFFFF }; // white
        int[] gray = ImageProcessor.toGrayscale(argb, 1, 1);
        assertEquals(1, gray.length);
        // R=255, G=255, B=255 -> (77*255 + 150*255 + 29*255) >> 8 = 255
        assertTrue("White pixel should be near 255", gray[0] >= 254);
    }

    @Test
    public void toGrayscaleBlackPixel() {
        int[] argb = new int[] { 0xFF000000 }; // black
        int[] gray = ImageProcessor.toGrayscale(argb, 1, 1);
        assertEquals(0, gray[0]);
    }

    @Test
    public void toGrayscaleRedPixel() {
        int[] argb = new int[] { 0xFFFF0000 }; // pure red
        int[] gray = ImageProcessor.toGrayscale(argb, 1, 1);
        // (77*255 + 150*0 + 29*0) >> 8 = 76
        assertEquals(76, gray[0]);
    }

    @Test
    public void toGrayscaleGreenPixel() {
        int[] argb = new int[] { 0xFF00FF00 }; // pure green
        int[] gray = ImageProcessor.toGrayscale(argb, 1, 1);
        // (77*0 + 150*255 + 29*0) >> 8 = 149
        assertEquals(149, gray[0]);
    }

    @Test
    public void toGrayscaleBluePixel() {
        int[] argb = new int[] { 0xFF0000FF }; // pure blue
        int[] gray = ImageProcessor.toGrayscale(argb, 1, 1);
        // (77*0 + 150*0 + 29*255) >> 8 = 28
        assertEquals(28, gray[0]);
    }

    @Test
    public void toGrayscaleMultiplePixels() {
        int[] argb = new int[] {
            0xFFFFFFFF, 0xFF000000, 0xFF808080, 0xFFFF0000
        };
        int[] gray = ImageProcessor.toGrayscale(argb, 2, 2);
        assertEquals(4, gray.length);
        assertTrue(gray[0] >= 254);  // white
        assertEquals(0, gray[1]);     // black
        // gray (128,128,128): (77*128 + 150*128 + 29*128)>>8 = 128
        assertEquals(128, gray[2]);
        assertEquals(76, gray[3]);    // red
    }

    @Test
    public void adaptiveThresholdUniformWhite() {
        int w = 16;
        int h = 16;
        int[] gray = new int[w * h];
        // All white (255)
        for (int i = 0; i < gray.length; i++) {
            gray[i] = 255;
        }
        boolean[][] grid = ImageProcessor.adaptiveThreshold(gray, w, h);
        assertEquals(h, grid.length);
        assertEquals(w, grid[0].length);
        // Uniform white -> nothing is "dark"
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                assertFalse("Uniform white should not produce dark pixels",
                        grid[y][x]);
            }
        }
    }

    @Test
    public void adaptiveThresholdUniformBlack() {
        int w = 16;
        int h = 16;
        int[] gray = new int[w * h];
        // All black (0)
        boolean[][] grid = ImageProcessor.adaptiveThreshold(gray, w, h);
        // Uniform black -> mean is 0, and 0 < (0 - offset) is false
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                assertFalse("Uniform black should not produce dark pixels " +
                        "(mean == value)", grid[y][x]);
            }
        }
    }

    @Test
    public void adaptiveThresholdDarkOnLight() {
        // Create a 16x16 image: light background (200) with a dark square (20)
        // in the center (4x4 pixels at positions 6-9).
        int w = 16;
        int h = 16;
        int[] gray = new int[w * h];
        for (int i = 0; i < gray.length; i++) {
            gray[i] = 200;
        }
        for (int y = 6; y <= 9; y++) {
            for (int x = 6; x <= 9; x++) {
                gray[y * w + x] = 20;
            }
        }

        boolean[][] grid = ImageProcessor.adaptiveThreshold(gray, w, h);

        // Center dark pixels should be detected as dark
        assertTrue("Center dark pixel should be true", grid[7][7]);
        assertTrue("Center dark pixel should be true", grid[8][8]);

        // Corner light pixels should not be dark
        assertFalse("Corner light pixel should be false", grid[0][0]);
        assertFalse("Corner light pixel should be false", grid[15][15]);
    }

    @Test
    public void adaptiveThresholdCheckerboard() {
        // 16x16 checkerboard: alternating 0 and 255 in 4x4 blocks
        int w = 16;
        int h = 16;
        int[] gray = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean darkBlock = ((x / 4) + (y / 4)) % 2 == 0;
                gray[y * w + x] = darkBlock ? 20 : 230;
            }
        }

        boolean[][] grid = ImageProcessor.adaptiveThreshold(gray, w, h);

        // Pixels deep in dark blocks should be true
        assertTrue("Center of dark block should be true", grid[1][1]);
        // Pixels deep in light blocks should be false
        assertFalse("Center of light block should be false", grid[1][5]);
    }

    // snapshotToGrid() uses javax.microedition.lcdui.Image which is only
    // available on real MIDP devices. The MIDP stub JARs throw errors, so
    // these tests are only runnable on-device.
    // The testable parts (toGrayscale + adaptiveThreshold) are covered above.

    @Test
    public void snapshotToGridNullReturnsNull() {
        // This path doesn't touch Image.createImage
        assertNull(ImageProcessor.snapshotToGrid(null));
    }

    @Test
    public void snapshotToGridEmptyReturnsNull() {
        // This path doesn't touch Image.createImage
        assertNull(ImageProcessor.snapshotToGrid(new byte[0]));
    }
}
