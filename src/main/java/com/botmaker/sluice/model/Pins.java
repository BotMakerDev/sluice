package com.botmaker.sluice.model;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.model.PomFile.Parent;
import com.botmaker.sluice.model.PomFile.Reference;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The edges the poms say: every place a module's pom names another module's coordinate, and the pin that moves it.
 * One edge per reference here; {@link Graph} merges them.
 */
final class Pins {

    /** A version that is exactly one property. */
    private static final Pattern PROPERTY = Pattern.compile("^\\$\\{([^}]+)}$");

    private final List<Module> modules;
    private final Map<Path, PomFile> read = new LinkedHashMap<>();

    private Pins(List<Module> modules) {
        this.modules = modules;
        modules.forEach(m -> m.pom().ifPresent(p -> read.put(p.file(), p)));
    }

    /** Every edge {@code modules}' poms say, in the order found. */
    static List<Edge> of(List<Module> modules) {
        Pins pins = new Pins(modules);
        pins.refuseSharedProperties();
        List<Edge> edges = new ArrayList<>();
        for (Module down : modules) {
            down.pom().ifPresent(pom -> pins.edges(down, pom, edges));
        }
        return edges;
    }

    private void edges(Module down, PomFile pom, List<Edge> into) {
        pom.parent().ifPresent(parent -> byCoordinate(parent.coordinate()).filter(up -> up != down)
                .ifPresent(up -> into.add(new Edge(up, down, List.of(new Pin.Parent(pom.file())), "its parent"))));
        for (Reference reference : pom.references()) {
            Optional<Module> found = byCoordinate(reference.coordinate()).filter(up -> up != down);
            if (found.isEmpty()) {
                continue;
            }
            Module up = found.get();
            String where = reference.where().spelling();
            if (reference.version().isEmpty()) {
                into.add(new Edge(up, down, List.of(), where + ", its version from its parent"));
                continue;
            }
            String version = reference.version().get();
            Matcher property = PROPERTY.matcher(version);
            if (property.matches()) {
                into.add(propertyEdge(up, down, pom, property.group(1), where));
            } else if (version.contains("${")) {
                throw new Refusal(pom.file() + ": " + where + " on " + up + " has the version " + version
                        + "; sluice moves a literal version or exactly one ${property}");
            } else {
                into.add(new Edge(up, down, List.of(new Pin.Literal(pom.file(), reference.coordinate())), where));
            }
        }
    }

    private Edge propertyEdge(Module up, Module down, PomFile pom, String name, String where) {
        if (name.startsWith("project.") || name.startsWith("pom.")) {
            throw new Refusal(pom.file() + ": " + where + " on " + up + " has the version ${" + name
                    + "}, a property Maven sets itself; sluice moves a literal version or a declared property");
        }
        PomFile declaring = declaring(pom, name).orElseThrow(() -> new Refusal(pom.file() + ": " + where + " on "
                + up + " has the version ${" + name + "}, which no pom of the project declares"));
        Optional<Module> owner = owner(declaring.file());
        if (owner.isEmpty()) {
            throw new Refusal(pom.file() + ": " + where + " on " + up + " has the version ${" + name
                    + "}, declared in " + declaring.file() + ", which is in no module sluice releases");
        }
        if (owner.get() != down) {
            return new Edge(up, down, List.of(), where + ", its version ${" + name + "} from " + owner.get());
        }
        return new Edge(up, down, List.of(new Pin.Property(declaring.file(), name)), where);
    }

    /**
     * A property pinning a module must pin nothing else: moving it to the module's release would move the other
     * coordinate too.
     */
    private void refuseSharedProperties() {
        Map<Pin.Property, Set<Coordinate>> uses = new LinkedHashMap<>();
        for (PomFile pom : List.copyOf(read.values())) {
            for (Reference reference : pom.references()) {
                Matcher property = PROPERTY.matcher(reference.version().orElse(""));
                if (property.matches()) {
                    String name = property.group(1);
                    declaring(pom, name).ifPresent(d -> uses.computeIfAbsent(new Pin.Property(d.file(), name),
                            k -> new LinkedHashSet<>()).add(reference.coordinate()));
                }
            }
        }
        uses.forEach((property, coordinates) -> {
            if (coordinates.size() > 1 && coordinates.stream().anyMatch(c -> byCoordinate(c).isPresent())) {
                throw new Refusal(property.pom() + ": the property " + property.name() + " is the version of "
                        + coordinates.stream().map(Coordinate::toString).toList()
                        + "; give each module its own property, so its release moves only it");
            }
        });
    }

    /** The pom declaring {@code name} for {@code pom}: itself, else up its parents. */
    private Optional<PomFile> declaring(PomFile pom, String name) {
        Set<Path> seen = new HashSet<>();
        Optional<PomFile> current = Optional.of(pom);
        while (current.isPresent() && seen.add(current.get().file())) {
            PomFile here = current.get();
            if (here.properties().containsKey(name)) {
                return current;
            }
            current = here.parent().flatMap(parent -> parentPom(here, parent));
        }
        return Optional.empty();
    }

    /** The parent beside the pom when its coordinate matches, else the module that has it. */
    private Optional<PomFile> parentPom(PomFile child, Parent parent) {
        Optional<PomFile> beside = parent.file(child.file()).filter(Files::isRegularFile).flatMap(this::readOnce)
                .filter(p -> p.coordinate().equals(parent.coordinate()));
        return beside.or(() -> byCoordinate(parent.coordinate()).flatMap(Module::pom));
    }

    /** The pom at {@code file}, read once; empty when it is not one, as a stray file at a relativePath may be. */
    private Optional<PomFile> readOnce(Path file) {
        if (!read.containsKey(file)) {
            try {
                read.put(file, PomReader.read(file));
            } catch (Refusal notAPom) {
                return Optional.empty();
            }
        }
        return Optional.of(read.get(file));
    }

    private Optional<Module> byCoordinate(Coordinate coordinate) {
        return modules.stream().filter(m -> m.coordinate().filter(coordinate::equals).isPresent()).findFirst();
    }

    /** The module whose directory holds {@code file}. */
    private Optional<Module> owner(Path file) {
        return Graph.owner(modules, file);
    }
}
