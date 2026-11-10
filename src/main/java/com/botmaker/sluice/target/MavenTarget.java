package com.botmaker.sluice.target;

import com.botmaker.sluice.Spelling;
import com.botmaker.sluice.model.Coordinate;
import com.botmaker.sluice.model.PomReader;
import com.botmaker.sluice.spi.PublishTarget;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * A Maven repository over HTTP (Central, a Nexus, GitHub Packages): the pin is the bare version, and a release
 * is published once the repository serves its pom. Something else deploys there: the tag's own build.
 */
public final class MavenTarget implements PublishTarget {

    /** The addresses of the repository every Maven knows without being told. */
    private static final Set<String> CENTRAL = Set.of("https://repo.maven.apache.org/maven2",
            "https://repo1.maven.org/maven2");

    private final String url;
    private final Http http;

    MavenTarget(String url, Http http) {
        this.url = PomReader.withoutSlash(url);
        this.http = http;
    }

    /** Where a Maven repository keeps the pom of {@code coordinate} at {@code version}, from its root. */
    static String pomPath(Coordinate coordinate, String version) {
        return coordinate.groupId().replace('.', '/') + "/" + coordinate.artifactId() + "/" + version + "/"
                + coordinate.artifactId() + "-" + version + ".pom";
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
        return CENTRAL.contains(url) ? Optional.empty() : Optional.of(url);
    }

    @Override
    public Served serves(Coordinate coordinate, String version) {
        return served(http.head(url + "/" + pomPath(coordinate, version)));
    }

    /** What a status says: only a plain "not found" is a no; a refusal or an error says nothing. */
    static Served served(Optional<Integer> status) {
        if (status.isEmpty()) {
            return Served.UNREACHABLE;
        }
        return switch (status.get()) {
            case 200 -> Served.YES;
            case 404 -> Served.NO;
            default -> Served.UNREACHABLE;
        };
    }

    /** Central takes its time to show a release its portal has accepted. */
    @Override
    public Duration patience() {
        return Duration.ofMinutes(30);
    }
}
