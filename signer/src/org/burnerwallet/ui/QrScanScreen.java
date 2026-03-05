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
 * the user selects the "Enter Manually" command.
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

        manualCmd = new Command("Enter Manually", Command.SCREEN, 1);
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
            statusMessage = "Use Enter Manually";
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
            statusMessage = "Use Enter Manually";
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

        int yPos = h - font.getHeight() * 3;

        // Error message in red
        if (errorMessage != null) {
            g.setColor(0xFF0000);
            int ew = font.stringWidth(errorMessage);
            g.drawString(errorMessage, (w - ew) / 2, yPos,
                    Graphics.TOP | Graphics.LEFT);
            yPos += font.getHeight() + 2;
        }

        // Status message in white
        if (statusMessage != null) {
            g.setColor(0xFFFFFF);
            int sw = font.stringWidth(statusMessage);
            g.drawString(statusMessage, (w - sw) / 2, yPos,
                    Graphics.TOP | Graphics.LEFT);
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

                // TODO: Convert snapshot image to grayscale boolean[][] grid.
                // This requires device-specific image decoding (PNG/JPEG)
                // which varies across J2ME devices. For now, this is a
                // placeholder — actual image-to-grid conversion will be
                // implemented when testing on real hardware.
                //
                // The decoding pipeline would be:
                // 1. Decode snapshot bytes to pixel array
                // 2. Convert to grayscale
                // 3. Apply adaptive threshold to get boolean[][] grid
                // 4. Detect finder patterns to locate QR code
                // 5. Extract and de-warp module grid
                // 6. Pass to QrDecoder.decode()
                //
                // For now, QrDecoder is verified via round-trip tests.
                // Camera scanning will be validated on-device in M2.

            } catch (Exception e) {
                statusMessage = "Scan error";
                repaint();
            }
        }
    }
}
