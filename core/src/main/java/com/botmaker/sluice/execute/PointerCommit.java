package com.botmaker.sluice.execute;

import com.botmaker.sluice.Git;
import com.botmaker.sluice.Proc;
import com.botmaker.sluice.Runner;
import com.botmaker.sluice.model.Module;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The umbrella's one commit of a run: each submodule the run moved, and the run's log and state, limited to those
 * paths so nothing else of the umbrella rides along. Then pushed, when the umbrella has an origin and a branch.
 * Never stops anything: every module is released already, and a pointer can be committed by hand.
 */
final class PointerCommit {

    private PointerCommit() {
    }

    /** @param base the run's files without their extension, empty for a preview */
    static void commit(Runner runner, Path umbrella, Collection<Module> moved, Optional<Path> base, String message) {
        List<String> paths = new ArrayList<>();
        moved.forEach(module -> paths.add(module.dir().path()));
        base.ifPresent(b -> List.of(".md", ".json").forEach(ext -> {
            Path file = b.resolveSibling(b.getFileName() + ext);
            if (Files.isRegularFile(file)) {
                paths.add(umbrella.relativize(file).toString().replace('\\', '/'));
            }
        }));
        if (paths.isEmpty()) {
            return;
        }
        if (!ok(runner, git(runner, umbrella, paths, "add"), "staging the umbrella's pointers")) {
            return;
        }
        if (!runner.dryRun() && Git.run(umbrella, with(paths, "diff", "--cached", "--quiet")).ok()) {
            runner.say("    the umbrella's pointers are where they were: nothing to commit");
            return;
        }
        if (!ok(runner, git(runner, umbrella, paths, "commit", "-q", "-m", message),
                "the umbrella's pointer commit")) {
            return;
        }
        Optional<String> branch = Git.branch(umbrella);
        if (Git.line(umbrella, "remote", "get-url", "origin").isEmpty() || branch.isEmpty()) {
            runner.say("    the umbrella has no origin or no branch: its pointer commit stays local");
            return;
        }
        ok(runner, runner.git(umbrella, "push", "-q", "origin", branch.get()), "pushing the umbrella");
    }

    private static Proc.Result git(Runner runner, Path umbrella, List<String> paths, String... command) {
        return runner.git(umbrella, with(paths, command));
    }

    private static String[] with(List<String> paths, String... command) {
        List<String> args = new ArrayList<>(List.of(command));
        args.add("--");
        args.addAll(paths);
        return args.toArray(String[]::new);
    }

    private static boolean ok(Runner runner, Proc.Result result, String what) {
        if (result.ok()) {
            return true;
        }
        runner.say("    " + what + " failed: " + result.out().strip());
        return false;
    }
}
