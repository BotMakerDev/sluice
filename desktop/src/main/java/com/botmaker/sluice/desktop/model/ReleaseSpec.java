// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.desktop.model;

import com.botmaker.sluice.Level;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.decide.Dependents;
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
 * @param dependents     {@code --with-dependents}, {@code --no-dependents}, or neither
 * @param noWait         {@code --no-wait}
 */
public record ReleaseSpec(Optional<Level> all, Map<String, String> picked, boolean force,
                          DependentsFlag dependents, boolean noWait) {

    /** What the run does with an owed dependent: what {@code sluice.yml} says, or a flag. */
    public enum DependentsFlag {
        CONFIG("owed: as sluice.yml", Optional.empty()),
        WITH("--with-dependents", Optional.of(Dependents.ADD)),
        WITHOUT("--no-dependents", Optional.of(Dependents.LEAVE));

        private final String flag;
        private final Optional<Dependents> dependents;

        DependentsFlag(String flag, Optional<Dependents> dependents) {
            this.flag = flag;
            this.dependents = dependents;
        }

        /** Its flag on the command line, or what the choice shows when it adds none. */
        public String flag() {
            return flag;
        }

        /** What it asks of the run; empty: the config's {@code owed}. */
        public Optional<Dependents> dependents() {
            return dependents;
        }

        @Override
        public String toString() {
            return flag;
        }
    }

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
        return new Release.Options(execute, force, dependents.dependents(), !noWait);
    }

    /** {@code sluice preview --all minor --module core=1.2.0 --force}: the same run from a terminal, as a preview. */
    public String commandLine() {
        return selected("preview");
    }

    /** The command line that cuts it. */
    public String executeCommandLine() {
        return selected("release");
    }

    /**
     * The command line that resumes the newest unfinished run with these flags; a resume reads no request, and its
     * dependents are the run's own.
     */
    public String resumeCommandLine() {
        List<String> words = new ArrayList<>(List.of("sluice", "resume"));
        flags(words);
        return String.join(" ", words);
    }

    private String selected(String verb) {
        List<String> words = new ArrayList<>(List.of("sluice", verb));
        all.ifPresent(level -> words.addAll(List.of("--all", level.spelling())));
        moduleArgs().forEach(arg -> words.addAll(List.of("--module", arg)));
        if (dependents != DependentsFlag.CONFIG) {
            words.add(dependents.flag());
        }
        flags(words);
        return String.join(" ", words);
    }

    private void flags(List<String> words) {
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
