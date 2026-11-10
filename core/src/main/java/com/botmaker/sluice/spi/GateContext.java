package com.botmaker.sluice.spi;

import com.botmaker.sluice.Runner;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.decide.Plan;
import com.botmaker.sluice.model.Graph;
import com.botmaker.sluice.model.Module;

import java.nio.file.Path;
import java.util.Map;

/**
 * What a gate is given.
 *
 * @param umbrella the umbrella's directory
 * @param plan     what the run would release
 * @param runner   the run's runner: a gate asks it only whether this is a preview, and changes nothing
 * @param force    whether {@code --force} was given; the run applies it, a gate does not
 */
public record GateContext(Path umbrella, Graph graph, Plan plan, Config config, Runner runner, boolean force) {

    /** The release's facts as a command run in {@code module} gets them. */
    public Map<String, String> facts(Module module) {
        return Facts.of(umbrella, plan.releasing(), module, config.tagPattern(), runner.dryRun());
    }

    /** A file as a verdict names it: from the umbrella's root, {@code /}-separated. */
    public String said(Path file) {
        Path root = umbrella.toAbsolutePath().normalize();
        Path absolute = file.toAbsolutePath().normalize();
        return (absolute.startsWith(root) ? root.relativize(absolute) : absolute).toString().replace('\\', '/');
    }
}
