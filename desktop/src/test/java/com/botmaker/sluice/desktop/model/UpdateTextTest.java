// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.desktop.model;

import com.botmaker.sluice.Version;
import com.botmaker.sluice.update.Install;
import com.botmaker.sluice.update.SelfUpdate;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateTextTest {

    private static final Install JAR = new Install.Jar(Path.of("sluice-desktop-all.jar"));

    private static SelfUpdate.Check check(String installed, Install install) {
        return new SelfUpdate.Check(Optional.ofNullable(installed).flatMap(Version::parse), "v0.5.0", install);
    }

    @Test
    void anUpdateIsOfferedOnlyForANewerReleaseThisInstallCanTake() {
        assertTrue(check("0.4.2", JAR).updatable());
        assertFalse(check("0.5.0", JAR).updatable(), "current");
        assertFalse(check(null, JAR).updatable(), "a development build");
        assertFalse(check("0.4.2", new Install.Unmanaged("an IDE run")).updatable(), "unmanaged");
        assertEquals("sluice v0.5.0 is out", UpdateText.header(check("0.4.2", JAR)));
        assertEquals("sluice is up to date", UpdateText.header(check("0.5.0", JAR)));
        assertEquals("This is a development build", UpdateText.header(check(null, JAR)),
                "never up to date: it has no version to compare");
        assertEquals("A newer sluice is out", UpdateText.header(check("0.4.2", new Install.Unmanaged("x"))));
    }

    @Test
    void theWordsSayWhereItStandsAndWhyNot() {
        assertEquals("sluice 0.4.2; the latest release is v0.5.0.", UpdateText.standing(check("0.4.2", JAR)));
        assertEquals("This development build; the latest release is v0.5.0.",
                UpdateText.standing(check(null, JAR)));
        assertEquals("Not updated from here: an IDE run.",
                UpdateText.install(check("0.4.2", new Install.Unmanaged("an IDE run"))));
        assertEquals("Installed as the jar sluice-desktop-all.jar.", UpdateText.install(check("0.4.2", JAR)));
        assertEquals("sluice v0.5.0 is downloaded and checked: msiexec runs. Close sluice to finish.",
                UpdateText.outcome(new SelfUpdate.Outcome.Later("v0.5.0", "msiexec runs")));
    }
}
