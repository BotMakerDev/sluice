package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.Changelog;
import com.botmaker.sluice.desktop.model.ChangelogFile;
import com.botmaker.sluice.desktop.model.Io;
import com.botmaker.sluice.desktop.model.Umbrella;
import com.botmaker.sluice.model.Module;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Each module's changelog, to write its {@code [Unreleased]} notes before a release: the file to edit, the
 * commits since its newest tag beside it, and that tag's section for the tone. Save writes the file; Save and
 * commit commits it alone in its module.
 */
public final class ChangelogTab extends BorderPane {

    private final Umbrella umbrella;
    private final ListView<Module> modules = new ListView<>();
    private final Map<String, ChangelogFile> read = new HashMap<>();
    private final TextArea editor = new TextArea();
    private final TextArea commits = new TextArea();
    private final TextArea last = new TextArea();
    private final Label heading = new Label();
    private final Label status = new Label();
    private final Button reload = new Button("Reload");
    private final Button save = new Button("Save");
    private final Button commit = new Button("Save and commit");

    private Optional<ChangelogFile> shown = Optional.empty();
    /** Set while the list goes back to the module whose edit was kept. */
    private boolean switching;

    public ChangelogTab(Umbrella umbrella) {
        this.umbrella = umbrella;
        getStyleClass().add("changelog-tab");

        modules.getItems().setAll(umbrella.modules());
        modules.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Module module, boolean empty) {
                super.updateItem(module, empty);
                if (empty || module == null) {
                    setText(null);
                    return;
                }
                ChangelogFile file = read.get(module.name());
                setText(module.name() + (file == null ? "" : "  ·  " + file.state()));
                getStyleClass().removeAll("cell--ok", "cell--dim");
                if (file != null) {
                    getStyleClass().add(file.hasNotes() ? "cell--ok" : "cell--dim");
                }
            }
        });
        modules.getSelectionModel().selectedItemProperty().addListener((o, was, is) -> {
            if (switching) {
                return;
            }
            if (dirty() && !discard(was)) {
                switching = true;
                Platform.runLater(() -> {
                    modules.getSelectionModel().select(was);
                    switching = false;
                });
                return;
            }
            open(is);
        });
        modules.setPrefWidth(280);

        editor.getStyleClass().add("output-text");
        editor.textProperty().addListener((o, was, is) -> edited());
        for (TextArea side : new TextArea[] {commits, last}) {
            side.setEditable(false);
            side.getStyleClass().add("output-text");
        }
        heading.getStyleClass().add("placeholder-title");
        Label commitsTitle = new Label("Commits since the newest tag");
        Label lastTitle = new Label("The newest tag's section");
        VBox side = new VBox(6, commitsTitle, commits, lastTitle, last);
        VBox.setVgrow(commits, Priority.ALWAYS);
        VBox.setVgrow(last, Priority.ALWAYS);
        VBox centre = new VBox(6, heading, editor);
        VBox.setVgrow(editor, Priority.ALWAYS);
        SplitPane body = new SplitPane(centre, side);
        body.setDividerPositions(0.6);
        body.setPadding(new Insets(10));

        reload.setOnAction(e -> reload());
        save.setOnAction(e -> save(false));
        commit.setOnAction(e -> save(true));
        status.getStyleClass().add("status-line");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, reload, save, commit, spacer, status);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 10, 8, 10));

        SplitPane split = new SplitPane(modules, body);
        split.setDividerPositions(0.25);
        setTop(bar);
        setCenter(split);
        edited();
        reload();
    }

    /** Reads every module's changelog again; an edit not saved is kept only for the module on screen. */
    public void reload() {
        reload.setDisable(true);
        String tagPattern = umbrella.config().tagPattern();
        Io.async(() -> {
            Map<String, ChangelogFile> files = new HashMap<>();
            for (Module module : umbrella.modules()) {
                files.put(module.name(), ChangelogFile.read(module, tagPattern));
            }
            return files;
        }).whenComplete((files, error) -> Platform.runLater(() -> {
            reload.setDisable(false);
            if (error != null) {
                status.setText(Io.message(error));
                return;
            }
            read.clear();
            read.putAll(files);
            modules.refresh();
            Module selected = modules.getSelectionModel().getSelectedItem();
            if (selected == null && !modules.getItems().isEmpty()) {
                modules.getSelectionModel().selectFirst();
            } else if (selected != null && !dirty()) {
                open(selected);
            }
            long noted = files.values().stream().filter(ChangelogFile::hasNotes).count();
            status.setText(noted + " of " + files.size() + " modules have " + Changelog.UNRELEASED + " notes");
        }));
    }

    void select(String module) {
        umbrella.module(module).ifPresent(m -> modules.getSelectionModel().select(m));
    }

    TextArea editor() {
        return editor;
    }

    Button saveButton() {
        return save;
    }

    Button commitButton() {
        return commit;
    }

    String status() {
        return status.getText();
    }

    private void open(Module module) {
        shown = module == null ? Optional.empty() : Optional.ofNullable(read.get(module.name()));
        if (shown.isEmpty()) {
            heading.setText(module == null ? "" : module.name());
            editor.clear();
            commits.clear();
            last.clear();
            edited();
            return;
        }
        ChangelogFile file = shown.get();
        heading.setText(file.module().name() + "  ·  " + file.file().getFileName()
                + file.newest().map(v -> "  ·  newest " + v.tag(umbrella.config().tagPattern())).orElse("  ·  never tagged"));
        editor.setText(file.text());
        commits.setText(file.commits().isEmpty() ? "No commit since." : String.join("\n", file.commits()));
        last.setText(file.lastSection().orElse("No section for it."));
        edited();
    }

    /** Asks whether the unsaved edit of {@code module} may go. */
    private boolean discard(Module module) {
        Alert alert = Themed.dialog(new Alert(Alert.AlertType.CONFIRMATION,
                "Your edit of " + (module == null ? "this changelog" : module.name() + "'s changelog")
                        + " is not saved. Discard it?", ButtonType.CANCEL, ButtonType.OK),
                getScene() == null ? null : getScene().getWindow());
        alert.setHeaderText("Discard the edit");
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private boolean dirty() {
        return shown.isPresent() && !shown.get().text().equals(editor.getText());
    }

    private void edited() {
        boolean dirty = dirty();
        save.setDisable(!dirty);
        commit.setDisable(shown.isEmpty() || (!dirty && shown.get().text().isEmpty()));
    }

    private void save(boolean andCommit) {
        if (shown.isEmpty()) {
            return;
        }
        ChangelogFile file = shown.get();
        String text = editor.getText();
        String message = file.module().name() + ": changelog";
        save.setDisable(true);
        commit.setDisable(true);
        Io.run(() -> {
            file.save(text);
            if (andCommit) {
                file.commit(message);
            }
        }).whenComplete((done, error) -> Platform.runLater(() -> {
            if (error != null) {
                status.setText(Io.message(error));
                edited();
                return;
            }
            status.setText(andCommit ? "Saved and committed " + file.module().name() + "'s changelog."
                    : "Saved " + file.module().name() + "'s changelog.");
            ChangelogFile updated = new ChangelogFile(file.module(), file.file(), text, file.newest(),
                    file.commits(), file.lastSection());
            read.put(file.module().name(), updated);
            // The user may have picked another module while it saved: that one stays on screen.
            if (shown.isPresent() && shown.get().module().name().equals(file.module().name())) {
                shown = Optional.of(updated);
            }
            modules.refresh();
            edited();
        }));
    }
}
