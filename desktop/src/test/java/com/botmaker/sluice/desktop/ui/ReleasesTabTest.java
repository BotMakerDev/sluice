// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.desktop.Fixtures;
import com.botmaker.sluice.desktop.model.Umbrella;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleasesTabTest extends FxHeadless {

    @TempDir
    Path tmp;

    private static String state(String status) {
        return "{\"status\": \"" + status + "\", \"modules\": [{\"module\": \"a\", \"version\": \"1.0.1\", "
                + "\"stage\": \"FAILED\", \"detail\": \"\"}]}\n";
    }

    @Test
    void onlyTheNewestUnfinishedRunResumes() throws IOException, TimeoutException {
        Umbrella umbrella = Fixtures.open(Fixtures.pinned(tmp));
        Path dir = Files.createDirectories(umbrella.root().resolve("releases"));
        Files.writeString(dir.resolve("2026-11-10-1200.json"), state("STOPPED"));
        Files.writeString(dir.resolve("2026-11-09-0900.json"), state("STOPPED"));
        AtomicInteger asked = new AtomicInteger();
        ReleasesTab[] tab = new ReleasesTab[1];
        interact(() -> {
            tab[0] = new ReleasesTab(umbrella, url -> {
            }, asked::incrementAndGet);
            show(tab[0]);
        });
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> tab[0].runs().size() == 2);
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(tab[0].resumeButton().isDisabled(), "the newest run stopped: it resumes");
        interact(() -> tab[0].resumeButton().fire());
        assertEquals(1, asked.get());

        interact(() -> tab[0].list().getSelectionModel().select(1));
        assertTrue(tab[0].resumeButton().isDisabled(), "an older run never resumes");
    }

    @Test
    void aFinishedRunDoesNotResume() throws IOException, TimeoutException {
        Umbrella umbrella = Fixtures.open(Fixtures.pinned(tmp));
        Path dir = Files.createDirectories(umbrella.root().resolve("releases"));
        Files.writeString(dir.resolve("2026-11-10-1200.json"), state("DONE"));
        ReleasesTab[] tab = new ReleasesTab[1];
        interact(() -> {
            tab[0] = new ReleasesTab(umbrella, url -> {
            }, () -> {
            });
            show(tab[0]);
        });
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> tab[0].runs().size() == 1);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(tab[0].resumeButton().isDisabled());
    }
}
