package com.botmaker.sluice;

import com.botmaker.sluice.model.Pin;
import com.botmaker.sluice.model.PinReader;
import com.botmaker.sluice.model.PomReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moves a pom's version and each kind of pin, through {@link Runner}: {@code versions-maven-plugin} for what Maven
 * spells, a text replacement for a text pin and for a {@code <parent>}.
 *
 * <p><b>A parent pin is moved as text, never by {@code versions:update-parent}</b>: every mojo first resolves the
 * parent the pom names now, a snapshot nothing may serve, so the goal fails wherever the local repository lacks
 * that exact snapshot. The same holds of any goal on a pom whose parent is not resolvable, which is why a release
 * moves a parent pin before {@code versions:set} and back after it.
 *
 * <p><b>Every move is read back</b> once it ran: {@code set-property} is silent about a property the pom does not
 * declare, and a release that believes it moved a pin it did not move is the failure worth being loud about.
 */
public final class PinMoves {

    public static final String PLUGIN = "org.codehaus.mojo:versions-maven-plugin:2.18.0";

    private static final Pattern PARENT_VERSION =
            Pattern.compile("(<parent>(?:(?!</parent>)[\\s\\S])*?<version>)\\s*[^<]*?\\s*(</version>)");

    private final Runner runner;
    private final List<String> mavenArgs;

    /** @param mavenArgs arguments every Maven run gets before its goal: {@code -o}, a settings file */
    public PinMoves(Runner runner, List<String> mavenArgs) {
        this.runner = runner;
        this.mavenArgs = List.copyOf(mavenArgs);
    }

    /** The pom's own version, by {@code versions:set}, which moves each module of a multi-module pom with it. */
    public void version(Path pom, String version) {
        mvn(pom, "set", "-DnewVersion=" + version, "-DgenerateBackupPoms=false");
        if (!runner.dryRun()) {
            Optional<String> now = PomReader.read(pom).version().map(String::strip);
            if (!now.equals(Optional.of(version))) {
                throw new Refusal(pom + ": its version did not move to " + version + " (it is "
                        + now.orElse("not written") + ")");
            }
        }
    }

    /** {@code pin} moved to {@code value}, by the goal its kind needs, then read back. */
    public void move(Pin pin, String value) {
        switch (pin) {
            case Pin.Property property -> mvn(property.pom(), "set-property", "-Dproperty=" + property.name(),
                    "-DnewVersion=" + value, "-DgenerateBackupPoms=false");
            case Pin.Literal literal -> mvn(literal.pom(), "use-dep-version",
                    "-Dincludes=" + literal.dep().groupId() + ":" + literal.dep().artifactId(),
                    "-DdepVersion=" + value, "-DforceVersion=true", "-DgenerateBackupPoms=false");
            case Pin.Parent parent ->
                    runner.replace(parent.pom(), PARENT_VERSION, "$1" + Matcher.quoteReplacement(value) + "$2");
            case Pin.Text text -> runner.replaceGroup(text.file(), text.match(), value);
        }
        if (!runner.dryRun()) {
            Optional<String> now = held(pin);
            if (!now.equals(Optional.of(value))) {
                throw new Refusal(pin.file() + ": its pin did not move to " + value + " (it holds "
                        + now.orElse("nothing") + ")");
            }
        }
    }

    /** What {@code pin} holds in its file now; empty when the file or the pin cannot be read. */
    public static Optional<String> held(Pin pin) {
        try {
            return PinReader.value(pin, Files.readString(pin.file())).map(String::strip);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void mvn(Path pom, String goal, String... properties) {
        List<String> argv = new ArrayList<>(List.of("mvn", "-B", "-q"));
        argv.addAll(mavenArgs);
        argv.addAll(List.of("-f", pom.toString(), PLUGIN + ":" + goal));
        argv.addAll(List.of(properties));
        Proc.Result result = runner.run(pom.toAbsolutePath().getParent(), argv, Map.of());
        if (!result.ok()) {
            String errors = String.join("\n", result.out().lines().filter(line -> line.contains("[ERROR]"))
                    .limit(10).toList());
            throw new Refusal(pom + ": versions:" + goal + " failed\n"
                    + (errors.isEmpty() ? result.out().strip() : errors));
        }
    }
}
