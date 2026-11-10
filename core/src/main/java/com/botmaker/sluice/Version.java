package com.botmaker.sluice;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A release version, {@code x.y.z}. A snapshot is not a {@code Version}: it is what a release moves away from and
 * back to, spelled by {@link #nextSnapshot()}.
 */
public record Version(int major, int minor, int patch) implements Comparable<Version> {

    private static final Pattern SHAPE = Pattern.compile("^v?([0-9]+)\\.([0-9]+)\\.([0-9]+)$");

    /** The version a module with no tag at all is bumped from. */
    public static final Version ZERO = new Version(0, 0, 0);

    /** The placeholder a tag pattern names the version by: {@code v{version}}. */
    public static final String PLACEHOLDER = "{version}";

    /** Parses {@code x.y.z} or {@code vx.y.z}; anything else is empty, never an exception. */
    public static Optional<Version> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = SHAPE.matcher(text.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Version(
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
    }

    /** Major zeroes the two below it, minor zeroes the patch. */
    public Version bump(Level level) {
        return switch (level) {
            case MAJOR -> new Version(major + 1, 0, 0);
            case MINOR -> new Version(major, minor + 1, 0);
            case PATCH -> new Version(major, minor, patch + 1);
        };
    }

    /** The tag this version is cut as, by a pattern such as {@code v{version}}. */
    public String tag(String pattern) {
        return pattern.replace(PLACEHOLDER, toString());
    }

    /** The version {@code main} moves to once this one is released: the next patch, as a snapshot. */
    public String nextSnapshot() {
        return bump(Level.PATCH) + "-SNAPSHOT";
    }

    /** {@code 1.2.3}, no {@code v}: a tag pattern adds what it adds. */
    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }

    @Override
    public int compareTo(Version other) {
        int byMajor = Integer.compare(major, other.major);
        if (byMajor != 0) {
            return byMajor;
        }
        int byMinor = Integer.compare(minor, other.minor);
        return byMinor != 0 ? byMinor : Integer.compare(patch, other.patch);
    }
}
