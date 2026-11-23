// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.update;

import com.botmaker.sluice.github.GitHub.Repo;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Where the latest release is read and its assets fetched: GitHub's, or a stand-in in a test. */
public interface Releases {

    /** The latest release: its tag and each asset's download address by name. */
    record Latest(String tag, Map<String, String> assets) {
    }

    /** The latest published release, or empty when the repository has none. */
    Optional<Latest> latest() throws IOException;

    /** Saves {@code url}'s body to {@code to}. */
    void download(String url, Path to) throws IOException;

    /** {@code url}'s body as text. */
    String text(String url) throws IOException;

    /** {@code repo}'s releases on GitHub, read anonymously. */
    static Releases github(Repo repo) {
        return new GitHubReleases(URI.create("https://api.github.com"), repo);
    }
}
