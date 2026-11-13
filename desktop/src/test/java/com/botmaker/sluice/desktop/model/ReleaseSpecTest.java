package com.botmaker.sluice.desktop.model;

import com.botmaker.sluice.Level;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseSpecTest {

    @Test
    void theCommandLineSpellsEveryFlagAsTheCommandTakesIt() {
        Map<String, String> picked = new LinkedHashMap<>();
        picked.put("core", "1.2.0");
        picked.put("cli", " minor ");
        ReleaseSpec spec = new ReleaseSpec(Optional.of(Level.PATCH), picked, true, true, true);

        assertEquals("sluice --all patch --module core=1.2.0 --module cli=minor --with-dependents --force --no-wait",
                spec.commandLine());
        assertEquals(spec.commandLine() + " --execute", spec.executeCommandLine());
    }

    @Test
    void aRowIsALevelOrAVersion() {
        assertTrue(ReleaseSpec.wellFormed("major"));
        assertTrue(ReleaseSpec.wellFormed("2.0.0"));
        assertFalse(ReleaseSpec.wellFormed("soon"));
        assertFalse(new ReleaseSpec(Optional.empty(), Map.of("a", "soon"), false, false, false).wellFormed());
    }

    @Test
    void noWaitIsNotWaitingForTargets() {
        ReleaseSpec spec = new ReleaseSpec(Optional.empty(), Map.of("a", "patch"), false, false, true);

        assertFalse(spec.options(true).waitForTargets());
        assertTrue(spec.options(true).execute());
        assertFalse(spec.options(false).execute());
        assertFalse(spec.empty());
        assertTrue(new ReleaseSpec(Optional.empty(), Map.of(), false, false, false).empty());
    }

    @Test
    void equalSpecsAreEqual() {
        assertEquals(new ReleaseSpec(Optional.of(Level.MINOR), Map.of("a", "patch"), false, true, false),
                new ReleaseSpec(Optional.of(Level.MINOR), Map.of("a", "patch"), false, true, false));
    }
}
