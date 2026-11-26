// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.execute;

import com.botmaker.sluice.Changelog;
import com.botmaker.sluice.Git;
import com.botmaker.sluice.Level;
import com.botmaker.sluice.Proc;
import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.Runner;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.Config.AssetPinConfig;
import com.botmaker.sluice.github.GitHub.Repo;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.target.Waits.Sleeper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The run's {@code asset-pins} ({@link AssetPinConfig}): once a module is pushed, the file pinning its released
 * asset gets the release's version, url and sha256, committed and pushed in the file's own repository.
 *
 * <p>Run once every module is done, since the asset is built by the tag's CI after the push: waiting there costs
 * the run nothing it could have done meanwhile. Never stops anything: the tags are out, and a pin can be written by
 * hand. A resumed run finds what it pinned already and only records it.
 */
final class AssetPins {

    /** How long a run waits for a tag's CI to attach the asset. */
    static final Duration PATIENCE = Duration.ofMinutes(30);

    /** How often it asks. */
    static final Duration EVERY = Duration.ofSeconds(20);

    /** What pinning asks the network, so a test answers instead. */
    interface Fetch {

        /** Whether {@code url} is there now; false when it is not, or the server did not answer. */
        boolean exists(String url);

        /** Saves {@code url}'s body to {@code to}. */
        void download(String url, Path to) throws IOException;

        /**
         * The network. GitHub answers a release asset by a redirect to a signed url, which may refuse a
         * {@code HEAD}: so the redirect itself says the asset is there, and only the download follows it.
         */
        static Fetch real() {
            Duration timeout = Duration.ofSeconds(20);
            HttpClient ask = HttpClient.newBuilder().connectTimeout(timeout)
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            HttpClient get = HttpClient.newBuilder().connectTimeout(timeout)
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            return new Fetch() {
                @Override
                public boolean exists(String url) {
                    try {
                        int status = ask.send(HttpRequest.newBuilder(URI.create(url))
                                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).timeout(timeout).build(),
                                HttpResponse.BodyHandlers.discarding()).statusCode();
                        return status == 200 || status == 301 || status == 302 || status == 307;
                    } catch (IOException | IllegalArgumentException e) {
                        return false;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }

                @Override
                public void download(String url, Path to) throws IOException {
                    try {
                        HttpResponse<Path> response = get.send(HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofMinutes(10)).build(), HttpResponse.BodyHandlers.ofFile(to));
                        if (response.statusCode() != 200) {
                            throw new IOException(url + " answered " + response.statusCode());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted while downloading " + url);
                    }
                }
            };
        }
    }

    private AssetPins() {
    }

    /**
     * Pins every asset of {@code pushed}'s modules the configuration names.
     *
     * @param pushed each module this run pushed, at the version it released
     * @return the umbrella paths of the repositories holding a pin, for the umbrella's pointer commit
     */
    static List<String> pin(Runner runner, Path umbrella, Config config, Map<Module, Version> pushed, Fetch fetch,
                            Sleeper sleeper) {
        List<String> pinned = new ArrayList<>();
        for (AssetPinConfig pin : config.assetPins()) {
            pushed.entrySet().stream().filter(e -> e.getKey().answersTo(pin.module())).findFirst()
                    .ifPresent(e -> {
                        try {
                            one(runner, umbrella, config, pin, e.getKey(), e.getValue(), fetch, sleeper)
                                    .filter(path -> !pinned.contains(path)).ifPresent(pinned::add);
                        } catch (Refusal refusal) {
                            runner.say("    " + pin.file() + " keeps its pin of " + e.getKey() + ": "
                                    + refusal.getMessage());
                        }
                    });
        }
        return pinned;
    }

    /** One pin; the umbrella path of its repository when it holds the pin now. */
    private static Optional<String> one(Runner runner, Path umbrella, Config config, AssetPinConfig pin,
                                        Module module, Version version, Fetch fetch, Sleeper sleeper) {
        String tag = version.tag(config.tagPattern());
        Repo repo = Repo.ofCheckout(module.dir().dir()).orElseThrow(() -> new Refusal(module
                + "'s origin is not on GitHub, which serves the release's " + pin.asset()));
        String url = "https://github.com/" + repo + "/releases/download/" + tag + "/" + pin.asset();
        if (runner.dryRun()) {
            runner.say("    would wait for " + url + " and pin it in " + pin.file()
                    + (pin.bumps().isEmpty() ? "" : ", raising the version of " + String.join(", ", pin.bumps())));
            return Optional.empty();
        }
        Path root = real(umbrella);
        Path file = real(umbrella.resolve(pin.file()));
        Path checkout = Git.line(file.getParent(), "rev-parse", "--show-toplevel").map(Path::of).map(AssetPins::real)
                .orElseThrow(() -> new Refusal("it is in no git repository"));
        if (checkout.equals(root)) {
            throw new Refusal("it is in the umbrella's own repository; a pin's file is in a submodule");
        }
        List<Path> files = new ArrayList<>(List.of(file));
        for (String bump : pin.bumps()) {
            Path bumped = real(umbrella.resolve(bump));
            if (!bumped.startsWith(checkout)) {
                throw new Refusal(bump + " is not in " + checkout.getFileName() + ", the repository of " + pin.file());
            }
            files.add(bumped);
        }
        String rel = root.relativize(checkout).toString().replace('\\', '/');
        // Where notes come from pins, the pin is a note too: without one, a module whose only change is this
        // commit is refused its next release.
        Optional<Path> changelog = Optional.of(checkout.resolve(Changelog.FILE))
                .filter(log -> config.changelog().enabled() && config.changelog().pins() && Files.isRegularFile(log));
        changelog.ifPresent(files::add);

        // Its commit and push must carry the pin only: never the author's own work.
        String branch = config.branch();
        if (!Git.branch(checkout).equals(Optional.of(branch))) {
            throw new Refusal(rel + " is not on " + branch);
        }
        must(runner.git(checkout, "fetch", "-q", "origin", branch), "fetching " + rel);
        List<String> status = new ArrayList<>(List.of("status", "--porcelain", "--"));
        files.forEach(f -> status.add(f.toString()));
        if (!Git.run(checkout, status.toArray(String[]::new)).out().isBlank()) {
            throw new Refusal(rel + " has uncommitted changes to the pin's files");
        }
        if (!Git.run(checkout, "rev-list", "HEAD..origin/" + branch).out().isBlank()
                || !Git.run(checkout, "rev-list", "origin/" + branch + "..HEAD").out().isBlank()) {
            throw new Refusal(rel + " is not where its origin's " + branch + " is: pull or push it first");
        }

        String text = read(file);
        // A JSON pin has fixed fields; an XML one names its elements, and may leave the url out.
        boolean xml = !pin.properties().isEmpty();
        Map<String, String> names = xml ? pin.properties()
                : Map.of("version", "version", "url", "url", "sha256", "sha256");
        BiFunction<String, String, Optional<String>> get = xml ? AssetPins::element : AssetPins::field;
        if (get.apply(text, names.get("version")).equals(Optional.of(version.toString()))
                && (!names.containsKey("url") || get.apply(text, names.get("url")).equals(Optional.of(url)))
                && get.apply(text, names.get("sha256")).filter(hash -> !hash.isBlank()).isPresent()) {
            runner.say("    " + pin.file() + " pins " + module + " " + tag + " already");
            return Optional.of(rel);
        }
        Map<String, String> set = new LinkedHashMap<>();
        set.put("version", version.toString());
        if (names.containsKey("url")) {
            set.put("url", url);
        }
        set.put("sha256", sha256(runner, url, fetch, sleeper));
        String pinned = text;
        for (Map.Entry<String, String> value : set.entrySet()) {
            String name = names.get(value.getKey());
            pinned = xml ? setElement(pinned, name, value.getValue()) : set(pinned, name, value.getValue());
        }
        Map<Path, String> writes = new LinkedHashMap<>();
        writes.put(file, pinned);
        changelog.ifPresent(log -> writes.put(log, PinNotes.with(read(log),
                List.of("- Ships " + pin.asset() + " of " + module + " " + version + "."))));
        for (Path bumped : files.subList(1, 1 + pin.bumps().size())) {
            String json = read(bumped);
            Version raised = field(json, "version").flatMap(Version::parse).orElseThrow(() -> new Refusal(
                    umbrella.relativize(bumped) + " has no \"version\" of the form 1.2.3")).bump(Level.PATCH);
            writes.put(bumped, set(json, "version", raised.toString()));
        }
        runner.say("    " + pin.file() + " pins " + module + " " + tag + ": " + pin.asset() + " sha256 "
                + set.get("sha256"));
        writes.forEach(runner::write);
        List<String> commit = new ArrayList<>(List.of("commit", "-q", "-m",
                "pin " + module + " " + tag + " (" + pin.asset() + ")", "--"));
        files.forEach(f -> commit.add(f.toString()));
        must(runner.git(checkout, commit.toArray(String[]::new)), "the commit in " + rel);
        Proc.Result push = runner.git(checkout, "push", "-q", "origin", branch);
        if (!push.ok()) {
            // Undone, so the next run finds the old pin and writes it again instead of an unpushed commit.
            runner.git(checkout, "reset", "-q", "--keep", "HEAD~1");
            must(push, "the push of " + rel);
        }
        return Optional.of(rel);
    }

    /** Waits for {@code url}, downloads it and hashes it. */
    private static String sha256(Runner runner, String url, Fetch fetch, Sleeper sleeper) {
        runner.say("waiting for " + url + " ...");
        long tries = PATIENCE.dividedBy(EVERY);
        for (long tried = 0; !fetch.exists(url); tried++) {
            if (tried >= tries) {
                throw new Refusal(url + " is not there after " + PATIENCE.toMinutes() + " min");
            }
            if (runner.stopping() || !sleeper.sleep(EVERY)) {
                throw new Refusal("stopped waiting for " + url);
            }
        }
        Path part = null;
        try {
            part = Files.createTempFile("sluice-asset", ".part");
            fetch.download(url, part);
            return sha256(part);
        } catch (IOException e) {
            throw new Refusal(url + " could not be downloaded (" + e.getMessage() + ")");
        } finally {
            if (part != null) {
                try {
                    Files.deleteIfExists(part);
                } catch (IOException ignored) {
                    // A temporary file left behind harms nothing.
                }
            }
        }
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java has SHA-256", e);
        }
    }

    private static Pattern pattern(String field) {
        return Pattern.compile("(\"" + Pattern.quote(field) + "\"\\s*:\\s*)\"([^\"\\\\]*)\"");
    }

    /** {@code field}'s string value in {@code json}; empty when it has none. */
    static Optional<String> field(String json, String field) {
        Matcher matcher = pattern(field).matcher(json);
        return matcher.find() ? Optional.of(matcher.group(2)) : Optional.empty();
    }

    /** {@code json} with {@code field}'s string value {@code value}, the rest as written. */
    static String set(String json, String field, String value) {
        Matcher matcher = pattern(field).matcher(json);
        if (!matcher.find()) {
            throw new Refusal("it has no string field \"" + field + "\"");
        }
        return matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + "\"" + value + "\""));
    }

    /** A comment, matched first so an element inside one is passed over, or {@code <name>value</name>} or {@code <name/>}. */
    private static Optional<MatchResult> found(String xml, String name) {
        String quoted = Pattern.quote(name);
        Pattern pattern = Pattern.compile("(?s)<!--.*?-->|<" + quoted + "\\s*/>|<" + quoted + ">([^<]*)</" + quoted
                + ">");
        return pattern.matcher(xml).results().filter(r -> !r.group().startsWith("<!--")).findFirst();
    }

    /** The text of {@code xml}'s first element {@code name}; empty when it has none. */
    static Optional<String> element(String xml, String name) {
        return found(xml, name).map(r -> r.group(1) == null ? "" : r.group(1));
    }

    /** {@code xml} with the text of its first element {@code name} set to {@code value}, the rest as written. */
    static String setElement(String xml, String name, String value) {
        MatchResult at = found(xml, name).orElseThrow(() -> new Refusal("it has no element <" + name + ">"));
        return xml.substring(0, at.start()) + "<" + name + ">" + value + "</" + name + ">" + xml.substring(at.end());
    }

    /** {@code path} past any link, or as it is when it does not exist yet. */
    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new Refusal(file + " could not be read (" + e.getMessage() + ")");
        }
    }

    private static void must(Proc.Result result, String what) {
        if (!result.ok()) {
            throw new Refusal(what + " failed (exit " + result.exit() + "): " + result.out().strip());
        }
    }
}
