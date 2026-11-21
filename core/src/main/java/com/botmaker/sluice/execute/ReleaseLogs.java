// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.execute;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.execute.Release.Status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.stream.Stream;

/**
 * The runs an umbrella has had, as they left their files under {@code releases/}: a run that went past its gates
 * left its state ({@code .json}) and its log ({@code .md}); one its gates refused left its log only.
 */
public final class ReleaseLogs {

    private ReleaseLogs() {
    }

    /**
     * One run.
     *
     * @param name       its files' name without the extension, {@code 2026-11-10-1200} or {@code 2026-11-10-1200-2}
     * @param log        its log, for a person
     * @param stateFile  its state file, when it went past its gates
     * @param state      what its state file says, when it could be read
     * @param status     how its log says it ended, when it says
     * @param unreadable why its state file could not be read, when it could not
     * @param live       whether it still runs: its state says running and a release holds the umbrella's lock now;
     *                   a state that says running with no holder was left by a run that died
     */
    public record LoggedRelease(String name, Optional<Path> log, Optional<Path> stateFile,
                                Optional<ReleaseState> state, Optional<Status> status, Optional<String> unreadable,
                                boolean live) {
    }

    /** Every run under {@code umbrella}'s {@code releases/}, the newest first. */
    public static List<LoggedRelease> list(Path umbrella) {
        Path dir = umbrella.resolve(StateFile.DIR);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        Map<String, Path> firsts = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(file -> StateFile.NAME.matcher(file.getFileName().toString()).matches())
                    .sorted(StateFile.ORDER.reversed())
                    .forEach(file -> firsts.putIfAbsent(base(file), file));
        } catch (IOException e) {
            throw new Refusal(dir + ": could not be listed (" + e.getMessage() + ")");
        }
        // The lock names no run: only the newest whose state says running is the one holding it.
        boolean held = Lock.held(umbrella);
        List<LoggedRelease> runs = new ArrayList<>();
        for (Path file : firsts.values()) {
            LoggedRelease run = read(file, held);
            held &= !run.live();
            runs.add(run);
        }
        return List.copyOf(runs);
    }

    /** The run whose state or log is {@code file}, read with the other file beside it. */
    public static LoggedRelease read(Path file) {
        Path releases = file.toAbsolutePath().getParent();
        return read(file, releases.getParent() != null && Lock.held(releases.getParent()));
    }

    private static LoggedRelease read(Path file, boolean held) {
        Matcher name = StateFile.NAME.matcher(file.getFileName().toString());
        if (!name.matches()) {
            throw new Refusal(file + ": not a release's file (" + StateFile.DIR + "/<YYYY-MM-DD-HHMM>.json or .md)");
        }
        String base = base(file);
        Optional<Path> json = existing(file.resolveSibling(base + ".json"));
        Optional<Path> md = existing(file.resolveSibling(base + ".md"));
        Optional<ReleaseState> state = Optional.empty();
        Optional<String> unreadable = Optional.empty();
        if (json.isPresent()) {
            try {
                state = Optional.of(StateFile.read(json.get()));
            } catch (Refusal refusal) {
                unreadable = Optional.of(refusal.getMessage());
            }
        }
        boolean live = held && state.filter(s -> s.status() == ReleaseState.Status.RUNNING).isPresent();
        return new LoggedRelease(base, md, json, state, md.flatMap(ReleaseLogs::status), unreadable, live);
    }

    /** The {@code Status:} line a log starts with. */
    private static Optional<Status> status(Path log) {
        try (Stream<String> lines = Files.lines(log)) {
            return lines.limit(8).filter(line -> line.startsWith("Status: ")).findFirst()
                    .map(line -> line.substring("Status: ".length()).strip())
                    .flatMap(word -> Arrays.stream(Status.values()).filter(s -> s.name().equals(word)).findFirst());
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String base(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.lastIndexOf('.'));
    }

    private static Optional<Path> existing(Path file) {
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }
}
