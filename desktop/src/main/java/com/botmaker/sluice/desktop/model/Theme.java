package com.botmaker.sluice.desktop.model;

import java.util.Optional;

/** The window's two palettes. {@link #id()} is what the prefs keep; {@link #styleClass()} what scene roots carry. */
public enum Theme {

    DARK("dark"),
    LIGHT("light");

    private final String id;

    Theme(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public String styleClass() {
        return "theme-" + id;
    }

    public Theme other() {
        return this == DARK ? LIGHT : DARK;
    }

    /** Empty for no preference: the window then follows the desktop. */
    public static Optional<Theme> fromId(String id) {
        for (Theme theme : values()) {
            if (theme.id.equals(id)) {
                return Optional.of(theme);
            }
        }
        return Optional.empty();
    }
}
