// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.gate;

import com.botmaker.sluice.Git;
import com.botmaker.sluice.github.GitHub;
import com.botmaker.sluice.github.GitHub.BranchRuns;
import com.botmaker.sluice.github.GitHub.Repo;
import com.botmaker.sluice.github.GitHub.Run;
import com.botmaker.sluice.model.Edge;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.spi.Gate;
import com.botmaker.sluice.spi.GateContext;
import com.botmaker.sluice.target.Waits.Sleeper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * No module released is red on its release branch: for each of its workflows, the newest finished run there did
 * not fail. The tag would run the same build and fail the same way, and a pushed tag cannot be edited.
 *
 * <p>Only a finished run refuses; one still going, or cancelled, leaves the finished one before it to answer. No
 * token, a remote that is not on GitHub, GitHub not answering, no finished run and green runs that are all of
 * an older commit than the module's {@code HEAD} are all skipped.
 *
 * <p><b>A run does not leave CI unchecked when it can wait for it</b>: a module ahead of its origin is pushed
 * first (the release would push it anyway, untested), then the gate waits, up to {@code ci-wait}, for a finished
 * run of each workflow on its {@code HEAD}. A preview pushes nothing and waits for nothing: it says it would.
 * A red run that failed before an upstream went green is rerun (its failed jobs) and waited on the same way.
 */
public final class CiGate implements Gate {

    /** How often a wait asks GitHub. */
    static final Duration EVERY = Duration.ofSeconds(30);

    private final Optional<GitHub> github;
    private final Function<Module, Optional<Repo>> repos;
    private final Optional<Sleeper> sleeper;

    public CiGate(Optional<GitHub> github) {
        this(github, module -> Repo.ofCheckout(module.dir().dir()), Optional.empty());
    }

    CiGate(Optional<GitHub> github, Function<Module, Optional<Repo>> repos) {
        this(github, repos, Optional.empty());
    }

    /** @param sleeper how a wait passes time; empty: the real one, stopped with the run */
    CiGate(Optional<GitHub> github, Function<Module, Optional<Repo>> repos, Optional<Sleeper> sleeper) {
        this.github = github;
        this.repos = repos;
        this.sleeper = sleeper;
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
        // Every module is pushed before any is waited on, so their CI runs at once.
        Map<Module, GateVerdict> early = new HashMap<>();
        for (Module module : ctx.plan().releasing().keySet()) {
            if (repos.apply(module).isEmpty()) {
                continue;
            }
            int ahead = Git.line(module.dir().dir(), "rev-list", "--count", "refs/remotes/origin/" + branch + "..HEAD")
                    .map(Integer::parseInt).orElse(0);
            if (ahead == 0) {
                continue;
            }
            if (ctx.runner().dryRun()) {
                early.put(module, skipped(module + " is " + ahead + " commit(s) ahead of origin/" + branch
                        + ": a run pushes it" + (waits(ctx, module) ? " and waits up to "
                        + ctx.config().ciWait().toMinutes() + " min for CI on its HEAD" : "")
                        + "; CI was not checked"));
            } else if (!ctx.runner().git(module.dir().dir(), "push", "-q", "origin", branch).ok()) {
                early.put(module, refused(module + ": its " + ahead + " commit(s) ahead of origin/" + branch
                        + " could not be pushed", "     git -C " + module + " push origin " + branch));
            }
        }
        List<GateVerdict> verdicts = new ArrayList<>();
        for (Module module : ctx.plan().releasing().keySet()) {
            Optional<Repo> repo = repos.apply(module);
            if (repo.isEmpty()) {
                verdicts.add(skipped(module + ": its origin is not on GitHub, CI was not checked"));
                continue;
            }
            if (early.containsKey(module)) {
                verdicts.add(early.get(module));
                continue;
            }
            if (!ctx.runner().dryRun() && waits(ctx, module)) {
                asked.put(module, waitForHead(ctx, module, repo.get(), branch));
            }
            Optional<List<Run>> runs = runsOf.apply(module);
            if (runs.isEmpty()) {
                verdicts.add(skipped(module + ": GitHub did not answer for " + repo.get() + ", CI was not checked"));
                continue;
            }
            GateVerdict verdict = verdict(ctx, module, branch, runs.get(), runsOf);
            List<Run> rerun = failedInAnUpstream(ctx, module, runs.get(), runsOf);
            if (verdict.status() == GateVerdict.Status.REFUSED && !rerun.isEmpty()) {
                verdict = rerun(ctx, module, repo.get(), branch, rerun, verdict, asked, runsOf);
            }
            verdicts.add(verdict);
        }
        return verdicts;
    }

    /**
     * The red runs of {@code module} an upstream went green after: its CI builds its upstreams' branches, so it
     * may have failed in one fixed since. Empty when no upstream did.
     */
    private static List<Run> failedInAnUpstream(GateContext ctx, Module module, List<Run> runs,
                                                Function<Module, Optional<List<Run>>> runsOf) {
        List<Run> red = BranchRuns.of(runs, module.dir().dir()).red();
        boolean upstreamGreen = !red.isEmpty() && ctx.graph().upstreams(module).stream()
                .anyMatch(edge -> greenSince(edge.upstream(), red, runsOf).isPresent());
        return upstreamGreen ? red : List.of();
    }

    /**
     * Reruns {@code red}'s failed jobs and waits for them, up to {@code ci-wait}, then answers again from the
     * branch's runs; a preview says it would. {@code refused} stands when GitHub does not take a rerun.
     */
    private GateVerdict rerun(GateContext ctx, Module module, Repo repo, String branch, List<Run> red,
                              GateVerdict refused, Map<Module, Optional<List<Run>>> asked,
                              Function<Module, Optional<List<Run>>> runsOf) {
        if (ctx.runner().dryRun()) {
            return skipped(module + ": its red CI run failed before an upstream went green: a run reruns its "
                    + "failed jobs and waits up to " + ctx.config().ciWait().toMinutes() + " min; CI was not checked");
        }
        for (Run run : red) {
            Optional<String> why = github.orElseThrow().rerunFailed(repo, run);
            if (why.isPresent()) {
                return new GateVerdict(refused.gate(), refused.status(), refused.line(),
                        refused.refusal() + "\n     Its rerun was refused: " + why.get());
            }
            ctx.runner().say(module + ": reran the failed jobs of " + run.workflow() + " at " + run.commit());
        }
        Sleeper sleep = sleeper.orElseGet(() -> Sleeper.real(ctx.runner()::stopping));
        long asks = ctx.config().ciWait().toSeconds() / EVERY.toSeconds();
        // ci-wait 0: the rerun is started, and the branch answers as it is now.
        Optional<List<Run>> runs = asks == 0 ? github.get().runs(repo, branch) : Optional.empty();
        // GitHub queues a rerun under the run's own id: wait at least once, then until each is finished again.
        for (long ask = 0; ask < asks && sleep.sleep(EVERY); ask++) {
            runs = github.get().runs(repo, branch);
            if (runs.isEmpty() || runs.get().stream().filter(run -> red.stream()
                    .anyMatch(r -> r.url().equals(run.url()))).allMatch(Run::finished)) {
                break;
            }
        }
        if (runs.isEmpty()) {
            return refused;
        }
        asked.put(module, runs);
        return verdict(ctx, module, branch, runs.get(), runsOf);
    }

    /**
     * The branch's runs once a finished run built the module's {@code HEAD} and none is still going,
     * or as they are when {@code ci-wait} ran out, the run was stopped, or GitHub did not answer.
     */
    private Optional<List<Run>> waitForHead(GateContext ctx, Module module, Repo repo, String branch) {
        Path dir = module.dir().dir();
        Optional<String> head = Git.line(dir, "rev-parse", "HEAD");
        Sleeper sleep = sleeper.orElseGet(() -> Sleeper.real(ctx.runner()::stopping));
        long asks = Math.max(1, ctx.config().ciWait().toSeconds() / EVERY.toSeconds());
        Optional<List<Run>> runs = github.orElseThrow().runs(repo, branch);
        for (long ask = 1; ask < asks && runs.isPresent() && !builtAll(runs.get(), dir, head); ask++) {
            if (ask == 1) {
                String at = head.map(sha -> sha.substring(0, Math.min(7, sha.length()))).orElse("HEAD");
                ctx.runner().say(module + ": waiting up to " + ctx.config().ciWait().toMinutes() + " min for CI on "
                        + at);
            }
            if (!sleep.sleep(EVERY)) {
                break;
            }
            runs = github.get().runs(repo, branch);
        }
        return runs;
    }

    /**
     * Whether the verdict can be given: a finished run built {@code head}, as the verdict asks, and none is still
     * going. A workflow that skips the commit (a {@code paths} filter) is not waited on.
     */
    private static boolean builtAll(List<Run> runs, Path checkout, Optional<String> head) {
        BranchRuns read = BranchRuns.of(runs, checkout);
        return !read.running() && read.built(head);
    }

    /** Whether a run waits for {@code module}'s CI: {@code ci-wait} is not zero and it has a workflow of its own. */
    private static boolean waits(GateContext ctx, Module module) {
        return !ctx.config().ciWait().isZero() && hasWorkflows(module.dir().dir());
    }

    /** Whether the checkout has a workflow of its own: without one, no run will come to wait for. */
    private static boolean hasWorkflows(Path checkout) {
        try (Stream<Path> files = Files.list(checkout.resolve(GitHub.WORKFLOWS))) {
            return files.anyMatch(file -> file.toString().endsWith(".yml") || file.toString().endsWith(".yaml"));
        } catch (IOException e) {
            return false;
        }
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
        Map<String, Run> newest = runsOf.apply(upstream)
                .map(runs -> BranchRuns.of(runs, upstream.dir().dir()).newestFinished()).orElse(Map.of());
        if (newest.isEmpty() || !newest.values().stream().allMatch(Run::green)) {
            return Optional.empty();
        }
        return newest.values().stream().filter(run -> run.updated().compareTo(failed.get()) > 0)
                .max(Comparator.comparing(Run::updated));
    }

    /** The rule over a module's runs, newest first. */
    private GateVerdict verdict(GateContext ctx, Module module, String branch, List<Run> runs,
                                Function<Module, Optional<List<Run>>> runsOf) {
        BranchRuns read = BranchRuns.of(runs, module.dir().dir());
        Map<String, Run> newestFinished = read.newestFinished();
        String also = read.running() ? " (a newer run is still going)" : "";
        if (newestFinished.isEmpty()) {
            return skipped(module + ": no finished CI run on " + branch + also + ", CI was not checked");
        }
        List<Run> red = read.red();
        if (red.isEmpty() && !read.built(Git.line(module.dir().dir(), "rev-parse", "HEAD"))) {
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
