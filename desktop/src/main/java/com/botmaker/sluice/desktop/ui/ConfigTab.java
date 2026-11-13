package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.config.ConfigDocument;
import com.botmaker.sluice.config.ConfigLoader;
import com.botmaker.sluice.config.ConfigProblem;
import com.botmaker.sluice.desktop.model.ConfigFile;
import com.botmaker.sluice.desktop.model.GraphView;
import com.botmaker.sluice.desktop.model.Io;
import com.botmaker.sluice.desktop.model.Umbrella;
import com.botmaker.sluice.model.Module;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The umbrella's {@code sluice.yml}, edited by forms or as text: one document both change, so each follows the
 * other. Beside them, the tag order and the graph the library builds from the edited text; under them, every
 * problem the loader and the graph find, each a click from its line. Save is refused while one remains; it writes
 * the file, shows what changed and offers to commit it.
 */
public final class ConfigTab extends BorderPane {

    /** One entry of the section list. */
    private record Section(String title, Node node, Runnable refresh) {
    }

    static final String YAML = "YAML";

    private final Path root;
    private final ReadOnlyObjectWrapper<ConfigDocument> doc = new ReadOnlyObjectWrapper<>();
    private ConfigDocument saved;

    private final List<Section> sections = new ArrayList<>();
    private final ListView<String> sectionList = new ListView<>();
    private final StackPane body = new StackPane();
    private final TextArea yaml = new TextArea();
    private final ListView<ConfigProblem> problems = new ListView<>();
    private final VBox graph = new VBox(4);
    private final Label status = new Label();
    private final Label edited = new Label();
    private final Button save = new Button("Save");
    private final Button revert = new Button("Revert");

    private final PauseTransition settle = new PauseTransition(Duration.millis(250));
    /** Counts the graphs asked for: an answer for an older text is dropped. */
    private int asked;
    private Optional<GraphView> graphView = Optional.empty();
    private List<ConfigProblem> loaderProblems = List.of();
    private boolean syncingYaml;
    /** Set while a save writes: no second one starts. */
    private boolean saving;
    private Section current;

    public ConfigTab(Umbrella umbrella) {
        this.root = umbrella.root();
        getStyleClass().add("config-tab");
        saved = ConfigDocument.load(root);
        doc.set(saved);
        List<String> names = umbrella.modules().stream().map(Module::name).toList();
        ConfigForm.Host host = new ConfigForm.Host() {
            @Override
            public ConfigDocument doc() {
                return doc.get();
            }

            @Override
            public void edit(UnaryOperator<ConfigDocument> change) {
                ConfigTab.this.edit(change);
            }
        };

        ConfigForm general = new ConfigForm(host);
        general.text("Branch", () -> List.of("branch"));
        general.text("Tag pattern", () -> List.of("tag"));
        general.target("Target", () -> List.of("target"));
        general.bool("GitHub releases", () -> List.of("github-releases"), true);
        general.list("Relevance ignore", () -> List.of("relevance", "ignore"));
        general.toggle("Changelog", () -> List.of("changelog"));
        general.bool("Changelog from pins", () -> List.of("changelog", "pins"), false);
        general.command("Changelog extractor", () -> List.of("changelog", "extractor"));
        general.note("A cleared field takes its key out of sluice.yml, so the default holds: branch main, "
                + "tag v{version}, target none, GitHub releases on, relevance ignoring **/*.md and docs/**.");
        ScrollPane generalScroll = new ScrollPane(general);
        generalScroll.setFitToWidth(true);
        sections.add(new Section("General", generalScroll, general::refresh));

        ConfigModulesSection modules = new ConfigModulesSection(host, umbrella);
        sections.add(new Section("Modules", modules, modules::refresh));

        ConfigListSection edges = new ConfigListSection(host, "Edges", "edges",
                List.of(Map.entry("From", "from"), Map.entry("To", "to"), Map.entry("Why", "why")),
                ordered("from", "", "to", "", "why", ""), (form, at) -> {
                    form.choice("From", at.apply("from"), () -> withNone(names));
                    form.choice("To", at.apply("to"), () -> withNone(names));
                    form.text("Why", at.apply("why"));
                    form.note("A dependency no pom says: to depends on from, so from tags first.");
                });
        sections.add(new Section("Edges", edges, edges::refresh));

        ConfigListSection gates = new ConfigListSection(host, "Gates", "gates",
                List.of(Map.entry("Name", "name"), Map.entry("Modules", "modules"), Map.entry("Run", "run")),
                ordered("name", "", "run", ""), (form, at) -> {
                    form.text("Name", at.apply("name"));
                    form.list("Modules", at.apply("modules"));
                    form.command("Run", at.apply("run"));
                    form.integer("Skip exit", at.apply("skip-exit"));
                    form.note("A command run in each module before any tag; exit 0 passes, the skip exit skips it, "
                            + "any other refuses the release. No module listed: every module.");
                });
        sections.add(new Section("Gates", gates, gates::refresh));

        ConfigListSection steps = new ConfigListSection(host, "Steps", "steps",
                List.of(Map.entry("At", "at"), Map.entry("Modules", "modules"), Map.entry("Run", "run")),
                ordered("at", "after-tag", "run", ""), (form, at) -> {
                    form.choice("At", at.apply("at"),
                            () -> List.of("before-release-commit", "after-tag", "after-snapshot"));
                    form.list("Modules", at.apply("modules"));
                    form.command("Run", at.apply("run"));
                });
        sections.add(new Section("Steps", steps, steps::refresh));

        ConfigAfterRelease afterRelease = new ConfigAfterRelease(host, names);
        sections.add(new Section("After release", afterRelease, afterRelease::refresh));

        ConfigListSection textPins = new ConfigListSection(host, "Text pins", "text-pins",
                List.of(Map.entry("In", "in"), Map.entry("Pins", "pins"), Map.entry("Match", "match")),
                ordered("in", "", "pins", "", "match", ""), (form, at) -> {
                    form.text("In", at.apply("in"));
                    form.choice("Pins", at.apply("pins"), () -> withNone(names));
                    form.text("Match", at.apply("match"));
                    form.choice("Spelled", at.apply("spelled"), () -> List.of("", "version", "tag"));
                    form.note("A version written as text in a file: match is a regular expression whose first "
                            + "group is the version.");
                });
        sections.add(new Section("Text pins", textPins, textPins::refresh));

        yaml.getStyleClass().add("output-text");
        yaml.setId("config-yaml");
        yaml.textProperty().addListener((o, was, is) -> {
            if (!syncingYaml) {
                doc.set(ConfigDocument.of(is, ConfigLoader.FILE));
            }
        });
        sections.add(new Section(YAML, yaml, this::syncYaml));

        sectionList.getItems().setAll(sections.stream().map(Section::title).toList());
        sectionList.getStyleClass().add("config-sections");
        sectionList.getSelectionModel().selectedIndexProperty().addListener((o, was, is) -> {
            if (is.intValue() >= 0) {
                show(sections.get(is.intValue()));
            }
        });
        sectionList.setMinWidth(150);
        sectionList.setMaxWidth(200);

        Label graphTitle = new Label("Tag order");
        graphTitle.getStyleClass().add("placeholder-title");
        VBox graphBox = new VBox(8, graphTitle, graph);
        graphBox.setPadding(new Insets(10));
        ScrollPane graphScroll = new ScrollPane(graphBox);
        graphScroll.setFitToWidth(true);
        graphScroll.setMinWidth(260);
        graphScroll.getStyleClass().add("config-graph");

        problems.setId("config-problems");
        problems.setPrefHeight(110);
        problems.setPlaceholder(new Label("No problem."));
        problems.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(ConfigProblem problem, boolean empty) {
                super.updateItem(problem, empty);
                getStyleClass().remove("cell--broken");
                if (empty || problem == null) {
                    setText(null);
                    return;
                }
                setText((problem.line() > 0 ? "line " + problem.line() + "  ·  " : "") + problem.message());
                getStyleClass().add("cell--broken");
            }
        });
        problems.getSelectionModel().selectedItemProperty().addListener((o, was, is) -> {
            if (is != null && is.line() > 0) {
                jumpTo(is.line());
            }
        });

        save.setId("config-save");
        save.setDefaultButton(false);
        save.setOnAction(e -> save());
        revert.setOnAction(e -> revert());
        status.getStyleClass().add("status-line");
        edited.getStyleClass().add("status-line");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, edited, spacer, status, revert, save);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 10, 8, 10));

        SplitPane split = new SplitPane(sectionList, body, graphScroll);
        split.setDividerPositions(0.14, 0.76);
        SplitPane.setResizableWithParent(sectionList, false);
        setCenter(split);
        setBottom(new VBox(problems, bar));

        doc.addListener((o, was, is) -> changed());
        changed();
        sectionList.getSelectionModel().selectFirst();
    }

    /** Whether the text differs from the file as last read or saved. */
    public boolean dirty() {
        return !doc.get().text().equals(saved.text());
    }

    ReadOnlyObjectProperty<ConfigDocument> doc() {
        return doc.getReadOnlyProperty();
    }

    void select(String section) {
        sectionList.getSelectionModel().select(section);
    }

    TextArea yamlArea() {
        return yaml;
    }

    ListView<ConfigProblem> problemList() {
        return problems;
    }

    Button saveButton() {
        return save;
    }

    Optional<GraphView> graphView() {
        return graphView;
    }

    private void edit(UnaryOperator<ConfigDocument> change) {
        try {
            doc.set(change.apply(doc.get()));
            status.setText("");
        } catch (RuntimeException e) {
            status.setText(Io.message(e));
            // The fields go back to what the document still says.
            if (current != null) {
                current.refresh().run();
            }
        }
    }

    private void show(Section section) {
        current = section;
        body.getChildren().setAll(section.node());
        boolean form = !section.title().equals(YAML);
        if (form && !readable()) {
            Label wait = new Label("sluice.yml is not YAML the forms can read: fix it in the YAML section first.");
            wait.setWrapText(true);
            wait.getStyleClass().add("cell--broken");
            body.getChildren().setAll(wait);
            return;
        }
        section.refresh().run();
    }

    /** Whether the forms can read the text: blank, or YAML that is a map. */
    private boolean readable() {
        return doc.get().text().isBlank() || doc.get().get(List.of()).filter(Map.class::isInstance).isPresent();
    }

    private void syncYaml() {
        if (yaml.getText().equals(doc.get().text())) {
            return;
        }
        int caret = Math.min(yaml.getCaretPosition(), doc.get().text().length());
        syncingYaml = true;
        try {
            yaml.setText(doc.get().text());
            yaml.positionCaret(caret);
        } finally {
            syncingYaml = false;
        }
    }

    private void changed() {
        loaderProblems = doc.get().problems();
        graphView = Optional.empty();
        showProblems();
        // The section on screen follows: the text after a form's edit or a revert, a form after either.
        if (current != null) {
            boolean onScreen = body.getChildren().size() == 1 && body.getChildren().getFirst() == current.node();
            if (onScreen && (current.title().equals(YAML) || readable())) {
                current.refresh().run();
            } else {
                // A form that waited for readable YAML, or one that can read it no more.
                show(current);
            }
        }
        edited.setText(dirty() ? "Edited — not saved" : "");
        buttons();
        settle.setOnFinished(e -> askGraph());
        settle.playFromStart();
    }

    private void askGraph() {
        int ask = ++asked;
        ConfigDocument text = doc.get();
        graph.getChildren().setAll(dim("Reading the poms…"));
        Io.async(() -> GraphView.of(root, text)).whenComplete((view, error) -> Platform.runLater(() -> {
            if (ask != asked) {
                return;
            }
            graphView = error == null ? Optional.of(view)
                    : Optional.of(new GraphView(List.of(), Optional.of(Io.message(error)), false));
            drawGraph(graphView.get());
            showProblems();
            buttons();
        }));
    }

    private void drawGraph(GraphView view) {
        graph.getChildren().clear();
        if (view.waiting()) {
            graph.getChildren().add(dim("Shown once the problems below are fixed."));
            return;
        }
        if (view.refusal().isPresent()) {
            Label refused = new Label(view.refusal().get());
            refused.setWrapText(true);
            refused.getStyleClass().add("cell--broken");
            graph.getChildren().add(refused);
            return;
        }
        for (GraphView.Row row : view.rows()) {
            Label module = new Label(row.place() + "  " + row.module());
            module.getStyleClass().add("graph-module");
            VBox entry = new VBox(1, module);
            entry.getStyleClass().add("graph-row");
            if (!row.upstreams().isEmpty()) {
                entry.getChildren().add(dim("← " + String.join(", ", row.upstreams())));
            }
            if (!row.downstreams().isEmpty()) {
                entry.getChildren().add(dim("→ " + String.join(", ", row.downstreams())));
            }
            for (String added : row.added()) {
                Label mark = new Label("✎ " + added);
                mark.setWrapText(true);
                mark.getStyleClass().add("graph-added");
                entry.getChildren().add(mark);
            }
            graph.getChildren().add(entry);
        }
    }

    private void showProblems() {
        List<ConfigProblem> all = new ArrayList<>(loaderProblems);
        graphView.flatMap(GraphView::refusal).ifPresent(r -> all.add(new ConfigProblem("", r, 0)));
        problems.getItems().setAll(all);
    }

    /**
     * Save waits for no graph: an edit written as the click takes a field's focus clears the graph, and Save checks
     * it again itself. Only a graph that came back refused holds it.
     */
    private void buttons() {
        boolean refused = graphView.flatMap(GraphView::refusal).isPresent();
        save.setDisable(saving || !dirty() || !loaderProblems.isEmpty() || refused);
        revert.setDisable(saving);
    }

    /** The file as it is on disk now, read again: what Revert goes back to, outside changes included. */
    private void revert() {
        try {
            saved = ConfigDocument.of(ConfigFile.onDisk(root), ConfigLoader.FILE);
        } catch (RuntimeException e) {
            status.setText(Io.message(e));
            return;
        }
        status.setText("");
        if (saved.text().equals(doc.get().text())) {
            changed();
        } else {
            doc.set(saved);
        }
    }

    private void jumpTo(int line) {
        select(YAML);
        String text = yaml.getText();
        int start = 0;
        for (int i = 1; i < line && start >= 0; i++) {
            start = text.indexOf('\n', start) + 1;
            if (start == 0) {
                start = text.length();
                break;
            }
        }
        int end = text.indexOf('\n', start);
        yaml.selectRange(start, end < 0 ? text.length() : end);
        yaml.requestFocus();
    }

    private void save() {
        ConfigDocument text = doc.get();
        String before = saved.text();
        saving = true;
        buttons();
        Io.async(() -> {
            if (!ConfigFile.onDisk(root).equals(before)) {
                throw new Refusal("sluice.yml changed on disk since it was read: nothing was written. Revert reads "
                        + "it again, and drops this edit.");
            }
            GraphView view = GraphView.of(root, text);
            if (view.waiting() || view.refusal().isPresent()) {
                return view;
            }
            text.save(root);
            return view;
        }).whenComplete((view, error) -> Platform.runLater(() -> {
            saving = false;
            if (error == null && view.refusal().isPresent() && text == doc.get()) {
                graphView = Optional.of(view);
                drawGraph(view);
                showProblems();
            }
            if (error != null || view.waiting() || view.refusal().isPresent()) {
                status.setText(error != null ? Io.message(error) : "Not saved: sluice.yml has a problem, listed below.");
                buttons();
                return;
            }
            saved = text;
            edited.setText(dirty() ? "Edited — not saved" : "");
            buttons();
            status.setText("Saved sluice.yml. The Modules tab's Refresh and the next preview read it.");
            review(ConfigFile.diff(before, text.text()));
        }));
    }

    /** What the save changed, and Commit. */
    private void review(String diff) {
        TextArea shown = new TextArea(diff.isEmpty() ? "Nothing changed." : diff);
        shown.setEditable(false);
        shown.getStyleClass().add("output-text");
        shown.setPrefSize(720, 380);
        TextField message = new TextField("sluice.yml: configuration");
        VBox content = new VBox(8, shown, new Label("Commit message"), message);
        VBox.setVgrow(shown, Priority.ALWAYS);
        ButtonType commit = new ButtonType("Commit", ButtonBar.ButtonData.OK_DONE);
        Dialog<ButtonType> dialog = Themed.dialog(new Dialog<>(), getScene() == null ? null : getScene().getWindow());
        dialog.setTitle("sluice.yml saved");
        dialog.setHeaderText("Saved. Commit it in the umbrella? Nothing else staged goes with it.");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE, commit);
        if (dialog.showAndWait().filter(commit::equals).isEmpty()) {
            return;
        }
        String words = message.getText().isBlank() ? "sluice.yml: configuration" : message.getText().strip();
        Io.run(() -> ConfigFile.commit(root, words)).whenComplete((done, error) -> Platform.runLater(() ->
                status.setText(error == null ? "Committed sluice.yml." : Io.message(error))));
    }

    /** Asks whether the unsaved edit may go; true when there is none. */
    public boolean mayDiscard() {
        // A field being typed in writes when it loses focus: take it, so its text counts.
        requestFocus();
        if (!dirty()) {
            return true;
        }
        Alert alert = Themed.dialog(new Alert(Alert.AlertType.CONFIRMATION,
                "Your edit of sluice.yml is not saved. Discard it?", ButtonType.CANCEL, ButtonType.OK),
                getScene() == null ? null : getScene().getWindow());
        alert.setHeaderText("Discard the edit");
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    /** A new entry's keys in the order they are written: {@code Map.of} has none. */
    private static Map<String, Object> ordered(String... keysAndValues) {
        Map<String, Object> entry = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            entry.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return entry;
    }

    private static List<String> withNone(List<String> names) {
        List<String> options = new ArrayList<>(List.of(""));
        options.addAll(names);
        return options;
    }

    private static Label dim(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("cell--dim");
        return label;
    }
}
