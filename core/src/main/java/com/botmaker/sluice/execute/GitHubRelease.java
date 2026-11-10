package com.botmaker.sluice.execute;

import com.botmaker.sluice.Changelog;
import com.botmaker.sluice.Git;
import com.botmaker.sluice.Proc;
import com.botmaker.sluice.Runner;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.github.GitHub;
import com.botmaker.sluice.github.GitHub.Repo;
import com.botmaker.sluice.model.Module;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The release page of a tag on GitHub, its notes the changelog's section of the version. Never stops a release:
 * the tag is out already, and a page can be written by hand later.
 */
final class GitHubRelease {

    private GitHubRelease() {
    }

    static void create(Runner runner, Optional<GitHub> github, Config config, Module module, Version version,
                       Map<String, String> facts) {
        if (!config.githubReleases()) {
            return;
        }
        String tag = version.tag(config.tagPattern());
        if (github.isEmpty()) {
            runner.say("    no GitHub token: " + module + " " + tag + " gets no release page");
            return;
        }
        Path dir = module.dir().dir();
        Optional<Repo> repo = Git.line(dir, "remote", "get-url", "origin").flatMap(Repo::ofRemote);
        if (repo.isEmpty()) {
            runner.say("    " + module + "'s origin is not on GitHub: " + tag + " gets no release page");
            return;
        }
        String where = repo.get().owner() + "/" + repo.get().name();
        if (runner.dryRun()) {
            runner.say("    would create the GitHub release " + tag + " of " + where);
            return;
        }
        github.get().release(repo.get(), tag, notes(runner, config, module, version, facts)).ifPresentOrElse(
                why -> runner.say("    no GitHub release for " + where + " " + tag + ": " + why),
                () -> runner.say("    GitHub release " + where + " " + tag + " created"));
    }

    /** The version's notes: what the extractor prints, else the changelog's section; empty when neither has any. */
    static String notes(Runner runner, Config config, Module module, Version version, Map<String, String> facts) {
        Path dir = module.dir().dir();
        Optional<List<String>> extractor = config.changelog().extractor()
                .flatMap(command -> command.argv(List.of(version.toString())));
        if (extractor.isPresent()) {
            Proc.Result result = Proc.run(dir, extractor.get(), facts);
            if (!result.ok()) {
                runner.say("    the changelog extractor failed for " + module + " (exit " + result.exit()
                        + "): its GitHub release has no notes");
                return "";
            }
            return result.out().strip();
        }
        try {
            Path file = dir.resolve(Changelog.FILE);
            return Files.isRegularFile(file)
                    ? Changelog.section(Files.readString(file), version.toString()).orElse("").strip() : "";
        } catch (IOException unreadable) {
            return "";
        }
    }
}
