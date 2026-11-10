package com.botmaker.sluice.decide;

import com.botmaker.sluice.Level;
import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.ConfigLoader;
import com.botmaker.sluice.layout.UmbrellaLayout;
import com.botmaker.sluice.model.Graph;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.testing.FixtureUmbrella;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.botmaker.sluice.testing.PlainUmbrella.dep;
import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecideTest {

    private static final String PINS_A = "  <properties><a.version>1.0.0</a.version></properties>\n"
            + dep("a", "${a.version}");

    @TempDir
    Path tmp;

    private FixtureUmbrella umbrella;
    private Config config = Config.defaults();

    @BeforeEach
    void create() {
        umbrella = FixtureUmbrella.create(tmp);
    }

    private Plan decide(Optional<Level> all, boolean withDependents, String... moduleArgs) {
        Graph graph = Graph.build(UmbrellaLayout.of(umbrella.root()), config);
        return Decider.decide(graph, config, Requests.parse(List.of(moduleArgs), all), withDependents);
    }

    private static Map<String, String> releasing(Plan plan) {
        Map<String, String> byName = new LinkedHashMap<>();
        plan.releasing().forEach((module, version) -> byName.put(module.name(), version.toString()));
        return byName;
    }

    private static Decision of(Plan plan, String module) {
        return plan.decisions().stream().filter(d -> d.module().name().equals(module)).findFirst().orElseThrow();
    }

    /** {@code a} released at 1.0.0 and back on its snapshot; {@code b}, which pins it by a property, the same. */
    private void aAndItsDependent() {
        umbrella.module("a", pom("a", "1.0.0", "")).tag("a", "v1.0.0")
                .commit("a", "pom.xml", pom("a", "1.0.1-SNAPSHOT", ""))
                .module("b", pom("b", "1.0.0", PINS_A)).tag("b", "v1.0.0")
                .commit("b", "pom.xml", pom("b", "1.0.1-SNAPSHOT", PINS_A.replace("1.0.0", "1.0.1-SNAPSHOT")));
    }

    @Test
    void changedSinceTagReleasesNextPatch() {
        umbrella.module("a", pom("a", "1.0.0", "")).tag("a", "v1.0.0")
                .commit("a", "src/A.java", "class A {}\n");

        Plan plan = decide(Optional.empty(), false, "a");

        assertEquals(Map.of("a", "1.0.1"), releasing(plan));
        assertEquals(ChangeKind.CHANGED, of(plan, "a").change());
        assertEquals(new Version(1, 0, 0), of(plan, "a").base());
    }

    @Test
    void docsOnlyChangeSkippedAsIgnoredOnly() {
        umbrella.module("a", pom("a", "1.0.0", "")).tag("a", "v1.0.0")
                .commit("a", "README.md", "words\n")
                .commit("a", "docs/guide.txt", "more words\n");

        Plan plan = decide(Optional.empty(), false, "a");

        assertEquals(Map.of(), releasing(plan));
        assertEquals(ChangeKind.IGNORED_ONLY, of(plan, "a").change());
        assertTrue(of(plan, "a").reason().contains("README.md"), of(plan, "a").reason());
    }

    @Test
    void untaggedModuleReleasesAtRequestedVersion() {
        umbrella.module("core", pom("core", "0.1.0-SNAPSHOT", ""));

        Plan plan = decide(Optional.empty(), false, "core=0.1.0");

        assertEquals(Map.of("core", "0.1.0"), releasing(plan));
        assertEquals(Version.ZERO, of(plan, "core").base());
        assertEquals(ChangeKind.CHANGED, of(plan, "core").change());
    }

    @Test
    void untaggedModuleRequestedByLevelBumpsFromZero() {
        umbrella.module("core", pom("core", "0.1.0-SNAPSHOT", ""));

        assertEquals(Map.of("core", "0.1.0"), releasing(decide(Optional.empty(), false, "core=minor")));
    }

    @Test
    void allMinorReleasesEveryChanged() {
        umbrella.module("a", pom("a", "1.0.0", "")).tag("a", "v1.0.0")
                .commit("a", "src/A.java", "class A {}\n")
                .module("still", pom("still", "2.3.0", "")).tag("still", "v2.3.0")
                .module("fresh", pom("fresh", "0.1.0-SNAPSHOT", ""));

        Plan plan = decide(Optional.of(Level.MINOR), false);

        assertEquals(Map.of("a", "1.1.0", "fresh", "0.1.0"), releasing(plan));
        assertEquals(List.of("a", "fresh"), plan.releasing().keySet().stream().map(Module::name).toList());
        assertEquals(ChangeKind.NONE, of(plan, "still").change());
    }

    @Test
    void backToSnapshotAndMovedPinsAreNoChange() {
        aAndItsDependent();

        Plan plan = decide(Optional.of(Level.PATCH), false);

        assertEquals(Map.of(), releasing(plan));
        assertEquals(ChangeKind.NONE, of(plan, "a").change());
        assertEquals(ChangeKind.NONE, of(plan, "b").change());
    }

    @Test
    void pomChangedBeyondVersionsIsAChange() {
        umbrella.module("a", pom("a", "1.0.0", "")).tag("a", "v1.0.0")
                .commit("a", "pom.xml", pom("a", "1.0.1-SNAPSHOT", "  <packaging>pom</packaging>\n"));

        assertEquals(Map.of("a", "1.0.1"), releasing(decide(Optional.of(Level.PATCH), false)));
    }

    @Test
    void upstreamReleasedSinceMakesItsDependentChanged() {
        aAndItsDependent();
        umbrella.commit("a", "src/A.java", "class A {}\n").tag("a", "v1.1.0");

        Plan plan = decide(Optional.empty(), false, "b");

        assertEquals(Map.of("b", "1.0.1"), releasing(plan));
        assertTrue(of(plan, "b").reason().contains("released since at 1.1.0"), of(plan, "b").reason());
    }

    @Test
    void owedDependentListedNotAdded() {
        aAndItsDependent();
        umbrella.commit("a", "src/A.java", "class A {}\n");

        Plan plan = decide(Optional.empty(), false, "a");

        assertEquals(Map.of("a", "1.0.1"), releasing(plan));
        assertEquals(1, plan.owedUnrequested().size());
        assertEquals("a", plan.owedUnrequested().getFirst().upstream().name());
        assertEquals("b", plan.owedUnrequested().getFirst().downstream().name());
    }

    @Test
    void withDependentsAddsAtNextPatch() {
        aAndItsDependent();
        umbrella.commit("a", "src/A.java", "class A {}\n");

        Plan plan = decide(Optional.empty(), true, "a=minor");

        assertEquals(Map.of("a", "1.1.0", "b", "1.0.1"), releasing(plan));
        assertEquals(List.of(), plan.owedUnrequested());
    }

    @Test
    void requestedDependentWithNoChangeIsReleasedWhenOwed() {
        aAndItsDependent();
        umbrella.commit("a", "src/A.java", "class A {}\n");

        Plan plan = decide(Optional.empty(), false, "a", "b=minor");

        assertEquals(Map.of("a", "1.0.1", "b", "1.1.0"), releasing(plan));
    }

    @Test
    void textPinMakesOwed() {
        umbrella.module("a", pom("a", "1.0.0", ""))
                .module("b", pom("b", "1.0.0", ""))
                .commit("b", "src/Fallback.java", "String V = \"1.0.0\";\n");
        config = ConfigLoader.parse("""
                text-pins:
                  - in: b/src/Fallback.java
                    pins: a
                    match: 'V = "([^"]+)"'
                """, "t");
        umbrella.tag("a", "v1.0.0").tag("b", "v1.0.0").commit("a", "src/A.java", "class A {}\n");

        Plan plan = decide(Optional.empty(), false, "a");

        assertEquals(Map.of("a", "1.0.1"), releasing(plan));
        assertEquals("text pin in b/src/Fallback.java", plan.owedUnrequested().getFirst().why());
    }

    @Test
    void versionAlreadyTaggedRefused() {
        umbrella.module("a", pom("a", "1.0.0", "")).tag("a", "v1.0.0");

        Refusal refusal = assertThrows(Refusal.class, () -> decide(Optional.empty(), false, "a=1.0.0"));

        assertTrue(refusal.getMessage().contains("already tagged"), refusal.getMessage());
    }

    @Test
    void versionBelowTheNewestRefused() {
        umbrella.module("a", pom("a", "2.3.0", "")).tag("a", "v2.3.0");

        Refusal refusal = assertThrows(Refusal.class, () -> decide(Optional.empty(), false, "a=1.0.0"));

        assertTrue(refusal.getMessage().contains("below its newest release"), refusal.getMessage());
    }

    @Test
    void fileMovedIntoAnIgnoredDirectoryIsAChange() {
        umbrella.module("a", pom("a", "1.0.0", "")).commit("a", "src/help.txt", "help\n")
                .commit("a", "docs/guide.txt", "words\n").tag("a", "v1.0.0");
        FixtureUmbrella.git(umbrella.dir("a"), "mv", "src/help.txt", "docs/help.txt");
        FixtureUmbrella.git(umbrella.dir("a"), "commit", "-q", "-m", "move");

        assertEquals(ChangeKind.CHANGED, of(decide(Optional.empty(), false, "a"), "a").change());
    }

    @Test
    void pathOutsideAsciiStillMatchesItsGlob() {
        umbrella.module("a", pom("a", "1.0.0", "")).tag("a", "v1.0.0").commit("a", "docs/café.md", "words\n");

        assertEquals(ChangeKind.IGNORED_ONLY, of(decide(Optional.empty(), false, "a"), "a").change());
    }

    @Test
    void textPinAddedSinceTheTagInAnIgnoredFileIsNoChange() {
        umbrella.module("a", pom("a", "1.0.0", "")).tag("a", "v1.0.0")
                .module("b", pom("b", "1.0.0", "")).tag("b", "v1.0.0")
                .commit("b", "README.md", "use a 1.0.0\n");
        config = ConfigLoader.parse("""
                text-pins:
                  - in: b/README.md
                    pins: a
                    match: 'use a ([0-9.]+)'
                """, "t");

        assertEquals(ChangeKind.IGNORED_ONLY, of(decide(Optional.empty(), false, "b"), "b").change());
    }

    @Test
    void tagFromTheRemoteCounts() {
        umbrella.module("a", pom("a", "1.0.0", "")).tag("a", "v1.0.0").tag("a", "v1.10.0").push("a");
        FixtureUmbrella.git(umbrella.dir("a"), "tag", "-d", "v1.10.0");
        umbrella.commit("a", "src/A.java", "class A {}\n");

        assertEquals(Map.of("a", "1.10.1"), releasing(decide(Optional.empty(), false, "a")));
    }
}
