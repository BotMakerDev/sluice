package com.botmaker.sluice.gate;

import com.botmaker.sluice.Git;
import com.botmaker.sluice.github.GitHub;
import com.botmaker.sluice.github.GitHub.Repo;
import com.botmaker.sluice.github.GitHub.Run;
import com.botmaker.sluice.model.Edge;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.spi.Gate;
import com.botmaker.sluice.spi.GateContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * No module released is red on its release branch: for each of its workflows, the newest finished run there did
 * not fail. The tag would run the same build and fail the same way, and a pushed tag cannot be edited.
 *
 * <p>Only a finished run refuses; one still going, or cancelled, leaves the finished one before it to answer. No
 * token, a remote that is not on GitHub, GitHub not answering, no finished run and green runs that are all of
 * an older commit than the module's {@code HEAD} are all skipped.
 */
public final class CiGate implements Gate {

    private final Optional<GitHub> github;
    private final Function<Module, Optional<Repo>> repos;

    public CiGate(Optional<GitHub> github) {
        this(github, module -> Git.line(module.dir().dir(), "remote", "get-url", "origin").flatMap(Repo::ofRemote));
    }

    CiGate(Optional<GitHub> github, Function<Module, Optional<Repo>> repos) {
        this.github = github;
        this.repos = repos;
    }

    @Override
    public String name() {
        return "ci";
    }

    @Override
    public List<GateVerdict> check(GateContext ctx) {
        if (ctx.plan().releasing().isEmpty()) {
            return List.of();
        }
        if (github.isEmpty()) {
            return List.of(skipped("no GitHub token (GITHUB_TOKEN, or gh auth token): CI was not checked"));
        }
        String branch = ctx.config().branch();
        Map<Module, Optional<List<Run>>> asked = new HashMap<>();
        Function<Module, Optional<List<Run>>> runsOf = module -> asked.computeIfAbsent(module,
                m -> repos.apply(m).flatMap(repo -> github.get().runs(repo, branch)));
        List<GateVerdict> verdicts = new ArrayList<>();
        for (Module module : ctx.plan().releasing().keySet()) {
            Optional<Repo> repo = repos.apply(module);
            if (repo.isEmpty()) {
                verdicts.add(skipped(module + ": its origin is not on GitHub, CI was not checked"));
                continue;
            }
            Optional<List<Run>> runs = runsOf.apply(module);
            verdicts.add(runs.isEmpty()
                    ? skipped(module + ": GitHub did not answer for " + repo.get() + ", CI was not checked")
                    : verdict(ctx, module, branch, runs.get(), runsOf));
        }
        return verdicts;
    }

    /** Each workflow's newest finished run that answers for the module: neither cancelled nor of a removed file. */
    private static Map<String, Run> newestFinished(Module module, List<Run> runs) {
        Map<String, Run> newest = new LinkedHashMap<>();
        for (Run run : runs) {
            if (run.finished() && !run.cancelled() && !run.removedFrom(module.dir().dir())) {
                newest.putIfAbsent(run.workflow(), run);
            }
        }
        return newest;
    }

    /**
     * {@code upstream}'s newest green run, when every workflow of it is green now and that run ended after each
     * of {@code red}: a dependent's CI builds its upstreams' branches, so it may have failed in one fixed since.
     * Empty when a time is unknown.
     */
    private static Optional<Run> greenSince(Module upstream, List<Run> red,
                                            Function<Module, Optional<List<Run>>> runsOf) {
        Optional<String> failed = red.stream().map(Run::updated).max(String::compareTo).filter(t -> !t.isEmpty());
        if (failed.isEmpty() || red.stream().anyMatch(run -> run.updated().isEmpty())) {
            return Optional.empty();
        }
        Map<String, Run> newest = runsOf.apply(upstream).map(runs -> newestFinished(upstream, runs)).orElse(Map.of());
        if (newest.isEmpty() || !newest.values().stream().allMatch(Run::green)) {
            return Optional.empty();
        }
        return newest.values().stream().filter(run -> run.updated().compareTo(failed.get()) > 0)
                .max(Comparator.comparing(Run::updated));
    }

    /** The rule over a module's runs, newest first. */
    private GateVerdict verdict(GateContext ctx, Module module, String branch, List<Run> runs,
                                Function<Module, Optional<List<Run>>> runsOf) {
        Map<String, Run> newestFinished = new LinkedHashMap<>();
        boolean running = false;
        for (Run run : runs) {
            if (run.finished() && run.cancelled() || run.removedFrom(module.dir().dir())) {
                continue;
            }
            if (run.finished()) {
                newestFinished.putIfAbsent(run.workflow(), run);
            } else if (!newestFinished.containsKey(run.workflow())) {
                running = true;
            }
        }
        String also = running ? " (a newer run is still going)" : "";
        if (newestFinished.isEmpty()) {
            return skipped(module + ": no finished CI run on " + branch + also + ", CI was not checked");
        }
        List<Run> red = newestFinished.values().stream().filter(run -> !run.green()).toList();
        Optional<String> head = Git.line(module.dir().dir(), "rev-parse", "HEAD");
        if (red.isEmpty() && newestFinished.values().stream().noneMatch(run -> head.equals(Optional.of(run.sha())))) {
            return skipped(module + ": CI on " + branch + " is green at "
                    + newestFinished.values().iterator().next().commit() + also
                    + ", but no finished run built its HEAD: CI was not checked");
        }
        if (red.isEmpty()) {
            return ok(module + ": CI on " + branch + " is green at "
                    + newestFinished.values().iterator().next().commit() + also);
        }
        StringBuilder repair = new StringBuilder();
        for (Run run : red) {
            repair.append("     ").append(run.workflow()).append(": ").append(run.conclusion()).append(" at ")
                    .append(run.commit()).append("  ").append(run.url()).append('\n');
        }
        for (Edge edge : ctx.graph().upstreams(module)) {
            greenSince(edge.upstream(), red, runsOf).ifPresent(green -> repair.append("     ")
                    .append(edge.upstream()).append(" went green after it, at ").append(green.commit())
                    .append(": if it failed in ").append(edge.upstream()).append("'s build, rerun it.\n"));
        }
        repair.append("     Fix ").append(branch).append(" first, or wait for a newer run.");
        return refused(module + ": the newest finished CI run on " + branch + " failed" + also, repair.toString());
    }
}
