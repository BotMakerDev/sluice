package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.desktop.ui.widgets.CopyButton;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;

/** A refusal said in a box: a title, a line each, and a button that copies it all. Hidden while empty. */
public final class Banner extends VBox {

    private String title = "";

    public Banner() {
        super(4);
        getStyleClass().add("refusal-banner");
        hide();
    }

    public void show(String title, List<String> lines) {
        this.title = title;
        getChildren().clear();
        Label heading = new Label(title);
        heading.getStyleClass().add("refusal-title");
        HBox row = new HBox(10, heading, new CopyButton("Copy", () -> title + "\n\n" + String.join("\n\n", lines)));
        row.setAlignment(Pos.CENTER_LEFT);
        getChildren().add(row);
        for (String line : lines) {
            Label label = new Label(line);
            label.getStyleClass().add("refusal-line");
            label.setWrapText(true);
            getChildren().add(label);
        }
        setVisible(true);
        setManaged(true);
    }

    public void hide() {
        title = "";
        getChildren().clear();
        setVisible(false);
        setManaged(false);
    }

    /** The title shown, or empty while hidden. */
    public String title() {
        return title;
    }
}
