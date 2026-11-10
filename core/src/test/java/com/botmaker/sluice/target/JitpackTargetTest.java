package com.botmaker.sluice.target;

import com.botmaker.sluice.Runner;
import com.botmaker.sluice.Spelling;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.ConfigLoader;
import com.botmaker.sluice.gate.GateVerdict;
import com.botmaker.sluice.gate.GateVerdict.Status;
import com.botmaker.sluice.gate.TargetGate;
import com.botmaker.sluice.model.Coordinate;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.spi.PublishTarget;
import com.botmaker.sluice.spi.PublishTarget.Served;
import com.botmaker.sluice.target.Waits.Outcome;
import com.botmaker.sluice.target.Waits.Published;
import com.botmaker.sluice.testing.FixtureUmbrella;
import com.botmaker.sluice.testing.GateFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.botmaker.sluice.testing.GateFixture.check;
import static com.botmaker.sluice.testing.GateFixture.only;
import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JitpackTargetTest {

    private static final Coordinate A = new Coordinate("g", "a");
    private static final String POM = "https://jitpack.io/g/a/v0.0.1/a-v0.0.1.pom";
    private static final String BUILD = "https://jitpack.io/api/builds/g/a/v0.0.1";

    @TempDir
    Path tmp;

    private PublishTarget jitpack(StubHttp http) {
        return new JitpackTarget(Optional.empty(), http, tmp.resolve("m2"));
    }

    private Module a(FixtureUmbrella umbrella) {
        return GateFixture.context(umbrella, Config.defaults(), false, "a").graph().module("a").orElseThrow();
    }

    @Test
    void pinIsTagName() {
        StubHttp http = new StubHttp().head(POM, 200);
        PublishTarget target = jitpack(http);

        assertEquals(Spelling.TAG, target.spelling());
        assertEquals("v0.0.1", target.spelling().spell(new Version(0, 0, 1), "v{version}"));
        assertEquals(Served.YES, target.serves(A, "v0.0.1"));
        assertEquals(Optional.of("https://jitpack.io"), target.repository());
    }

    @Test
    void buildErrorIsFinal() {
        StubHttp http = new StubHttp().head(POM, 404)
                .body(BUILD, "{\"status\": \"Error\", \"message\": \"No build artifacts found. "
                        + "See the log: g/a/v0.0.1/build.log\"}")
                .body("https://jitpack.io/g/a/v0.0.1/build.log",
                        "[ERROR] Failed: status code: 429, reason phrase: Too Many Requests (429)");
        FixtureUmbrella umbrella = FixtureUmbrella.create(tmp).module("a", pom("a", "0.0.1-SNAPSHOT", ""));
        List<Integer> slept = new ArrayList<>();

        Published published = Waits.await(jitpack(http), a(umbrella), new Version(0, 0, 1), Config.defaults(),
                new Runner(false, line -> { }), duration -> slept.add(1));

        assertEquals(Outcome.FAILED, published.outcome());
        assertTrue(published.detail().contains("No build artifacts found"), published.detail());
        assertTrue(published.detail().contains("429 Too Many Requests"), published.detail());
        assertEquals(List.of(), slept, "a failed build is not waited on");
    }

    @Test
    void okAfterPolling() {
        StubHttp http = new StubHttp().head(POM, 404, 404, 200)
                .body(BUILD, "{\"status\": \"Building\"}");
        FixtureUmbrella umbrella = FixtureUmbrella.create(tmp).module("a", pom("a", "0.0.1-SNAPSHOT", ""));
        List<Integer> slept = new ArrayList<>();

        Published published = Waits.await(jitpack(http), a(umbrella), new Version(0, 0, 1), Config.defaults(),
                new Runner(false, line -> { }), duration -> slept.add(1));

        assertEquals(Outcome.PUBLISHED, published.outcome());
        assertEquals(2, slept.size());
        assertTrue(http.asked.contains("GET " + BUILD), "the build API is asked, which starts the build");
    }

    /** A plugin pinned through a property, whose own pom demands a Maven later than the build's. */
    private static final String COMPILER = """
              <properties><compiler.version>3.13.0</compiler.version></properties>
              <build><plugins><plugin>
                <artifactId>maven-compiler-plugin</artifactId><version>${compiler.version}</version>
              </plugin></plugins></build>
            """;

    private static final String COMPILER_POM = "org/apache/maven/plugins/maven-compiler-plugin/3.13.0/"
            + "maven-compiler-plugin-3.13.0.pom";

    private int umbrellas;

    private List<GateVerdict> plugins(String target, StubHttp http) {
        FixtureUmbrella umbrella = FixtureUmbrella.create(tmp.resolve("u" + umbrellas++))
                .module("a", pom("a", "0.0.1-SNAPSHOT", COMPILER));
        Config config = ConfigLoader.parse("target: " + target + "\n", "t");
        return check(new TargetGate(Targets.of(config, http, tmp.resolve("m2"))), umbrella, config, "a");
    }

    @Test
    void pluginTooNewForJitpacksMavenRefused() throws IOException {
        Path cached = tmp.resolve("m2").resolve(COMPILER_POM);
        Files.createDirectories(cached.getParent());
        Files.writeString(cached, "<project><prerequisites><maven>3.6.3</maven></prerequisites></project>");

        GateVerdict refused = only(plugins("jitpack", new StubHttp()));
        GateVerdict ok = only(plugins("{jitpack: {maven: '3.9.9'}}", new StubHttp()));

        assertEquals(Status.REFUSED, refused.status());
        assertTrue(refused.refusal().contains("maven-compiler-plugin:3.13.0 requires Maven 3.6.3"),
                refused.refusal());
        assertEquals(Status.OK, ok.status(), ok.refusal());
    }

    @Test
    void prerequisiteReadFromCentralWhenNotLocal() {
        StubHttp http = new StubHttp().body(MavenPrerequisite.CENTRAL + COMPILER_POM,
                "<project><prerequisites><maven>${maven.min}</maven></prerequisites>"
                        + "<properties><maven.min>3.9.6</maven.min></properties></project>");

        assertEquals(Status.REFUSED, only(plugins("jitpack", http)).status());
    }

    @Test
    void unreadablePrerequisiteSkipped() {
        GateVerdict verdict = only(plugins("jitpack", new StubHttp()));

        assertEquals(Status.SKIPPED, verdict.status());
        assertTrue(verdict.line().contains("maven-compiler-plugin:3.13.0 (its pom could not be read)"),
                verdict.line());
    }

    @Test
    void scriptNotCommittedRefused() throws IOException {
        FixtureUmbrella umbrella = FixtureUmbrella.create(tmp).module("a", pom("a", "0.0.1-SNAPSHOT", ""))
                .commit("a", "jitpack.yml", "jdk:\n  - openjdk21\ninstall:\n  - bash .jitpack/build.sh\n");
        Path script = umbrella.dir("a").resolve(".jitpack/build.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "mvn install\n");
        Config config = ConfigLoader.parse("target: jitpack\n", "t");
        TargetGate gate = new TargetGate(Targets.of(config, new StubHttp(), tmp.resolve("m2")));

        GateVerdict refused = only(check(gate, umbrella, config, "a"));
        umbrella.commit("a", ".jitpack/build.sh", "mvn install\n");

        assertEquals(Status.REFUSED, refused.status());
        assertTrue(refused.refusal().contains(".jitpack/build.sh is not committed"), refused.refusal());
        assertEquals(List.of(), check(gate, umbrella, config, "a"));
    }

    @Test
    void scriptsReadFromEveryList() {
        assertEquals(Optional.of(List.of("./tools/prepare.sh", ".jitpack/build.sh")), JitpackScript.scripts(
                "jdk:\n  - openjdk21\nbefore_install:\n  - ./tools/prepare.sh --fast\n  - bash ../up.sh\n"
                        + "install:\n  - \"bash .jitpack/build.sh\"\n  - mvn install\n"));
        assertEquals(Optional.empty(), JitpackScript.scripts("install: [unclosed\n"));
    }

    /** A version a parent's property spells is said as not checked, never left out of the count. */
    @Test
    void pluginVersionFromElsewhereSkipped() {
        FixtureUmbrella umbrella = FixtureUmbrella.create(tmp).module("a", pom("a", "0.0.1-SNAPSHOT", """
                  <build><plugins><plugin>
                    <artifactId>maven-shade-plugin</artifactId><version>${shade.version}</version>
                  </plugin></plugins></build>
                """));
        Config config = ConfigLoader.parse("target: jitpack\n", "t");

        GateVerdict verdict = only(check(new TargetGate(Targets.of(config, new StubHttp(), tmp.resolve("m2"))),
                umbrella, config, "a"));

        assertEquals(Status.SKIPPED, verdict.status());
        assertTrue(verdict.line().contains("maven-shade-plugin:${shade.version}"), verdict.line());
    }

    @Test
    void pluginPomReadOncePerRun() {
        StubHttp http = new StubHttp();
        Config config = ConfigLoader.parse("target: jitpack\n", "t");
        FixtureUmbrella umbrella = FixtureUmbrella.create(tmp)
                .module("a", pom("a", "0.0.1-SNAPSHOT", COMPILER)).module("b", pom("b", "0.0.1-SNAPSHOT", COMPILER));

        check(new TargetGate(Targets.of(config, http, tmp.resolve("m2"))), umbrella, config, "a", "b");

        assertEquals(1, http.asked.stream().filter(url -> url.endsWith(COMPILER_POM)).count(), http.asked.toString());
    }
}
