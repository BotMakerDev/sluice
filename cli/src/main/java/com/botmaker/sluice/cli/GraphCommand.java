package com.botmaker.sluice.cli;

import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.ConfigLoader;
import com.botmaker.sluice.layout.UmbrellaLayout;
import com.botmaker.sluice.model.Edge;
import com.botmaker.sluice.model.Graph;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.model.Pin;
import com.botmaker.sluice.model.TagOrder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/** {@code sluice graph}: the modules in their tag order, and each edge with the pins that spell it. */
@Command(name = "graph", mixinStandardHelpOptions = true,
        description = "Print the modules in tag order, and each dependency edge with its pins.")
final class GraphCommand implements Callable<Integer> {

    @ParentCommand
    private Main parent;

    @Spec
    private CommandSpec spec;

    @Option(names = "--umbrella", paramLabel = "DIR", description = "The umbrella repository (default: here).")
    private Path umbrella;

    @Override
    public Integer call() {
        Path root = umbrella != null ? umbrella.toAbsolutePath().normalize() : parent.umbrella();
        Config config = ConfigLoader.load(root);
        Graph graph = Graph.build(UmbrellaLayout.of(root), config);
        PrintWriter out = spec.commandLine().getOut();
        out.println("tag order:");
        List<Module> order = TagOrder.of(graph, config);
        for (int i = 0; i < order.size(); i++) {
            Module module = order.get(i);
            out.println("  " + (i + 1) + ". " + module
                    + module.alias().map(alias -> " (--" + alias + ")").orElse("")
                    + (module.kind() == Module.Kind.MAVEN ? "" : " [" + module.kind().id() + "]")
                    + module.coordinate().map(c -> "  " + c + module.version().map(v -> " " + v).orElse(""))
                    .orElse(""));
        }
        out.println("edges:");
        for (Edge edge : graph.edges()) {
            out.println("  " + edge);
            for (Pin pin : edge.pins()) {
                out.println("      " + describe(graph.root(), pin));
            }
        }
        out.flush();
        return Main.OK;
    }

    private static String describe(Path root, Pin pin) {
        String in = " in " + root.toAbsolutePath().normalize().relativize(pin.file().toAbsolutePath().normalize())
                .toString().replace('\\', '/');
        return switch (pin) {
            case Pin.Property property -> "property " + property.name() + in;
            case Pin.Literal literal -> "version of " + literal.dep() + in;
            case Pin.Parent ignored -> "parent version" + in;
            case Pin.Text text -> "text /" + text.match().pattern() + "/ (" + text.spelling().id() + ")" + in;
        };
    }
}
