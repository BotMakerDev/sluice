package com.botmaker.sluice.gate;

import com.botmaker.sluice.Proc;
import com.botmaker.sluice.config.Config.CommandConfig;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.spi.Gate;
import com.botmaker.sluice.spi.GateContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A project's own gate: a command run in each of its modules that this run releases, with the release's facts in
 * its environment. Exit 0 passes, the configured exit code means it could not check, anything else refuses with
 * its output. It runs in a preview too, so it must change nothing.
 */
public final class CommandGate implements Gate {

    private final CommandConfig command;

    public CommandGate(CommandConfig command) {
        this.command = command;
    }

    @Override
    public String name() {
        return command.name();
    }

    @Override
    public List<GateVerdict> check(GateContext ctx) {
        List<GateVerdict> verdicts = new ArrayList<>();
        for (Module module : ctx.plan().releasing().keySet()) {
            if (!command.modules().isEmpty() && command.modules().stream().noneMatch(module::answersTo)) {
                continue;
            }
            Optional<List<String>> argv = command.run().argv();
            if (argv.isEmpty()) {
                verdicts.add(skipped(module + ": no command for this system"));
                continue;
            }
            Proc.Result run = Proc.run(module.dir().dir(), argv.get(), ctx.facts(module));
            if (run.ok()) {
                verdicts.add(ok(module + ": passed"));
            } else if (command.skipExit().filter(code -> code == run.exit()).isPresent()) {
                verdicts.add(skipped(module + ": could not check (exit " + run.exit() + ")"));
            } else {
                verdicts.add(refused(module + ": failed (exit " + run.exit() + ")",
                        "     $ " + String.join(" ", argv.get()) + "\n" + Gates.quoted(run.out())));
            }
        }
        return verdicts;
    }
}
