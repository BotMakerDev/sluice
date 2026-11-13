package com.botmaker.sluice.desktop.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * What the window remembers for this user: the umbrellas opened, newest first, and the theme. The one state the
 * window keeps; everything else it reads from the umbrella.
 */
public final class Prefs {

    /** How many umbrellas the start page lists. */
    public static final int RECENT = 8;

    private static final String RECENT_KEY = "recent";
    private static final String THEME_KEY = "theme";

    private final Preferences node;

    public Prefs(Preferences node) {
        this.node = node;
    }

    public static Prefs user() {
        return new Prefs(Preferences.userRoot().node("sluice-desktop"));
    }

    /** The umbrellas opened, newest first. */
    public List<Path> recent() {
        String stored = node.get(RECENT_KEY, "");
        return Arrays.stream(stored.split("\n")).filter(line -> !line.isBlank()).map(Path::of).toList();
    }

    /** Puts {@code root} first, once. */
    public void opened(Path root) {
        List<Path> next = new ArrayList<>(List.of(root));
        recent().stream().filter(p -> !p.equals(root)).limit(RECENT - 1).forEach(next::add);
        node.put(RECENT_KEY, String.join("\n", next.stream().map(Path::toString).toList()));
        flush();
    }

    public void forget(Path root) {
        List<Path> next = recent().stream().filter(p -> !p.equals(root)).toList();
        node.put(RECENT_KEY, String.join("\n", next.stream().map(Path::toString).toList()));
        flush();
    }

    public Optional<Theme> theme() {
        return Theme.fromId(node.get(THEME_KEY, ""));
    }

    public void theme(Theme theme) {
        node.put(THEME_KEY, theme.id());
        flush();
    }

    private void flush() {
        try {
            node.flush();
        } catch (BackingStoreException e) {
            // Kept for this run; the next start forgets it, which costs a click.
        }
    }
}
