package com.botmaker.sluice.desktop.ui.widgets;

import com.botmaker.sluice.desktop.model.ReleaseProgress;
import com.botmaker.sluice.desktop.model.ReleaseProgress.Lane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;

import java.time.Duration;
import java.util.List;

/** One bar, a segment per module that has started, each as wide as its share of the time taken. */
public final class ReleaseTimeline extends Region {

    /** A module that took a second still shows. */
    private static final double MIN_SHARE = 0.01;

    private List<Lane> lanes = List.of();

    public ReleaseTimeline() {
        getStyleClass().add("release-timeline");
        setMinHeight(8);
        setPrefHeight(8);
        setMaxHeight(8);
    }

    public void show(List<Lane> lanes) {
        this.lanes = lanes.stream().filter(lane -> lane.elapsed().isPresent()).toList();
        getChildren().clear();
        for (Lane lane : this.lanes) {
            Region segment = new Region();
            segment.getStyleClass().addAll("timeline-segment", "timeline-segment--" + (lane.failed() ? "failed"
                    : lane.running() ? "running" : "done"));
            Tooltip.install(segment, new Tooltip(lane.module() + " · "
                    + ReleaseProgress.clock(lane.elapsed().orElse(Duration.ZERO))));
            getChildren().add(segment);
        }
        boolean shown = !this.lanes.isEmpty();
        setVisible(shown);
        setManaged(shown);
        requestLayout();
    }

    @Override
    protected void layoutChildren() {
        double total = lanes.stream().mapToDouble(ReleaseTimeline::seconds).sum();
        double shares = lanes.stream().mapToDouble(lane -> Math.max(MIN_SHARE, seconds(lane) / Math.max(1, total)))
                .sum();
        double width = getWidth() - snappedLeftInset() - snappedRightInset();
        double height = getHeight() - snappedTopInset() - snappedBottomInset();
        double x = snappedLeftInset();
        for (int i = 0; i < lanes.size(); i++) {
            double share = Math.max(MIN_SHARE, seconds(lanes.get(i)) / Math.max(1, total)) / shares;
            getChildren().get(i).resizeRelocate(x, snappedTopInset(), Math.max(1, width * share - 1), height);
            x += width * share;
        }
    }

    private static double seconds(Lane lane) {
        return lane.elapsed().map(Duration::toMillis).orElse(0L) / 1000.0;
    }
}
