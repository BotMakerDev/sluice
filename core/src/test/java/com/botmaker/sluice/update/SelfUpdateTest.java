// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.update;

import com.botmaker.sluice.github.GitHub.Repo;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SelfUpdate} against a stand-in GitHub on localhost, on a host that records what it is asked to run. */
class SelfUpdateTest {

    private static final Repo REPO = new Repo("owner", "sluice");

    @TempDir
    Path tmp;

    private HttpServer github;
    /** The stand-in's latest release: asset name to bytes; empty serves 404. */
    private final Map<String, byte[]> assets = new LinkedHashMap<>();
    private String tag = "v0.5.0";

    @BeforeEach
    void serve() throws IOException {
        github = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        github.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            if (path.equals("/repos/owner/sluice/releases/latest") && !assets.isEmpty()) {
                body = releaseJson().getBytes(StandardCharsets.UTF_8);
            } else if (path.startsWith("/download/") && assets.containsKey(path.substring(10))) {
                body = assets.get(path.substring(10));
            } else {
                body = null;
            }
            exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                if (body != null) {
                    out.write(body);
                }
            }
        });
        github.start();
    }

    @AfterEach
    void stop() {
        github.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + github.getAddress().getPort();
    }

    private String releaseJson() {
        String list = assets.keySet().stream()
                .map(name -> "{\"name\": \"" + name + "\", \"browser_download_url\": \"" + base() + "/download/"
                        + name + "\"}")
                .collect(Collectors.joining(", "));
        return "{\"tag_name\": \"" + tag + "\", \"assets\": [" + list + "]}";
    }

    /** Publishes {@code asset} with {@code bytes}, and the checksums file listing every asset. */
    private void release(String asset, String bytes) {
        assets.put(asset, bytes.getBytes(StandardCharsets.UTF_8));
        String sums = assets.entrySet().stream().filter(e -> !e.getKey().equals(Checksums.FILE))
                .map(e -> sha256(e.getValue()) + "  " + e.getKey()).collect(Collectors.joining("\n", "", "\n"));
        assets.put(Checksums.FILE, sums.getBytes(StandardCharsets.UTF_8));
    }

    private SelfUpdate update(Install install, FakeHost host, String installed) {
        return new SelfUpdate(new GitHubReleases(URI.create(base()), REPO), host, install,
                Optional.ofNullable(installed));
    }

    private Path jar(String relative, String content) throws IOException {
        Path jar = tmp.resolve(relative);
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, content);
        return jar;
    }

    @Test
    void aJarOfTheUsersIsReplacedByTheCheckedOne() throws IOException {
        release(SelfUpdate.JAR, "new jar");
        Path jar = jar("bin/sluice-all.jar", "old jar");
        FakeHost host = new FakeHost();
        Install install = Install.of(jar, host);
        assertEquals(new Install.Jar(jar.toRealPath()), install);

        SelfUpdate.Outcome outcome = update(install, host, "0.4.2").update();

        assertInstanceOf(SelfUpdate.Outcome.Updated.class, outcome);
        assertEquals("v0.5.0", outcome.tag());
        assertEquals("new jar", Files.readString(jar));
        try (var left = Files.list(jar.getParent())) {
            assertEquals(List.of(jar.getFileName().toString()),
                    left.map(p -> p.getFileName().toString()).toList(), "no .new or .part left");
        }
    }

    @Test
    void theWindowsJarIsReplacedByTheReleasesDesktopJar() throws IOException {
        release(SelfUpdate.JAR, "new command");
        release(SelfUpdate.DESKTOP_JAR, "new window");
        Path jar = jar("sluice-desktop-all.jar", "old window");
        FakeHost host = new FakeHost();

        SelfUpdate.Outcome outcome = new SelfUpdate(new GitHubReleases(URI.create(base()), REPO), host,
                Install.of(jar, host), Optional.of("0.4.2"), SelfUpdate.DESKTOP_JAR).update();

        assertInstanceOf(SelfUpdate.Outcome.Updated.class, outcome);
        assertEquals("new window", Files.readString(jar));
    }

    @Test
    void theLatestNoNewerIsCurrentAndDownloadsNothing() throws IOException {
        release(SelfUpdate.JAR, "new jar");
        Path jar = jar("sluice-all.jar", "old jar");
        FakeHost host = new FakeHost();

        SelfUpdate.Outcome outcome = update(Install.of(jar, host), host, "0.5.0").update();

        assertEquals(new SelfUpdate.Outcome.Current("v0.5.0"), outcome);
        assertEquals("old jar", Files.readString(jar));
        assertFalse(update(Install.of(jar, host), host, "0.5.0").check().newer());
        assertTrue(update(Install.of(jar, host), host, "0.4.9").check().newer());
    }

    @Test
    void aDownloadThatFailsItsChecksumIsDeletedAndNothingChanges() throws IOException {
        release(SelfUpdate.JAR, "new jar");
        assets.put(SelfUpdate.JAR, "tampered".getBytes(StandardCharsets.UTF_8));
        Path jar = jar("sluice-all.jar", "old jar");
        FakeHost host = new FakeHost();

        IOException refused = assertThrows(IOException.class,
                () -> update(Install.of(jar, host), host, "0.4.2").update());

        assertTrue(refused.getMessage().contains("does not match"), refused.getMessage());
        assertEquals("old jar", Files.readString(jar));
        assertFalse(Files.exists(SelfUpdate.pending(jar)));
    }

    @Test
    void aReleaseWithoutChecksumsIsRefusedBeforeAnyDownload() throws IOException {
        assets.put(SelfUpdate.JAR, "new jar".getBytes(StandardCharsets.UTF_8));
        Path jar = jar("sluice-all.jar", "old jar");
        FakeHost host = new FakeHost();

        IOException refused = assertThrows(IOException.class,
                () -> update(Install.of(jar, host), host, "0.4.2").update());

        assertTrue(refused.getMessage().contains("has no " + Checksums.FILE), refused.getMessage());
        assertEquals("old jar", Files.readString(jar));
    }

    @Test
    void aDevelopmentBuildAndNoReleaseAreRefused() throws IOException {
        Path jar = jar("sluice-all.jar", "old jar");
        FakeHost host = new FakeHost();
        IOException dev = assertThrows(IOException.class, () -> update(Install.of(jar, host), host, null).update());
        assertTrue(dev.getMessage().contains("development build"), dev.getMessage());

        IOException none = assertThrows(IOException.class,
                () -> update(Install.of(jar, host), host, "0.4.2").update());
        assertTrue(none.getMessage().contains("no release"), none.getMessage());
    }

    @Test
    void anRpmIsInstalledByDnfUnderSudoOnATerminal() throws IOException {
        release(Install.Format.RPM.asset(), "rpm bytes");
        Path jar = jar("usr/lib/sluice/sluice-all.jar", "old jar");
        FakeHost host = new FakeHost();
        host.owner = Install.Format.RPM;
        host.terminal = true;
        host.path.addAll(List.of("sudo", "pkexec", "dnf"));
        Install install = Install.of(jar, host);
        assertEquals(new Install.Package(Install.Format.RPM, false), install);

        SelfUpdate.Outcome outcome = update(install, host, "0.4.2").update();

        assertInstanceOf(SelfUpdate.Outcome.Updated.class, outcome);
        List<String> ran = host.ran.getFirst();
        assertEquals(List.of("sudo", "dnf", "install", "-y"), ran.subList(0, 4));
        assertTrue(ran.get(4).endsWith("sluice.rpm"), ran.toString());
        assertEquals("rpm bytes", host.seen.getFirst(), "the package was there, checked, when it ran");
        assertFalse(Files.exists(Path.of(ran.get(4)).getParent()), "the download's folder is removed after");
    }

    @Test
    void aRegisteredRepositoryUpgradesBySignatureWithoutADownload() throws IOException {
        release(Install.Format.RPM.asset(), "unused");
        Path jar = jar("usr/lib/sluice/sluice-all.jar", "old jar");
        FakeHost host = new FakeHost();
        host.root = true;
        host.owner = Install.Format.RPM;
        host.registered.add(Install.Format.RPM.repository());
        assertEquals(new Install.Package(Install.Format.RPM, true), Install.of(jar, host));
        update(Install.of(jar, host), host, "0.4.2").update();

        host.owner = Install.Format.DEB;
        host.registered.add(Install.Format.DEB.repository());
        update(Install.of(jar, host), host, "0.4.2").update();

        assertEquals(List.of(List.of("dnf", "upgrade", "-y", "--refresh", "sluice"),
                List.of("apt-get", "update", "-o", "Dir::Etc::sourcelist=/etc/apt/sources.list.d/sluice.list",
                        "-o", "Dir::Etc::sourceparts=-", "-o", "APT::Get::List-Cleanup=0"),
                List.of("apt-get", "install", "--only-upgrade", "-y", "sluice")), host.ran);
    }

    @Test
    void aDebIsInstalledByAptUnderPkexecOffATerminalAndAsRootByItself() throws IOException {
        release(Install.Format.DEB.asset(), "deb bytes");
        Path jar = jar("usr/lib/sluice/sluice-all.jar", "old jar");
        FakeHost host = new FakeHost();
        host.owner = Install.Format.DEB;
        host.path.addAll(List.of("sudo", "pkexec", "apt-get"));

        update(Install.of(jar, host), host, "0.4.2").update();
        host.root = true;
        update(Install.of(jar, host), host, "0.4.2").update();

        assertEquals(List.of("pkexec", "apt-get", "install", "-y"), host.ran.get(0).subList(0, 4));
        assertEquals(List.of("apt-get", "install", "-y"), host.ran.get(1).subList(0, 3));
    }

    @Test
    void offATerminalWithoutPkexecSudoNeverAsks() throws IOException {
        release(Install.Format.DEB.asset(), "deb bytes");
        Path jar = jar("usr/lib/sluice/sluice-all.jar", "old jar");
        FakeHost host = new FakeHost();
        host.owner = Install.Format.DEB;
        host.path.addAll(List.of("sudo", "apt-get"));

        update(Install.of(jar, host), host, "0.4.2").update();

        assertEquals(List.of("sudo", "-n", "apt-get", "install", "-y"), host.ran.getFirst().subList(0, 5));
    }

    @Test
    void aFailedPackageInstallIsSaid() throws IOException {
        release(Install.Format.RPM.asset(), "rpm bytes");
        Path jar = jar("sluice-all.jar", "old jar");
        FakeHost host = new FakeHost();
        host.owner = Install.Format.RPM;
        host.root = true;
        host.exit = 1;

        IOException failed = assertThrows(IOException.class,
                () -> update(Install.of(jar, host), host, "0.4.2").update());

        assertTrue(failed.getMessage().contains("exit code 1"), failed.getMessage());
        assertEquals(List.of("rpm", "-U"), host.ran.getFirst().subList(0, 2), "no dnf: rpm itself");
    }

    @Test
    void theMsisInstallRunsTheReleasesMsiOnceEverySluiceOfItEnded() throws IOException {
        release(SelfUpdate.MSI, "msi bytes");
        Path jar = jar("tools/sluice/app/sluice-all.jar", "old jar");
        jar("tools/sluice/sluice.exe", "exe");
        FakeHost host = FakeHost.windows(tmp.resolve("local"));
        Path image = tmp.resolve("tools/sluice").toRealPath();
        host.msi.add(image.toString());
        Install install = Install.of(jar, host);
        assertEquals(new Install.Msi(image), install, "where the registry says, even outside LOCALAPPDATA");

        SelfUpdate.Outcome outcome = update(install, host, "0.4.2").update();

        assertInstanceOf(SelfUpdate.Outcome.Later.class, outcome);
        String script = host.afterExit.getFirst();
        assertTrue(script.startsWith(SelfUpdate.waitFor(image)), script);
        assertTrue(script.contains("Start-Process msiexec -Wait -ArgumentList '/i', '")
                && script.contains("sluice.msi'"), script);
        assertTrue(host.ran.isEmpty(), "nothing runs while this sluice holds the image");
    }

    @Test
    void anImageIsTheMsisByItsLocationOnlyWhenTheRegistryNamesNone() throws IOException {
        Path jar = jar("local/sluice/app/sluice-all.jar", "old jar");
        jar("local/sluice/sluice.exe", "exe");
        FakeHost host = FakeHost.windows(tmp.resolve("local"));
        assertInstanceOf(Install.Image.class, Install.of(jar, host), "no entry: unzipped, wherever it is");
        host.msi.add("");
        assertInstanceOf(Install.Msi.class, Install.of(jar, host));
    }

    @Test
    void anUnzippedImageIsUnpackedOverInAWindowOnceEverySluiceOfItEnded() throws IOException {
        release(SelfUpdate.ZIP, "zip bytes");
        Path jar = jar("tools/sluice/app/sluice-all.jar", "old jar");
        jar("tools/sluice/sluice.exe", "exe");
        FakeHost host = FakeHost.windows(tmp.resolve("local"));
        Path image = tmp.resolve("tools/sluice").toRealPath();
        Install install = Install.of(jar, host);
        assertEquals(new Install.Image(image), install);

        assertInstanceOf(SelfUpdate.Outcome.Later.class, update(install, host, "0.4.2").update());

        String script = host.afterExit.getFirst();
        assertTrue(script.startsWith(SelfUpdate.waitFor(image)), script);
        assertTrue(script.contains("Expand-Archive -LiteralPath '") && script.contains("sluice-windows.zip'"), script);
        assertTrue(script.contains("-Destination '" + image + "'"), script);
        assertEquals(List.of(true), host.shown, "a failure is seen");
    }

    @Test
    void scoopsInstallIsScoopsToUpdateInAWindowOfItsOwn() throws IOException {
        tag = "v0.5.0";
        release(SelfUpdate.JAR, "unused");
        Path jar = jar("scoop/apps/sluice/current/app/sluice-all.jar", "old jar");
        FakeHost host = FakeHost.windows(tmp.resolve("local"));
        Install install = Install.of(jar, host);
        assertEquals(new Install.Scoop(), install);

        assertInstanceOf(SelfUpdate.Outcome.Later.class, update(install, host, "0.4.2").update());

        assertEquals(List.of("scoop update sluice"), host.afterExit);
        assertEquals(List.of(true), host.shown);
    }

    @Test
    void aJarOnWindowsWaitsCheckedBesideItForThisToEnd() throws IOException {
        release(SelfUpdate.JAR, "new jar");
        Path jar = jar("bin/sluice-all.jar", "old jar");
        FakeHost host = FakeHost.windows(tmp.resolve("local"));

        assertInstanceOf(SelfUpdate.Outcome.Later.class, update(Install.of(jar, host), host, "0.4.2").update());

        assertEquals("old jar", Files.readString(jar));
        assertEquals("new jar", Files.readString(SelfUpdate.pending(jar)));
        assertTrue(host.afterExit.getFirst().startsWith("Move-Item -Force -LiteralPath '"), host.afterExit.toString());
    }

    @Test
    void nothingOutsideAJarOrInMavensRepositoryIsUpdated() throws IOException {
        FakeHost host = new FakeHost();
        assertInstanceOf(Install.Unmanaged.class, Install.of(null, host));
        assertInstanceOf(Install.Unmanaged.class, Install.of(tmp.resolve("classes"), host));
        Path cached = jar(".m2/repository/x/sluice-all.jar", "jar");
        Install install = Install.of(cached, host);
        assertInstanceOf(Install.Unmanaged.class, install);

        IOException refused = assertThrows(IOException.class, () -> update(install, host, "0.4.2").update());
        assertTrue(refused.getMessage().contains("Maven"), refused.getMessage());
    }

    @Test
    void checksumsAreReadAsSha256sumWritesThem() {
        String sums = "x".repeat(64) + "  other\n" + "AB".repeat(32) + " *sluice.rpm\n";
        assertEquals(Optional.of("ab".repeat(32)), Checksums.expected(sums, "sluice.rpm"));
        assertEquals(Optional.empty(), Checksums.expected(sums, "other"), "not hex");
        assertEquals(Optional.empty(), Checksums.expected(sums, "sluice.deb"));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A host that runs nothing: it records each command, and what the file it names held then. */
    private static final class FakeHost implements Host {
        boolean windows;
        boolean root;
        boolean terminal;
        int exit;
        Install.Format owner;
        Path localAppData;
        final Set<String> path = new HashSet<>();
        final Set<Path> registered = new HashSet<>();
        final List<String> msi = new ArrayList<>();
        final List<List<String>> ran = new ArrayList<>();
        final List<String> seen = new ArrayList<>();
        final List<String> afterExit = new ArrayList<>();
        final List<Boolean> shown = new ArrayList<>();

        static FakeHost windows(Path localAppData) {
            FakeHost host = new FakeHost();
            host.windows = true;
            host.localAppData = localAppData;
            return host;
        }

        @Override
        public boolean windows() {
            return windows;
        }

        @Override
        public boolean root() {
            return root;
        }

        @Override
        public boolean terminal() {
            return terminal;
        }

        @Override
        public boolean onPath(String program) {
            return path.contains(program);
        }

        @Override
        public boolean exists(Path file) {
            return registered.contains(file) || Files.exists(file) && !file.startsWith("/etc");
        }

        @Override
        public List<String> msiLocations() {
            return msi;
        }

        @Override
        public Optional<String> env(String name) {
            return name.equals("LOCALAPPDATA") && localAppData != null
                    ? Optional.of(real(localAppData)) : Optional.empty();
        }

        private static String real(Path dir) {
            try {
                Files.createDirectories(dir);
                return dir.toRealPath().toString();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Optional<Install.Format> owner(Path file) {
            return Optional.ofNullable(owner);
        }

        @Override
        public int run(List<String> argv) throws IOException {
            ran.add(argv);
            Path last;
            try {
                last = Path.of(argv.getLast());
            } catch (InvalidPathException notAPath) { // Windows: `APT::Get::List-Cleanup=0`
                return exit;
            }
            if (Files.isRegularFile(last)) seen.add(Files.readString(last));
            return exit;
        }

        @Override
        public void afterExit(String script, boolean shown) {
            afterExit.add(script);
            this.shown.add(shown);
        }
    }
}
