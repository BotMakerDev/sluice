package com.botmaker.sluice.desktop;

import com.botmaker.sluice.desktop.model.Umbrella;
import com.botmaker.sluice.testing.FixtureUmbrella;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.botmaker.sluice.testing.PlainUmbrella.dep;
import static com.botmaker.sluice.testing.PlainUmbrella.pom;

/** The umbrellas this module's tests open: core's fixture, with a {@code sluice.yml}. */
public final class Fixtures {

    private Fixtures() {
    }

    /** {@code a} at 1.0.0 then changed; {@code b} pinning it by a property, at 0.1.0 then changed. */
    public static FixtureUmbrella pinned(Path tmp) {
        FixtureUmbrella umbrella = FixtureUmbrella.create(tmp)
                .module("a", pom("a", "1.0.1-SNAPSHOT", "")).tag("a", "v1.0.0")
                .commit("a", "src/A.java", "class A {}\n").push("a")
                .module("b", pom("b", "0.1.1-SNAPSHOT", "  <properties><a.version>1.0.1-SNAPSHOT</a.version>"
                        + "</properties>\n" + dep("a", "${a.version}"))).tag("b", "v0.1.0")
                .commit("b", "src/B.java", "class B {}\n").push("b");
        config(umbrella, "changelog: true\n");
        return umbrella;
    }

    public static Umbrella open(FixtureUmbrella umbrella) {
        return Umbrella.open(umbrella.root());
    }

    public static void config(FixtureUmbrella umbrella, String yaml) {
        try {
            Files.writeString(umbrella.root().resolve("sluice.yml"), yaml);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
