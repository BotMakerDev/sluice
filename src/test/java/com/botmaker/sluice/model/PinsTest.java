package com.botmaker.sluice.model;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.testing.PlainUmbrella;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.botmaker.sluice.testing.PlainUmbrella.dep;
import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinsTest {

    @TempDir
    Path tmp;

    private Edge onlyEdge(PlainUmbrella umbrella) {
        List<Edge> edges = Graph.build(umbrella.layout(), Config.defaults()).edges();
        assertEquals(1, edges.size(), edges.toString());
        return edges.getFirst();
    }

    private Path pomOf(String module) {
        return tmp.resolve(module).resolve("pom.xml").toAbsolutePath().normalize();
    }

    @Test
    void propertyPinFound() {
        PlainUmbrella umbrella = new PlainUmbrella(tmp)
                .module("a", pom("a", "1.0.0-SNAPSHOT", ""))
                .module("b", pom("b", "1.0.0-SNAPSHOT",
                        "  <properties><a.version>1.0.0-SNAPSHOT</a.version></properties>\n"
                                + dep("a", "${a.version}")));

        Edge edge = onlyEdge(umbrella);

        assertEquals("a", edge.upstream().name());
        assertEquals("b", edge.downstream().name());
        assertEquals(List.of(new Pin.Property(pomOf("b"), "a.version")), edge.pins());
    }

    @Test
    void literalPinFound() {
        PlainUmbrella umbrella = new PlainUmbrella(tmp)
                .module("a", pom("a", "1.0.0-SNAPSHOT", ""))
                .module("b", pom("b", "1.0.0-SNAPSHOT", dep("a", "1.0.0-SNAPSHOT")));

        assertEquals(List.of(new Pin.Literal(pomOf("b"), new Coordinate("g", "a"))), onlyEdge(umbrella).pins());
    }

    @Test
    void parentPinFound() {
        PlainUmbrella umbrella = new PlainUmbrella(tmp)
                .module("parent", pom("parent", "1.0.0-SNAPSHOT", "  <packaging>pom</packaging>\n"))
                .module("child", "<project><modelVersion>4.0.0</modelVersion>"
                        + "<parent><groupId>g</groupId><artifactId>parent</artifactId><version>1.0.0-SNAPSHOT</version>"
                        + "<relativePath>../parent/pom.xml</relativePath></parent>"
                        + "<artifactId>child</artifactId><version>2.0.0-SNAPSHOT</version></project>");

        Edge edge = onlyEdge(umbrella);

        assertEquals("parent", edge.upstream().name());
        assertEquals(List.of(new Pin.Parent(pomOf("child"))), edge.pins());
    }

    @Test
    void propertyDeclaredInParentIsMovedThere() {
        PlainUmbrella umbrella = new PlainUmbrella(tmp)
                .module("a", pom("a", "1.0.0-SNAPSHOT", ""))
                .module("b", "<project><modelVersion>4.0.0</modelVersion>"
                        + "<parent><groupId>g</groupId><artifactId>b-parent</artifactId><version>1</version>"
                        + "<relativePath>build/pom.xml</relativePath></parent>"
                        + "<artifactId>b</artifactId><version>1.0.0-SNAPSHOT</version>"
                        + dep("a", "${a.version}") + "</project>")
                .file("b/build/pom.xml", pom("b-parent", "1",
                        "  <packaging>pom</packaging>\n  <properties><a.version>1.0.0</a.version></properties>\n"));

        Edge edge = onlyEdge(umbrella);

        Path parent = tmp.resolve("b/build/pom.xml").toAbsolutePath().normalize();
        assertEquals(List.of(new Pin.Property(parent, "a.version")), edge.pins());
    }

    @Test
    void propertyDeclaredOutsideEveryModuleRefused() {
        PlainUmbrella umbrella = new PlainUmbrella(tmp)
                .file("pom.xml", pom("root", "1", "  <properties><a.version>1.0.0</a.version></properties>\n"))
                .module("a", pom("a", "1.0.0-SNAPSHOT", ""))
                .module("b", "<project><modelVersion>4.0.0</modelVersion>"
                        + "<parent><groupId>g</groupId><artifactId>root</artifactId><version>1</version></parent>"
                        + "<artifactId>b</artifactId><version>1.0.0-SNAPSHOT</version>"
                        + dep("a", "${a.version}") + "</project>");

        Refusal refusal = assertThrows(Refusal.class, () -> Graph.build(umbrella.layout(), Config.defaults()));

        assertTrue(refusal.getMessage().contains("in no module"), refusal.getMessage());
    }

    @Test
    void onePropertyPinningTwoUpstreamsRefused() {
        PlainUmbrella umbrella = new PlainUmbrella(tmp)
                .module("a", pom("a", "1.0.0-SNAPSHOT", ""))
                .module("c", pom("c", "1.0.0-SNAPSHOT", ""))
                .module("b", pom("b", "1.0.0-SNAPSHOT",
                        "  <properties><shared.version>1.0.0</shared.version></properties>\n"
                                + "  <dependencies>"
                                + "<dependency><groupId>g</groupId><artifactId>a</artifactId><version>${shared.version}</version></dependency>"
                                + "<dependency><groupId>g</groupId><artifactId>c</artifactId><version>${shared.version}</version></dependency>"
                                + "</dependencies>\n"));

        Refusal refusal = assertThrows(Refusal.class, () -> Graph.build(umbrella.layout(), Config.defaults()));

        assertTrue(refusal.getMessage().contains("shared.version"), refusal.getMessage());
    }
}
