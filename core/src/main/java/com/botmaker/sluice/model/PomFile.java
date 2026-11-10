package com.botmaker.sluice.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A pom as written, never resolved: every version is the raw text of its element, so {@code ${x.version}} stays
 * {@code ${x.version}}. A pin is that text, and moving it means knowing which text it is.
 *
 * @param file        the pom, absolute and normalized
 * @param coordinate  its own coordinate, the groupId inherited from its parent when it has none
 * @param version     its own {@code <version>}; empty when inherited from its parent
 * @param parent      its {@code <parent>}
 * @param references  every coordinate it names with a version that sluice may have to move
 * @param properties  its {@code <properties>}, in the order written
 */
public record PomFile(Path file, Coordinate coordinate, Optional<String> version, Optional<Parent> parent,
                      List<Reference> references, Map<String, String> properties) {

    public PomFile {
        references = List.copyOf(references);
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    /**
     * A pom's {@code <parent>}.
     *
     * @param relativePath as written, {@link #DEFAULT_PATH} when absent, empty for {@code <relativePath/>}
     */
    public record Parent(Coordinate coordinate, String version, String relativePath) {

        /** Maven's default when a pom gives no {@code <relativePath>}. */
        public static final String DEFAULT_PATH = "../pom.xml";

        /** The file the parent would be read from beside {@code pom}, when the pom names one. */
        public Optional<Path> file(Path pom) {
            if (relativePath.isEmpty()) {
                return Optional.empty();
            }
            Path path = pom.getParent().resolve(relativePath);
            return Optional.of((Files.isDirectory(path) ? path.resolve("pom.xml") : path).toAbsolutePath().normalize());
        }
    }

    /** A coordinate a pom names, with the raw text of its version (empty when it gives none). */
    public record Reference(Coordinate coordinate, Optional<String> version, Where where) {
    }

    /** Where in a pom a reference sits. */
    public enum Where {
        DEPENDENCY("a dependency"),
        MANAGED("a managed dependency"),
        /** A pom {@code <dependencyManagement>} imports ({@code <scope>import</scope>}): fetched by this build. */
        IMPORTED("an imported bom"),
        PLUGIN("a plugin"),
        PLUGIN_DEPENDENCY("a plugin's dependency"),
        /** An {@code <artifactItem>} of a plugin's configuration, as {@code maven-dependency-plugin} copies one. */
        ARTIFACT_ITEM("an artifact a plugin copies");

        private final String spelling;

        Where(String spelling) {
            this.spelling = spelling;
        }

        /** How a sentence names it: "a managed dependency". */
        public String spelling() {
            return spelling;
        }
    }
}
