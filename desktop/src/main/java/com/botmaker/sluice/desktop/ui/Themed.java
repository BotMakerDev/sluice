package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.desktop.model.Theme;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

/**
 * The one stylesheet and the theme class, on every window's scene root: the main one, each dialog, menu, tooltip
 * and popup, since each is a scene of its own and a token set on the main root does not reach it.
 */
public final class Themed {

    public static final String STYLESHEET = Themed.class.getResource("/css/sluice.css").toExternalForm();

    private static Theme current = Theme.DARK;

    private Themed() {
    }

    /** The preference, else the desktop's scheme, else dark. */
    public static Theme resolve(Optional<Theme> preference) {
        if (preference.isPresent()) {
            return preference.get();
        }
        try {
            return Platform.getPreferences().getColorScheme() == ColorScheme.LIGHT ? Theme.LIGHT : Theme.DARK;
        } catch (RuntimeException | LinkageError e) {
            return Theme.DARK;
        }
    }

    /** Themes each window as it appears. FX thread, before the first window. */
    public static void install(Theme theme) {
        current = theme;
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                change.getAddedSubList().forEach(Themed::decorate);
            }
        });
    }

    public static Theme current() {
        return current;
    }

    public static void set(Theme theme) {
        current = theme;
        List.copyOf(Window.getWindows()).forEach(Themed::restyle);
    }

    /** Every dialog goes through here: owned by {@code owner}, styled before it is first sized. */
    public static <D extends Dialog<?>> D dialog(D dialog, Window owner) {
        if (owner != null && dialog.getOwner() == null) {
            dialog.initOwner(owner);
        }
        if (!dialog.getDialogPane().getStylesheets().contains(STYLESHEET)) {
            dialog.getDialogPane().getStylesheets().add(STYLESHEET);
        }
        return dialog;
    }

    public static void scene(Scene scene) {
        if (!scene.getStylesheets().contains(STYLESHEET)) {
            scene.getStylesheets().add(STYLESHEET);
        }
        if (scene.getRoot() != null) {
            mark(scene.getRoot());
        }
    }

    private static void decorate(Window window) {
        restyle(window);
        window.sceneProperty().addListener((obs, was, now) -> restyle(window));
    }

    private static void restyle(Window window) {
        if (window.getScene() != null) {
            scene(window.getScene());
        }
    }

    private static void mark(Parent node) {
        for (Theme theme : Theme.values()) {
            node.getStyleClass().remove(theme.styleClass());
        }
        node.getStyleClass().add(current.styleClass());
    }
}
