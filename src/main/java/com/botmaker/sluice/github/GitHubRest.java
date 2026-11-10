package com.botmaker.sluice.github;

import com.botmaker.sluice.Json;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** GitHub over its REST API. */
final class GitHubRest implements GitHub {

    private static final String API = "https://api.github.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    GitHubRest(String token) {
        this.token = token;
    }

    @Override
    public Optional<List<Run>> runs(Repo repo, String branch) {
        // Pushes only: a pull request from a fork's branch of the same name is not this branch's build.
        return get("/repos/" + repo.owner() + "/" + repo.name() + "/actions/runs?event=push&per_page=100&branch="
                + URLEncoder.encode(branch, StandardCharsets.UTF_8)).map(GitHubRest::runs);
    }

    @Override
    public Optional<String> release(Repo repo, String tag, String body) {
        String json = "{\"tag_name\": " + Json.quote(tag) + ", \"name\": " + Json.quote(tag) + ", \"body\": "
                + Json.quote(body) + "}";
        HttpRequest request = request("/repos/" + repo.owner() + "/" + repo.name() + "/releases")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 201) {
                return Optional.empty();
            }
            if (response.statusCode() == 422 && response.body().contains("already_exists")) {
                return notesOnExisting(repo, tag, body);
            }
            return Optional.of("GitHub answered " + response.statusCode());
        } catch (IOException e) {
            return Optional.of("GitHub did not answer (" + e.getMessage() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of("interrupted");
        }
    }

    /**
     * The page exists already: a resumed release asked before, or the tag's own CI made it to attach its packages.
     * Notes it lacks are written; notes it has are left as they are.
     */
    private Optional<String> notesOnExisting(Repo repo, String tag, String body)
            throws IOException, InterruptedException {
        String releases = "/repos/" + repo.owner() + "/" + repo.name() + "/releases";
        Optional<Existing> existing = get(releases + "/tags/" + URLEncoder.encode(tag, StandardCharsets.UTF_8))
                .flatMap(GitHubRest::existing);
        if (existing.isEmpty()) {
            return Optional.of("the release page exists, but GitHub did not say what it holds");
        }
        if (body.isBlank() || !existing.get().body().isBlank()) {
            return Optional.empty();
        }
        HttpRequest patch = request(releases + "/" + existing.get().id())
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"body\": " + Json.quote(body) + "}"))
                .build();
        int status = http.send(patch, HttpResponse.BodyHandlers.ofString()).statusCode();
        return status == 200 ? Optional.empty()
                : Optional.of("the release page exists, and GitHub answered " + status + " to its notes");
    }

    /** A release page as GitHub describes it. */
    record Existing(long id, String body) {
    }

    /** The id and notes of a release page from GitHub's answer; empty when the answer holds no id. */
    static Optional<Existing> existing(String json) {
        try {
            Object root = new Load(LoadSettings.builder().build()).loadFromString(json);
            if (root instanceof Map<?, ?> map && map.get("id") instanceof Number id) {
                return Optional.of(new Existing(id.longValue(), text(map, "body")));
            }
        } catch (RuntimeException unreadable) {
            // Not what GitHub documents.
        }
        return Optional.empty();
    }

    /** The runs of an answer; JSON is read as the YAML it also is. */
    static List<Run> runs(String json) {
        List<Run> runs = new ArrayList<>();
        try {
            Object root = new Load(LoadSettings.builder().build()).loadFromString(json);
            if (root instanceof Map<?, ?> map && map.get("workflow_runs") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> run) {
                        runs.add(new Run(text(run, "name"), text(run, "status"), text(run, "conclusion"),
                                text(run, "html_url"), text(run, "head_sha")));
                    }
                }
            }
        } catch (RuntimeException unreadable) {
            // An answer that is not what GitHub documents holds no run.
        }
        return List.copyOf(runs);
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : value.toString();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(API + path)).timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28");
    }

    private Optional<String> get(String path) {
        HttpRequest request = request(path).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? Optional.of(response.body()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
