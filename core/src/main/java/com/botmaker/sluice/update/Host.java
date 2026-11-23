// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.update;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** What an update asks of the machine it runs on: {@link #SYSTEM}, or a stand-in that records it in a test. */
public interface Host {

    /** This machine. */
    Host SYSTEM = new SystemHost();

    boolean windows();

    /** Whether this process runs as root, and needs no {@code sudo}. */
    boolean root();

    /** Whether this process has a terminal, where {@code sudo} asks; without one, {@code pkexec} asks in a window. */
    boolean terminal();

    boolean onPath(String program);

    boolean exists(Path file);

    Optional<String> env(String name);

    /** The package format whose database owns {@code file} ({@code rpm -qf}, {@code dpkg -S}), if one does. */
    Optional<Install.Format> owner(Path file);

    /**
     * Windows: the install location of each uninstall entry named sluice, blank for an entry that names none;
     * empty when the MSI never installed it.
     */
    List<String> msiLocations();

    /** Runs {@code argv} with this process's input and output, so it can ask for a password; answers its exit code. */
    int run(List<String> argv) throws IOException;

    /**
     * Windows: starts a PowerShell that runs {@code script} once this process has ended, which is when its files
     * are no longer locked; in a window of its own when {@code shown}, else hidden.
     */
    void afterExit(String script, boolean shown) throws IOException;
}
