// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.config;

import com.botmaker.sluice.Spelling;
import com.botmaker.sluice.config.Config.ModuleConfig;
import com.botmaker.sluice.config.Config.StepPoint;
import org.junit.jupiter.api.Test;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code sluice.schema.json}, the editors' JSON Schema of {@code sluice.yml} (SchemaStore), says what
 * {@link ConfigLoader} reads: each map's keys are the ones its refusal of an unknown key lists, each closed set's
 * values are the ones it accepts, and each default is {@link Config#defaults()}'s.
 */
class SchemaTest {

    /** The repository's root file: the test runs in {@code core/}. */
    private static final Map<String, Object> SCHEMA = load();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() {
        try {
            return (Map<String, Object>) new Load(LoadSettings.builder().build())
                    .loadFromString(Files.readString(Path.of("..", "sluice.schema.json")));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void everyMapHasTheLoadersKeys() {
        Map<String, String> places = Map.ofEntries(
                Map.entry("zz: 1", "#"),
                Map.entry("relevance: {zz: 1}", "#/properties/relevance"),
                Map.entry("modules: {a: {zz: 1}}", "#/definitions/module"),
                Map.entry("modules: {a: {place: {zz: 1}}}", "#/definitions/module/properties/place"),
                Map.entry("target: {zz: 1}", "#/definitions/target"),
                Map.entry("target: {jitpack: {zz: 1}}", "#/definitions/target/oneOf/2/properties/jitpack"),
                Map.entry("text-pins: [{zz: 1}]", "#/definitions/textPin"),
                Map.entry("edges: [{zz: 1}]", "#/definitions/edge"),
                Map.entry("changelog: {zz: 1}", "#/properties/changelog"),
                Map.entry("gates: [{zz: 1}]", "#/definitions/gate"),
                Map.entry("gates: [{name: g, run: {zz: 1}}]", "#/definitions/command"),
                Map.entry("steps: [{zz: 1}]", "#/definitions/step"),
                Map.entry("asset-pins: [{zz: 1}]", "#/definitions/assetPin"));
        places.forEach((yaml, pointer) -> assertEquals(known(yaml), keys(at(pointer)), yaml));
    }

    @Test
    void everyClosedSetHasTheLoadersValues() {
        assertEquals(ids(ModuleConfig.Kind.values(), ModuleConfig.Kind::id),
                values("#/definitions/module/properties/kind"));
        assertEquals(ids(StepPoint.values(), StepPoint::id), values("#/definitions/step/properties/at"));
        assertEquals(ids(Spelling.values(), Spelling::id), values("#/definitions/textPin/properties/spelled"));
        assertEquals(ids(Owed.values(), Owed::id), values("#/properties/owed"));
        accepted("#/properties/owed", v -> "owed: " + v);

        accepted("#/definitions/target/oneOf/0", v -> "target: " + v);
        accepted("#/definitions/module/properties/place/oneOf/0", v -> "modules: {a: {place: " + v + "}}");
        accepted("#/definitions/module/properties/kind", v -> "modules: {a: {kind: " + v + "}}");
        accepted("#/definitions/step/properties/at", v -> "steps: [{at: " + v + ", run: x}]");
        accepted("#/definitions/textPin/properties/spelled",
                v -> "text-pins: [{in: f, pins: m, match: x, spelled: " + v + "}]");
    }

    @Test
    void defaultsAreTheLoaders() {
        Config defaults = Config.defaults();
        assertEquals(defaults.branch(), at("#/properties/branch").get("default"));
        assertEquals(defaults.tagPattern(), at("#/properties/tag").get("default"));
        assertEquals(defaults.relevanceIgnore(), at("#/properties/relevance/properties/ignore").get("default"));
        assertEquals(defaults.githubReleases(), at("#/properties/github-releases").get("default"));
        assertEquals(defaults.owed().id(), at("#/properties/owed").get("default"));
    }

    /** The keys the loader's refusal of the unknown key {@code zz} in {@code yaml} lists. */
    private static Set<String> known(String yaml) {
        String marker = "known here: ";
        String message = ConfigLoader.check(yaml, "t").stream().map(ConfigProblem::message)
                .filter(m -> m.contains("zz is not a key"))
                .findFirst().orElseThrow(() -> new AssertionError(yaml + ": no refusal of zz"));
        String list = message.substring(message.indexOf(marker) + marker.length(), message.lastIndexOf(')'));
        return Arrays.stream(list.split(", ")).collect(Collectors.toCollection(TreeSet::new));
    }

    /** The keys a value at {@code node} may have: its own properties and every branch's. */
    private static Set<String> keys(Map<String, Object> node) {
        Set<String> keys = new TreeSet<>();
        if (node.get("properties") instanceof Map<?, ?> properties) {
            properties.keySet().forEach(key -> keys.add((String) key));
        }
        if (node.get("oneOf") instanceof List<?> branches) {
            branches.forEach(branch -> keys.addAll(keys(resolve(branch))));
        }
        return keys;
    }

    private static Set<String> values(String pointer) {
        return ((List<?>) at(pointer).get("enum")).stream().map(String.class::cast)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static <E> Set<String> ids(E[] constants, Function<E, String> id) {
        return Stream.of(constants).map(id).collect(Collectors.toCollection(TreeSet::new));
    }

    private static void accepted(String pointer, Function<String, String> yaml) {
        values(pointer).forEach(value -> assertTrue(ConfigLoader.check(yaml.apply(value), "t").isEmpty(),
                yaml.apply(value)));
    }

    /** The schema node at the JSON pointer {@code pointer}, its {@code $ref} followed. */
    private static Map<String, Object> at(String pointer) {
        Object node = SCHEMA;
        for (String part : pointer.substring(1).split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            node = node instanceof List<?> list ? list.get(Integer.parseInt(part)) : resolve(node).get(part);
        }
        return resolve(node);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolve(Object node) {
        Map<String, Object> map = (Map<String, Object>) node;
        return map.get("$ref") instanceof String ref ? at(ref) : map;
    }
}
