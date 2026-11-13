package com.botmaker.sluice.desktop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The window came from BotMaker's dashboard: none of what it was built on comes with it. It reads the library,
 * never BotMaker's command or its host platform; core's {@code NoBotMakerTest} keeps the name out of its labels.
 */
class DesktopSourcesTest {

    private static final Pattern REFUSED = Pattern.compile("com\\.botmaker\\.(cli|shared|dashboard)\\b");

    @Test
    void noSourceNamesBotMakersCommandOrPlatform() throws IOException {
        List<String> hits;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            hits = files.filter(file -> file.toString().endsWith(".java")).flatMap(DesktopSourcesTest::hits).toList();
        }
        assertEquals(List.of(), hits);
    }

    private static Stream<String> hits(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            return IntStream.range(0, lines.size()).filter(i -> REFUSED.matcher(lines.get(i)).find())
                    .mapToObj(i -> file + ":" + (i + 1) + ": " + lines.get(i).strip());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
