package com.botmaker.sluice.target;

import com.botmaker.sluice.Spelling;
import com.botmaker.sluice.model.Coordinate;
import com.botmaker.sluice.model.PomReader;
import com.botmaker.sluice.spi.PublishTarget;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** A Maven repository that is a directory, named by a {@code file:} address: a shared drive, or a test's. */
public final class FileRepoTarget implements PublishTarget {

    private final String url;
    private final Path root;

    /** @throws IllegalArgumentException when {@code url} is not a {@code file:} address of a path */
    FileRepoTarget(String url) {
        this.url = PomReader.withoutSlash(url);
        this.root = Path.of(URI.create(url));
    }

    @Override
    public String name() {
        return "maven";
    }

    @Override
    public boolean publishes() {
        return true;
    }

    @Override
    public Spelling spelling() {
        return Spelling.VERSION;
    }

    @Override
    public Optional<String> repository() {
        return Optional.of(url);
    }

    @Override
    public Served serves(Coordinate coordinate, String version) {
        if (!Files.isDirectory(root)) {
            return Served.UNREACHABLE;
        }
        return Files.isRegularFile(root.resolve(MavenTarget.pomPath(coordinate, version))) ? Served.YES : Served.NO;
    }
}
