// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.desktop.model.Io;
import com.botmaker.sluice.desktop.model.Links;
import com.botmaker.sluice.desktop.model.ReleaseProgress;
import com.botmaker.sluice.desktop.model.Umbrella;
import com.botmaker.sluice.desktop.ui.widgets.ReleaseBoard;
import com.botmaker.sluice.execute.ReleaseLogs;
import com.botmaker.sluice.execute.ReleaseLogs.LoggedRelease;
import com.botmaker.sluice.execute.ReleaseState;
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
    private final Button resume = new Button("Resume…");

    /** {@code resume} goes on with the newest run that did not finish, through the Release tab. */
    public ReleasesTab(Umbrella umbrella, Consumer<String> open, Runnable resume) {
        this.umbrella = umbrella;
        this.board = new ReleaseBoard(open);
        Io.async(() -> Links.of(umbrella.modules()))
                .thenAccept(links -> Platform.runLater(() -> board.setLinks(links)));
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
        list.getSelectionModel().selectedItemProperty().addListener((o, was, is) -> {
            this.resume.setDisable(!resumable(is));
            show(is);
        });
        list.setPrefWidth(320);

        log.setEditable(false);
        log.getStyleClass().add("output-text");
        VBox right = new VBox(8, board, log);
        right.setPadding(new Insets(10));
        VBox.setVgrow(log, Priority.ALWAYS);
        board.setVisible(false);
        board.setManaged(false);

        reload.setOnAction(e -> reload());
        this.resume.setDisable(true);
        this.resume.setOnAction(e -> resume.run());
        status.getStyleClass().add("status-line");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, reload, this.resume, spacer, status);
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

    /** Only the newest run resumes, and only one that did not finish and no sluice runs now. */
    private boolean resumable(LoggedRelease release) {
        return release != null && !list.getItems().isEmpty() && list.getItems().getFirst() == release
                && !release.live()
                && release.state().filter(state -> state.status() != ReleaseState.Status.DONE).isPresent();
    }

    Button resumeButton() {
        return resume;
    }

    ListView<LoggedRelease> list() {
        return list;
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
        // Its state and log are files: read off the FX thread, shown only if it is still the one selected.
        Io.async(() -> new Shown(progress(release), logOf(release))).whenComplete((shown, error) ->
                Platform.runLater(() -> {
                    if (list.getSelectionModel().getSelectedItem() != release) {
                        return;
                    }
                    if (error != null) {
                        log.setText("This run could not be read: " + Io.message(error));
                        return;
                    }
                    board.show(shown.progress());
                    board.setVisible(true);
                    board.setManaged(true);
                    log.setText(shown.log());
                }));
    }

    /** A past run as read. */
    private record Shown(ReleaseProgress progress, String log) {
    }

    private static String logOf(LoggedRelease release) {
        return release.log().map(file -> {
            try {
                return Files.readString(file);
            } catch (IOException e) {
                return file + ": could not be read (" + e.getMessage() + ")";
            }
        }).orElse("This run left no log.");
    }
}
