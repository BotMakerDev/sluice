// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.gate.GateVerdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReleaseConfirmTest {

    @Test
    void forcedListsOnlyTheRefusalsForceLifted() {
        GateVerdict clean = new GateVerdict("clean", GateVerdict.Status.REFUSED, "b is dirty", "b: src/B.java");
        List<GateVerdict> verdicts = List.of(
                new GateVerdict("ci", GateVerdict.Status.OK, "green", ""),
                new GateVerdict("builds-at-pins", GateVerdict.Status.SKIPPED, "no upstream released", ""),
                clean.forced());

        assertEquals(List.of("    clean: b is dirty (forced)"), ReleaseConfirm.forced(verdicts));
    }
}
