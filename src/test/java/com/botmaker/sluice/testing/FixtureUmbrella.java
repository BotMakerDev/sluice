package com.botmaker.sluice.testing;

import com.botmaker.sluice.Proc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A throwaway umbrella for a test: a git repository whose submodules are real repositories, each with a bare
 * remote under {@code <tmp>/remotes/}. Nothing here reaches the network or the caller's git configuration.
 */
public final class FixtureUmbrella {

    /** Settings every fixture git command runs with, so a developer's own configuration changes nothing. */
    private static final List<String> SETTINGS = List.of(
            "-c", "user.name=fixture", "-c", "user.email=fixture@example.invalid",
            "-c", "init.defaultBranch=main", "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false",
            "-c", "protocol.file.allow=always", "-c", "core.autocrlf=false");

    private final Path tmp;
    private final Path root;

    private FixtureUmbrella(Path tmp) {
        this.tmp = tmp;
        this.root = tmp.resolve("umbrella");
    }

    /** An umbrella with no submodule yet, its one commit on {@code main}. */
    public static FixtureUmbrella create(Path tmp) {
        FixtureUmbrella fixture = new FixtureUmbrella(tmp);
        fixture.mkdirs(fixture.root);
        git(fixture.root, "init", "-q");
        fixture.writeFile(fixture.root.resolve("README.md"), "fixture\n");
        git(fixture.root, "add", "-A");
        git(fixture.root, "commit", "-q", "-m", "umbrella");
        return fixture;
    }

    /** The umbrella's directory. */
    public Path root() {
        return root;
    }

    /** The checkout of a submodule inside the umbrella. */
    public Path dir(String path) {
        return root.resolve(path);
    }

    /**
     * Adds a submodule at {@code path}: a repository holding {@code pom.xml} (none when {@code pomXml} is null),
     * pushed to its bare remote, then added to the umbrella and committed there.
     */
    public FixtureUmbrella module(String path, String pomXml) {
        Path remote = tmp.resolve("remotes").resolve(path + ".git");
        Path seed = tmp.resolve("seeds").resolve(path);
        mkdirs(remote);
        mkdirs(seed);
        git(remote, "init", "-q", "--bare");
        git(seed, "init", "-q");
        writeFile(seed.resolve(pomXml == null ? "README.md" : "pom.xml"), pomXml == null ? path + "\n" : pomXml);
        git(seed, "add", "-A");
        git(seed, "commit", "-q", "-m", "start");
        String url = remote.toAbsolutePath().toString().replace('\\', '/');
        git(seed, "remote", "add", "origin", url);
        git(seed, "push", "-q", "origin", "main");
        git(root, "submodule", "add", "-q", url, path);
        git(dir(path), "switch", "-q", "main");
        git(root, "commit", "-q", "-m", "add " + path);
        return this;
    }

    /** Writes {@code file} in a submodule's checkout and commits it there (not in the umbrella). */
    public FixtureUmbrella commit(String path, String file, String text) {
        writeFile(dir(path).resolve(file), text);
        git(dir(path), "add", "-A");
        git(dir(path), "commit", "-q", "-m", "change " + file);
        return this;
    }

    /** Tags a submodule's {@code HEAD}. */
    public FixtureUmbrella tag(String path, String tag) {
        git(dir(path), "tag", tag);
        return this;
    }

    /** Pushes a submodule's {@code main} and its tags to its bare remote. */
    public FixtureUmbrella push(String path) {
        git(dir(path), "push", "-q", "--tags", "origin", "main");
        return this;
    }

    /** Runs git in {@code dir} with the fixture's settings, failing the test when git fails. */
    public static Proc.Result git(Path dir, String... args) {
        List<String> argv = new ArrayList<>();
        argv.add("git");
        argv.addAll(SETTINGS);
        argv.addAll(List.of(args));
        Proc.Result result = Proc.run(dir, argv);
        if (!result.ok()) {
            throw new IllegalStateException("git " + String.join(" ", args) + " in " + dir + ": " + result.out());
        }
        return result;
    }

    private void writeFile(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void mkdirs(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
