package com.botmaker.sluice.model;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.model.PomFile.Parent;
import com.botmaker.sluice.model.PomFile.Reference;
import com.botmaker.sluice.model.PomFile.Where;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads a pom's raw text through the JDK's DOM: its coordinate, parent, properties and every versioned coordinate
 * it names (dependencies, managed dependencies, plugins and their dependencies, in {@code <build>} and its
 * {@code <pluginManagement>}). Profiles are not read: a pin inside one is not one sluice moves.
 */
public final class PomReader {

    /** The groupId Maven gives a plugin that names none. */
    static final String DEFAULT_PLUGIN_GROUP = "org.apache.maven.plugins";

    /** The spellings of a pom's own groupId. */
    private static final Set<String> OWN_GROUP = Set.of("${project.groupId}", "${pom.groupId}");

    private PomReader() {
    }

    /** The pom at {@code pom}, refused when it is not a readable pom. */
    public static PomFile read(Path pom) {
        Path file = pom.toAbsolutePath().normalize();
        Element project = parse(file);
        if (!"project".equals(name(project))) {
            throw new Refusal(file + ": not a pom, its root element is <" + name(project) + ">");
        }
        Optional<Parent> parent = child(project, "parent").map(p -> new Parent(
                new Coordinate(text(p, "groupId").orElse(""), text(p, "artifactId").orElse("")),
                text(p, "version").orElse(""),
                child(p, "relativePath").map(r -> r.getTextContent().strip()).orElse(Parent.DEFAULT_PATH)));
        String groupId = text(project, "groupId")
                .or(() -> parent.map(p -> p.coordinate().groupId()))
                .orElseThrow(() -> new Refusal(file + ": no <groupId>, and no <parent> to inherit one from"));
        String artifactId = text(project, "artifactId")
                .orElseThrow(() -> new Refusal(file + ": no <artifactId>"));

        List<Reference> found = new ArrayList<>();
        child(project, "dependencies").ifPresent(d -> dependencies(d, Where.DEPENDENCY, found));
        child(project, "dependencyManagement").flatMap(m -> child(m, "dependencies"))
                .ifPresent(d -> dependencies(d, Where.MANAGED, found));
        child(project, "build").ifPresent(build -> {
            child(build, "plugins").ifPresent(p -> plugins(p, found));
            child(build, "pluginManagement").flatMap(m -> child(m, "plugins")).ifPresent(p -> plugins(p, found));
        });
        // A sibling named by ${project.groupId} is still a sibling: only the groupId is resolved, never a version.
        List<Reference> references = found.stream().map(r -> OWN_GROUP.contains(r.coordinate().groupId())
                ? new Reference(new Coordinate(groupId, r.coordinate().artifactId()), r.version(), r.where())
                : r).toList();

        Map<String, String> properties = new LinkedHashMap<>();
        child(project, "properties").ifPresent(p -> children(p).forEach(e -> properties.put(name(e), e.getTextContent().strip())));
        return new PomFile(file, new Coordinate(groupId, artifactId), text(project, "version"), parent, references,
                properties);
    }

    private static void dependencies(Element list, Where where, List<Reference> into) {
        for (Element dependency : children(list)) {
            if ("dependency".equals(name(dependency))) {
                into.add(new Reference(new Coordinate(text(dependency, "groupId").orElse(""),
                        text(dependency, "artifactId").orElse("")), text(dependency, "version"), where));
            }
        }
    }

    private static void plugins(Element list, List<Reference> into) {
        for (Element plugin : children(list)) {
            if (!"plugin".equals(name(plugin))) {
                continue;
            }
            into.add(new Reference(new Coordinate(text(plugin, "groupId").orElse(DEFAULT_PLUGIN_GROUP),
                    text(plugin, "artifactId").orElse("")), text(plugin, "version"), Where.PLUGIN));
            child(plugin, "dependencies").ifPresent(d -> dependencies(d, Where.PLUGIN_DEPENDENCY, into));
        }
    }

    private static Element parse(Path file) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(file.toFile()).getDocumentElement();
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new Refusal(file + ": not a readable pom (" + e.getMessage() + ")");
        }
    }

    private static String name(Element element) {
        return element.getLocalName() != null ? element.getLocalName() : element.getNodeName();
    }

    private static List<Element> children(Element parent) {
        List<Element> elements = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    private static Optional<Element> child(Element parent, String name) {
        return children(parent).stream().filter(e -> name.equals(name(e))).findFirst();
    }

    private static Optional<String> text(Element parent, String name) {
        return child(parent, name).map(e -> e.getTextContent().strip()).filter(s -> !s.isEmpty());
    }
}
