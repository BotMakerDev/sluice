package com.botmaker.sluice.decide;

import com.botmaker.sluice.config.Config;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Whether a changed file can change what a release publishes. A deny-list: a file no glob names counts, since a
 * needless tag costs a duplicate artifact and a missed one drops the change it was cut for.
 *
 * <p>A glob is matched against the whole path from the module's root, {@code /}-separated: {@code *} and
 * {@code ?} stay inside one name, {@code **} crosses directories, and a leading or inner {@code **}{@code /}
 * also matches no directory at all, so {@code **}{@code /*.md} names {@code README.md} too.
 */
public final class Relevance {

    private final List<Pattern> ignored;

    private Relevance(List<Pattern> ignored) {
        this.ignored = ignored;
    }

    /** The filter {@code config}'s {@code relevance.ignore} describes. */
    public static Relevance of(Config config) {
        return new Relevance(config.relevanceIgnore().stream().map(Relevance::compile).toList());
    }

    /** Whether a change to {@code path} is one to release. */
    public boolean relevant(String path) {
        String normal = path.replace('\\', '/');
        return ignored.stream().noneMatch(p -> p.matcher(normal).matches());
    }

    private static Pattern compile(String glob) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (glob.startsWith("**/", i)) {
                regex.append("(?:.*/)?");
                i += 3;
            } else if (glob.startsWith("**", i)) {
                regex.append(".*");
                i += 2;
            } else if (c == '*') {
                regex.append("[^/]*");
                i++;
            } else if (c == '?') {
                regex.append("[^/]");
                i++;
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        return Pattern.compile(regex.toString());
    }
}
