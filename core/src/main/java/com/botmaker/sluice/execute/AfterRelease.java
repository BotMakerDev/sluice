package com.botmaker.sluice.execute;

import com.botmaker.sluice.PinMoves;
import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.Runner;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.model.Pin;

import java.nio.file.Path;
import java.util.Map;

/**
 * The pom properties a module's back-to-snapshot commit sets to what it just released, such as a compatibility
 * check's baseline: {@code {version}} is the release's version, {@code {tag}} its tag.
 */
final class AfterRelease {

    private AfterRelease() {
    }

    static void set(Runner runner, PinMoves moves, Config config, Module module, Path pom, Version version) {
        for (Map.Entry<String, Map<String, String>> entry : config.afterRelease().entrySet()) {
            if (!module.answersTo(entry.getKey())) {
                continue;
            }
            entry.getValue().forEach((name, template) -> {
                Pin.Property pin = new Pin.Property(pom, name);
                if (!runner.dryRun() && PinMoves.held(pin).isEmpty()) {
                    throw new Refusal(pom + " declares no property " + name + ", which after-release sets for "
                            + module);
                }
                moves.move(pin, template.replace("{version}", version.toString())
                        .replace("{tag}", version.tag(config.tagPattern())));
            });
        }
    }
}
