package org.burnerwallet.ui;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
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

    /**
     * Show an alert, then continue to {@code next}. If {@code next} is null
     * the alert returns to the previously current screen. (MIDP throws
     * NullPointerException from {@code setCurrent(alert, null)}, which would
     * kill the MIDlet from inside an error handler.)
     */
    public void showAlert(String title, String message, AlertType type,
                          Displayable next, int timeoutMs) {
        Alert alert = new Alert(title, message, null, type);
        alert.setTimeout(timeoutMs);
        if (next == null) {
            display.setCurrent(alert);
        } else {
            display.setCurrent(alert, next);
        }
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
        if (next == null) {
            display.setCurrent(alert);
        } else {
            display.setCurrent(alert, next);
        }
    }

    public Display getDisplay() { return display; }
    public MIDlet getMidlet() { return midlet; }

    public void exit() {
        midlet.notifyDestroyed();
    }
}
