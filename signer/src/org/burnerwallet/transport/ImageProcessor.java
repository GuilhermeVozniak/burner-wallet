package org.burnerwallet.transport;

import javax.microedition.lcdui.Image;

/**
 * Converts camera snapshot bytes to a grayscale boolean grid suitable
 * for QR code decoding.
 *
 * The pipeline is:
 * <ol>
 *   <li>Decode snapshot bytes to a MIDP {@link Image}</li>
 *   <li>Extract ARGB pixel array via {@code getRGB()}</li>
 *   <li>Convert each pixel to grayscale luminance (0-255)</li>
 *   <li>Apply block-based adaptive threshold to produce boolean grid</li>
 * </ol>
 *
 * The boolean grid uses the same convention as {@link QrDecoder}:
 * {@code true} = dark module, {@code false} = light module.
 *
 * Java 1.4 compatible (CLDC 1.1 / MIDP 2.0).
 */
public final class ImageProcessor {

    /** Block size for adaptive thresholding (in pixels). */
    private static final int BLOCK_SIZE = 8;

    /** Threshold offset: a pixel must be this much darker than the block mean. */
    private static final int THRESHOLD_OFFSET = 15;

    /**
     * Convert raw snapshot image bytes to a boolean grid.
     *
     * @param imageBytes raw PNG or JPEG bytes from VideoControl.getSnapshot()
     * @return boolean grid where true = dark pixel, false = light pixel,
     *         or null if decoding fails
     */
    public static boolean[][] snapshotToGrid(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }

        Image img;
        try {
            img = Image.createImage(imageBytes, 0, imageBytes.length);
        } catch (Exception e) {
            return null;
        }

        int w = img.getWidth();
        int h = img.getHeight();
        if (w == 0 || h == 0) {
            return null;
        }

        int[] argb = new int[w * h];
        img.getRGB(argb, 0, w, 0, 0, w, h);

        int[] gray = toGrayscale(argb, w, h);
        return adaptiveThreshold(gray, w, h);
    }

    /**
     * Convert a grayscale luminance array to a boolean grid using
     * block-based adaptive thresholding.
     *
     * This is equivalent to {@link #snapshotToGrid(byte[])} but accepts
     * pre-computed grayscale values, making it testable without MIDP images.
     *
     * @param gray  luminance values 0-255, row-major [y*width + x]
     * @param width  image width in pixels
     * @param height image height in pixels
     * @return boolean grid (true = dark)
     */
    public static boolean[][] adaptiveThreshold(int[] gray, int width,
            int height) {
        boolean[][] grid = new boolean[height][width];

        // Build integral image for fast block-mean computation.
        // Using long[] to avoid overflow on large images.
        long[] integral = new long[width * height];
        for (int y = 0; y < height; y++) {
            long rowSum = 0;
            for (int x = 0; x < width; x++) {
                rowSum += gray[y * width + x];
                long above = (y > 0) ? integral[(y - 1) * width + x] : 0;
                integral[y * width + x] = rowSum + above;
            }
        }

        int halfBlock = BLOCK_SIZE / 2;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Define the averaging window (clamped to image bounds)
                int y1 = (y - halfBlock > 0) ? y - halfBlock : 0;
                int x1 = (x - halfBlock > 0) ? x - halfBlock : 0;
                int y2 = (y + halfBlock < height) ? y + halfBlock
                        : height - 1;
                int x2 = (x + halfBlock < width) ? x + halfBlock
                        : width - 1;

                int count = (y2 - y1 + 1) * (x2 - x1 + 1);

                // Sum of pixel values in the window via integral image
                long sum = integral[y2 * width + x2];
                if (y1 > 0) {
                    sum -= integral[(y1 - 1) * width + x2];
                }
                if (x1 > 0) {
                    sum -= integral[y2 * width + (x1 - 1)];
                }
                if (y1 > 0 && x1 > 0) {
                    sum += integral[(y1 - 1) * width + (x1 - 1)];
                }

                int mean = (int) (sum / count);
                grid[y][x] = gray[y * width + x] < mean - THRESHOLD_OFFSET;
            }
        }

        return grid;
    }

    /**
     * Convert ARGB pixel array to grayscale luminance (0-255).
     *
     * Uses the ITU-R BT.601 luma formula:
     * Y = 0.299*R + 0.587*G + 0.114*B
     *
     * Implemented with fixed-point integer math (no floating point).
     *
     * @param argb  ARGB pixel array
     * @param width  image width
     * @param height image height
     * @return grayscale luminance values 0-255
     */
    public static int[] toGrayscale(int[] argb, int width, int height) {
        int len = width * height;
        int[] gray = new int[len];
        for (int i = 0; i < len; i++) {
            int pixel = argb[i];
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            // Fixed-point: 77/256 ~= 0.301, 150/256 ~= 0.586, 29/256 ~= 0.113
            gray[i] = (77 * r + 150 * g + 29 * b) >> 8;
        }
        return gray;
    }

    // Prevent instantiation
    private ImageProcessor() {
    }
}
