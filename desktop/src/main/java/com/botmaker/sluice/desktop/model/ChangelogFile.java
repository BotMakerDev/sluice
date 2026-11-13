package com.botmaker.sluice.desktop.model;

import com.botmaker.sluice.Changelog;
import com.botmaker.sluice.Git;
import com.botmaker.sluice.Proc;
import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.decide.Tags;
import com.botmaker.sluice.model.Module;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * One module's {@code CHANGELOG.md}, as the Changelog tab shows it: the file, what its {@code [Unreleased]} section
 * says, the commits since the newest tag that it should describe, and the last released section for tone. The
 * changelog gate decides whether it is enough; this only reads and writes the file.
 *
 * @param file       the changelog's path, which may not exist yet
 * @param text       its text; empty when it does not exist
 * @param newest     the module's newest tagged version
 * @param commits    {@code git log --oneline} since that tag, or every commit when it has none
 * @param lastSection the newest tag's section, when the file has one
 */
public record ChangelogFile(Module module, Path file, String text, Optional<Version> newest, List<String> commits,
                            Optional<String> lastSection) {

    public ChangelogFile {
        commits = List.copyOf(commits);
    }

    public static ChangelogFile read(Module module, String tagPattern) {
        Path dir = module.dir().dir();
        Path file = dir.resolve(Changelog.FILE);
        String text;
        try {
            text = Files.isRegularFile(file) ? Files.readString(file) : "";
        } catch (IOException e) {
            throw new Refusal(file + ": could not be read (" + e.getMessage() + ")");
        }
        Optional<Version> newest = Tags.known(module, tagPattern).stream().max(Version::compareTo);
        List<String> range = newest.map(v -> List.of("log", "--oneline", v.tag(tagPattern) + "..HEAD"))
                .orElse(List.of("log", "--oneline"));
        Proc.Result log = Git.run(dir, range.toArray(String[]::new));
        List<String> commits = log.ok() ? log.lines() : List.of("git log failed: " + log.out().strip());
        return new ChangelogFile(module, file, text, newest, commits,
                newest.flatMap(v -> Changelog.section(text, v.toString())));
    }

    /** What {@code [Unreleased]} says; empty when the file has no such section. */
    public Optional<String> unreleased() {
        return Changelog.section(text, Changelog.UNRELEASED);
    }

    /** Whether a release would find notes for this module: an {@code [Unreleased]} section that says something. */
    public boolean hasNotes() {
        return unreleased().filter(Changelog::hasNotes).isPresent();
    }

    /** A one-word state for the module list. */
    public String state() {
        if (text.isEmpty()) {
            return "no changelog";
        }
        if (hasNotes()) {
            return "notes";
        }
        return commits.isEmpty() ? "nothing to say" : commits.size() + " commit(s), no notes";
    }

    /** Writes {@code edited} over the file. */
    public void save(String edited) {
        try {
            Files.writeString(file, edited);
        } catch (IOException e) {
            throw new Refusal(file + ": could not be written (" + e.getMessage() + ")");
        }
    }

    /**
     * Commits the file alone in its module, with {@code message}; nothing else staged goes with it.
     *
     * @throws Refusal when git refuses, with its words
     */
    public void commit(String message) {
        Path dir = module.dir().dir();
        must(Git.run(dir, "add", "--", Changelog.FILE), "git add");
        must(Git.run(dir, "commit", "-q", "-m", message, "--", Changelog.FILE), "git commit");
    }

    private static void must(Proc.Result result, String what) {
        if (!result.ok()) {
            throw new Refusal(what + " failed: " + result.out().strip());
        }
    }
}
