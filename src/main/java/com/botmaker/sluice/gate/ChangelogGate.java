package com.botmaker.sluice.gate;

import com.botmaker.sluice.Changelog;
import com.botmaker.sluice.Proc;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.config.Config.Command;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.spi.Gate;
import com.botmaker.sluice.spi.GateContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every module released says what its release holds: its {@code CHANGELOG.md} has notes under the version, or
 * under {@code [Unreleased]}, which the release stamps. The notes become the release's page, and a pushed tag
 * cannot be edited. Off unless the configuration turns the changelog on.
 *
 * <p>A configured extractor replaces the built-in reader: the command, given the version as its last argument,
 * prints the notes and exits 0, or the module has none.
 */
public final class ChangelogGate implements Gate {

    @Override
    public String name() {
        return "changelog";
    }

    @Override
    public List<GateVerdict> check(GateContext ctx) {
        if (!ctx.config().changelog().enabled()) {
            return List.of();
        }
        Optional<Command> extractor = ctx.config().changelog().extractor();
        List<GateVerdict> verdicts = new ArrayList<>();
        ctx.plan().releasing().forEach((module, version) -> verdicts.add(extractor.isPresent()
                ? extracted(ctx, extractor.get(), module, version)
                : read(module, version)));
        return verdicts;
    }

    private GateVerdict read(Module module, Version version) {
        Path file = module.dir().dir().resolve(Changelog.FILE);
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            return refused(module + " has no readable " + Changelog.FILE,
                    "     Add one with a '## [" + Changelog.UNRELEASED + "]' section saying what " + version
                            + " holds.");
        }
        for (String name : List.of(version.toString(), Changelog.UNRELEASED)) {
            Optional<String> section = Changelog.section(text, name);
            if (section.filter(Changelog::hasNotes).isPresent()) {
                return ok(module + ": " + Changelog.FILE + " has notes under [" + name + "]");
            }
        }
        return refused(module + ": " + Changelog.FILE + " has no notes for " + version,
                "     Write them under '## [" + Changelog.UNRELEASED + "]' in " + module + "/" + Changelog.FILE
                        + ": the release stamps that heading with the version.");
    }

    private GateVerdict extracted(GateContext ctx, Command extractor, Module module, Version version) {
        Optional<List<String>> command = extractor.argv(List.of(version.toString()));
        if (command.isEmpty()) {
            return skipped(module + ": the changelog extractor has no command for this system");
        }
        List<String> argv = command.get();
        Proc.Result run = Proc.run(module.dir().dir(), argv, ctx.facts(module));
        if (run.ok() && !run.out().isBlank()) {
            return ok(module + ": the changelog extractor gave notes for " + version);
        }
        return refused(module + ": the changelog extractor gave no notes for " + version,
                "     $ " + String.join(" ", argv) + "\n" + Gates.quoted(run.out()));
    }
}
