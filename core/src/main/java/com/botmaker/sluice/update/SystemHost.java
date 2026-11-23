// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.update;

import com.botmaker.sluice.Proc;

import java.io.Console;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/** {@link Host} as this machine answers it. */
final class SystemHost implements Host {

    @Override
    public boolean windows() {
        return Proc.isWindows();
    }

    @Override
    public boolean root() {
        return !windows() && "root".equals(System.getProperty("user.name"));
    }

    @Override
    public boolean terminal() {
        Console console = System.console();
        return console != null && console.isTerminal();
    }

    @Override
    public boolean onPath(String program) {
        return Proc.onPath(program);
    }

    @Override
    public boolean exists(Path file) {
        return Files.exists(file);
    }

    @Override
    public Optional<String> env(String name) {
        return Optional.ofNullable(System.getenv(name)).filter(value -> !value.isBlank());
    }

    @Override
    public Optional<Install.Format> owner(Path file) {
        Path here = Path.of("").toAbsolutePath();
        if (onPath("rpm") && Proc.run(here, List.of("rpm", "-qf", file.toString())).ok()) {
            return Optional.of(Install.Format.RPM);
        }
        if (onPath("dpkg") && Proc.run(here, List.of("dpkg", "-S", file.toString())).ok()) {
            return Optional.of(Install.Format.DEB);
        }
        return Optional.empty();
    }

    /** Each entry printed as {@code >} and its location, so one that names none is a line too. */
    @Override
    public List<String> msiLocations() {
        if (!windows()) {
            return List.of();
        }
        String uninstall = "\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*";
        String script = "Get-ItemProperty -Path 'HKCU:" + uninstall + "','HKLM:" + uninstall
                + "','HKLM:\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*' "
                + "-ErrorAction SilentlyContinue | Where-Object { $_.DisplayName -eq 'sluice' } "
                + "| ForEach-Object { '>' + $_.InstallLocation }";
        Proc.Result result = Proc.run(Path.of("").toAbsolutePath(),
                List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script));
        return result.ok() ? result.lines().stream().filter(line -> line.startsWith(">"))
                .map(line -> line.substring(1).strip()).toList() : List.of();
    }

    @Override
    public int run(List<String> argv) throws IOException {
        Process process = new ProcessBuilder(argv).inheritIO().start();
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while " + argv.getFirst() + " ran", e);
        }
    }

    private static void start(List<String> argv) throws IOException {
        new ProcessBuilder(argv).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    /**
     * The script is passed encoded (UTF-16LE in base64, PowerShell's {@code -EncodedCommand}), so no quoting of the
     * command line can change it. A shown one opens in a console of its own through {@code start}, whose first
     * quoted argument is the window's title, and stays open so what it printed can be read.
     */
    @Override
    public void afterExit(String script, boolean shown) throws IOException {
        String waits = "$ErrorActionPreference = 'Stop'; "
                + "Wait-Process -Id " + ProcessHandle.current().pid() + " -ErrorAction SilentlyContinue; "
                + "Start-Sleep -Milliseconds 500; ";
        String encoded = Base64.getEncoder().encodeToString((waits + script).getBytes(StandardCharsets.UTF_16LE));
        List<String> argv = shown
                ? List.of("cmd.exe", "/c", "start", "sluice update", "powershell.exe", "-NoProfile", "-NoExit",
                        "-EncodedCommand", encoded)
                : List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                        "-EncodedCommand", encoded);
        start(argv);
    }
}
