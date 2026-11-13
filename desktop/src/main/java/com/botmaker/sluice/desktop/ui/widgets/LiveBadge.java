package com.botmaker.sluice.desktop.ui.widgets;

import com.botmaker.sluice.desktop.model.ReleaseProgress;
import javafx.scene.control.Label;

/** The Release tab's header while a release runs, so it shows from every tab. Hidden otherwise. */
public final class LiveBadge extends Label {

    public LiveBadge() {
        getStyleClass().add("live-badge");
        show(null);
    }

    /** {@code null}, or a run that has ended, hides it. */
    public void show(ReleaseProgress progress) {
        boolean live = progress != null && progress.phase().running();
        setVisible(live);
        setManaged(live);
        getStyleClass().remove("live-badge--failed");
        if (!live) {
            setText("");
            return;
        }
        ReleaseProgress.Tiles tiles = progress.tiles();
        setText("● " + progress.phase().label() + (tiles.total() == 0 ? "" : " — " + tiles.done() + "/" + tiles.total()));
        if (progress.failed()) {
            getStyleClass().add("live-badge--failed");
        }
    }
}
