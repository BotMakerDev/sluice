package com.botmaker.sluice.target;

import com.botmaker.sluice.gate.GateVerdict;
import com.botmaker.sluice.model.Coordinate;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.model.PomFile;
import com.botmaker.sluice.spi.Gate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every Maven plugin a module pins runs on the Maven its publish target builds with. Maven refuses to execute a
 * plugin whose {@code <prerequisites><maven>} exceeds its own version, before anything is published, so the tag
 * resolves to nothing; locally it builds, because a developer's Maven is newer.
 *
 * <p>The prerequisite is read from the plugin's own pom, never from a table here. <b>One that cannot be read is
 * said, never refused</b>; a plugin whose pom declares none is not a finding.
 */
final class MavenPrerequisite {

    static final String CENTRAL = "https://repo.maven.apache.org/maven2/";

    private static final Pattern NUMERIC = Pattern.compile("\\d+(\\.\\d+)*");
    private static final Pattern PREREQUISITE =
            Pattern.compile("<prerequisites>.*?<maven>(.*?)</maven>", Pattern.DOTALL);
    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    /** What one pinned plugin demands of the Maven that runs it. */
    sealed interface Needs {

        /** The plugin as a line names it: {@code maven-compiler-plugin:3.13.0}. */
        String plugin();

        /** Its pom demands {@code maven}. */
        record Demands(String plugin, String maven) implements Needs {
        }

        /** Its pom demands nothing. */
        record Nothing(String plugin) implements Needs {
        }

        /** Not known, for the reason {@code why}. */
        record Unknown(String plugin, String why) implements Needs {
        }
    }

    private MavenPrerequisite() {
    }

    /**
     * @param poms the text of a plugin's pom by its path in a Maven repository; empty when it cannot be read
     */
    static List<GateVerdict> check(Module module, String ceiling, Function<String, Optional<String>> poms,
                                   Gate as) {
        if (module.pom().isEmpty()) {
            return List.of();
        }
        List<Needs> needs = read(module.pom().get(), poms);
        List<Needs.Demands> tooNew = needs.stream()
                .filter(Needs.Demands.class::isInstance).map(Needs.Demands.class::cast)
                .filter(demands -> exceeds(demands.maven(), ceiling)).toList();
        List<Needs.Unknown> unknown = needs.stream()
                .filter(Needs.Unknown.class::isInstance).map(Needs.Unknown.class::cast).toList();
        List<GateVerdict> verdicts = new ArrayList<>();
        if (!tooNew.isEmpty()) {
            verdicts.add(as.refused(module + ": a pinned Maven plugin cannot run on its build's Maven " + ceiling,
                    String.join("\n", tooNew.stream()
                            .map(need -> "     " + need.plugin() + " requires Maven " + need.maven()).toList())
                            + "\n     Maven refuses such a plugin before publishing anything, so the tag would "
                            + "resolve to nothing.\n     Pin an older release of it."));
        }
        if (!unknown.isEmpty()) {
            verdicts.add(as.skipped(module + ": " + String.join(", ", unknown.stream()
                    .map(need -> need.plugin() + " (" + need.why() + ")").toList()) + ", prerequisite not checked"));
        }
        if (verdicts.isEmpty() && !needs.isEmpty()) {
            verdicts.add(as.ok(module + ": every Maven plugin its pom pins runs on Maven " + ceiling));
        }
        return verdicts;
    }

    /** What each plugin the pom pins demands. */
    static List<Needs> read(PomFile pom, Function<String, Optional<String>> poms) {
        List<Needs> needs = new ArrayList<>();
        for (PomFile.Reference reference : pom.references()) {
            if (reference.where() != PomFile.Where.PLUGIN || reference.version().isEmpty()) {
                continue;
            }
            String written = reference.version().get().strip();
            Optional<String> version = resolved(written, pom);
            needs.add(version.isPresent()
                    ? needs(reference.coordinate(), version.get(), poms)
                    : new Needs.Unknown(reference.coordinate().artifactId() + ":" + written,
                            "its version is not one this pom spells"));
        }
        return List.copyOf(needs);
    }

    /** A version with its pom's own properties filled in; empty when one comes from elsewhere. */
    private static Optional<String> resolved(String version, PomFile pom) {
        Matcher property = PROPERTY.matcher(version);
        if (!property.matches()) {
            return version.contains("${") ? Optional.empty() : Optional.of(version);
        }
        return Optional.ofNullable(pom.properties().get(property.group(1))).map(String::strip)
                .filter(value -> !value.contains("${"));
    }

    private static Needs needs(Coordinate plugin, String version, Function<String, Optional<String>> poms) {
        String named = plugin.artifactId() + ":" + version;
        Optional<String> text = poms.apply(MavenTarget.pomPath(plugin, version));
        if (text.isEmpty()) {
            return new Needs.Unknown(named, "its pom could not be read");
        }
        Matcher found = PREREQUISITE.matcher(text.get());
        if (!found.find()) {
            return new Needs.Nothing(named);
        }
        String value = found.group(1).strip();
        Matcher property = PROPERTY.matcher(value);
        if (property.matches()) {
            value = tag(text.get(), property.group(1));
        }
        return NUMERIC.matcher(value).matches()
                ? new Needs.Demands(named, value)
                : new Needs.Unknown(named, "its prerequisite '" + value + "' is not a version");
    }

    /** Whether {@code needs} is a later Maven than {@code ceiling}, part by part as numbers. */
    static boolean exceeds(String needs, String ceiling) {
        String[] want = needs.split("\\.");
        String[] limit = ceiling.split("\\.");
        for (int i = 0; i < Math.max(want.length, limit.length); i++) {
            int a = i < want.length ? Integer.parseInt(want[i]) : 0;
            int b = i < limit.length ? Integer.parseInt(limit[i]) : 0;
            if (a != b) {
                return a > b;
            }
        }
        return false;
    }

    private static String tag(String xml, String name) {
        Matcher matcher = Pattern.compile("<" + Pattern.quote(name) + ">([^<]*)</" + Pattern.quote(name) + ">")
                .matcher(xml);
        return matcher.find() ? matcher.group(1).strip() : "";
    }
}
