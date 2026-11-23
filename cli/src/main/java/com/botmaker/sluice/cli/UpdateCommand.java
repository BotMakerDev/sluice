// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.cli;

import com.botmaker.sluice.update.Install;
import com.botmaker.sluice.update.SelfUpdate;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.Callable;

/**
 * {@code sluice update}: this sluice to its latest release, the way it was installed ({@link SelfUpdate}). With
 * {@code --check}, says what it would do and changes nothing.
 */
@Command(name = "update", mixinStandardHelpOptions = true,
        description = "Update this sluice to its latest release, the way it was installed: a jar of your own is "
                + "replaced, the rpm or deb reinstalled through dnf or apt (as root, through sudo or pkexec), the "
                + "MSI run, an unzipped image unpacked over, Scoop asked. Every download is checked against the "
                + "release's checksums_sha256.txt first.")
final class UpdateCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Option(names = "--check", description = "Say whether a newer release exists and how it would be installed.")
    private boolean check;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        SelfUpdate update = Main.of(spec).engine().selfUpdate();
        try {
            if (check) {
                SelfUpdate.Check checked = update.check();
                String installed = checked.installed().map(v -> "sluice " + v).orElse("this development build");
                out.println(installed + "; the latest release is " + checked.latest()
                        + (checked.newer() ? ": sluice update installs it" : ""));
                out.println((checked.install() instanceof Install.Unmanaged ? "not updated here: "
                        : "installed as ") + checked.install().describe());
                return Main.OK;
            }
            switch (update.update()) {
                case SelfUpdate.Outcome.Current current ->
                        out.println("sluice is up to date (latest release " + current.tag() + ")");
                case SelfUpdate.Outcome.Updated updated ->
                        out.println("sluice updated to " + updated.tag() + ": " + updated.how());
                case SelfUpdate.Outcome.Later later ->
                        out.println("sluice " + later.tag() + " is downloaded and checked: " + later.how());
            }
            return Main.OK;
        } catch (IOException e) {
            err.println("refused: " + e.getMessage());
            return Main.REFUSED;
        } finally {
            out.flush();
            err.flush();
        }
    }
}
