package com.botmaker.sluice.desktop.model;

import com.botmaker.sluice.Level;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.decide.Requests;
import com.botmaker.sluice.execute.Release;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the Release tab's flags ask for, as the command line spells it. The window decides nothing from it:
 * {@link #requests()} is {@link Requests#parse}'s reading, the one the command makes of the same words.
 *
 * @param all            {@code --all LEVEL}, when ticked
 * @param picked         per module name, what its row asks: a level or an exact version, as typed
 * @param force          {@code --force}
 * @param withDependents {@code --with-dependents}
 * @param noWait         {@code --no-wait}
 */
public record ReleaseSpec(Optional<Level> all, Map<String, String> picked, boolean force, boolean withDependents,
                          boolean noWait) {

    public ReleaseSpec {
        picked = Collections.unmodifiableMap(new LinkedHashMap<>(picked));
    }

    /** Asks for nothing: no {@code --all} and no module. */
    public boolean empty() {
        return all.isEmpty() && picked.isEmpty();
    }

    /** Whether a row's text is a request the command takes: a level or a version. */
    public static boolean wellFormed(String request) {
        String word = request == null ? "" : request.strip();
        return Level.of(word).isPresent() || Version.parse(word).isPresent();
    }

    /** Every row's text is a level or a version. */
    public boolean wellFormed() {
        return picked.values().stream().allMatch(ReleaseSpec::wellFormed);
    }

    /** The requests the command line below means. */
    public Requests requests() {
        return Requests.parse(moduleArgs(), all);
    }

    /** How a run of it goes; a preview reads none of {@code execute}. */
    public Release.Options options(boolean execute) {
        return new Release.Options(execute, force, withDependents, !noWait);
    }

    /** {@code sluice --all minor --module core=1.2.0 --force}: the same run from a terminal, as a preview. */
    public String commandLine() {
        List<String> words = new ArrayList<>(List.of("sluice"));
        all.ifPresent(level -> words.addAll(List.of("--all", level.spelling())));
        moduleArgs().forEach(arg -> words.addAll(List.of("--module", arg)));
        flags(words);
        return String.join(" ", words);
    }

    /** The command line that cuts it. */
    public String executeCommandLine() {
        return commandLine() + " --execute";
    }

    /** The command line that resumes the newest unfinished run with these flags; a resume reads no request. */
    public String resumeCommandLine() {
        List<String> words = new ArrayList<>(List.of("sluice", "--resume"));
        flags(words);
        words.add("--execute");
        return String.join(" ", words);
    }

    private void flags(List<String> words) {
        if (withDependents) {
            words.add("--with-dependents");
        }
        if (force) {
            words.add("--force");
        }
        if (noWait) {
            words.add("--no-wait");
        }
    }

    private List<String> moduleArgs() {
        return picked.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().strip()).toList();
    }
}
