package com.botmaker.sluice;

import com.botmaker.sluice.model.Coordinate;
import com.botmaker.sluice.model.Pin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinMovesTest {

    @TempDir
    Path tmp;

    @Test
    void eachPinKindMovedByItsGoal() {
        List<String> said = new ArrayList<>();
        PinMoves moves = new PinMoves(new Runner(true, said::add), List.of("-o"));
        Path pom = tmp.resolve("pom.xml");

        moves.version(pom, "1.2.3");
        moves.move(new Pin.Property(pom, "a.version"), "v1.0.0");
        moves.move(new Pin.Literal(pom, new Coordinate("g", "a")), "v1.0.0");
        moves.move(new Pin.Parent(pom), "2.0.0");
        moves.move(new Pin.Text(tmp.resolve("V.java"), Pattern.compile("V = \"([^\"]+)\""), Spelling.VERSION), "1.0.0");

        assertEquals(5, said.size(), String.join("\n", said));
        assertTrue(said.get(0).contains("mvn -B -q -o -f " + pom + " " + PinMoves.PLUGIN
                + ":set -DnewVersion=1.2.3 -DgenerateBackupPoms=false"), said.get(0));
        assertTrue(said.get(1).contains(":set-property -Dproperty=a.version -DnewVersion=v1.0.0"), said.get(1));
        assertTrue(said.get(2).contains(":use-dep-version -Dincludes=g:a -DdepVersion=v1.0.0 -DforceVersion=true"),
                said.get(2));
        assertTrue(said.get(3).contains("$ replace") && said.get(3).contains("<parent>"), said.get(3));
        assertTrue(said.get(4).contains("$ replace group 1"), said.get(4));
    }

    @Test
    void parentAndTextMovedAsTextAndReadBack() throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, "<project><parent><groupId>g</groupId><artifactId>p</artifactId>\n"
                + "  <version>1.0.0-SNAPSHOT</version></parent><artifactId>b</artifactId>"
                + "<version>0.1.0</version></project>\n");
        Path text = tmp.resolve("V.java");
        Files.writeString(text, "String V = \"1.0.0\"; // V = \"keep\"\n");
        PinMoves moves = new PinMoves(new Runner(false, line -> { }), List.of());

        moves.move(new Pin.Parent(pom), "v1.0.0");
        moves.move(new Pin.Text(text, Pattern.compile("V = \"([^\"]+)\""), Spelling.VERSION), "1.1.0");

        assertTrue(Files.readString(pom).contains("<version>v1.0.0</version></parent>"), Files.readString(pom));
        assertTrue(Files.readString(pom).contains("<version>0.1.0</version></project>"), Files.readString(pom));
        assertEquals("String V = \"1.1.0\"; // V = \"keep\"\n", Files.readString(text));
    }

    @Test
    void textPinThatMatchesNothingRefused() throws IOException {
        Path text = tmp.resolve("V.java");
        Files.writeString(text, "nothing here\n");
        PinMoves moves = new PinMoves(new Runner(false, line -> { }), List.of());

        assertThrows(Refusal.class,
                () -> moves.move(new Pin.Text(text, Pattern.compile("V = \"([^\"]+)\""), Spelling.VERSION), "1"));
    }
}
