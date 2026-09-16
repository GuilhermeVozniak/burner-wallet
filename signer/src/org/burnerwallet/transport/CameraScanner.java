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
        // Ask for a small frame first: a full VGA snapshot plus its
        // grayscale/integral buffers does not fit the C1-01's 2 MB heap.
        try {
            byte[] small = videoCtrl.getSnapshot("encoding=jpeg&width=160&height=120");
            if (small != null) {
                return small;
            }
        } catch (Exception e) {
            // Device does not support that encoding string; fall through
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
     * Uses the MMAPI system property {@code supports.video.capture} and,
     * failing that, the content types offered for the {@code capture}
     * protocol. ({@code Manager.getSupportedProtocols} takes a content
     * type, not a protocol name, so querying it with "capture" can never
     * report availability.)
     *
     * @return true if video/image capture is supported
     */
    public boolean isAvailable() {
        try {
            String prop = System.getProperty("supports.video.capture");
            if ("true".equals(prop)) {
                return true;
            }
            String[] types = Manager.getSupportedContentTypes("capture");
            if (types != null) {
                for (int i = 0; i < types.length; i++) {
                    if (types[i] != null
                            && (types[i].startsWith("video/")
                                || types[i].startsWith("image/"))) {
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
