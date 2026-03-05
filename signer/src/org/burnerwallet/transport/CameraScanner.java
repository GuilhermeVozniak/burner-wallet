package org.burnerwallet.transport;

import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.control.VideoControl;

/**
 * MMAPI camera wrapper for capturing video snapshots on MIDP 2.0 devices.
 *
 * Manages the lifecycle of a camera {@link Player} and provides snapshot
 * capture via {@link VideoControl}. Used by {@link org.burnerwallet.ui.QrScanScreen}
 * to capture frames for QR code decoding.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class CameraScanner {

    private Player player;
    private VideoControl videoCtrl;

    /**
     * Start the camera capture pipeline.
     * Must be called before {@link #getSnapshot()} or {@link #getVideoControl()}.
     *
     * @throws Exception if the camera cannot be opened or started
     */
    public void startCamera() throws Exception {
        player = Manager.createPlayer("capture://video");
        player.realize();
        videoCtrl = (VideoControl) player.getControl("VideoControl");
        player.start();
    }

    /**
     * Capture a single snapshot from the camera.
     *
     * @return raw image bytes (format depends on device), or null if
     *         the video control is not initialized
     * @throws Exception if the snapshot capture fails
     */
    public byte[] getSnapshot() throws Exception {
        if (videoCtrl == null) {
            return null;
        }
        return videoCtrl.getSnapshot(null);
    }

    /**
     * Get the underlying VideoControl for viewfinder display.
     *
     * @return the VideoControl, or null if camera is not started
     */
    public VideoControl getVideoControl() {
        return videoCtrl;
    }

    /**
     * Stop the camera and release all resources.
     * Safe to call even if the camera was never started.
     */
    public void stopCamera() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception e) {
                // Best-effort stop
            }
            player.close();
            player = null;
            videoCtrl = null;
        }
    }

    /**
     * Check whether the device supports video capture.
     *
     * @return true if "capture://video" is a supported protocol
     */
    public boolean isAvailable() {
        try {
            String[] protocols = Manager.getSupportedProtocols("capture");
            if (protocols != null) {
                for (int i = 0; i < protocols.length; i++) {
                    if ("video".equals(protocols[i])) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // Not available
        }
        return false;
    }
}
