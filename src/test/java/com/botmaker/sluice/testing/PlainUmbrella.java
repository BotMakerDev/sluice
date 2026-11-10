package com.botmaker.sluice.testing;

import com.botmaker.sluice.layout.ModuleDir;
import com.botmaker.sluice.layout.UmbrellaLayout;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** An umbrella on disk without git, for what reads only files: poms, the graph, the order. */
public final class PlainUmbrella {

    private final Path root;
    private final List<String> paths = new ArrayList<>();

    public PlainUmbrella(Path root) {
        this.root = root;
    }

    /** A submodule at {@code path} holding {@code pomXml}; none when it is null. */
    public PlainUmbrella module(String path, String pomXml) {
        paths.add(path);
        mkdirs(root.resolve(path));
        if (pomXml != null) {
            file(path + "/pom.xml", pomXml);
        }
        return this;
    }

    /** Writes {@code text} at {@code path} from the root. */
    public PlainUmbrella file(String path, String text) {
        Path file = root.resolve(path);
        mkdirs(file.getParent());
        try {
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public Path root() {
        return root;
    }

    public UmbrellaLayout layout() {
        return new UmbrellaLayout(root, paths.stream()
                .map(p -> new ModuleDir(p, root.resolve(p), Optional.empty())).toList());
    }

    /** A pom of {@code g:artifactId} at {@code version}, {@code body} inside its {@code <project>}. */
    public static String pom(String artifactId, String version, String body) {
        return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>g</groupId>\n"
                + "  <artifactId>" + artifactId + "</artifactId>\n"
                + "  <version>" + version + "</version>\n"
                + body
                + "</project>\n";
    }

    /** A {@code <dependencies>} block naming {@code g:artifactId} at {@code version}. */
    public static String dep(String artifactId, String version) {
        return "  <dependencies><dependency><groupId>g</groupId><artifactId>" + artifactId
                + "</artifactId><version>" + version + "</version></dependency></dependencies>\n";
    }

    private static void mkdirs(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
