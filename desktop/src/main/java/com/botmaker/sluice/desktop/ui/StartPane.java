package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.desktop.model.Prefs;
import com.botmaker.sluice.desktop.model.Umbrella;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * What the window shows before an umbrella is open: a folder to pick, and the umbrellas opened before. Only a
 * folder holding a {@code sluice.yml} opens.
 */
public final class StartPane extends VBox {

    private final Prefs prefs;
    private final Consumer<Path> open;
    private final VBox recent = new VBox(4);
    private final Label problem = new Label();

    public StartPane(Prefs prefs, Consumer<Path> open) {
        super(14);
        this.prefs = prefs;
        this.open = open;
        getStyleClass().add("start-pane");
        setAlignment(Pos.CENTER);
        setPadding(new Insets(40));

        Label title = new Label("sluice");
        title.getStyleClass().add("placeholder-title");
        Label body = new Label("Open an umbrella: the folder that holds its sluice.yml.");
        body.getStyleClass().add("placeholder-body");
        Button choose = new Button("Open an umbrella…");
        choose.setDefaultButton(true);
        choose.setOnAction(e -> choose());
        problem.getStyleClass().add("error-text");
        problem.setWrapText(true);
        problem.setVisible(false);
        problem.setManaged(false);
        recent.setAlignment(Pos.CENTER);
        getChildren().addAll(title, body, choose, problem, recent);
        showRecent();
    }

    /** Says why a folder did not open. */
    public void problem(String why) {
        problem.setText(why);
        problem.setVisible(true);
        problem.setManaged(true);
        showRecent();
    }

    /** Opens {@code dir} if it is an umbrella, else says why not. */
    public void tryOpen(Path dir) {
        if (!Umbrella.looksLikeOne(dir)) {
            problem(dir + " holds no sluice.yml.");
            return;
        }
        open.accept(dir);
    }

    VBox recent() {
        return recent;
    }

    private void choose() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Open an umbrella");
        prefs.recent().stream().findFirst().map(Path::getParent).map(Path::toFile).filter(File::isDirectory)
                .ifPresent(chooser::setInitialDirectory);
        File picked = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
        if (picked != null) {
            tryOpen(picked.toPath());
        }
    }

    private void showRecent() {
        recent.getChildren().clear();
        if (prefs.recent().isEmpty()) {
            return;
        }
        recent.getChildren().add(new Label("Recent"));
        for (Path dir : prefs.recent()) {
            Hyperlink link = new Hyperlink(dir.toString());
            link.setDisable(!Umbrella.looksLikeOne(dir));
            link.setOnAction(e -> tryOpen(dir));
            recent.getChildren().add(link);
        }
    }
}
