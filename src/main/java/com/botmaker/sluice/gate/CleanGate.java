package com.botmaker.sluice.gate;

import com.botmaker.sluice.Git;
import com.botmaker.sluice.Proc;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.spi.Gate;
import com.botmaker.sluice.spi.GateContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every module released is on its release branch, with no tracked file uncommitted and nothing its remote has
 * that it lacks. An untracked file is no concern of a release, which commits only what it changes. A submodule on a detached {@code HEAD} is the usual state after {@code git submodule update}.
 */
public final class CleanGate implements Gate {

    /** How many uncommitted files a refusal lists. */
    private static final int LISTED = 5;

    @Override
    public String name() {
        return "clean";
    }

    @Override
    public List<GateVerdict> check(GateContext ctx) {
        String branch = ctx.config().branch();
        List<GateVerdict> verdicts = new ArrayList<>();
        for (Module module : ctx.plan().releasing().keySet()) {
            Path dir = module.dir().dir();
            Optional<String> on = Git.branch(dir);
            if (!on.equals(Optional.of(branch))) {
                verdicts.add(refused(module + " is on " + on.map(b -> "branch " + b).orElse("a detached HEAD")
                        + ", not on " + branch, "     git -C " + module + " switch " + branch));
            }
            // Untracked files are left out: the release commits only the files it changes.
            Proc.Result status = Git.run(dir, "status", "--porcelain", "--untracked-files=no");
            if (!status.ok()) {
                verdicts.add(skipped(module + ": git status failed, its tree was not checked"));
            } else if (!status.lines().isEmpty()) {
                List<String> files = status.lines();
                String more = files.size() > LISTED ? "\n     … and " + (files.size() - LISTED) + " more" : "";
                verdicts.add(refused(module + " has uncommitted changes", "     "
                        + String.join("\n     ", files.subList(0, Math.min(LISTED, files.size()))) + more
                        + "\n     Commit or stash them: the release commits and tags what is committed."));
            }
            if (on.equals(Optional.of(branch))) {
                behind(module, dir, branch).ifPresent(verdicts::add);
            }
        }
        if (verdicts.isEmpty() && !ctx.plan().releasing().isEmpty()) {
            verdicts.add(ok("every module released is on " + branch + ", committed and not behind its remote"));
        }
        return verdicts;
    }

    private Optional<GateVerdict> behind(Module module, Path dir, String branch) {
        if (!Git.run(dir, "fetch", "--quiet", "origin", branch).ok()) {
            return Optional.of(skipped(module + ": origin did not answer, not checked against its remote"));
        }
        Optional<String> count = Git.line(dir, "rev-list", "--count", "HEAD..refs/remotes/origin/" + branch);
        if (count.isEmpty()) {
            return Optional.of(skipped(module + ": origin/" + branch + " could not be read, not checked against it"));
        }
        if (count.get().equals("0")) {
            return Optional.empty();
        }
        return Optional.of(refused(module + " is " + count.get() + " commit(s) behind origin/" + branch,
                "     git -C " + module + " pull --ff-only"));
    }
}
