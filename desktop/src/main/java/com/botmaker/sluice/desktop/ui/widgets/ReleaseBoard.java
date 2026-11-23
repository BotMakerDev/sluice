// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.desktop.ui.widgets;

import com.botmaker.sluice.desktop.model.Links;
import com.botmaker.sluice.desktop.model.ReleaseProgress;
import com.botmaker.sluice.desktop.model.ReleaseProgress.Filter;
import com.botmaker.sluice.desktop.model.ReleaseProgress.Lane;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A release drawn: a headline, the tiles, the timeline and a lane per module. The Release tab draws the run it
 * watches with it, the Releases tab a past one.
 */
public final class ReleaseBoard extends VBox {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final Consumer<String> open;
    private final Label headline = new Label();
    private final SummaryTiles tiles = new SummaryTiles();
    private final ReleaseTimeline timeline = new ReleaseTimeline();
    private final VBox laneBox = new VBox(8);
    private final Label empty = new Label();
    /** Kept across redraws, so a pulse runs on without restarting each tick. */
    private final Map<String, ModuleLane> lanes = new LinkedHashMap<>();

    private Function<String, Optional<Links>> links = module -> Optional.empty();
    private ReleaseProgress progress;
    private boolean animated = true;

    public ReleaseBoard(Consumer<String> open) {
        super(10);
        this.open = open;
        getStyleClass().add("release-board");
        headline.getStyleClass().add("placeholder-title");
        empty.getStyleClass().add("placeholder-body");
        empty.setWrapText(true);
        tiles.setOnFilter(filter -> redrawLanes());

        ScrollPane scroll = new ScrollPane(laneBox);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(headline, tiles, timeline, empty, scroll);
    }

    /** Where each module's pages are, by its name, read off the FX thread; the lanes show them at once. */
    public void setLinks(Map<String, Optional<Links>> links) {
        this.links = module -> links.getOrDefault(module, Optional.empty());
        redrawLanes();
    }

    public void show(ReleaseProgress progress) {
        this.progress = progress;
        headline.setText(progress.phase().label() + " · started " + TIME.format(progress.started()));
        tiles.show(progress);
        timeline.show(progress.lanes());
        boolean noLanes = progress.lanes().isEmpty();
        empty.setText(noLanes ? switch (progress.phase()) {
            case GATING -> "Deciding and running the gates — nothing is tagged yet.";
            case REFUSED -> "A gate refused, so nothing was tagged. Its words are above.";
            default -> "No module was released. The output says why.";
        } : "");
        empty.setVisible(noLanes);
        empty.setManaged(noLanes);
        redrawLanes();
    }

    /** Pulses run only while the board can be seen. */
    public void setAnimated(boolean animated) {
        this.animated = animated;
        lanes.values().forEach(lane -> lane.setAnimated(animated));
    }

    SummaryTiles tiles() {
        return tiles;
    }

    ModuleLane lane(String module) {
        return lanes.get(module);
    }

    long visibleLanes() {
        return laneBox.getChildren().stream().filter(Node::isVisible).count();
    }

    private void redrawLanes() {
        if (progress == null) {
            return;
        }
        Filter filter = tiles.selected();
        lanes.keySet().retainAll(progress.lanes().stream().map(Lane::module).toList());
        List<ModuleLane> views = new ArrayList<>();
        for (Lane lane : progress.lanes()) {
            ModuleLane view = lanes.computeIfAbsent(lane.module(), module -> {
                ModuleLane created = new ModuleLane(open);
                created.setAnimated(animated);
                return created;
            });
            view.show(lane, links.apply(lane.module()));
            boolean shown = lane.shownUnder(filter);
            view.setVisible(shown);
            view.setManaged(shown);
            views.add(view);
        }
        // Laid out again only when a lane comes, goes or moves, not on every tick.
        if (!laneBox.getChildren().equals(views)) {
            laneBox.getChildren().setAll(views);
        }
    }
}
