package com.botmaker.sluice.target;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.Config.Target;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.spi.PublishTarget;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Where each module of an umbrella publishes: its own entry's target, else the umbrella's. */
public final class Targets {

    private Targets() {
    }

    /** The targets a configuration names, asking the network. */
    public static Function<Module, PublishTarget> of(Config config) {
        return of(config, Http.real(), Path.of(System.getProperty("user.home"), ".m2", "repository"));
    }

    /**
     * Every target is made here, once, so a bad address is refused before any gate runs and a target's answers
     * are shared by every module published to it.
     */
    static Function<Module, PublishTarget> of(Config config, Http http, Path localRepository) {
        PublishTarget umbrella = target(config.target(), http, localRepository);
        PublishTarget none = new NoneTarget();
        Map<String, PublishTarget> own = new LinkedHashMap<>();
        config.modules().forEach((path, module) -> module.target()
                .ifPresent(target -> own.put(path, target(target, http, localRepository))));
        return module -> module.kind() == Module.Kind.TAG_ONLY ? none : own.getOrDefault(module.name(), umbrella);
    }

    private static PublishTarget target(Target target, Http http, Path localRepository) {
        return switch (target) {
            case Target.Jitpack jitpack -> new JitpackTarget(jitpack.maven(), http, localRepository);
            case Target.None none -> new NoneTarget();
            case Target.Maven maven when maven.url().startsWith("file:") -> {
                try {
                    yield new FileRepoTarget(maven.url());
                } catch (IllegalArgumentException e) {
                    throw new Refusal("target: " + maven.url() + " is not a directory's address (" + e.getMessage()
                            + ")");
                }
            }
            case Target.Maven maven -> new MavenTarget(maven.url(), http);
        };
    }
}
