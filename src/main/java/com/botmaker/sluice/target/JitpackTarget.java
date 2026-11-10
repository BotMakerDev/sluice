package com.botmaker.sluice.target;

import com.botmaker.sluice.Spelling;
import com.botmaker.sluice.gate.GateVerdict;
import com.botmaker.sluice.model.Coordinate;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.spi.Gate;
import com.botmaker.sluice.spi.GateContext;
import com.botmaker.sluice.spi.PublishTarget;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JitPack: it builds a tag the first time anyone asks for it, and the pin is the tag's name.
 *
 * <p><b>A build result is cached per tag.</b> A tag JitPack built {@code Error} is broken for good and only a new
 * tag repairs it, which is why a release waits for an upstream's build before tagging what resolves it (a
 * dependent tagged seconds after its upstream starts a build that cannot find the upstream yet), why the wait
 * reads the build API on every try (a build that failed in its first minute is not waited on for ten), and why
 * its gates look for what would fail a build before anything is tagged.
 */
public final class JitpackTarget implements PublishTarget {

    /** The Maven a build runs when its {@code jitpack.yml} installs none. */
    public static final String OWN_MAVEN = "3.6.1";

    static final String SITE = "https://jitpack.io/";
    private static final String RATE_LIMITED = "429 Too Many Requests";
    /**
     * Each way a build log words a 429: Maven 3.6's wagon, Maven 3.9's resolver ({@code status code: 429}),
     * curl over HTTP/2 ({@code returned error: 429}).
     */
    private static final Pattern RATE_LIMIT =
            Pattern.compile("429 Too Many Requests|status code: 429|Too Many Requests \\(429\\)|error: 429\\b");
    private static final Pattern LOG_PATH = Pattern.compile("See the log: (\\S+/build\\.log)");

    private final Optional<String> maven;
    private final Http http;
    private final Path localRepository;
    private final Map<String, Optional<String>> pluginPoms = new ConcurrentHashMap<>();

    /**
     * @param maven           the Maven each module's {@code jitpack.yml} installs; empty for JitPack's own
     * @param localRepository where a plugin's pom is read before asking Maven Central for it
     */
    JitpackTarget(Optional<String> maven, Http http, Path localRepository) {
        this.maven = maven;
        this.http = http;
        this.localRepository = localRepository;
    }

    /** The pom a consumer downloads: its presence is what "built" means. */
    static String pomUrl(Coordinate coordinate, String tag) {
        return SITE + MavenTarget.pomPath(coordinate, tag);
    }

    /** The endpoint that says how a build went, and starts one nobody had asked for. */
    static String buildUrl(Coordinate coordinate, String tag) {
        return SITE + "api/builds/" + coordinate.groupId() + "/" + coordinate.artifactId() + "/" + tag;
    }

    @Override
    public String name() {
        return "jitpack";
    }

    @Override
    public boolean publishes() {
        return true;
    }

    @Override
    public Spelling spelling() {
        return Spelling.TAG;
    }

    @Override
    public Optional<String> repository() {
        return Optional.of("https://jitpack.io");
    }

    @Override
    public Served serves(Coordinate coordinate, String version) {
        return MavenTarget.served(http.head(pomUrl(coordinate, version)));
    }

    @Override
    public Optional<String> failure(Coordinate coordinate, String version) {
        return http.get(buildUrl(coordinate, version)).flatMap(JitpackTarget::failureIn)
                .map(message -> withCause(message, logPath(message).flatMap(path -> http.get(SITE + path))));
    }

    @Override
    public List<GateVerdict> check(GateContext ctx, Module module, Gate as) {
        List<GateVerdict> verdicts = new ArrayList<>(JitpackScript.check(module, as));
        verdicts.addAll(MavenPrerequisite.check(module, maven.orElse(OWN_MAVEN), this::pluginPom, as));
        return verdicts;
    }

    /**
     * A plugin's pom by its path in a Maven repository: the local repository's copy, else Central's. Each is read
     * once a run, though every module pins the same few.
     */
    private Optional<String> pluginPom(String path) {
        return pluginPoms.computeIfAbsent(path, p -> local(localRepository.resolve(p))
                .or(() -> http.get(MavenPrerequisite.CENTRAL + p)));
    }

    private static Optional<String> local(Path pom) {
        try {
            return Files.isRegularFile(pom) ? Optional.of(Files.readString(pom)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** The message of a build-API answer whose {@code status} is {@code Error}; it names the build log. */
    static Optional<String> failureIn(String json) {
        try {
            if (new Load(LoadSettings.builder().build()).loadFromString(json) instanceof Map<?, ?> answer
                    && answer.get("status") instanceof String status && status.equalsIgnoreCase("error")) {
                String message = answer.get("message") instanceof String text ? text.strip() : "";
                return Optional.of(message.isEmpty() ? "the build failed" : message);
            }
        } catch (RuntimeException unreadable) {
            // An answer that is not what JitPack writes says nothing about the build.
        }
        return Optional.empty();
    }

    /** The build log a failure message names ({@code See the log: com/github/…/build.log}), from the site. */
    static Optional<String> logPath(String message) {
        Matcher log = LOG_PATH.matcher(message);
        return log.find() ? Optional.of(log.group(1)) : Optional.empty();
    }

    /**
     * The message, with the cause said when the log shows Maven Central's rate limit, which is nothing in the
     * module and reads like something in it.
     */
    static String withCause(String message, Optional<String> log) {
        return log.filter(text -> RATE_LIMIT.matcher(text).find()).isPresent()
                ? message + " (the log shows Maven Central's " + RATE_LIMITED + ": a rate limit on JitPack's "
                        + "builders, not this module; its next patch repairs the tag)"
                : message;
    }
}
