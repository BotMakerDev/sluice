package com.botmaker.sluice.target;

import com.botmaker.sluice.Runner;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.decide.Plan;
import com.botmaker.sluice.model.Coordinate;
import com.botmaker.sluice.model.Edge;
import com.botmaker.sluice.model.Graph;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.spi.PublishTarget;
import com.botmaker.sluice.spi.PublishTarget.Served;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Waiting for a tag's publish before tagging what resolves it.
 *
 * <p><b>The wait has one reason</b>: a dependent tagged before its upstream is served starts a build that cannot
 * find the upstream, and a target that caches a build per tag keeps that failure for good. So a module is owed a
 * wait only when a module tagged after it in the same run resolves it from a pom; waiting on one nothing later
 * resolves buys nothing and is paid every release. The rule is read off the graph, never listed.
 */
public final class Waits {

    /** How often a wait asks. */
    static final Duration EVERY = Duration.ofSeconds(10);

    private Waits() {
    }

    /** How a wait ended. Only {@link Outcome#PUBLISHED} lets the run tag what resolves the module. */
    public enum Outcome {
        PUBLISHED,
        /** Its build ran and failed: the tag is broken for good. */
        FAILED,
        /** Not served after the target's patience. */
        TIMEOUT,
        /** The operator stopped the run, or the thread was interrupted. */
        STOPPED
    }

    /**
     * What a wait saw.
     *
     * @param detail the target's message for {@link Outcome#FAILED}, empty otherwise
     */
    public record Published(Outcome outcome, String detail) {
    }

    /** How a wait passes time; the real one sleeps, a test's does not. */
    @FunctionalInterface
    public interface Sleeper {

        /** @return false to give up: the thread was interrupted, or the operator stopped the run */
        boolean sleep(Duration duration);

        /** Sleeps in half-second slices, giving up as soon as {@code stop} says so. */
        static Sleeper real(BooleanSupplier stop) {
            return duration -> {
                long until = System.nanoTime() + duration.toNanos();
                try {
                    while (System.nanoTime() < until) {
                        if (stop.getAsBoolean()) {
                            return false;
                        }
                        Thread.sleep(Math.max(1, Math.min(500, (until - System.nanoTime()) / 1_000_000)));
                    }
                    return !stop.getAsBoolean();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            };
        }
    }

    /**
     * The modules of this run a module tagged after it resolves from a pom, and whose target publishes.
     *
     * @param order the run's modules in tag order
     */
    public static Set<Module> owed(Plan plan, Graph graph, List<Module> order,
                                   Function<Module, PublishTarget> targets) {
        Set<Module> releasing = plan.releasing().keySet();
        List<Module> tagged = order.stream().filter(releasing::contains).toList();
        Set<Module> owed = new LinkedHashSet<>();
        for (int at = 0; at < tagged.size(); at++) {
            Module module = tagged.get(at);
            if (!targets.apply(module).publishes() || module.coordinate().isEmpty()) {
                continue;
            }
            List<Module> later = tagged.subList(at + 1, tagged.size());
            boolean resolved = graph.dependents(module).stream()
                    .filter(edge -> later.contains(edge.downstream()))
                    .anyMatch(Edge::pinnedInAPom);
            if (resolved) {
                owed.add(module);
            }
        }
        return owed;
    }

    /**
     * Asks {@code target} until it serves {@code module} at {@code version}, says its build failed, or the
     * operator stops; a preview says what it would wait for and waits for nothing.
     */
    public static Published await(PublishTarget target, Module module, Version version, Config config,
                                  Runner runner, Sleeper sleeper) {
        return await(target, module, version, config, runner, sleeper, System::nanoTime);
    }

    /** @param clock nanoseconds, as {@link System#nanoTime} counts them */
    static Published await(PublishTarget target, Module module, Version version, Config config, Runner runner,
                           Sleeper sleeper, LongSupplier clock) {
        String pin = target.spelling().spell(version, config.tagPattern());
        String what = module + " " + pin;
        if (runner.dryRun()) {
            runner.say("    would wait for " + target.name() + " to serve " + what);
            return new Published(Outcome.PUBLISHED, "");
        }
        Coordinate coordinate = module.coordinate().orElseThrow();
        runner.say("waiting for " + target.name() + " to serve " + what + " ...");
        // A deadline, not a count of tries: a target slow to answer spends the patience too.
        long deadline = clock.getAsLong() + target.patience().toNanos();
        while (true) {
            // The failure first: asking is also what starts a build nobody requested yet.
            Optional<String> failure = target.failure(coordinate, pin);
            if (failure.isPresent()) {
                runner.say(target.name() + " failed to build " + what + ": " + failure.get());
                return new Published(Outcome.FAILED, failure.get());
            }
            if (target.serves(coordinate, pin) == Served.YES) {
                runner.say(target.name() + " serves " + what + ".");
                return new Published(Outcome.PUBLISHED, "");
            }
            if (clock.getAsLong() >= deadline) {
                runner.say(what + " is not served after " + target.patience().toMinutes() + " min");
                return new Published(Outcome.TIMEOUT, "");
            }
            if (runner.stopping() || !sleeper.sleep(EVERY)) {
                runner.say("stopped waiting for " + what + ".");
                return new Published(Outcome.STOPPED, "");
            }
        }
    }

    /** The line a run prints for a module it does not wait on, so a shorter run is a fact in its log. */
    public static String notWaiting(Module module) {
        return "not waiting on " + module + ": nothing tagged after it in this run resolves it";
    }
}
