package com.botmaker.sluice.github;

import com.botmaker.sluice.Proc;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What a release asks GitHub. The token is read from the environment and never written anywhere. */
public interface GitHub {

    /** A repository on GitHub. */
    record Repo(String owner, String name) {

        private static final Pattern REMOTE = Pattern.compile("github\\.com[:/]([^/\\s]+)/([^/\\s]+?)(?:\\.git)?/?$");

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
     */
    record Run(String workflow, String status, String conclusion, String url, String sha) {

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

    /** The newest workflow runs on a branch, newest first; empty when GitHub could not be asked. */
    Optional<List<Run>> runs(Repo repo, String branch);

    /**
     * Creates the release page of {@code tag}, its notes {@code body}. A page the tag already has counts as
     * created: a resumed release asks again.
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
