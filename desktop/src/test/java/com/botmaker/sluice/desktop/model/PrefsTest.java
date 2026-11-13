package com.botmaker.sluice.desktop.model;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PrefsTest {

    private Preferences node;
    private Prefs prefs;

    @BeforeEach
    void node() {
        node = Preferences.userRoot().node("sluice-desktop-test-" + ProcessHandle.current().pid());
        prefs = new Prefs(node);
    }

    @AfterEach
    void remove() throws BackingStoreException {
        node.removeNode();
    }

    @Test
    void theNewestOpenedComesFirstOnce() {
        Path one = Path.of("one").toAbsolutePath();
        Path two = Path.of("two").toAbsolutePath();
        prefs.opened(one);
        prefs.opened(two);
        prefs.opened(one);

        assertEquals(List.of(one, two), prefs.recent());
        prefs.forget(one);
        assertEquals(List.of(two), prefs.recent());
    }

    @Test
    void theListKeepsTheNewestOnly() {
        for (int i = 0; i < Prefs.RECENT + 3; i++) {
            prefs.opened(Path.of("u" + i).toAbsolutePath());
        }

        assertEquals(Prefs.RECENT, prefs.recent().size());
        assertEquals(Path.of("u" + (Prefs.RECENT + 2)).toAbsolutePath(), prefs.recent().getFirst());
    }

    @Test
    void theThemeIsKept() {
        assertEquals(Optional.empty(), prefs.theme());
        prefs.theme(Theme.LIGHT);
        assertEquals(Optional.of(Theme.LIGHT), new Prefs(node).theme());
    }
}
