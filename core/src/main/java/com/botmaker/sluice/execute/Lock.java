// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.execute;

import com.botmaker.sluice.Git;
import com.botmaker.sluice.Refusal;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One release at a time per umbrella: a file under the umbrella's git directory naming the run holding it, its
 * pid and host. A lock whose pid no longer runs on this host was left by a killed run (Ctrl-C, a crash) and is
 * taken over, said; a lock held on another host is refused, since nothing here can tell whether it still runs.
 */
final class Lock implements AutoCloseable {

    static final String FILE = "sluice.lock";

    private final Path file;
    private final String mine;

    private Lock(Path file, String mine) {
        this.file = file;
        this.mine = mine;
    }

    /**
     * Whether a release holds {@code umbrella}'s lock now: its file is there and its holder still runs, or runs on
     * another host, where nothing here can tell. False for a folder that is no git repository.
     */
    static boolean held(Path umbrella) {
        return file(umbrella).filter(Files::isRegularFile).map(file -> !dead(holder(file))).orElse(false);
    }

    /** Takes the umbrella's lock, or refuses naming who holds it. */
    static Lock take(Path umbrella, Consumer<String> say) {
        Path file = file(umbrella)
                .orElseThrow(() -> new Refusal(umbrella + " is not a git repository, so it holds no lock"));
        String mine = "pid=" + ProcessHandle.current().pid() + "\nhost=" + host() + "\nsince=" + Instant.now()
                + "\n";
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Files.writeString(file, mine, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return new Lock(file, mine);
            } catch (FileAlreadyExistsException held) {
                Map<String, String> holder = holder(file);
                String who = "pid " + holder.getOrDefault("pid", "?") + " on " + holder.getOrDefault("host", "?")
                        + " since " + holder.getOrDefault("since", "?");
                if (!dead(holder)) {
                    throw new Refusal("another release holds the lock: " + who
                            + "\n     When no release runs there any more, delete " + file);
                }
                say.accept("taking over the lock of " + who + ", which no longer runs");
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    throw new Refusal(file + ": a dead run's lock could not be removed (" + e.getMessage() + ")");
                }
            } catch (IOException e) {
                throw new Refusal(file + ": the lock could not be taken (" + e.getMessage() + ")");
            }
        }
        throw new Refusal(file + ": the lock was taken by another run while this one took it over");
    }

    private static Optional<Path> file(Path umbrella) {
        return Git.line(umbrella, "rev-parse", "--absolute-git-dir").map(Path::of).map(dir -> dir.resolve(FILE));
    }

    /** Whether the holder is a run of this host that no longer runs. */
    private static boolean dead(Map<String, String> holder) {
        if (!host().equals(holder.get("host"))) {
            return false;
        }
        try {
            long pid = Long.parseLong(holder.getOrDefault("pid", ""));
            return ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true);
        } catch (NumberFormatException unreadable) {
            return false;
        }
    }

    private static Map<String, String> holder(Path file) {
        Map<String, String> holder = new LinkedHashMap<>();
        try {
            Files.readAllLines(file).forEach(line -> {
                int at = line.indexOf('=');
                if (at > 0) {
                    holder.put(line.substring(0, at), line.substring(at + 1));
                }
            });
        } catch (IOException unreadable) {
            // A lock that cannot be read is held by nobody known: refused, never taken over.
        }
        return holder;
    }

    static String host() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return Optional.ofNullable(System.getenv("COMPUTERNAME")).or(() -> Optional.ofNullable(
                    System.getenv("HOSTNAME"))).orElse("unknown");
        }
    }

    /** Lets the lock go, unless another run has taken it over meanwhile. */
    @Override
    public void close() {
        try {
            if (Files.isRegularFile(file) && Files.readString(file).equals(mine)) {
                Files.delete(file);
            }
        } catch (IOException ignored) {
            // A lock that will not go is reported by the next run, with the file to delete.
        }
    }
}
