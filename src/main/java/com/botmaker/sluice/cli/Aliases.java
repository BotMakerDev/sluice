package com.botmaker.sluice.cli;

import com.botmaker.sluice.Level;
import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.Config.ModuleConfig;
import com.botmaker.sluice.config.ConfigLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A module's alias as a flag of its own: {@code --sdk 1.10.0} is {@code --module sdk=1.10.0}, and a bare
 * {@code --sdk} is {@code --module sdk}. The aliases come from the umbrella's {@code sluice.yml}, so they are read
 * before the command line is parsed; an option sluice has itself always wins over an alias of the same name.
 */
final class Aliases {

    private Aliases() {
    }

    /** {@code args} with every alias flag spelled as {@code --module}; unchanged when the config cannot be read. */
    static List<String> expand(List<String> args, Set<String> ownOptions) {
        Set<String> aliases;
        try {
            Config config = ConfigLoader.load(umbrella(args));
            aliases = config.modules().values().stream().map(ModuleConfig::alias).flatMap(Optional::stream)
                    .collect(Collectors.toSet());
        } catch (Refusal unreadable) {
            // The run itself reads the config again and says what is wrong with it.
            return args;
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("--")) {
                out.addAll(args.subList(i, args.size()));
                break;
            }
            if (!arg.startsWith("--")) {
                out.add(arg);
                continue;
            }
            int equals = arg.indexOf('=');
            String name = arg.substring(2, equals < 0 ? arg.length() : equals);
            if (!aliases.contains(name) || ownOptions.contains("--" + name)) {
                out.add(arg);
                continue;
            }
            String value = equals < 0 ? null : arg.substring(equals + 1);
            if (value == null && i + 1 < args.size() && isValue(args.get(i + 1))) {
                value = args.get(++i);
            }
            out.add("--module");
            out.add(value == null ? name : name + "=" + value);
        }
        return out;
    }

    /** Whether a word after an alias flag is its version or level, rather than the next argument. */
    private static boolean isValue(String word) {
        return Level.of(word).isPresent() || Version.parse(word).isPresent();
    }

    /** The umbrella {@code --umbrella} names, else the working directory. */
    private static Path umbrella(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("--umbrella") && i + 1 < args.size()) {
                return Path.of(args.get(i + 1));
            }
            if (arg.startsWith("--umbrella=")) {
                return Path.of(arg.substring("--umbrella=".length()));
            }
        }
        return Path.of("");
    }
}
