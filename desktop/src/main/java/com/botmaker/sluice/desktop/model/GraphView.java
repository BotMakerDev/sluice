package com.botmaker.sluice.desktop.model;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.ConfigDocument;
import com.botmaker.sluice.layout.UmbrellaLayout;
import com.botmaker.sluice.model.Edge;
import com.botmaker.sluice.model.Graph;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.model.TagOrder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The dependency graph and the tag order of an edited {@code sluice.yml}, as the library builds them from the poms
 * and that text: what a release would make of the file if it were saved now.
 *
 * @param rows    every module in tag order
 * @param refusal what the library refuses in the file that its loader does not see (a name that is no module, an
 *                edge against the order); a save is refused while there is one
 * @param waiting whether the file has problems of its own, so no graph was built
 */
public record GraphView(List<Row> rows, Optional<String> refusal, boolean waiting) {

    public GraphView {
        rows = List.copyOf(rows);
    }

    /**
     * One module.
     *
     * @param place       its place in the tag order, from 1
     * @param upstreams   the modules it depends on
     * @param downstreams the modules depending on it
     * @param added       the dependencies {@code sluice.yml} adds to it (its edges and text pins), each with why
     */
    public record Row(int place, String module, List<String> upstreams, List<String> downstreams,
                      List<String> added) {

        public Row {
            upstreams = List.copyOf(upstreams);
            downstreams = List.copyOf(downstreams);
            added = List.copyOf(added);
        }
    }

    /** What {@code doc} makes of the umbrella at {@code root}; reads the poms. */
    public static GraphView of(Path root, ConfigDocument doc) {
        if (!doc.problems().isEmpty()) {
            return new GraphView(List.of(), Optional.empty(), true);
        }
        try {
            Config config = doc.config();
            Graph graph = Graph.build(UmbrellaLayout.of(root), config);
            List<Module> order = TagOrder.of(graph, config);
            List<Row> rows = new ArrayList<>();
            for (Module module : order) {
                rows.add(new Row(rows.size() + 1, module.name(),
                        graph.upstreams(module).stream().map(e -> e.upstream().name()).toList(),
                        graph.dependents(module).stream().map(e -> e.downstream().name()).toList(),
                        graph.upstreams(module).stream().filter(e -> !e.pinnedInAPom())
                                .map(GraphView::added).toList()));
            }
            return new GraphView(rows, Optional.empty(), false);
        } catch (Refusal refusal) {
            return new GraphView(List.of(), Optional.of(refusal.getMessage()), false);
        }
    }

    private static String added(Edge edge) {
        return edge.upstream().name() + " (" + edge.why() + ")";
    }
}
