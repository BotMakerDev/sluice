package com.botmaker.sluice.decide;

import com.botmaker.sluice.Git;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.model.Module;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A module's released versions, read from its git tags. A tag that is not a version by the pattern is ignored: a
 * repository may carry tags no release cut.
 */
public final class Tags {

    private Tags() {
    }

    /**
     * The module's newest released version, empty when it was never tagged. Fetches the remote's tags first, so a
     * release cut from another machine counts; a fetch that fails (offline, no remote yet) leaves the local tags
     * standing.
     */
    public static Optional<Version> newest(Module module, String tagPattern) {
        Path dir = module.dir().dir();
        Git.run(dir, "fetch", "--tags", "--quiet", "origin");
        return known(module, tagPattern).stream().max(Version::compareTo);
    }

    /** Every version this checkout holds a tag of, without fetching. */
    public static List<Version> known(Module module, String tagPattern) {
        return Git.run(module.dir().dir(), "tag", "--list").lines().stream()
                .map(name -> version(name, tagPattern))
                .flatMap(Optional::stream)
                .toList();
    }

    /** The version the tag {@code name} was cut for by {@code tagPattern}, or empty when it is no such tag. */
    public static Optional<Version> version(String name, String tagPattern) {
        int at = tagPattern.indexOf(Version.PLACEHOLDER);
        String prefix = tagPattern.substring(0, at);
        String suffix = tagPattern.substring(at + Version.PLACEHOLDER.length());
        if (name.length() < prefix.length() + suffix.length() || !name.startsWith(prefix) || !name.endsWith(suffix)) {
            return Optional.empty();
        }
        String bare = name.substring(prefix.length(), name.length() - suffix.length());
        // Version.parse takes a leading v; here the pattern alone says what surrounds the number.
        return bare.isEmpty() || !Character.isDigit(bare.charAt(0)) ? Optional.empty() : Version.parse(bare);
    }

    /** Whether this checkout can read the tag of {@code version} as a commit. */
    public static boolean resolves(Module module, Version version, String tagPattern) {
        return Git.run(module.dir().dir(), "rev-parse", "-q", "--verify",
                "refs/tags/" + version.tag(tagPattern) + "^{commit}").ok();
    }
}
