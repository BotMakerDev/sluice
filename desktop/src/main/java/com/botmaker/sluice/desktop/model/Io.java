// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.desktop.model;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The window's background work: git, GitHub and the release itself, never on the FX thread. Daemon threads, so a
 * read still going never keeps a closed window's process alive.
 */
public final class Io {

    private static final AtomicInteger COUNT = new AtomicInteger();

    /**
     * At most this many at once: a refresh asks git and GitHub something per module, and an umbrella of thirty
     * would otherwise start thirty processes together. A release holds one for its whole run.
     */
    private static final int THREADS = 8;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS, task -> {
        Thread thread = new Thread(task, "sluice-io-" + COUNT.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private Io() {
    }

    public static <T> CompletableFuture<T> async(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, POOL);
    }

    public static CompletableFuture<Void> run(Runnable work) {
        return CompletableFuture.runAsync(work, POOL);
    }

    /** The message of what {@code error} wraps, for a sentence. */
    public static String message(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    public static void shutdown() {
        POOL.shutdownNow();
    }
}
