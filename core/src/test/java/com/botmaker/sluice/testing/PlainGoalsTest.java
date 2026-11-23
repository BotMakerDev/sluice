// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.testing;

import com.botmaker.sluice.PinMoves;
import com.botmaker.sluice.PinMoves.Goals;
import com.botmaker.sluice.Proc;
import com.botmaker.sluice.Runner;
import com.botmaker.sluice.model.Pin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.botmaker.sluice.testing.PlainUmbrella.dep;
import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The stand-in the release tests move versions with makes, byte for byte, the edit the plugin makes. */
class PlainGoalsTest {

    private static final String POM = pom("b", "0.1.1-SNAPSHOT",
            "  <properties>\n    <a.version>1.0.0</a.version>\n  </properties>\n" + dep("a", "${a.version}"));

    @TempDir
    Path tmp;

    @Test
    void aPlainPomMovesAsThePluginMovesIt() throws IOException {
        assumeTrue(Proc.onPath("mvn"), "the plugin's own edit needs mvn on PATH");
        Runner runner = new Runner(false, line -> { });
        Path byPlugin = moved(tmp.resolve("plugin"), Goals.maven(runner, List.of("-o")), runner);
        Path byStandIn = moved(tmp.resolve("stand-in"), new PlainGoals(runner, (pom, goal, properties) -> {
            throw new AssertionError("a plain pom needs no Maven: " + goal);
        }), runner);

        assertEquals(Files.readString(byPlugin), Files.readString(byStandIn));
        assertTrue(Files.readString(byStandIn).contains("<version>0.1.1</version>"));
    }

    @Test
    void aPomWithAParentGoesToMaven() throws IOException {
        Path pom = Files.createDirectories(tmp.resolve("child")).resolve("pom.xml");
        Files.writeString(pom, POM.replace("<modelVersion>4.0.0</modelVersion>",
                "<modelVersion>4.0.0</modelVersion>\n  <parent><groupId>g</groupId><artifactId>p</artifactId>"
                        + "<version>1</version></parent>"));
        List<String> asked = new ArrayList<>();
        Runner runner = new Runner(false, line -> { });

        new PlainGoals(runner, (at, goal, properties) -> asked.add(goal))
                .run(pom, "set", List.of("-DnewVersion=2.0.0", "-DgenerateBackupPoms=false"));

        assertEquals(List.of("set"), asked);
    }

    private static Path moved(Path dir, Goals goals, Runner runner) throws IOException {
        Path pom = Files.createDirectories(dir).resolve("pom.xml");
        Files.writeString(pom, POM);
        PinMoves moves = new PinMoves(runner, goals);
        moves.version(pom, "0.1.1");
        moves.move(new Pin.Property(pom, "a.version"), "1.0.1");
        return pom;
    }
}
