// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** How the running sluice was installed, which says how it is updated ({@link SelfUpdate}). */
public sealed interface Install {

    /** The launcher of a Windows image, beside its {@code app/} folder that holds the jar. */
    String EXE = "sluice.exe";

    /** A Linux package format, and the release asset that is the package. */
    enum Format {
        RPM("sluice.rpm", Path.of("/etc/yum.repos.d/sluice.repo")),
        DEB("sluice.deb", Path.of("/etc/apt/sources.list.d/sluice.list"));

        private final String asset;
        private final Path repository;

        Format(String asset, Path repository) {
            this.asset = asset;
            this.repository = repository;
        }

        public String asset() {
            return asset;
        }

        /** The file the install script writes to register the signed repository. */
        public Path repository() {
            return repository;
        }
    }

    /** What {@code sluice update --check} says this install is. */
    String describe();

    /** A jar of the user's own, {@code java -jar}: replaced by the release's. */
    record Jar(Path jar) implements Install {
        @Override
        public String describe() {
            return "the jar " + jar;
        }
    }

    /**
     * The rpm or the deb, as root: upgraded from its signed repository when that is registered ({@code repository}),
     * else the release's package installed by the system's package manager.
     */
    record Package(Format format, boolean repository) implements Install {
        @Override
        public String describe() {
            return "the " + format.name().toLowerCase(Locale.ROOT) + " package"
                    + (repository ? ", from its signed repository" : ", from the release's package");
        }
    }

    /** The MSI's install at {@code dir}: the release's MSI run over it. */
    record Msi(Path dir) implements Install {
        @Override
        public String describe() {
            return "the MSI's install at " + dir;
        }
    }

    /** {@code sluice-windows.zip} unzipped by hand at {@code dir}: the release's zip unpacked over it. */
    record Image(Path dir) implements Install {
        @Override
        public String describe() {
            return "the unzipped image at " + dir;
        }
    }

    /** Scoop's install: {@code scoop update sluice}. */
    record Scoop() implements Install {
        @Override
        public String describe() {
            return "Scoop's install";
        }
    }

    /** Nothing this command may update, and why. */
    record Unmanaged(String why) implements Install {
        @Override
        public String describe() {
            return why;
        }
    }

    /** The install the jar this process runs from belongs to; {@code jar} is null outside a jar. */
    static Install of(Path jar, Host host) {
        if (jar == null || !jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                || !host.exists(jar)) {
            return new Unmanaged("sluice is not running from a jar (an IDE or a test run): nothing to update");
        }
        Path real = real(jar);
        String path = real.toString().replace('\\', '/');
        if (path.contains("/.m2/repository/")) {
            return new Unmanaged("sluice runs from Maven's local repository, which Maven keeps: nothing to update");
        }
        Path dir = real.getParent();
        if (host.windows()) {
            if (path.toLowerCase(Locale.ROOT).contains("/scoop/apps/")) {
                return new Scoop();
            }
            if (dir.getFileName() != null && dir.getFileName().toString().equalsIgnoreCase("app")
                    && host.exists(dir.resolveSibling(EXE))) {
                Path image = dir.getParent();
                return installedByMsi(image, host) ? new Msi(image) : new Image(image);
            }
        } else {
            Optional<Format> owner = host.owner(real);
            if (owner.isPresent()) {
                return new Package(owner.get(), host.exists(owner.get().repository()));
            }
        }
        if (!Files.isWritable(dir)) {
            return new Unmanaged(dir + " is not writable, so the jar in it cannot be replaced");
        }
        return new Jar(real);
    }

    /**
     * Whether Windows Installer registered this image as the MSI's: an uninstall entry of sluice's whose install
     * location is the image. An entry that names no location counts only for an image where the MSI puts one, per
     * user under {@code %LOCALAPPDATA%} or for every user under {@code %ProgramFiles%}. Otherwise it was unzipped.
     */
    private static boolean installedByMsi(Path image, Host host) {
        List<String> locations = host.msiLocations();
        if (locations.stream().filter(location -> !location.isBlank())
                .anyMatch(location -> same(Path.of(location), image))) {
            return true;
        }
        return locations.contains("")
                && (host.env("LOCALAPPDATA").map(dir -> image.startsWith(Path.of(dir))).orElse(false)
                || host.env("ProgramFiles").map(dir -> image.startsWith(Path.of(dir))).orElse(false));
    }

    private static boolean same(Path a, Path b) {
        return a.toAbsolutePath().normalize().toString().replaceAll("[\\\\/]+$", "")
                .equalsIgnoreCase(b.toAbsolutePath().normalize().toString().replaceAll("[\\\\/]+$", ""));
    }

    private static Path real(Path jar) {
        try {
            return jar.toRealPath();
        } catch (IOException e) {
            return jar.toAbsolutePath().normalize();
        }
    }
}
