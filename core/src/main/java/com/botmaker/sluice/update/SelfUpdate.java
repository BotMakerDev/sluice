// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.update;

import com.botmaker.sluice.Version;
import com.botmaker.sluice.github.GitHub.Repo;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * sluice updating itself to its latest release, the way its install is updated ({@link Install}): a jar of the
 * user's is replaced, a package reinstalled through the package manager, the MSI run, an unzipped image unpacked
 * over, Scoop asked. Every download is checked against the release's {@link Checksums#FILE} before it is used.
 *
 * <p>On Windows a running program's files are locked, so the last step there waits for this process to end
 * ({@link Outcome.Later}). Prints nothing: what happened is the {@link Outcome}.
 */
public final class SelfUpdate {

    /** The release asset that is the whole command, for a jar of the user's. */
    public static final String JAR = "sluice-all.jar";

    /** The release's MSI. */
    public static final String MSI = "sluice.msi";

    /** The release's Windows image, zipped: its one folder is {@code sluice/}. */
    public static final String ZIP = "sluice-windows.zip";

    /** Where the update stands before it runs. */
    public record Check(Optional<Version> installed, String latest, Install install) {

        /** Whether the latest release is newer than this one; false for a build with no release version. */
        public boolean newer() {
            return installed.isPresent() && Version.parse(latest).filter(v -> v.compareTo(installed.get()) > 0)
                    .isPresent();
        }
    }

    /** How an update ended. */
    public sealed interface Outcome {

        String tag();

        /** Already at the latest release, or newer. */
        record Current(String tag) implements Outcome {
        }

        /** Updated: the next start runs {@code tag}. */
        record Updated(String tag, String how) implements Outcome {
        }

        /** Checked and handed over: {@code how} finishes it once this process has ended. */
        record Later(String tag, String how) implements Outcome {
        }
    }

    private final Releases releases;
    private final Host host;
    private final Install install;
    private final Optional<Version> installed;

    public SelfUpdate(Releases releases, Host host, Install install, Optional<String> installed) {
        this.releases = releases;
        this.host = host;
        this.install = install;
        this.installed = installed.flatMap(Version::parse);
    }

    /**
     * The update of the sluice {@code anchor} was loaded from: its jar, its manifest's version, and its manifest's
     * {@code Implementation-URL}, the GitHub repository whose releases it follows.
     */
    public static SelfUpdate of(Class<?> anchor) {
        Package pkg = anchor.getPackage();
        Optional<Path> jar = jar(anchor);
        Optional<Repo> home = jar.flatMap(SelfUpdate::manifestUrl).flatMap(Repo::ofRemote);
        Releases releases = home.map(Releases::github).orElse(NOWHERE);
        return new SelfUpdate(releases, Host.SYSTEM, Install.of(jar.orElse(null), Host.SYSTEM),
                Optional.ofNullable(pkg.getImplementationVersion()));
    }

    /**
     * Windows: replaces the jar with a checked {@code <jar>.new} an earlier update left, when this process can, else
     * hands it over for when this process ends. Elsewhere an update swaps at once, so a {@code .new} there is one a
     * failed swap left, maybe older than the jar now: it is deleted. Run at every start; never throws.
     */
    public static void finishPending(Class<?> anchor) {
        try {
            Optional<Path> jar = jar(anchor);
            if (jar.isEmpty() || !Files.isRegularFile(pending(jar.get()))) {
                return;
            }
            if (!Host.SYSTEM.windows()) {
                Files.deleteIfExists(pending(jar.get()));
            } else if (!swap(jar.get())) {
                Host.SYSTEM.afterExit(moveScript(jar.get()), false);
            }
        } catch (IOException | RuntimeException ignored) {
            // The staged jar stays for the next start; the command itself runs on.
        }
    }

    public Install install() {
        return install;
    }

    /** The latest release against this one, read from the releases. */
    public Check check() throws IOException {
        Releases.Latest latest = releases.latest().orElseThrow(() -> new IOException("sluice has no release yet"));
        return new Check(installed, latest.tag(), install);
    }

    /** Updates to the latest release, or says it is current. Throws why when it cannot; nothing unchecked is used. */
    public Outcome update() throws IOException {
        if (install instanceof Install.Unmanaged(String why)) {
            throw new IOException(why);
        }
        if (installed.isEmpty()) {
            throw new IOException("this is a development build of sluice, with no release version to update from");
        }
        Releases.Latest latest = releases.latest().orElseThrow(() -> new IOException("sluice has no release yet"));
        Check check = new Check(installed, latest.tag(), install);
        if (!check.newer()) {
            return new Outcome.Current(latest.tag());
        }
        String tag = latest.tag();
        return switch (install) {
            case Install.Jar(Path jar) -> jar(latest, jar);
            case Install.Package(Install.Format format, boolean repository) when repository -> {
                for (List<String> command : upgradeCommands(format)) {
                    root(command);
                }
                yield new Outcome.Updated(tag, "the package manager upgraded sluice from its signed repository");
            }
            case Install.Package(Install.Format format, boolean _) -> {
                Path file = fetched(latest, format.asset());
                try {
                    root(installCommand(format, file));
                } finally {
                    deleteFolder(file);
                }
                yield new Outcome.Updated(tag, "the package manager installed the release's " + format.asset());
            }
            case Install.Msi(Path dir) -> {
                Path msi = fetched(latest, MSI);
                host.afterExit(waitFor(dir) + "Start-Process msiexec -Wait -ArgumentList '/i', " + quoted(msi)
                        + "; " + removeFolder(msi), false);
                yield new Outcome.Later(tag, "the MSI's installer opens once every sluice of " + dir
                        + " has ended");
            }
            case Install.Image(Path dir) -> {
                Path zip = fetched(latest, ZIP);
                host.afterExit(waitFor(dir) + unzipScript(zip, dir) + "; " + removeFolder(zip), true);
                yield new Outcome.Later(tag, ZIP + " is unpacked over " + dir + ", in a window of its own, once "
                        + "every sluice of it has ended");
            }
            case Install.Scoop() -> {
                host.afterExit("scoop update sluice", true);
                yield new Outcome.Later(tag, "scoop update sluice runs in a window of its own once this sluice "
                        + "has ended");
            }
            case Install.Unmanaged(String why) -> throw new IOException(why);
        };
    }

    /** {@code <jar>.new}: a checked jar waiting to replace {@code jar}. */
    public static Path pending(Path jar) {
        return jar.resolveSibling(jar.getFileName() + ".new");
    }

    private Outcome jar(Releases.Latest latest, Path jar) throws IOException {
        Path part = jar.resolveSibling(jar.getFileName() + ".part");
        try {
            Files.deleteIfExists(part);   // one a killed run left would keep its tail past the new bytes
            download(latest, JAR, part);
            keepPermissions(jar, part);
            move(part, pending(jar));
        } finally {
            Files.deleteIfExists(part);
        }
        if (!host.windows() && swap(jar)) {
            return new Outcome.Updated(latest.tag(), jar + " replaced");
        }
        if (host.windows()) {
            host.afterExit(moveScript(jar), false);
            return new Outcome.Later(latest.tag(), "the checked jar replaces " + jar + " once this sluice has ended");
        }
        throw new IOException("the checked jar is " + pending(jar) + ", but it could not be moved over " + jar);
    }

    /** {@code asset} downloaded into a folder of its own under the temporary directory, and checked. */
    private Path fetched(Releases.Latest latest, String asset) throws IOException {
        Path file = Files.createTempDirectory("sluice-update").resolve(asset);
        try {
            download(latest, asset, file);
        } catch (IOException | RuntimeException e) {
            deleteFolder(file);
            throw e;
        }
        return file;
    }

    /** Deletes a fetched file and the folder {@link #fetched} made for it, best effort. */
    private static void deleteFolder(Path file) {
        try {
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.getParent());
        } catch (IOException ignored) {
            // Left in the temporary directory, never installed.
        }
    }

    /** The new jar keeps the old one's POSIX permissions, where the file system has them. */
    private static void keepPermissions(Path from, Path to) {
        try {
            Files.setPosixFilePermissions(to, Files.getPosixFilePermissions(from));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows, or a file system without them: the new jar's own stand.
        }
    }

    /** Runs {@code argv} as root; throws when it fails. */
    private void root(List<String> argv) throws IOException {
        int exit = host.run(elevated(argv));
        if (exit != 0) {
            throw new IOException(String.join(" ", argv) + " ended with exit code " + exit);
        }
    }

    /**
     * The upgrade from the registered repository, which the package manager checks by its signature: dnf refreshes
     * its metadata; apt reads only this repository's list, so nothing else is refreshed.
     */
    private List<List<String>> upgradeCommands(Install.Format format) {
        return switch (format) {
            case RPM -> List.of(List.of("dnf", "upgrade", "-y", "--refresh", "sluice"));
            case DEB -> List.of(
                    List.of("apt-get", "update", "-o", "Dir::Etc::sourcelist=" + format.repository(),
                            "-o", "Dir::Etc::sourceparts=-", "-o", "APT::Get::List-Cleanup=0"),
                    List.of("apt-get", "install", "--only-upgrade", "-y", "sluice"));
        };
    }

    /** Downloads {@code asset} to {@code to} and checks it, deleting it when it fails the check. */
    private void download(Releases.Latest latest, String asset, Path to) throws IOException {
        String url = latest.assets().get(asset);
        if (url == null) {
            throw new IOException("release " + latest.tag() + " has no " + asset);
        }
        String sums = latest.assets().get(Checksums.FILE);
        if (sums == null) {
            throw new IOException("release " + latest.tag() + " has no " + Checksums.FILE + ", so " + asset
                    + " cannot be checked; nothing was downloaded");
        }
        String checksums = releases.text(sums);
        releases.download(url, to);
        Checksums.verify(to, asset, checksums);
    }

    /**
     * The package manager's install of a package file: dnf or apt, else the low-level tool. Checked by the release's
     * checksums only: dnf does not check a local package's signature by default.
     */
    private List<String> installCommand(Install.Format format, Path file) {
        String path = file.toAbsolutePath().toString();
        return switch (format) {
            case RPM -> host.onPath("dnf") ? List.of("dnf", "install", "-y", path) : List.of("rpm", "-U", path);
            case DEB -> host.onPath("apt-get") ? List.of("apt-get", "install", "-y", path) : List.of("dpkg", "-i", path);
        };
    }

    /** {@code argv} as root: as it is when this is root, else through sudo on a terminal and pkexec off one. */
    private List<String> elevated(List<String> argv) throws IOException {
        if (host.root()) {
            return argv;
        }
        List<String> order = host.terminal() ? List.of("sudo", "pkexec") : List.of("pkexec", "sudo");
        String elevator = order.stream().filter(host::onPath).findFirst().orElseThrow(() -> new IOException(
                "installing a package needs root, and neither sudo nor pkexec is on the PATH"));
        List<String> elevatedArgv = new ArrayList<>(List.of(elevator));
        elevatedArgv.addAll(argv);
        return elevatedArgv;
    }

    private static String moveScript(Path jar) {
        return "Move-Item -Force -LiteralPath " + quoted(pending(jar)) + " -Destination " + quoted(jar);
    }

    /** Unpacks the zip into a folder of its own, then copies its {@code sluice/} over the image. */
    static String unzipScript(Path zip, Path image) {
        return "$t = Join-Path $env:TEMP ('sluice-' + [guid]::NewGuid()); "
                + "Expand-Archive -LiteralPath " + quoted(zip) + " -DestinationPath $t -Force; "
                + "Copy-Item -Path (Join-Path $t 'sluice\\*') -Destination " + quoted(image) + " -Recurse -Force; "
                + "Remove-Item -Recurse -Force -LiteralPath $t";
    }

    /**
     * Waits for every program running from {@code image} (sluice-desktop.exe beside sluice.exe), whose files the
     * update replaces and Windows keeps locked while they run.
     */
    static String waitFor(Path image) {
        return "Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -and $_.Path.StartsWith("
                + quoted(image.toAbsolutePath() + "\\") + ", 'OrdinalIgnoreCase') } "
                + "| ForEach-Object { $_.WaitForExit() }; ";
    }

    /** Removes the folder {@link #fetched} made for {@code file}. */
    private static String removeFolder(Path file) {
        return "Remove-Item -Recurse -Force -LiteralPath " + quoted(file.getParent());
    }

    /** A PowerShell single-quoted string: nothing in it is expanded, and a quote is doubled. */
    private static String quoted(Path path) {
        return quoted(path.toAbsolutePath().toString());
    }

    private static String quoted(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    /** Moves a staged {@code <jar>.new} over {@code jar}; false when the running jar is locked (Windows). */
    private static boolean swap(Path jar) {
        try {
            move(pending(jar), jar);
            return true;
        } catch (IOException locked) {
            return false;
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The jar {@code anchor} was loaded from, or empty outside one. */
    private static Optional<Path> jar(Class<?> anchor) {
        try {
            CodeSource source = anchor.getProtectionDomain().getCodeSource();
            if (source == null) {
                return Optional.empty();
            }
            Path path = Path.of(source.getLocation().toURI());
            return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
        } catch (URISyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The {@code Implementation-URL} of {@code jar}'s manifest, the repository its build names. */
    private static Optional<String> manifestUrl(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            return Optional.ofNullable(manifest == null ? null
                    : manifest.getMainAttributes().getValue("Implementation-URL"));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** The releases of a build whose manifest names no repository: an IDE run, which has none to follow. */
    private static final Releases NOWHERE = new Releases() {
        @Override
        public Optional<Latest> latest() throws IOException {
            throw new IOException("this build of sluice names no repository to update from");
        }

        @Override
        public void download(String url, Path to) throws IOException {
            throw new IOException("no repository");
        }

        @Override
        public String text(String url) throws IOException {
            throw new IOException("no repository");
        }
    };
}
