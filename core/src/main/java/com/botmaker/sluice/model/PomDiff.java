package com.botmaker.sluice.model;

import com.botmaker.sluice.Refusal;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Whether two texts of one pom differ only by versions a release moves. Every release leaves a back-to-snapshot
 * commit on its tag and moves its dependents' pins; neither is a change to release.
 */
public final class PomDiff {

    private PomDiff() {
    }

    /**
     * The versions of a pom a release writes.
     *
     * @param ownVersion    the project's own {@code <version>}
     * @param parentVersion its {@code <parent>}'s version
     * @param properties    the {@code <properties>} entries
     * @param dependencies  the dependencies and plugins whose literal {@code <version>} it moves
     */
    public record Moved(boolean ownVersion, boolean parentVersion, Set<String> properties,
                        Set<Coordinate> dependencies) {

        public Moved {
            properties = Set.copyOf(properties);
            dependencies = Set.copyOf(dependencies);
        }
    }

    /**
     * Whether {@code before} and {@code after} are the same pom once what {@code moved} names is left out.
     * Comments and layout are left out too. A text that is not a pom is never the same.
     */
    public static boolean sameButVersions(Path pom, String before, String after, Moved moved) {
        try {
            Element was = blanked(pom, before, moved);
            Element is = blanked(pom, after, moved);
            return was.isEqualNode(is);
        } catch (Refusal unreadable) {
            return false;
        }
    }

    private static Element blanked(Path pom, String text, Moved moved) {
        Element project = PomReader.parse(pom, text);
        String ownGroup = PomReader.groupId(project).orElse("");
        if (moved.ownVersion()) {
            PomReader.child(project, "version").ifPresent(PomDiff::blank);
        }
        if (moved.parentVersion()) {
            PomReader.child(project, "parent").flatMap(p -> PomReader.child(p, "version")).ifPresent(PomDiff::blank);
        }
        PomReader.child(project, "properties").ifPresent(p -> PomReader.children(p).stream()
                .filter(e -> moved.properties().contains(PomReader.name(e)))
                .forEach(PomDiff::blank));
        for (String kind : List.of("dependency", "plugin")) {
            String defaultGroup = kind.equals("plugin") ? PomReader.DEFAULT_PLUGIN_GROUP : "";
            NodeList named = project.getElementsByTagNameNS("*", kind);
            for (int i = 0; i < named.getLength(); i++) {
                Element element = (Element) named.item(i);
                String group = PomReader.text(element, "groupId").orElse(defaultGroup);
                Coordinate coordinate = new Coordinate(PomReader.OWN_GROUP.contains(group) ? ownGroup : group,
                        PomReader.text(element, "artifactId").orElse(""));
                if (moved.dependencies().contains(coordinate)) {
                    PomReader.child(element, "version").ifPresent(PomDiff::blank);
                }
            }
        }
        tidy(project);
        return project;
    }

    private static void blank(Element element) {
        element.setTextContent("");
    }

    /** Drops comments and layout, so only what the pom says is compared. */
    private static void tidy(Node node) {
        Node child = node.getFirstChild();
        while (child != null) {
            Node next = child.getNextSibling();
            switch (child.getNodeType()) {
                case Node.COMMENT_NODE -> node.removeChild(child);
                case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                    String text = child.getNodeValue().strip();
                    if (text.isEmpty()) {
                        node.removeChild(child);
                    } else {
                        child.setNodeValue(text);
                    }
                }
                default -> tidy(child);
            }
            child = next;
        }
    }
}
