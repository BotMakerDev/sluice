package com.botmaker.sluice.gate;

import com.botmaker.sluice.Proc;
import com.botmaker.sluice.Spelling;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.gate.GateVerdict.Status;
import com.botmaker.sluice.model.Coordinate;
import com.botmaker.sluice.spi.PublishTarget;
import com.botmaker.sluice.spi.PublishTarget.Served;
import com.botmaker.sluice.testing.FixtureUmbrella;
import com.botmaker.sluice.testing.GateFixture.FixedTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static com.botmaker.sluice.testing.GateFixture.check;
import static com.botmaker.sluice.testing.GateFixture.only;
import static com.botmaker.sluice.testing.PlainUmbrella.dep;
import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BuildsAtPinsGateTest {

    /** What {@code b}'s pom holds besides its coordinate: its pin of {@code a}, the plugin this build has too. */
    private static final String PINS_A = """
              <properties>
                <a.version>1.0.1-SNAPSHOT</a.version>
                <maven.compiler.release>17</maven.compiler.release>
              </properties>
              <build><plugins><plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.11.0</version>
              </plugin></plugins></build>
            """ + dep("a", "${a.version}");

    @TempDir
    Path tmp;

    /** {@code a} released at 1.0.0; {@code b} pins it by a property, {@code repositories} added to its pom. */
    private FixtureUmbrella umbrella(String repositories) {
        return FixtureUmbrella.create(tmp)
                .module("a", pom("a", "1.0.1-SNAPSHOT", "")).tag("a", "v1.0.0")
                .module("b", pom("b", "0.1.0-SNAPSHOT", PINS_A + repositories));
    }

    private static String repository(String url) {
        return "  <repositories><repository><id>published</id><url>" + url + "</url></repository></repositories>\n";
    }

    private static FixedTarget target(Optional<String> repository) {
        return new FixedTarget(Spelling.VERSION, repository, Served.YES);
    }

    @Test
    void undeclaredRepositoryRefusedWithoutBuilding() {
        BuildsAtPinsGate gate = new BuildsAtPinsGate(module -> target(Optional.of("https://repo.example.invalid/")));

        GateVerdict verdict = only(check(gate, umbrella(""), Config.defaults(), "b"));

        assertEquals(Status.REFUSED, verdict.status());
        assertTrue(verdict.line().contains("b pins a, which resolves from https://repo.example.invalid, and its "
                + "pom declares no such repository"), verdict.line());
        assertTrue(verdict.refusal().contains("b/pom.xml"), verdict.refusal());
    }

    /**
     * A parent with no {@code <relativePath>} points at the umbrella's pom, which the module's tag is built
     * without: what that pom declares is not the module's.
     */
    @Test
    void repositoryDeclaredOnlyByTheUmbrellasPomRefused() throws IOException {
        String url = "https://repo.example.invalid";
        FixtureUmbrella umbrella = umbrella(
                "  <parent><groupId>g</groupId><artifactId>umbrella</artifactId><version>1</version></parent>\n");
        Files.writeString(umbrella.root().resolve("pom.xml"), pom("umbrella", "1", repository(url)));

        GateVerdict verdict = only(check(new BuildsAtPinsGate(module -> target(Optional.of(url))), umbrella,
                Config.defaults(), "b"));

        assertEquals(Status.REFUSED, verdict.status());
        assertTrue(verdict.line().contains("declares no such repository"), verdict.line());
    }

    @Test
    void upstreamPublishedNowhereSkipped() {
        PublishTarget nowhere = new PublishTarget() {
            @Override
            public String name() {
                return "none";
            }

            @Override
            public boolean publishes() {
                return false;
            }

            @Override
            public Spelling spelling() {
                return Spelling.VERSION;
            }

            @Override
            public Optional<String> repository() {
                return Optional.empty();
            }

            @Override
            public Served serves(Coordinate coordinate, String version) {
                return Served.NO;
            }
        };

        GateVerdict verdict = only(check(new BuildsAtPinsGate(module -> nowhere), umbrella(""), Config.defaults(),
                "b"));

        assertEquals(Status.SKIPPED, verdict.status());
        assertTrue(verdict.line().contains("a is published nowhere"), verdict.line());
    }

    @Test
    void upstreamReleasedByThisRunSkipped() {
        FixtureUmbrella umbrella = umbrella("").commit("a", "src/A.java", "class A {}\n");

        GateVerdict verdict = only(check(new BuildsAtPinsGate(module -> target(Optional.empty())), umbrella,
                Config.defaults(), "a", "b"));

        assertEquals(Status.SKIPPED, verdict.status());
        assertTrue(verdict.line().contains("a released by this run, not published yet"), verdict.line());
    }

    /**
     * The case of a pom that builds on its author's machine only: {@code a} 1.0.0 is published to a repository
     * {@code b} does not declare. No local repository is consulted, so nothing hides it.
     */
    @Test
    void missingRepositoryRefused() throws IOException {
        publish("a", "1.0.0");
        BuildsAtPinsGate gate = new BuildsAtPinsGate(module -> target(Optional.empty()), offline());

        GateVerdict verdict = only(check(gate, umbrella(""), Config.defaults(), "b"));

        assertEquals(Status.REFUSED, verdict.status());
        assertTrue(verdict.line().contains("b does not build at the pins its tag would carry (a.version=1.0.0)"),
                verdict.line());
        assertTrue(verdict.refusal().contains("g:a:jar:1.0.0"), verdict.refusal());
    }

    @Test
    void declaredRepositoryBuilds() throws IOException {
        Path published = publish("a", "1.0.0");
        BuildsAtPinsGate gate = new BuildsAtPinsGate(module -> target(Optional.of(published.toUri().toString())),
                offline());
        FixtureUmbrella umbrella = umbrella(repository(published.toUri().toString()));

        GateVerdict verdict = only(check(gate, umbrella, Config.defaults(), "b"));

        assertEquals(Status.OK, verdict.status(), verdict.refusal());
        assertEquals("", FixtureUmbrella.git(umbrella.dir("b"), "status", "--porcelain").out(),
                "the module's checkout is as it was");
    }

    /** A file repository holding {@code g:artifactId} at {@code version}: a pom and an empty jar. */
    private Path publish(String artifactId, String version) throws IOException {
        Path repository = tmp.resolve("published");
        Path dir = repository.resolve("g").resolve(artifactId).resolve(version);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(artifactId + "-" + version + ".pom"), pom(artifactId, version, ""));
        try (OutputStream out = Files.newOutputStream(dir.resolve(artifactId + "-" + version + ".jar"));
             JarOutputStream jar = new JarOutputStream(out, new Manifest())) {
            jar.finish();
        }
        return repository;
    }

    /**
     * Maven arguments that make this build's own local repository stand in for Central, so the empty local
     * repository the gate builds in fills without the network. Skips the test where there is no Maven.
     */
    private List<String> offline() throws IOException {
        Path local = Path.of(System.getProperty("sluice.test.repository",
                Path.of(System.getProperty("user.home"), ".m2", "repository").toString()));
        assumeTrue(Proc.onPath("mvn"), "mvn is not on PATH");
        assumeTrue(Files.isDirectory(local.resolve("org/apache/maven/plugins/maven-compiler-plugin/3.11.0")),
                "no local repository to stand in for Central at " + local);
        Path settings = tmp.resolve("settings.xml");
        Files.writeString(settings, """
                <settings>
                  <mirrors><mirror>
                    <id>local-central</id><mirrorOf>central</mirrorOf><url>%s</url>
                  </mirror></mirrors>
                </settings>
                """.formatted(local.toUri()));
        // Not -o: offline, Maven refuses even a file repository it has not read before.
        return List.of("-s", settings.toString());
    }
}
