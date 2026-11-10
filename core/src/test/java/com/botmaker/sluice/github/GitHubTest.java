package com.botmaker.sluice.github;

import com.botmaker.sluice.github.GitHub.Repo;
import com.botmaker.sluice.github.GitHub.Run;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GitHubTest {

    @Test
    void remoteUrlNamesItsRepository() {
        Optional<Repo> repo = Optional.of(new Repo("Owner", "some.repo"));

        assertEquals(repo, Repo.ofRemote("https://github.com/Owner/some.repo.git"));
        assertEquals(repo, Repo.ofRemote("https://github.com/Owner/some.repo/"));
        assertEquals(repo, Repo.ofRemote("git@github.com:Owner/some.repo.git\n"));
        assertEquals(repo, Repo.ofRemote("ssh://git@github.com/Owner/some.repo"));
        assertEquals(Optional.empty(), Repo.ofRemote("/srv/git/some.repo.git"));
        assertEquals(Optional.empty(), Repo.ofRemote("https://gitlab.com/Owner/some.repo.git"));
    }

    @Test
    void runsReadFromAnAnswer() {
        String json = """
                {"total_count": 2, "workflow_runs": [
                  {"id": 2, "name": "ci", "status": "in_progress", "conclusion": null,
                   "html_url": "https:\\/\\/github.com\\/o\\/r\\/actions\\/runs\\/2", "head_sha": "abcdef0123456789",
                   "display_title": "fix: a \\"quoted\\" title\\twith a tab"},
                  {"id": 1, "name": "ci", "status": "completed", "conclusion": "failure",
                   "html_url": "https://github.com/o/r/actions/runs/1", "head_sha": "0123456789abcdef"}
                ]}
                """;

        assertEquals(List.of(
                new Run("ci", "in_progress", "", "https://github.com/o/r/actions/runs/2", "abcdef0123456789"),
                new Run("ci", "completed", "failure", "https://github.com/o/r/actions/runs/1", "0123456789abcdef")),
                GitHubRest.runs(json));
        assertEquals("0123456", GitHubRest.runs(json).getLast().commit());
    }

    @Test
    void answerThatIsNotRunsHoldsNone() {
        assertEquals(List.of(), GitHubRest.runs("{\"message\": \"Not Found\"}"));
        assertEquals(List.of(), GitHubRest.runs("<html>"));
    }

    @Test
    void existingReleaseReadsIdAndNotes() {
        assertEquals(Optional.of(new GitHubRest.Existing(42, "")),
                GitHubRest.existing("{\"id\": 42, \"tag_name\": \"v1.0.0\", \"body\": null}"));
        assertEquals("notes", GitHubRest.existing("{\"id\": 7, \"body\": \"notes\"}").orElseThrow().body());
        assertEquals(Optional.empty(), GitHubRest.existing("{\"message\": \"Not Found\"}"));
    }
}
