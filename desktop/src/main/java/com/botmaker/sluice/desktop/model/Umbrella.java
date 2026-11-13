package com.botmaker.sluice.desktop.model;

import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.ConfigLoader;
import com.botmaker.sluice.layout.UmbrellaLayout;
import com.botmaker.sluice.model.Graph;
import com.botmaker.sluice.model.Module;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * An umbrella as the window reads it once: its config and its module graph, read again on each refresh rather than
 * watched. Only a folder with a {@code sluice.yml} is one.
 */
public record Umbrella(Path root, Config config, Graph graph) {

    /** Whether {@code dir} holds a {@code sluice.yml}: the one thing the window asks of a folder before opening it. */
    public static boolean looksLikeOne(Path dir) {
        return dir != null && Files.isRegularFile(dir.resolve(ConfigLoader.FILE));
    }

    /**
     * Reads {@code root}'s config and graph.
     *
     * @throws com.botmaker.sluice.Refusal when its {@code sluice.yml} is wrong or its modules cannot be read
     */
    public static Umbrella open(Path root) {
        Path at = root.toAbsolutePath().normalize();
        Config config = ConfigLoader.load(at);
        return new Umbrella(at, config, Graph.build(UmbrellaLayout.of(at), config));
    }

    /** The folder's name, which the window's title carries. */
    public String name() {
        Path name = root.getFileName();
        return name == null ? root.toString() : name.toString();
    }

    public List<Module> modules() {
        return graph.modules();
    }

    public Optional<Module> module(String name) {
        return graph.modules().stream().filter(m -> m.name().equals(name)).findFirst();
    }
}
