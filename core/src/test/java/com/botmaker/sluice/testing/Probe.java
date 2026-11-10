package com.botmaker.sluice.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * A command for a test to configure, the same on every system: {@code Probe <record> <exit> [argument…]} writes
 * each argument it got ({@code arg=…}) and each {@code RT_} variable of its environment to {@code record}, prints
 * {@code probe ran}, and exits with {@code exit}.
 */
public final class Probe {

    private Probe() {
    }

    public static void main(String[] args) throws IOException {
        List<String> lines = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            lines.add("arg=" + args[i]);
        }
        new TreeMap<>(System.getenv()).forEach((name, value) -> {
            if (name.startsWith("RT_")) {
                lines.add(name + "=" + value);
            }
        });
        Files.write(Path.of(args[0]), lines);
        System.out.println("probe ran");
        System.exit(Integer.parseInt(args[1]));
    }
}
