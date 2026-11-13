package com.botmaker.sluice.desktop.ui.widgets;

import com.botmaker.sluice.desktop.model.ReleaseProgress;
import com.botmaker.sluice.desktop.model.ReleaseProgress.Filter;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;

import java.util.function.Consumer;

/**
 * Four tiles over the board: tagged, published, done and the clock. Clicking one of the first three shows only the
 * lanes whose step it counts is unsettled; clicking it again, or the clock, shows every lane.
 */
public final class SummaryTiles extends HBox {

    private final Tile tagged = new Tile(Filter.TAG);
    private final Tile published = new Tile(Filter.PUBLISH);
    private final Tile done = new Tile(Filter.SNAPSHOT);
    private final Tile elapsed = new Tile(Filter.ALL);

    private Consumer<Filter> onFilter = filter -> {
    };
    private Filter selected = Filter.ALL;

    public SummaryTiles() {
        super(10);
        getStyleClass().add("summary-tiles");
        elapsed.getStyleClass().add("tile--elapsed");
        elapsed.arcBox.setVisible(false);
        elapsed.arcBox.setManaged(false);
        getChildren().addAll(tagged, published, done, elapsed);
    }

    public void setOnFilter(Consumer<Filter> onFilter) {
        this.onFilter = onFilter;
    }

    public Filter selected() {
        return selected;
    }

    public void show(ReleaseProgress progress) {
        ReleaseProgress.Tiles t = progress.tiles();
        tagged.set(t.tagged() + "/" + t.total(), "Tagged", share(t.tagged(), t.total()), t.tagFailed());
        published.set(t.published() + "/" + t.total(), "Published · " + t.publishWaiting() + " waiting"
                + (t.publishFailed() == 0 ? "" : " · " + t.publishFailed() + " failed"),
                share(t.published(), t.total()), t.publishFailed() > 0);
        done.set(t.done() + "/" + t.total(), "Back on a snapshot", share(t.done(), t.total()), false);
        elapsed.set(ReleaseProgress.clock(t.elapsed()), progress.phase().label(), 0,
                progress.phase() == ReleaseProgress.Phase.STOPPED || progress.phase() == ReleaseProgress.Phase.DIED);
    }

    public void select(Filter filter) {
        selected = filter;
        for (Tile tile : new Tile[] {tagged, published, done}) {
            tile.getStyleClass().remove("tile--selected");
            if (tile.filter == filter) {
                tile.getStyleClass().add("tile--selected");
            }
        }
    }

    Tile tile(Filter filter) {
        return switch (filter) {
            case TAG -> tagged;
            case PUBLISH -> published;
            case SNAPSHOT -> done;
            case ALL -> elapsed;
        };
    }

    private static double share(int part, int whole) {
        return whole == 0 ? 0 : (double) part / whole;
    }

    final class Tile extends HBox {
        private final Filter filter;
        final Label number = new Label();
        final Label caption = new Label();
        private final Arc fill = new Arc(16, 16, 13, 13, 90, 0);
        private final StackPane arcBox;

        Tile(Filter filter) {
            super(10);
            this.filter = filter;
            getStyleClass().add("tile");
            setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(this, Priority.ALWAYS);
            setMaxWidth(Double.MAX_VALUE);

            Arc track = new Arc(16, 16, 13, 13, 90, 360);
            track.setType(ArcType.OPEN);
            track.getStyleClass().add("tile-arc-track");
            fill.setType(ArcType.OPEN);
            fill.getStyleClass().add("tile-arc");
            arcBox = new StackPane(track, fill);
            arcBox.setMinSize(32, 32);
            arcBox.setMaxSize(32, 32);

            number.getStyleClass().add("tile-number");
            caption.getStyleClass().add("tile-caption");
            getChildren().addAll(arcBox, new VBox(0, number, caption));

            setOnMouseClicked(e -> {
                Filter next = filter == Filter.ALL || selected == filter ? Filter.ALL : filter;
                select(next);
                onFilter.accept(next);
            });
        }

        void set(String value, String text, double fraction, boolean failed) {
            number.setText(value);
            caption.setText(text);
            // Clockwise from twelve o'clock; JavaFX measures a positive length anticlockwise.
            fill.setLength(-360 * Math.max(0, Math.min(1, fraction)));
            getStyleClass().remove("tile--failed");
            if (failed) {
                getStyleClass().add("tile--failed");
            }
        }
    }
}
