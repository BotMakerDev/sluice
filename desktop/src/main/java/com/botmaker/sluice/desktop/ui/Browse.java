package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.Proc;
import com.botmaker.sluice.desktop.model.Io;
import javafx.application.HostServices;
import javafx.application.Platform;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * The one way the window opens a URL: the system's opener first ({@code xdg-open}, {@code open}, Windows' URL
 * handler), JavaFX's host services when that fails, and a sentence with the link when both do.
 */
public final class Browse {

    private static volatile HostServices hostServices;

    private Browse() {
    }

    public static void install(HostServices services) {
        hostServices = services;
    }

    public static void open(String url, Consumer<String> failed) {
        Io.async(() -> Proc.run(Path.of("."), opener(url)).ok()).thenAccept(opened -> {
            if (opened) {
                return;
            }
            Platform.runLater(() -> {
                HostServices services = hostServices;
                if (services != null) {
                    try {
                        services.showDocument(url);
                        return;
                    } catch (RuntimeException e) {
                        // the sentence below
                    }
                }
                failed.accept("Could not open a browser — the link is " + url);
            });
        });
    }

    static List<String> opener(String url) {
        if (Proc.isWindows()) {
            return List.of("rundll32", "url.dll,FileProtocolHandler", url);
        }
        return System.getProperty("os.name", "").toLowerCase().contains("mac")
                ? List.of("open", url) : List.of("xdg-open", url);
    }
}
