package com.botmaker.sluice.execute;

import com.botmaker.sluice.Changelog;
import com.botmaker.sluice.Git;
import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.Runner;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.decide.Tags;
import com.botmaker.sluice.model.Edge;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.model.Pin;
import com.botmaker.sluice.model.PinReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * What a release commit says of the pins it moves, under {@code ## [Unreleased]}'s {@code ### Changed} of the
 * module's {@code CHANGELOG.md}, before the heading is stamped: one line per upstream it is now built against,
 * with what its newest tag pinned. A module released only for its upstreams has these lines as its notes; one
 * with notes of its own keeps them, the lines after.
 */
final class PinNotes {

    static final String CHANGED = "### Changed";

    private PinNotes() {
    }

    /**
     * One line per upstream whose pin the release moves to another version than the module's newest tag pinned.
     *
     * @param pinnedAt each upstream edge and the version the release commit pins it at
     * @param tag      the module's newest tag, empty when it was never tagged
     */
    static List<String> lines(Module module, Map<Edge, Version> pinnedAt, Optional<String> tag, String tagPattern) {
        Path dir = module.dir().dir().toAbsolutePath().normalize();
        List<String> lines = new ArrayList<>();
        pinnedAt.forEach((edge, version) -> {
            Optional<Version> was = tag.flatMap(t -> edge.pins().stream()
                    .map(pin -> pinnedAtTag(dir, "refs/tags/" + t, pin, tagPattern))
                    .flatMap(Optional::stream)
                    .findFirst());
            if (was.filter(version::equals).isEmpty()) {
                lines.add("- Built against " + edge.upstream() + " " + version
                        + was.map(w -> " (was " + w + ")").orElse("") + ".");
            }
        });
        return lines;
    }

    private static Optional<Version> pinnedAtTag(Path dir, String ref, Pin pin, String tagPattern) {
        String file = dir.relativize(pin.file().toAbsolutePath().normalize()).toString().replace('\\', '/');
        return Git.show(dir, ref, file).flatMap(text -> PinReader.value(pin, text))
                .flatMap(text -> Tags.released(text, tagPattern));
    }

    /** Writes {@code lines} into the module's changelog, creating what it lacks; a line there already stays one. */
    static void write(Runner runner, Module module, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        Path file = module.dir().dir().resolve(Changelog.FILE);
        String text;
        try {
            text = Files.isRegularFile(file) ? Files.readString(file) : "# Changelog\n";
        } catch (IOException e) {
            throw new Refusal(file + ": could not be read (" + e.getMessage() + ")");
        }
        String written = with(text, lines);
        if (!written.equals(text)) {
            runner.say("    " + module + " " + Changelog.FILE + ": the pins it moves, under " + CHANGED);
            runner.write(file, written);
        }
    }

    /** {@code text} with {@code lines} at the end of {@code [Unreleased]}'s {@code ### Changed}. */
    static String with(String text, List<String> lines) {
        List<String> all = new ArrayList<>(text.lines().toList());
        String heading = "## [" + Changelog.UNRELEASED + "]";
        int section = indexOf(all, 0, all.size(), line -> line.startsWith(heading));
        // Only [Unreleased] counts: an older release that moved the same pin to the same version (its target
        // failed, this is its next patch) says nothing of this one.
        List<String> unreleased = section < 0 ? List.of() : all.subList(section + 1, sectionEnd(all, section));
        List<String> missing = lines.stream().filter(line -> !unreleased.contains(line)).toList();
        if (missing.isEmpty()) {
            return text;
        }
        if (section < 0) {
            int first = indexOf(all, 0, all.size(), line -> line.startsWith("## "));
            section = first < 0 ? all.size() : first;
            List<String> fresh = new ArrayList<>(List.of(heading, ""));
            if (first < 0 && !all.isEmpty() && !all.getLast().isBlank()) {
                fresh.addFirst("");
            }
            all.addAll(section, fresh);
            section += fresh.indexOf(heading);
        }
        int end = sectionEnd(all, section);
        int changed = indexOf(all, section + 1, end, line -> line.strip().equals(CHANGED));
        List<String> insert = new ArrayList<>();
        int at;
        if (changed >= 0) {
            int next = indexOf(all, changed + 1, end, line -> line.startsWith("### ") || line.startsWith("## "));
            at = trimmedEnd(all, changed + 1, next < 0 ? end : next);
            if (at == changed + 1) {
                insert.add("");
            }
        } else {
            at = trimmedEnd(all, section + 1, end);
            if (!all.get(at - 1).isBlank()) {
                insert.add("");
            }
            insert.addAll(List.of(CHANGED, ""));
        }
        insert.addAll(missing);
        if (at < all.size() && !all.get(at).isBlank()) {
            insert.add("");
        }
        all.addAll(at, insert);
        return String.join("\n", all.subList(0, trimmedEnd(all, 0, all.size()))) + "\n";
    }

    /** Where the section headed at {@code section} ends: the next {@code ## } heading, else the end. */
    private static int sectionEnd(List<String> lines, int section) {
        int end = indexOf(lines, section + 1, lines.size(), line -> line.startsWith("## "));
        return end < 0 ? lines.size() : end;
    }

    private static int indexOf(List<String> lines, int from, int to, Predicate<String> test) {
        for (int i = from; i < to; i++) {
            if (test.test(lines.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Where the text between {@code from} and {@code to} ends, its trailing blank lines left out. */
    private static int trimmedEnd(List<String> lines, int from, int to) {
        int at = to;
        while (at > from && lines.get(at - 1).isBlank()) {
            at--;
        }
        return at;
    }
}
