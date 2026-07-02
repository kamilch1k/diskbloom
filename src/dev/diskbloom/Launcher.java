package dev.diskbloom;

import dev.diskbloom.ui.App;

/**
 * Plain (non-Application) entry point. Launching a class that extends
 * Application directly trips the "JavaFX runtime components are missing" check;
 * bouncing through here avoids it.
 */
public final class Launcher {

    public static void main(String[] args) {
        App.main(args);
    }

    private Launcher() {}
}
