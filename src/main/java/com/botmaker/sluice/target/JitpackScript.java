package com.botmaker.sluice.target;

import com.botmaker.sluice.Git;
import com.botmaker.sluice.gate.GateVerdict;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.spi.Gate;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A module's {@code jitpack.yml} runs only files its tag holds. The release commit adds no new file, so a script
 * the yml names that git does not track would tag a build that cannot start.
 */
final class JitpackScript {

    /** A command that starts with a file of the module: {@code bash .jitpack/build.sh}, {@code ./build.sh}. */
    private static final Pattern RUNS_FILE =
            Pattern.compile("^(?:(?:ba|z)?sh\\s+)?((?:\\./)?[\\w.-]+(?:/[\\w.-]+)*\\.sh)(?:\\s|$)");

    private JitpackScript() {
    }

    static List<GateVerdict> check(Module module, Gate as) {
        Path dir = module.dir().dir();
        Path yml = dir.resolve("jitpack.yml");
        if (!Files.isRegularFile(yml)) {
            return List.of();
        }
        Optional<List<String>> scripts;
        try {
            scripts = scripts(Files.readString(yml));
        } catch (IOException e) {
            scripts = Optional.empty();
        }
        if (scripts.isEmpty()) {
            return List.of(as.skipped(module + ": its jitpack.yml could not be read, the files it runs not checked"));
        }
        List<String> problems = new ArrayList<>();
        for (String file : scripts.get()) {
            String path = Path.of(file).normalize().toString().replace('\\', '/');
            if (!Files.isRegularFile(dir.resolve(path))) {
                problems.add(path + " is missing");
            } else if (!Git.run(dir, "ls-files", "--error-unmatch", path).ok()) {
                problems.add(path + " is not committed, and the release commit adds no new file");
            }
        }
        if (problems.isEmpty()) {
            return List.of();
        }
        return List.of(as.refused(module + ": JitPack could not start its build of the tag",
                String.join("\n", problems.stream().map(problem -> "     jitpack.yml runs " + problem).toList())
                        + "\n     Commit it, or change jitpack.yml."));
    }

    /**
     * Each file of the module a command of the yml's lists starts with; empty when the yml is not YAML. A path
     * that climbs out of the module is not the module's to commit, and is left out.
     */
    static Optional<List<String>> scripts(String yml) {
        Object root;
        try {
            root = new Load(LoadSettings.builder().build()).loadFromString(yml);
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
        List<String> files = new ArrayList<>();
        if (root instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                List<?> commands = value instanceof List<?> list ? list : List.of(value == null ? "" : value);
                for (Object command : commands) {
                    Matcher runs = RUNS_FILE.matcher(String.valueOf(command).strip());
                    if (runs.find() && !runs.group(1).startsWith("..")) {
                        files.add(runs.group(1));
                    }
                }
            }
        }
        return Optional.of(files);
    }
}
