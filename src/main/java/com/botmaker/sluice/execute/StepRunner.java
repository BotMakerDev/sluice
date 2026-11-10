package com.botmaker.sluice.execute;

import com.botmaker.sluice.Proc;
import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.Runner;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.Config.StepConfig;
import com.botmaker.sluice.config.Config.StepPoint;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.spi.Step;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The project's steps: each a command run at its point of a module's release, through the run's runner. */
final class StepRunner {

    /** How many lines of a failed step's output a refusal quotes. */
    private static final int QUOTED = 12;

    private final List<Step> steps;

    StepRunner(Config config) {
        this.steps = config.steps().stream().map(CommandStep::new).map(Step.class::cast).toList();
    }

    /** Runs every step of {@code at} in {@code module}'s release; a step that fails stops the release. */
    void run(StepPoint at, Module module, Map<String, String> facts, Runner runner) {
        for (Step step : steps) {
            if (step.at() == at && step.runsIn(module)) {
                step.run(module, facts, runner);
            }
        }
    }

    /** A step configured as a command. */
    private record CommandStep(StepConfig config) implements Step {

        @Override
        public StepPoint at() {
            return config.at();
        }

        @Override
        public boolean runsIn(Module module) {
            return config.modules().isEmpty() || config.modules().stream().anyMatch(module::answersTo);
        }

        @Override
        public void run(Module module, Map<String, String> facts, Runner runner) {
            Optional<List<String>> argv = config.run().argv();
            if (argv.isEmpty()) {
                runner.say("    the " + config.at().id() + " step of " + module + " has no command for this "
                        + "system: not run");
                return;
            }
            Proc.Result result = runner.run(module.dir().dir(), argv.get(), facts);
            if (!result.ok()) {
                List<String> out = result.out().lines().filter(line -> !line.isBlank()).toList();
                throw new Refusal("the " + config.at().id() + " step failed (exit " + result.exit() + ")\n"
                        + String.join("\n", out.subList(Math.max(0, out.size() - QUOTED), out.size()).stream()
                        .map(line -> "     " + line).toList()));
            }
        }
    }
}
