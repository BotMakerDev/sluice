package com.botmaker.sluice.github;

import com.botmaker.sluice.Git;
import com.botmaker.sluice.Proc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What a release asks GitHub. The token is read from the environment and never written anywhere. */
public interface GitHub {

    /** Where a repository keeps its own workflows. */
    String WORKFLOWS = ".github/workflows/";

    /** A repository on GitHub. */
    record Repo(String owner, String name) {

        private static final Pattern REMOTE = Pattern.compile("github\\.com[:/]([^/\\s]+)/([^/\\s]+?)(?:\\.git)?/?$");

        /** The repository {@code checkout}'s {@code origin} names; empty when it has none or it is not on GitHub. */
        public static Optional<Repo> ofCheckout(Path checkout) {
            return Git.line(checkout, "remote", "get-url", "origin").flatMap(Repo::ofRemote);
        }

        /** The repository a git remote's url names, https or ssh; empty when it is not on GitHub. */
        public static Optional<Repo> ofRemote(String url) {
            Matcher matcher = REMOTE.matcher(url.strip());
            return matcher.find() ? Optional.of(new Repo(matcher.group(1), matcher.group(2))) : Optional.empty();
        }

        @Override
        public String toString() {
            return owner + "/" + name;
        }
    }

    /**
     * One run of a workflow.
     *
     * @param workflow   the workflow's name
     * @param status     {@code completed}, or what it is doing
     * @param conclusion {@code success}, {@code failure}…; empty until it is finished
     * @param path       its workflow's file in the repository ({@code .github/workflows/ci.yml}); empty when unknown
     * @param updated    when it last changed, ISO-8601 in UTC as GitHub gives it ({@code 2026-11-10T03:19:47Z}),
     *                   so two compare as text; empty when unknown
     */
    record Run(String workflow, String status, String conclusion, String url, String sha, String path,
               String updated) {

        /** A run whose workflow file and time are not known. */
        public Run(String workflow, String status, String conclusion, String url, String sha) {
            this(workflow, status, conclusion, url, sha, "", "");
        }

        /**
         * Whether its workflow is gone from {@code checkout}: a file under {@code .github/workflows/} that is no
         * longer there, whose old runs say nothing of the branch now. GitHub's own workflows ({@code dynamic/…})
         * are never gone.
         */
        public boolean removedFrom(Path checkout) {
            return path.startsWith(WORKFLOWS) && !Files.isRegularFile(checkout.resolve(path));
        }

        public boolean finished() {
            return "completed".equals(status);
        }

        /** Whether it ended without an answer: stopped for a newer run, not failed. */
        public boolean cancelled() {
            return List.of("cancelled", "stale").contains(conclusion);
        }

        /** Whether a finished run did not fail. */
        public boolean green() {
            return List.of("success", "skipped", "neutral").contains(conclusion);
        }

        /** Its commit, short. */
        public String commit() {
            return sha.length() > 7 ? sha.substring(0, 7) : sha;
        }
    }

    /**
     * What a branch's runs say of a checkout.
     *
     * @param newestFinished each workflow's newest finished run that answers for it: neither cancelled nor of a
     *                       workflow file the checkout no longer has; by workflow, the newest first
     * @param running        whether a run newer than its workflow's newest finished one is still going
     */
    record BranchRuns(Map<String, Run> newestFinished, boolean running) {

        public BranchRuns {
            newestFinished = Collections.unmodifiableMap(new LinkedHashMap<>(newestFinished));
        }

        /** {@code runs}, newest first, read for {@code checkout}. */
        public static BranchRuns of(List<Run> runs, Path checkout) {
            Map<String, Run> newest = new LinkedHashMap<>();
            boolean running = false;
            for (Run run : runs) {
                if (run.finished() && run.cancelled() || run.removedFrom(checkout)) {
                    continue;
                }
                if (run.finished()) {
                    newest.putIfAbsent(run.workflow(), run);
                } else if (!newest.containsKey(run.workflow())) {
                    running = true;
                }
            }
            return new BranchRuns(newest, running);
        }

        /** The newest finished runs that failed. */
        public List<Run> red() {
            return newestFinished.values().stream().filter(run -> !run.green()).toList();
        }

        /** Whether one of the newest finished runs built {@code sha}. */
        public boolean built(Optional<String> sha) {
            return newestFinished.values().stream().anyMatch(run -> sha.equals(Optional.of(run.sha())));
        }
    }

    /** The newest workflow runs on a branch, newest first; empty when GitHub could not be asked. */
    Optional<List<Run>> runs(Repo repo, String branch);

    /**
     * Creates the release page of {@code tag}, its notes {@code body}. A page the tag already has counts as
     * created (a resumed release asks again), and gets {@code body} when it has no notes yet: the tag's CI may
     * have made it first.
     *
     * @return empty when it was created, else why not
     */
    default Optional<String> release(Repo repo, String tag, String body) {
        return Optional.of("this GitHub client creates no release");
    }

    /**
     * GitHub with the token of this environment: {@code GITHUB_TOKEN}, else what {@code gh auth token} answers.
     * Empty when there is neither.
     */
    public static Optional<GitHub> fromEnvironment() {
        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            token = Proc.onPath("gh")
                    ? Optional.of(Proc.run(Path.of("."), List.of("gh", "auth", "token")))
                            .filter(Proc.Result::ok).flatMap(r -> r.lines().stream().findFirst()).orElse("")
                    : "";
        }
        return token.isBlank() ? Optional.empty() : Optional.of(new GitHubRest(token.strip()));
    }
}
