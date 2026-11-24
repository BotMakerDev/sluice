// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.execute;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.execute.Release.Status;
import com.botmaker.sluice.execute.ReleaseFixture.Assets;
import com.botmaker.sluice.testing.FixtureUmbrella;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.botmaker.sluice.execute.ReleaseFixture.EXECUTE;
import static com.botmaker.sluice.execute.ReleaseFixture.PREVIEW;
import static com.botmaker.sluice.execute.ReleaseFixture.config;
import static com.botmaker.sluice.execute.ReleaseFixture.lines;
import static com.botmaker.sluice.execute.ReleaseFixture.read;
import static com.botmaker.sluice.execute.ReleaseFixture.run;
import static com.botmaker.sluice.execute.ReleaseFixture.served;
import static com.botmaker.sluice.execute.ReleaseFixture.wiring;
import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetPinsTest {

    private static final String URL = "https://github.com/example/a/releases/download/v1.0.1/a-all.jar";
    private static final byte[] JAR = {1, 2, 3, 4};
    private static final String PIN = """
            {
              "version": "1.0.0",
              "url": "https://github.com/example/a/releases/download/v1.0.0/a-all.jar",
              "sha256": "00"
            }
            """;
    private static final String MANIFEST = "{\"name\": \"plugin\", \"version\": \"0.1.0\"}\n";

    @TempDir
    Path tmp;

    /**
     * {@code a} changed since v1.0.0, its origin named on GitHub but pushed to its bare remote; {@code plugin}, a
     * repository with no pom, holds the pin.
     */
    private FixtureUmbrella umbrella() {
        FixtureUmbrella umbrella = FixtureUmbrella.create(tmp)
                .module("a", pom("a", "1.0.1-SNAPSHOT", "")).tag("a", "v1.0.0")
                .commit("a", "src/A.java", "class A {}\n").push("a")
                .module("plugin", null).commit("plugin", "cli.json", PIN)
                .commit("plugin", "plugin.json", MANIFEST).push("plugin");
        Path a = umbrella.dir("a");
        String bare = FixtureUmbrella.git(a, "remote", "get-url", "origin").out().strip();
        FixtureUmbrella.git(a, "remote", "set-url", "--push", "origin", bare);
        FixtureUmbrella.git(a, "remote", "set-url", "origin", "https://github.com/example/a.git");
        config(umbrella, "asset-pins:\n  - {module: a, asset: a-all.jar, file: plugin/cli.json,"
                + " bumps: [plugin/plugin.json]}\n");
        return umbrella;
    }

    @Test
    @Tag("slow")
    void aRepositoryBehindItsOriginKeepsItsPin() {
        FixtureUmbrella umbrella = umbrella();
        Path other = tmp.resolve("other");
        FixtureUmbrella.git(tmp, "clone", "-q", tmp.resolve("remotes").resolve("plugin.git").toString(),
                other.toString());
        FixtureUmbrella.git(other, "-c", "user.name=x", "-c", "user.email=x@example.com", "commit", "-q",
                "--allow-empty", "-m", "elsewhere");
        FixtureUmbrella.git(other, "push", "-q", "origin", "main");
        List<String> lines = lines();

        Status status = run(umbrella, EXECUTE, wiring(served(), new Assets(Map.of(URL, JAR))), lines, "a").status();

        assertEquals(Status.DONE, status, String.join("\n", lines));
        assertEquals(PIN, read(umbrella.dir("plugin").resolve("cli.json")));
        String said = String.join("\n", lines);
        assertTrue(said.contains("plugin is not where its origin's main is"), said);
    }

    @Test
    @Tag("slow")
    void aReleasedAssetIsPinnedCommittedAndPushed() throws IOException {
        FixtureUmbrella umbrella = umbrella();
        List<String> lines = lines();

        Status status = run(umbrella, EXECUTE, wiring(served(), new Assets(Map.of(URL, JAR))), lines, "a").status();

        assertEquals(Status.DONE, status, String.join("\n", lines));
        String pinned = read(umbrella.dir("plugin").resolve("cli.json"));
        Path jar = Files.write(tmp.resolve("jar"), JAR);
        assertEquals(PIN.replace("\"1.0.0\"", "\"1.0.1\"").replace("v1.0.0", "v1.0.1")
                .replace("\"00\"", "\"" + AssetPins.sha256(jar) + "\""), pinned);
        Path remote = tmp.resolve("remotes").resolve("plugin.git");
        assertEquals(pinned, FixtureUmbrella.git(remote, "show", "main:cli.json").out());
        assertEquals(MANIFEST.replace("0.1.0", "0.1.1"), FixtureUmbrella.git(remote, "show", "main:plugin.json").out());
        String umbrellaCommit = FixtureUmbrella.git(umbrella.root(), "show", "--stat", "--format=", "HEAD").out();
        assertTrue(umbrellaCommit.contains("plugin"), umbrellaCommit);
    }

    @Test
    @Tag("slow")
    void anAssetThatNeverCameLeavesThePinAndTheReleaseDone() {
        FixtureUmbrella umbrella = umbrella();
        List<String> lines = lines();

        Status status = run(umbrella, EXECUTE, wiring(served(), Assets.NONE), lines, "a").status();

        assertEquals(Status.DONE, status, String.join("\n", lines));
        assertEquals(PIN, read(umbrella.dir("plugin").resolve("cli.json")));
        String said = String.join("\n", lines);
        assertTrue(said.contains("plugin/cli.json keeps its pin of a: " + URL + " is not there after 30 min"), said);
    }

    @Test
    void aPreviewSaysWhatItWouldPinAndWritesNothing() {
        FixtureUmbrella umbrella = umbrella();
        List<String> lines = lines();

        run(umbrella, PREVIEW, wiring(served(), new Assets(Map.of(URL, JAR))), lines, "a");

        assertEquals(PIN, read(umbrella.dir("plugin").resolve("cli.json")));
        String said = String.join("\n", lines);
        assertTrue(said.contains("would wait for " + URL + " and pin it in plugin/cli.json, raising the version of "
                + "plugin/plugin.json"), said);
    }

    @Test
    void aFieldIsSetAndTheRestKeptAsWritten() {
        String json = "{\"sha256\" : \"ab\", \"other\": 1}";

        assertEquals("{\"sha256\" : \"cd\", \"other\": 1}", AssetPins.set(json, "sha256", "cd"));
        assertEquals(Optional.of("ab"), AssetPins.field(json, "sha256"));
        assertThrows(Refusal.class, () -> AssetPins.set(json, "url", "x"));
    }
}
