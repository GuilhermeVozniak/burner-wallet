package org.burnerwallet.ui;

import java.util.Timer;
import java.util.TimerTask;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

import org.burnerwallet.transport.MultiFrameEncoder;
import org.burnerwallet.transport.QrCode;

/**
 * Canvas-based QR code display with multi-frame auto-advance.
 *
 * Renders QR codes on the Nokia 128x160 screen. For payloads that exceed
 * a single QR frame, splits the data via {@link MultiFrameEncoder} and
 * cycles through frames automatically every 2 seconds. The user can also
 * navigate manually with LEFT/RIGHT keys.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class QrDisplayScreen extends Canvas implements CommandListener {

    /** Maximum bytes per QR frame (including 2-byte header). */
    private static final int MAX_BYTES_PER_FRAME = 150;

    /** Auto-advance interval in milliseconds. */
    private static final long ADVANCE_INTERVAL_MS = 2000;

    /** Quiet zone (margin) around the QR code in pixels. */
    private static final int QUIET_ZONE = 2;

    /**
     * Callback interface for QR display events.
     */
    public interface QrDisplayListener {
        /**
         * Called when the user is done viewing the QR code(s).
         */
        void onQrDisplayDone();
    }

    private final ScreenManager screens;
    private final QrDisplayListener listener;
    private final String title;

    private final QrCode[] qrCodes;
    private final int totalFrames;
    private int currentFrame;

    private Timer timer;
    private final Command doneCmd;

    /**
     * Create a new QrDisplayScreen.
     *
     * @param screens  the screen manager for display control
     * @param listener callback for display events
     * @param payload  the binary data to encode as QR code(s)
     * @param title    title text shown above the QR code
     */
    public QrDisplayScreen(ScreenManager screens, QrDisplayListener listener,
                           byte[] payload, String title) {
        this.screens = screens;
        this.listener = listener;
        this.title = title;

        // Split payload into frames
        byte[][] frames = MultiFrameEncoder.encode(payload, MAX_BYTES_PER_FRAME);
        this.totalFrames = frames.length;
        this.currentFrame = 0;

        // Pre-generate QR codes for all frames
        this.qrCodes = new QrCode[totalFrames];
        for (int i = 0; i < totalFrames; i++) {
            qrCodes[i] = QrCode.encodeBinary(frames[i], QrCode.ECC_LOW);
        }

        doneCmd = new Command("Done", Command.OK, 1);
        addCommand(doneCmd);
        setCommandListener(this);

        // Start auto-advance timer if multiple frames
        if (totalFrames > 1) {
            startTimer();
        }
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
     * Cancel the auto-advance timer and release resources.
     */
    public void destroy() {
        stopTimer();
    }

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();

        // White background
        g.setColor(0xFFFFFF);
        g.fillRect(0, 0, w, h);

        Font font = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        g.setFont(font);
        g.setColor(0x000000);

        int yOffset = 2;

        // Frame counter (only if multi-frame)
        if (totalFrames > 1) {
            String counter = (currentFrame + 1) + "/" + totalFrames;
            int counterWidth = font.stringWidth(counter);
            g.drawString(counter, (w - counterWidth) / 2, yOffset,
                    Graphics.TOP | Graphics.LEFT);
            yOffset += font.getHeight() + 2;
        }

        // Title
        if (title != null && title.length() > 0) {
            int titleWidth = font.stringWidth(title);
            // Truncate if too wide
            String displayTitle = title;
            if (titleWidth > w - 4) {
                while (font.stringWidth(displayTitle + "...") > w - 4
                        && displayTitle.length() > 0) {
                    displayTitle = displayTitle.substring(0, displayTitle.length() - 1);
                }
                displayTitle = displayTitle + "...";
            }
            int dtw = font.stringWidth(displayTitle);
            g.drawString(displayTitle, (w - dtw) / 2, yOffset,
                    Graphics.TOP | Graphics.LEFT);
            yOffset += font.getHeight() + 2;
        }

        // Draw QR code centered in remaining space
        QrCode qr = qrCodes[currentFrame];
        int qrSize = qr.size;
        int availableHeight = h - yOffset - 4;
        int availableWidth = w - 4;
        int available = availableWidth < availableHeight ? availableWidth : availableHeight;

        // Calculate module scale: fit QR + quiet zone into available space
        int totalModules = qrSize + QUIET_ZONE * 2;
        int scale = available / totalModules;
        if (scale < 1) {
            scale = 1;
        }

        int qrPixelSize = totalModules * scale;
        int qrX = (w - qrPixelSize) / 2;
        int qrY = yOffset + (availableHeight - qrPixelSize) / 2;
        if (qrY < yOffset) {
            qrY = yOffset;
        }

        // White quiet zone background
        g.setColor(0xFFFFFF);
        g.fillRect(qrX, qrY, qrPixelSize, qrPixelSize);

        // Draw dark modules
        g.setColor(0x000000);
        for (int row = 0; row < qrSize; row++) {
            for (int col = 0; col < qrSize; col++) {
                if (qr.getModule(col, row)) {
                    int px = qrX + (col + QUIET_ZONE) * scale;
                    int py = qrY + (row + QUIET_ZONE) * scale;
                    g.fillRect(px, py, scale, scale);
                }
            }
        }
    }

    protected void keyPressed(int keyCode) {
        int action = getGameAction(keyCode);
        if (action == Canvas.LEFT) {
            // Navigate to previous frame
            if (totalFrames > 1) {
                currentFrame = (currentFrame - 1 + totalFrames) % totalFrames;
                restartTimer();
                repaint();
            }
        } else if (action == Canvas.RIGHT) {
            // Navigate to next frame
            if (totalFrames > 1) {
                currentFrame = (currentFrame + 1) % totalFrames;
                restartTimer();
                repaint();
            }
        }
    }

    public void commandAction(Command c, Displayable d) {
        if (c == doneCmd) {
            destroy();
            listener.onQrDisplayDone();
        }
    }

    // ---- Timer management ----

    private void startTimer() {
        stopTimer();
        timer = new Timer();
        timer.schedule(new AdvanceTask(), ADVANCE_INTERVAL_MS, ADVANCE_INTERVAL_MS);
    }

    private void stopTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    private void restartTimer() {
        if (totalFrames > 1) {
            startTimer();
        }
    }

    /**
     * TimerTask that advances to the next frame and repaints.
     */
    private class AdvanceTask extends TimerTask {
        public void run() {
            currentFrame = (currentFrame + 1) % totalFrames;
            repaint();
        }
    }
}
