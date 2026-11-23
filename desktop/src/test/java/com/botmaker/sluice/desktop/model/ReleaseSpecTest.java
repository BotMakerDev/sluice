// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.desktop.model;

import com.botmaker.sluice.Level;
import com.botmaker.sluice.decide.Dependents;
import com.botmaker.sluice.desktop.model.ReleaseSpec.DependentsFlag;
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
        ReleaseSpec spec = new ReleaseSpec(Optional.of(Level.PATCH), picked, true, DependentsFlag.WITH, true);

        assertEquals("sluice --all patch --module core=1.2.0 --module cli=minor --with-dependents --force --no-wait",
                spec.commandLine());
        assertEquals(spec.commandLine() + " --execute", spec.executeCommandLine());
    }

    @Test
    void aRowIsALevelOrAVersion() {
        assertTrue(ReleaseSpec.wellFormed("major"));
        assertTrue(ReleaseSpec.wellFormed("2.0.0"));
        assertFalse(ReleaseSpec.wellFormed("soon"));
        assertFalse(new ReleaseSpec(Optional.empty(), Map.of("a", "soon"), false, DependentsFlag.CONFIG, false)
                .wellFormed());
    }

    @Test
    void noWaitIsNotWaitingForTargets() {
        ReleaseSpec spec = new ReleaseSpec(Optional.empty(), Map.of("a", "patch"), false, DependentsFlag.CONFIG, true);

        assertFalse(spec.options(true).waitForTargets());
        assertTrue(spec.options(true).execute());
        assertFalse(spec.options(false).execute());
        assertFalse(spec.empty());
        assertTrue(new ReleaseSpec(Optional.empty(), Map.of(), false, DependentsFlag.CONFIG, false).empty());
    }

    @Test
    void noDependentsIsSpelledAndLeavesThem() {
        ReleaseSpec spec = new ReleaseSpec(Optional.empty(), Map.of("a", "patch"), false, DependentsFlag.WITHOUT,
                false);

        assertEquals("sluice --module a=patch --no-dependents", spec.commandLine());
        assertEquals(Optional.of(Dependents.LEAVE), spec.options(false).dependents());
        assertEquals(Optional.empty(),
                new ReleaseSpec(Optional.empty(), Map.of(), false, DependentsFlag.CONFIG, false)
                        .options(false).dependents());
    }

    @Test
    void equalSpecsAreEqual() {
        assertEquals(new ReleaseSpec(Optional.of(Level.MINOR), Map.of("a", "patch"), false, DependentsFlag.WITH, false),
                new ReleaseSpec(Optional.of(Level.MINOR), Map.of("a", "patch"), false, DependentsFlag.WITH, false));
    }
}
