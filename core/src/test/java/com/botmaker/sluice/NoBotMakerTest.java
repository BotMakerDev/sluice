package com.botmaker.sluice;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * sluice is a generic tool whose first user is BotMaker: nothing it ships may know that. The package name is the
 * one exception, since the organisation publishes it.
 */
class NoBotMakerTest {

    private static final Pattern PACKAGE = Pattern.compile("com\\.botmaker\\.sluice|com/botmaker/sluice");

    /** Every module's main sources, this one's siblings too: the test runs in {@code core/}. */
    @Test
    void mainSourcesNameNoBotMaker() throws IOException {
        List<Path> mains;
        try (Stream<Path> modules = Files.list(Path.of(".."))) {
            mains = modules.map(module -> module.resolve("src/main")).filter(Files::isDirectory).toList();
        }
        assertTrue(mains.contains(Path.of("../core/src/main")) && mains.contains(Path.of("../cli/src/main")),
                "run from core/, so core and cli are siblings: " + mains);
        List<String> hits = new ArrayList<>();
        for (Path main : mains) {
            try (Stream<Path> files = Files.walk(main)) {
                files.filter(Files::isRegularFile).flatMap(NoBotMakerTest::hits).forEach(hits::add);
            }
        }
        assertEquals(List.of(), hits);
    }

    private static Stream<String> hits(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            return IntStream.range(0, lines.size())
                    .filter(i -> PACKAGE.matcher(lines.get(i)).replaceAll("").toLowerCase().contains("botmaker"))
                    .mapToObj(i -> file + ":" + (i + 1) + ": " + lines.get(i).strip());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
