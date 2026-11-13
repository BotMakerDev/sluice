package com.botmaker.sluice.desktop;

import javafx.application.Application;

/**
 * The shaded jar's main class: JavaFX refuses to start an {@link Application} subclass named as the main class
 * when it is not on the module path, so this one, which is not one, starts it.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(SluiceDesktop.class, args);
    }
}
