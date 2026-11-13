package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.desktop.model.Io;
import com.botmaker.sluice.desktop.model.Links;
import com.botmaker.sluice.desktop.model.ReleaseProgress;
import com.botmaker.sluice.desktop.model.Umbrella;
import com.botmaker.sluice.desktop.ui.widgets.ReleaseBoard;
import com.botmaker.sluice.execute.ReleaseLogs;
import com.botmaker.sluice.execute.ReleaseLogs.LoggedRelease;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The runs under the umbrella's {@code releases/}, the newest first: each drawn from its state file as the board
 * draws a live one, beside its log.
 */
public final class ReleasesTab extends BorderPane {

    private final Umbrella umbrella;
    private final ListView<LoggedRelease> list = new ListView<>();
    private final ReleaseBoard board;
    private final TextArea log = new TextArea();
    private final Label status = new Label();
    private final Button reload = new Button("Reload");

    public ReleasesTab(Umbrella umbrella, Consumer<String> open) {
        this.umbrella = umbrella;
        this.board = new ReleaseBoard(open);
        board.setLinks(name -> umbrella.module(name).flatMap(Links::of));
        board.setAnimated(false);
        getStyleClass().add("releases-tab");

        list.setPlaceholder(new Label("No release has run here yet."));
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(LoggedRelease item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                ReleaseProgress progress = progress(item);
                Circle dot = new Circle(5);
                dot.getStyleClass().addAll("health-dot", "health-dot--" + progress.health());
                setGraphic(dot);
                setText(item.name() + "  ·  " + progress.phase().label()
                        + item.unreadable().map(why -> "  ·  " + why).orElse(""));
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, was, is) -> show(is));
        list.setPrefWidth(320);

        log.setEditable(false);
        log.getStyleClass().add("output-text");
        VBox right = new VBox(8, board, log);
        right.setPadding(new Insets(10));
        VBox.setVgrow(log, Priority.ALWAYS);
        board.setVisible(false);
        board.setManaged(false);

        reload.setOnAction(e -> reload());
        status.getStyleClass().add("status-line");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, reload, spacer, status);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 10, 8, 10));

        SplitPane split = new SplitPane(list, right);
        split.setDividerPositions(0.3);
        setTop(bar);
        setCenter(split);
        reload();
    }

    /** Reads {@code releases/} again, keeping the selection by name. */
    public void reload() {
        String selected = Optional.ofNullable(list.getSelectionModel().getSelectedItem()).map(LoggedRelease::name)
                .orElse(null);
        reload.setDisable(true);
        Io.async(() -> ReleaseLogs.list(umbrella.root())).whenComplete((runs, error) -> Platform.runLater(() -> {
            reload.setDisable(false);
            if (error != null) {
                status.setText(Io.message(error));
                return;
            }
            status.setText(runs.size() + " release" + (runs.size() == 1 ? "" : "s"));
            list.getItems().setAll(runs);
            runs.stream().filter(run -> run.name().equals(selected)).findFirst()
                    .or(() -> runs.stream().findFirst())
                    .ifPresent(run -> list.getSelectionModel().select(run));
        }));
    }

    List<LoggedRelease> runs() {
        return List.copyOf(list.getItems());
    }

    String logText() {
        return log.getText();
    }

    private ReleaseProgress progress(LoggedRelease release) {
        return ReleaseProgress.past(release, umbrella.config().tagPattern());
    }

    private void show(LoggedRelease release) {
        if (release == null) {
            board.setVisible(false);
            board.setManaged(false);
            log.clear();
            return;
        }
        board.show(progress(release));
        board.setVisible(true);
        board.setManaged(true);
        log.setText(release.log().map(file -> {
            try {
                return Files.readString(file);
            } catch (IOException e) {
                return file + ": could not be read (" + e.getMessage() + ")";
            }
        }).orElse("This run left no log."));
    }
}
