package com.botmaker.sluice.spi;

import com.botmaker.sluice.Runner;
import com.botmaker.sluice.config.Config.StepPoint;
import com.botmaker.sluice.model.Module;

import java.util.Map;

/** Something a release does at one point of a module's release. Internal in v1, like {@link Gate}. */
public interface Step {

    /** The point of a module's release it runs at. */
    StepPoint at();

    /** Whether it runs in {@code module}'s release. */
    boolean runsIn(Module module);

    /**
     * Does it, every side effect through {@code runner}, so a preview prints it instead.
     *
     * @param facts the release's facts ({@link Facts})
     */
    void run(Module module, Map<String, String> facts, Runner runner);
}
