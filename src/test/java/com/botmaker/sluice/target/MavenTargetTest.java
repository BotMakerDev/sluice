package com.botmaker.sluice.target;

import com.botmaker.sluice.Runner;
import com.botmaker.sluice.Spelling;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.ConfigLoader;
import com.botmaker.sluice.model.Coordinate;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.spi.PublishTarget;
import com.botmaker.sluice.spi.PublishTarget.Served;
import com.botmaker.sluice.target.Waits.Outcome;
import com.botmaker.sluice.testing.FixtureUmbrella;
import com.botmaker.sluice.testing.GateFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class MavenTargetTest {

    private static final Coordinate A = new Coordinate("g.h", "a");
    private static final String POM = "https://repo.example/maven/g/h/a/1.2.3/a-1.2.3.pom";

    @TempDir
    Path tmp;

    @Test
    void pinIsBareVersion() {
        PublishTarget target = new MavenTarget("https://repo.example/maven/", new StubHttp().head(POM, 200));

        assertEquals(Spelling.VERSION, target.spelling());
        assertEquals("1.2.3", target.spelling().spell(new Version(1, 2, 3), "v{version}"));
        assertEquals(Served.YES, target.serves(A, "1.2.3"));
        assertEquals(Optional.of("https://repo.example/maven"), target.repository());
    }

    @Test
    void centralNeedsNoRepository() {
        assertEquals(Optional.empty(), new MavenTarget("https://repo1.maven.org/maven2/", new StubHttp()).repository());
    }

    @Test
    void onlyNotFoundIsANo() {
        assertEquals(Served.NO, new MavenTarget("https://repo.example/maven", new StubHttp().head(POM, 404))
                .serves(A, "1.2.3"));
        assertEquals(Served.UNREACHABLE, new MavenTarget("https://repo.example/maven",
                new StubHttp().head(POM, 401)).serves(A, "1.2.3"));
        assertEquals(Served.UNREACHABLE, new MavenTarget("https://repo.example/maven", new StubHttp())
                .serves(A, "1.2.3"));
    }

    @Test
    void waitsUntilPomServed() {
        FixtureUmbrella umbrella = FixtureUmbrella.create(tmp).module("a", pom("a", "0.0.1-SNAPSHOT", ""));
        Module a = GateFixture.context(umbrella, Config.defaults(), false, "a").graph().module("a").orElseThrow();
        String pom = "https://repo.example/g/a/0.0.1/a-0.0.1.pom";
        int[] slept = {0};

        Waits.Published published = Waits.await(new MavenTarget("https://repo.example", new StubHttp()
                        .head(pom, 404, 404, 404, 200)), a, new Version(0, 0, 1), Config.defaults(),
                new Runner(false, line -> { }), duration -> ++slept[0] > 0);

        assertEquals(Outcome.PUBLISHED, published.outcome());
        assertEquals(3, slept[0]);
    }

    @Test
    void fileAddressIsADirectory() throws IOException {
        Path repository = tmp.resolve("repository");
        Files.createDirectories(repository.resolve("g/h/a/1.2.3"));
        Files.writeString(repository.resolve("g/h/a/1.2.3/a-1.2.3.pom"), "<project/>");
        Config config = ConfigLoader.parse("target: {maven: '" + repository.toUri() + "'}\n", "t");
        FixtureUmbrella umbrella = FixtureUmbrella.create(tmp).module("a", pom("a", "0.0.1-SNAPSHOT", ""));
        Module a = GateFixture.context(umbrella, config, false, "a").graph().module("a").orElseThrow();

        PublishTarget target = Targets.of(config, new StubHttp(), tmp).apply(a);

        assertInstanceOf(FileRepoTarget.class, target);
        assertEquals(Served.YES, target.serves(A, "1.2.3"));
        assertEquals(Served.NO, target.serves(A, "1.2.4"));
    }
}
