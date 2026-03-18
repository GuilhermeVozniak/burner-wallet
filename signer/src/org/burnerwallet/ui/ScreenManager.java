package org.burnerwallet.ui;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Gauge;
import javax.microedition.midlet.MIDlet;

public class ScreenManager {
    private final MIDlet midlet;
    private final Display display;

    public ScreenManager(MIDlet midlet) {
        this.midlet = midlet;
        this.display = Display.getDisplay(midlet);
    }

    public void showScreen(Displayable screen) {
        display.setCurrent(screen);
    }

    public void showAlert(String title, String message, AlertType type,
                          Displayable next, int timeoutMs) {
        Alert alert = new Alert(title, message, null, type);
        alert.setTimeout(timeoutMs);
        display.setCurrent(alert, next);
    }

    public void showError(String message, Displayable returnTo) {
        showAlert("Error", message, AlertType.ERROR, returnTo, 3000);
    }

    public void showInfo(String message, Displayable next) {
        showAlert("Info", message, AlertType.INFO, next, 2000);
    }

    public void showModalAlert(String title, String message, AlertType type,
                                Displayable next) {
        Alert alert = new Alert(title, message, null, type);
        alert.setTimeout(Alert.FOREVER);
        display.setCurrent(alert, next);
    }

    /**
     * Show an indeterminate loading screen with the given message.
     * Use with runAsync() for long-running operations.
     *
     * @param message text to display (e.g. "Deriving keys...")
     */
    public void showLoading(String message) {
        Form form = new Form("Please wait");
        Gauge gauge = new Gauge(message, false,
                Gauge.INDEFINITE, Gauge.CONTINUOUS_RUNNING);
        form.append(gauge);
        display.setCurrent(form);
    }

    /**
     * Run a task on a background thread. When complete, the callback
     * is invoked on the UI thread via Display.callSerially().
     *
     * Java 1.4 compatible (CLDC 1.1).
     *
     * @param task     the work to execute in background
     * @param callback called on the UI thread when task finishes
     */
    public void runAsync(final Runnable task, final Runnable callback) {
        new Thread(new Runnable() {
            public void run() {
                task.run();
                display.callSerially(callback);
            }
        }).start();
    }

    public Display getDisplay() { return display; }
    public MIDlet getMidlet() { return midlet; }

    public void exit() {
        midlet.notifyDestroyed();
    }
}
