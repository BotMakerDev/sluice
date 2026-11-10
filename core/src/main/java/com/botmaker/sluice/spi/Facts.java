package com.botmaker.sluice.spi;

import com.botmaker.sluice.Version;
import com.botmaker.sluice.model.Module;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** The release's facts, as the environment of a project's command. */
public final class Facts {

    public static final String MODULE = "RT_MODULE";
    public static final String VERSION = "RT_VERSION";
    public static final String NEXT_SNAPSHOT = "RT_NEXT_SNAPSHOT";
    public static final String TAG = "RT_TAG";
    public static final String UMBRELLA = "RT_UMBRELLA";
    public static final String DRY_RUN = "RT_DRY_RUN";

    /** Before a module's name: the version this run releases it at. */
    public static final String VERSION_OF = "RT_VERSION_";

    private Facts() {
    }

    /**
     * The environment of a command run in {@code module}. Its own version, snapshot and tag are there only when
     * this run releases it; {@code RT_VERSION_<NAME>} is there for every module released, so a command of one
     * module can read another's version.
     */
    public static Map<String, String> of(Path umbrella, Map<Module, Version> releasing, Module module,
                                         String tagPattern, boolean dryRun) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put(MODULE, module.name());
        Version version = releasing.get(module);
        if (version != null) {
            facts.put(VERSION, version.toString());
            facts.put(NEXT_SNAPSHOT, version.nextSnapshot());
            facts.put(TAG, version.tag(tagPattern));
        }
        facts.put(UMBRELLA, umbrella.toAbsolutePath().normalize().toString());
        facts.put(DRY_RUN, Boolean.toString(dryRun));
        releasing.forEach((released, at) -> facts.put(VERSION_OF + name(released), at.toString()));
        return Collections.unmodifiableMap(facts);
    }

    /** A module's alias, else its directory, upper-snaked: {@code plugin-host} is {@code PLUGIN_HOST}. */
    static String name(Module module) {
        return module.alias().orElse(module.name()).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }
}
