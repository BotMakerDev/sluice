package com.botmaker.sluice.execute;

import com.botmaker.sluice.Changelog;
import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.Runner;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.model.Module;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The release commit stamps {@code ## [Unreleased]} of a module's {@code CHANGELOG.md} as {@code ## [x.y.z] —
 * date}: that heading's line and nothing else, so the commit carries no reformatting of a file somebody edits all
 * week. The first {@code [Unreleased]} only: a changelog holding a stamped section and a fresh one is the usual
 * state, and the fresh one is the release being cut.
 *
 * <p>Idempotent: a file that already has the version's heading is a resumed release's, and is left alone.
 */
final class ChangelogStamp {

    private static final Pattern UNRELEASED = Pattern.compile("(?m)^## \\[" + Changelog.UNRELEASED + "]");

    private ChangelogStamp() {
    }

    static void stamp(Runner runner, Module module, Version version, LocalDate date) {
        Path file = module.dir().dir().resolve(Changelog.FILE);
        if (!Files.isRegularFile(file)) {
            return;
        }
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new Refusal(file + ": could not be read (" + e.getMessage() + ")");
        }
        if (Pattern.compile("(?m)^## \\[" + Pattern.quote(version.toString()) + "]").matcher(text).find()
                || !UNRELEASED.matcher(text).find()) {
            return;
        }
        runner.say("    stamping " + module + " " + Changelog.FILE + ": [" + Changelog.UNRELEASED + "] -> ["
                + version + "]");
        runner.replace(file, UNRELEASED, Matcher.quoteReplacement("## [" + version + "] — " + date));
    }
}
