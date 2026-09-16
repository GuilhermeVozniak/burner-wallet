package org.burnerwallet.ui;

import java.util.Timer;
import java.util.TimerTask;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.media.control.VideoControl;

import org.burnerwallet.transport.CameraScanner;
import org.burnerwallet.transport.ImageProcessor;
import org.burnerwallet.transport.MultiFrameDecoder;
import org.burnerwallet.transport.QrDecoder;

/**
 * Camera-based QR code scanning screen.
 *
 * Displays a camera viewfinder and periodically captures snapshots
 * for QR code decoding. Supports multi-frame payloads via
 * {@link MultiFrameDecoder} and shows progress ("Frame 2/3 received").
 *
 * Falls back to manual entry if the camera is not available or
 * the user selects the "Manual" command.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class QrScanScreen extends Canvas implements CommandListener {

    /** Snapshot capture interval in milliseconds. */
    private static final long SCAN_INTERVAL_MS = 1000;

    /**
     * Callback interface for scan events.
     */
    public interface QrScanListener {
        /**
         * Called when scanning completes with a full payload.
         *
         * @param payload the decoded payload bytes
         */
        void onScanComplete(byte[] payload);

        /**
         * Called when the user cancels scanning.
         */
        void onScanCancelled();
    }

    private final ScreenManager screens;
    private final QrScanListener listener;
    private final CameraScanner camera;
    private final MultiFrameDecoder decoder;

    private Timer scanTimer;
    private boolean cameraStarted;
    private String statusMessage;
    private String errorMessage;

    private final Command manualCmd;
    private final Command cancelCmd;

    /**
     * Create a new QrScanScreen.
     *
     * @param screens  the screen manager for display control
     * @param listener callback for scan events
     */
    public QrScanScreen(ScreenManager screens, QrScanListener listener) {
        this.screens = screens;
        this.listener = listener;
        this.camera = new CameraScanner();
        this.decoder = new MultiFrameDecoder();
        this.statusMessage = "Starting camera...";

        // Short label: a 128 px command bar cannot fit "Enter Manually" next to "Cancel"
        manualCmd = new Command("Manual", Command.SCREEN, 1);
        cancelCmd = new Command("Cancel", Command.BACK, 2);
        addCommand(manualCmd);
        addCommand(cancelCmd);
        setCommandListener(this);
    }

    /**
     * Get this Canvas as a Displayable for ScreenManager.
     *
     * @return this Canvas
     */
    public Displayable getScreen() {
        return this;
    }

    /**
     * Start the camera and scanning timer.
     * Call this after the screen is shown.
     */
    public void startScanning() {
        if (!camera.isAvailable()) {
            errorMessage = "Camera not available";
            statusMessage = "Use Manual";
            repaint();
            return;
        }

        try {
            camera.startCamera();
            cameraStarted = true;

            // Set up viewfinder if possible
            VideoControl vc = camera.getVideoControl();
            if (vc != null) {
                vc.initDisplayMode(VideoControl.USE_DIRECT_VIDEO, this);
                vc.setDisplayLocation(0, 0);
                try {
                    vc.setDisplaySize(getWidth(), getHeight());
                } catch (Exception e) {
                    // Some devices don't support resize
                }
                vc.setVisible(true);
            }

            statusMessage = "Scanning...";
            startScanTimer();
        } catch (Exception e) {
            errorMessage = "Camera error: " + e.getMessage();
            statusMessage = "Use Manual";
        }
        repaint();
    }

    /**
     * Stop camera and scanning timer, release resources.
     */
    public void destroy() {
        stopScanTimer();
        if (cameraStarted) {
            camera.stopCamera();
            cameraStarted = false;
        }
    }

    /**
     * Release the camera when the canvas is hidden (incoming call, pause);
     * a leaked Player would make the next scan fail with "device busy".
     */
    protected void hideNotify() {
        destroy();
    }

    /**
     * Greedy word wrap for a proportional font. Words wider than the line
     * are split by character so nothing is ever drawn off-screen.
     */
    private static String[] wrapText(String text, Font font, int maxWidth) {
        java.util.Vector lines = new java.util.Vector();
        StringBuffer line = new StringBuffer();
        int start = 0;
        int len = text.length();
        while (start < len) {
            int end = text.indexOf(' ', start);
            if (end < 0) {
                end = len;
            }
            String word = text.substring(start, end);
            start = end + 1;
            if (word.length() == 0) {
                continue;
            }
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (font.stringWidth(candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (line.length() > 0) {
                lines.addElement(line.toString());
                line.setLength(0);
            }
            // Word alone is too wide: split it by characters
            while (font.stringWidth(word) > maxWidth && word.length() > 1) {
                int cut = word.length() - 1;
                while (cut > 1 && font.stringWidth(word.substring(0, cut)) > maxWidth) {
                    cut--;
                }
                lines.addElement(word.substring(0, cut));
                word = word.substring(cut);
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.addElement(line.toString());
        }
        String[] out = new String[lines.size()];
        lines.copyInto(out);
        return out;
    }

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();

        // If camera viewfinder is active, it paints the background.
        // We overlay status text.
        if (!cameraStarted) {
            // No camera — dark background
            g.setColor(0x000000);
            g.fillRect(0, 0, w, h);
        }

        Font font = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD,
                Font.SIZE_SMALL);
        g.setFont(font);

        // Messages are word-wrapped to the screen width and stacked upward
        // from the bottom so long camera errors stay readable on 128 px.
        int lineHeight = font.getHeight() + 2;
        String[] errorLines = errorMessage == null
                ? new String[0] : wrapText(errorMessage, font, w - 4);
        String[] statusLines = statusMessage == null
                ? new String[0] : wrapText(statusMessage, font, w - 4);
        int yPos = h - lineHeight * (errorLines.length + statusLines.length) - 2;
        if (yPos < 0) {
            yPos = 0;
        }

        // Error message in red
        g.setColor(0xFF0000);
        for (int i = 0; i < errorLines.length; i++) {
            int ew = font.stringWidth(errorLines[i]);
            g.drawString(errorLines[i], (w - ew) / 2, yPos,
                    Graphics.TOP | Graphics.LEFT);
            yPos += lineHeight;
        }

        // Status message in white
        g.setColor(0xFFFFFF);
        for (int i = 0; i < statusLines.length; i++) {
            int sw = font.stringWidth(statusLines[i]);
            g.drawString(statusLines[i], (w - sw) / 2, yPos,
                    Graphics.TOP | Graphics.LEFT);
            yPos += lineHeight;
        }

        // Progress indicator
        if (decoder.getTotalCount() > 0) {
            String progress = "Frame " + decoder.getReceivedCount()
                    + "/" + decoder.getTotalCount();
            g.setColor(0x00FF00);
            Font smallFont = Font.getFont(Font.FACE_SYSTEM,
                    Font.STYLE_PLAIN, Font.SIZE_SMALL);
            g.setFont(smallFont);
            int pw = smallFont.stringWidth(progress);
            g.drawString(progress, (w - pw) / 2, 2,
                    Graphics.TOP | Graphics.LEFT);
        }
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cancelCmd) {
            destroy();
            listener.onScanCancelled();
        } else if (c == manualCmd) {
            destroy();
            // Signal cancellation — caller should show ManualEntryScreen
            listener.onScanCancelled();
        }
    }

    // ---- Timer management ----

    private void startScanTimer() {
        stopScanTimer();
        scanTimer = new Timer();
        scanTimer.schedule(new ScanTask(), SCAN_INTERVAL_MS, SCAN_INTERVAL_MS);
    }

    private void stopScanTimer() {
        if (scanTimer != null) {
            scanTimer.cancel();
            scanTimer = null;
        }
    }

    /**
     * TimerTask that captures a snapshot and attempts to decode it.
     */
    private class ScanTask extends TimerTask {
        public void run() {
            if (!cameraStarted) {
                return;
            }

            try {
                byte[] snapshot = camera.getSnapshot();
                if (snapshot == null) {
                    return;
                }

                // Steps 1-3: snapshot bytes -> grayscale -> boolean grid
                boolean[][] grid = ImageProcessor.snapshotToGrid(snapshot);
                if (grid == null) {
                    return;
                }

                // NOTE: This grid covers the entire camera frame. For robust
                // QR decoding from camera, finder pattern detection and
                // perspective correction (steps 4-5) would be needed.
                // For now, attempt direct decode — works when the QR code
                // fills most of the camera frame.
                int size = grid.length;
                byte[] decoded = QrDecoder.decode(grid, size);
                if (decoded == null) {
                    return;
                }

                // Feed decoded frame to the multi-frame decoder
                decoder.addFrame(decoded);
                if (decoder.isComplete()) {
                    byte[] payload = decoder.assemble();
                    // Release camera + timer before handing off, otherwise
                    // the Player stays open during review.
                    destroy();
                    listener.onScanComplete(payload);
                    return;
                }

                statusMessage = "Frame " + decoder.getReceivedCount()
                        + "/" + decoder.getTotalCount() + " received";
                repaint();

            } catch (Throwable t) {
                // Includes OutOfMemoryError from a large snapshot: an Error
                // escaping here would kill the Timer thread and leave the
                // screen stuck on "Scanning...".
                statusMessage = "Scan error";
                repaint();
            }
        }
    }
}
