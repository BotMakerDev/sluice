// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.update;

import com.botmaker.sluice.github.GitHub.Repo;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A repository's releases over GitHub's REST API, anonymous. Asset addresses redirect to a CDN, so redirects are
 * followed. {@code api} is GitHub's API root, or a stand-in's in a test.
 */
final class GitHubReleases implements Releases {

    private final URI api;
    private final Repo repo;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    GitHubReleases(URI api, Repo repo) {
        this.api = api;
        this.repo = repo;
    }

    @Override
    public Optional<Latest> latest() throws IOException {
        URI uri = api.resolve("/repos/" + repo.owner() + "/" + repo.name() + "/releases/latest");
        HttpResponse<String> response = send(request(uri).header("Accept", "application/vnd.github+json").build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IOException("GitHub answered " + response.statusCode() + " for " + repo + "'s latest release");
        }
        return Optional.of(latest(response.body()));
    }

    /** A release's JSON, read for its tag and its assets. JSON is YAML, so the config's parser reads it. */
    static Latest latest(String json) throws IOException {
        if (!(new Load(LoadSettings.builder().build()).loadFromString(json) instanceof Map<?, ?> release)
                || !(release.get("tag_name") instanceof String tag)) {
            throw new IOException("GitHub's answer names no release tag");
        }
        Map<String, String> assets = new LinkedHashMap<>();
        if (release.get("assets") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> asset && asset.get("name") instanceof String name
                        && asset.get("browser_download_url") instanceof String url) {
                    assets.put(name, url);
                }
            }
        }
        return new Latest(tag, assets);
    }

    @Override
    public void download(String url, Path to) throws IOException {
        HttpResponse<Path> response = send(request(URI.create(url)).build(), HttpResponse.BodyHandlers.ofFile(to));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(to);
            throw new IOException("downloading " + url + " answered " + response.statusCode());
        }
    }

    @Override
    public String text(String url) throws IOException {
        HttpResponse<String> response = send(request(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("fetching " + url + " answered " + response.statusCode());
        }
        return response.body();
    }

    private static HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).header("User-Agent", "sluice");
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> body) throws IOException {
        try {
            return http.send(request, body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while reaching " + request.uri(), e);
        }
    }
}
