package com.botmaker.sluice.model;

import com.botmaker.sluice.model.PomFile.Where;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static com.botmaker.sluice.testing.PlainUmbrella.dep;
import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PomReaderTest {

    @TempDir
    Path tmp;

    @Test
    void readsRawVersionText() throws IOException {
        Path file = tmp.resolve("pom.xml");
        Files.writeString(file, pom("b", "1.0.0-SNAPSHOT",
                "  <properties><x.version>2.0.0</x.version></properties>\n" + dep("a", "${x.version}")));

        PomFile pom = PomReader.read(file);

        assertEquals(new Coordinate("g", "b"), pom.coordinate());
        assertEquals(Optional.of("1.0.0-SNAPSHOT"), pom.version());
        assertEquals("2.0.0", pom.properties().get("x.version"));
        assertEquals(1, pom.references().size());
        assertEquals(Optional.of("${x.version}"), pom.references().getFirst().version());
        assertEquals(Where.DEPENDENCY, pom.references().getFirst().where());
    }

    @Test
    void crlfPom() throws IOException {
        String text = pom("b", "1.0.0", "  <properties><x.version>2.0.0</x.version></properties>\n"
                + dep("a", "${x.version}"));
        Path lf = tmp.resolve("lf.xml");
        Path crlf = tmp.resolve("crlf.xml");
        Files.writeString(lf, text);
        Files.writeString(crlf, text.replace("\n", "\r\n"));

        PomFile fromLf = PomReader.read(lf);
        PomFile fromCrlf = PomReader.read(crlf);

        assertEquals(fromLf.coordinate(), fromCrlf.coordinate());
        assertEquals(fromLf.version(), fromCrlf.version());
        assertEquals(fromLf.references(), fromCrlf.references());
        assertEquals(fromLf.properties(), fromCrlf.properties());
    }
}
