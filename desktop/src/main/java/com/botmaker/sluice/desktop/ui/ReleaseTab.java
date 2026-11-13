package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.Level;
import com.botmaker.sluice.decide.Decision;
import com.botmaker.sluice.desktop.model.Io;
import com.botmaker.sluice.desktop.model.Links;
import com.botmaker.sluice.desktop.model.ReleaseProgress;
import com.botmaker.sluice.desktop.model.ReleaseSpec;
import com.botmaker.sluice.desktop.model.ReleaseWatch;
import com.botmaker.sluice.desktop.model.Umbrella;
import com.botmaker.sluice.desktop.ui.widgets.LiveBadge;
import com.botmaker.sluice.desktop.ui.widgets.ReleaseBoard;
import com.botmaker.sluice.execute.Event;
import com.botmaker.sluice.execute.Release;
import com.botmaker.sluice.execute.ReleaseState;
import com.botmaker.sluice.execute.Stage;
import com.botmaker.sluice.gate.GateVerdict;
import com.botmaker.sluice.model.Module;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Cuts a release: flags and a request per module, the same words the command takes; Preview runs the decide pass
 * and every gate; Execute… is armed only by a clean preview of exactly what the flags say now, and asks before it
 * tags. The run is the library's, in this process: its events draw the board, Stop asks it to stop between steps,
 * and Resume goes on with a run that did not finish.
 */
public final class ReleaseTab extends BorderPane {

    private static final String WARNING = "Each tag is pushed and published. A pushed tag is final: a mistake is "
            + "repaired by the next release, never by moving the tag.";

    private final Umbrella umbrella;
    private final ReleaseBackend backend;
    private final Map<String, Optional<Links>> links = new LinkedHashMap<>();

    private final CheckBox all = new CheckBox("--all");
    private final ComboBox<Level> level = new ComboBox<>();
    private final CheckBox force = new CheckBox("--force");
    private final CheckBox withDependents = new CheckBox("--with-dependents");
    private final CheckBox noWait = new CheckBox("--no-wait");
    private final TableView<Row> table = new TableView<>();
    private final Label commandLine = new Label();
    private final Button preview = new Button("Preview");
    private final Button execute = new Button("Execute…");
    private final Button stop = new Button("Stop");
    private final Button resume = new Button("Resume…");
    private final Label status = new Label();
    private final Banner banner = new Banner();
    private final ReleaseBoard board;
    private final VBox verdicts = new VBox(2);
    private final TextArea output = new TextArea();
    private final LiveBadge badge = new LiveBadge();
    private final Timeline ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> redraw()));

    /** The spec the last clean preview was of; Execute… cuts only that. */
    private Optional<ReleaseSpec> armed = Optional.empty();
    private Optional<Release.Preview> previewed = Optional.empty();
    /** A preview is running: no other starts until it answers. */
    private boolean previewing;
    private ReleaseWatch watch;
    private final AtomicBoolean stopAsked = new AtomicBoolean();
    private Optional<ReleaseState> resumable = Optional.empty();
    private Runnable onEnded = () -> {
    };

    /** One module's row: what it asks and what the last preview would cut. */
    static final class Row {
        final Module module;
        final StringProperty request = new SimpleStringProperty("");
        final StringProperty cut = new SimpleStringProperty("");

        Row(Module module) {
            this.module = module;
        }
    }

    public ReleaseTab(Umbrella umbrella, Consumer<String> open) {
        this(umbrella, ReleaseBackend.REAL, open);
    }

    ReleaseTab(Umbrella umbrella, ReleaseBackend backend, Consumer<String> open) {
        this.umbrella = umbrella;
        this.backend = backend;
        this.board = new ReleaseBoard(open);
        board.setLinks(name -> links.computeIfAbsent(name, n -> umbrella.module(n).flatMap(Links::of)));
        getStyleClass().add("release-tab");

        level.getItems().setAll(Level.values());
        level.setValue(Level.PATCH);
        level.setDisable(true);
        all.selectedProperty().addListener((o, was, is) -> {
            level.setDisable(!is);
            specChanged();
        });
        level.valueProperty().addListener((o, was, is) -> specChanged());
        for (CheckBox box : List.of(force, withDependents, noWait)) {
            box.selectedProperty().addListener((o, was, is) -> specChanged());
        }
        force.getStyleClass().add("danger");
        HBox flags = new HBox(12, all, level, withDependents, force, noWait);
        flags.setAlignment(Pos.CENTER_LEFT);
        flags.getStyleClass().add("flag-box");

        TableColumn<Row, String> name = new TableColumn<>("Module");
        name.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().module.name()));
        name.setPrefWidth(220);
        TableColumn<Row, String> kind = new TableColumn<>("Kind");
        kind.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().module.kind().id()));
        TableColumn<Row, String> request = new TableColumn<>("Request");
        request.setCellValueFactory(c -> c.getValue().request);
        request.setCellFactory(TextFieldTableCell.forTableColumn());
        request.setOnEditCommit(e -> {
            e.getRowValue().request.set(e.getNewValue() == null ? "" : e.getNewValue().strip());
            specChanged();
        });
        request.setPrefWidth(140);
        TableColumn<Row, String> cut = new TableColumn<>("Would cut");
        cut.setCellValueFactory(c -> c.getValue().cut);
        cut.setPrefWidth(260);
        table.getColumns().setAll(List.of(name, kind, request, cut));
        table.setEditable(true);
        table.setPlaceholder(new Label("No module in sluice.yml's umbrella."));
        umbrella.modules().forEach(module -> table.getItems().add(new Row(module)));

        commandLine.getStyleClass().add("command-line");
        commandLine.setWrapText(true);
        preview.setOnAction(e -> preview());
        preview.setDefaultButton(true);
        execute.getStyleClass().add("danger");
        execute.setOnAction(e -> execute());
        stop.setOnAction(e -> stop());
        resume.setOnAction(e -> resume());
        status.getStyleClass().add("status-line");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, preview, execute, stop, resume, spacer, status);
        buttons.setAlignment(Pos.CENTER_LEFT);

        output.setEditable(false);
        output.getStyleClass().add("output-text");
        output.setPrefRowCount(10);

        VBox top = new VBox(8, flags, table, commandLine, buttons, banner, verdicts);
        top.setPadding(new Insets(10));
        VBox.setVgrow(table, Priority.ALWAYS);
        VBox bottom = new VBox(8, board, output);
        bottom.setPadding(new Insets(10));
        VBox.setVgrow(board, Priority.ALWAYS);
        SplitPane split = new SplitPane(top, bottom);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.45);
        setCenter(split);

        board.setVisible(false);
        board.setManaged(false);
        ticker.setCycleCount(Animation.INDEFINITE);
        banner.hide();
        specChanged();
        refreshResumable();
    }

    /** The header the window shows on every tab while a run goes. */
    public LiveBadge badge() {
        return badge;
    }

    /** Pulses run only while this tab is on screen. */
    public void setShowing(boolean showing) {
        board.setAnimated(showing);
    }

    public boolean running() {
        return watch != null && watch.running();
    }

    /** Asks the run to stop after the step it is on. */
    public void stop() {
        if (!running()) {
            return;
        }
        stopAsked.set(true);
        stop.setDisable(true);
        status.setText("Stopping after the current step…");
    }

    /** Called on the FX thread once a run returns. */
    public void setOnEnded(Runnable onEnded) {
        this.onEnded = onEnded;
    }

    /** What the flags and rows ask now. */
    ReleaseSpec spec() {
        Map<String, String> picked = new LinkedHashMap<>();
        for (Row row : table.getItems()) {
            if (!row.request.get().isBlank()) {
                picked.put(row.module.name(), row.request.get());
            }
        }
        return new ReleaseSpec(all.isSelected() ? Optional.ofNullable(level.getValue()) : Optional.empty(), picked,
                force.isSelected(), withDependents.isSelected(), noWait.isSelected());
    }

    void request(String module, String request) {
        table.getItems().stream().filter(row -> row.module.name().equals(module)).findFirst()
                .ifPresent(row -> row.request.set(request));
        specChanged();
    }

    CheckBox all() {
        return all;
    }

    ComboBox<Level> level() {
        return level;
    }

    Button previewButton() {
        return preview;
    }

    Button executeButton() {
        return execute;
    }

    Button stopButton() {
        return stop;
    }

    Button resumeButton() {
        return resume;
    }

    Banner banner() {
        return banner;
    }

    String output() {
        return output.getText();
    }

    String commandLine() {
        return commandLine.getText();
    }

    String cut(String module) {
        return table.getItems().stream().filter(row -> row.module.name().equals(module)).findFirst()
                .map(row -> row.cut.get()).orElse("");
    }

    private void specChanged() {
        ReleaseSpec spec = spec();
        commandLine.setText(spec.empty() ? "Tick --all or type a level or a version in a module's row."
                : spec.commandLine());
        if (armed.isPresent() && !armed.get().equals(spec)) {
            armed = Optional.empty();
            status.setText("The flags changed since the preview: preview again.");
        }
        buttons();
    }

    private void buttons() {
        boolean busy = running();
        ReleaseSpec spec = spec();
        preview.setDisable(busy || previewing || spec.empty());
        execute.setDisable(busy || previewing || armed.isEmpty() || !armed.get().equals(spec));
        stop.setDisable(!busy || stopAsked.get());
        resume.setDisable(busy || previewing || resumable.isEmpty());
        all.setDisable(busy);
        table.setEditable(!busy);
    }

    private void preview() {
        ReleaseSpec spec = spec();
        if (!spec.wellFormed()) {
            banner.show("Not a request", spec.picked().entrySet().stream()
                    .filter(e -> !ReleaseSpec.wellFormed(e.getValue()))
                    .map(e -> e.getKey() + ": \"" + e.getValue() + "\" is neither patch, minor, major nor a version")
                    .toList());
            return;
        }
        armed = Optional.empty();
        previewed = Optional.empty();
        previewing = true;
        banner.hide();
        verdicts.getChildren().clear();
        status.setText("Deciding and running the gates…");
        buttons();
        Io.async(() -> backend.preview(umbrella.root(), spec)).whenComplete((result, error) -> Platform.runLater(() -> {
            previewing = false;
            if (error != null) {
                status.setText("");
                banner.show("The preview could not run", List.of(Io.message(error)));
                previewed = Optional.empty();
            } else {
                previewed(spec, result);
            }
            buttons();
        }));
    }

    private void previewed(ReleaseSpec spec, Release.Preview result) {
        previewed = Optional.of(result);
        output.setText(String.join("\n", ConsoleText.preview(result)) + "\n");
        for (Row row : table.getItems()) {
            row.cut.set(result.plan().decision(row.module).map(ReleaseTab::cutText).orElse(""));
        }
        result.verdicts().forEach(this::verdict);
        List<String> tags = ReleaseConfirm.tags(result.plan(), umbrella.config().tagPattern());
        if (tags.isEmpty()) {
            status.setText("Nothing to release.");
        } else if (result.refused()) {
            banner.show("A gate refused", result.verdicts().stream().filter(GateVerdict::stops)
                    .map(v -> v.gate() + ": " + v.line() + (v.refusal().isBlank() ? "" : "\n" + v.refusal()))
                    .toList());
            status.setText("Refused: nothing would be tagged.");
        } else if (spec.equals(spec())) {
            armed = Optional.of(spec);
            status.setText(tags.size() + " tag" + (tags.size() == 1 ? "" : "s") + " would be cut.");
        }
    }

    private static String cutText(Decision decision) {
        return decision.release().map(version -> decision.base() + " → " + version)
                .orElse("left (" + decision.reason() + ")");
    }

    private void verdict(GateVerdict verdict) {
        Label line = new Label("[" + verdict.status() + "] " + verdict.gate() + ": " + verdict.line());
        line.setWrapText(true);
        line.getStyleClass().add(switch (verdict.status()) {
            case OK -> "cell--ok";
            case REFUSED -> "cell--broken";
            case FORCED -> "cell--pending";
            case SKIPPED -> "cell--dim";
        });
        verdicts.getChildren().add(line);
    }

    private void execute() {
        ReleaseSpec spec = spec();
        if (armed.isEmpty() || !armed.get().equals(spec) || previewed.isEmpty()) {
            return;
        }
        List<String> tags = ReleaseConfirm.tags(previewed.get().plan(), umbrella.config().tagPattern());
        if (!ReleaseConfirm.ask(getScene() == null ? null : getScene().getWindow(), spec.executeCommandLine(), tags,
                WARNING)) {
            return;
        }
        start(spec, (stopFlag, events) -> backend.run(umbrella.root(), spec, stopFlag, events));
    }

    private void resume() {
        if (resumable.isEmpty()) {
            return;
        }
        ReleaseSpec spec = spec();
        List<String> left = resumable.get().modules().stream()
                .filter(entry -> entry.stage() != Stage.SNAPSHOT)
                .map(entry -> "    " + entry.module() + "  " + entry.version().tag(umbrella.config().tagPattern())
                        + "  (" + entry.stage().name().toLowerCase().replace('_', ' ') + ")")
                .toList();
        if (!ReleaseConfirm.ask(getScene() == null ? null : getScene().getWindow(), spec.resumeCommandLine(),
                left, "Goes on with the newest run that did not finish, each module from the stage it reached. "
                        + WARNING)) {
            return;
        }
        start(spec, (stopFlag, events) -> backend.resume(umbrella.root(), spec, stopFlag, events));
    }

    @FunctionalInterface
    private interface Runs {
        Release.Outcome run(BooleanSupplier stop, Consumer<Event> events);
    }

    private void start(ReleaseSpec spec, Runs runs) {
        armed = Optional.empty();
        stopAsked.set(false);
        banner.hide();
        verdicts.getChildren().clear();
        output.clear();
        ReleaseWatch started = new ReleaseWatch(umbrella.config().tagPattern(), Instant.now());
        watch = started;
        board.setVisible(true);
        board.setManaged(true);
        status.setText("Running…");
        ticker.play();
        redraw();
        buttons();
        Io.async(() -> runs.run(stopAsked::get, event -> {
            started.accept(event, Instant.now());
            Platform.runLater(() -> shown(event));
        })).whenComplete((outcome, error) -> {
            if (error != null) {
                started.threw(Io.message(error), Instant.now());
            } else {
                started.ended(outcome.status(), Instant.now());
            }
            Platform.runLater(() -> ended(outcome, error));
        });
    }

    private void shown(Event event) {
        ConsoleText.event(event).forEach(line -> output.appendText(line + "\n"));
        if (event instanceof Event.Verdict v) {
            verdict(v.verdict());
        }
        redraw();
    }

    private void ended(Release.Outcome outcome, Throwable error) {
        ticker.stop();
        redraw();
        if (error != null) {
            boolean refused = watch.progress(Instant.now()).phase() == ReleaseProgress.Phase.REFUSED;
            output.appendText((refused ? "refused: " : "stopped: ") + Io.message(error) + "\n");
            banner.show(refused ? "The release did not start" : "The run stopped", List.of(Io.message(error)));
            status.setText(refused ? "Refused: nothing was tagged." : "Stopped: Resume goes on from here.");
        } else {
            output.appendText(ConsoleText.ended(outcome.status()) + "\n");
            outcome.log().ifPresent(log -> output.appendText("log: " + log + "\n"));
            status.setText(ConsoleText.ended(outcome.status()));
            if (outcome.status() == Release.Status.REFUSED) {
                banner.show("A gate refused", watch.progress(Instant.now()).verdicts().stream()
                        .filter(GateVerdict::stops).map(v -> v.gate() + ": " + v.line()).toList());
            }
        }
        refreshResumable();
        onEnded.run();
    }

    private void redraw() {
        if (watch == null) {
            return;
        }
        ReleaseProgress progress = watch.progress(Instant.now());
        board.show(progress);
        badge.show(progress);
        buttons();
    }

    private void refreshResumable() {
        Io.async(() -> backend.resumable(umbrella.root())).whenComplete((state, error) -> Platform.runLater(() -> {
            resumable = error == null ? state : Optional.empty();
            resume.setText(resumable.map(s -> "Resume… (" + s.modules().stream()
                    .filter(e -> e.stage() != Stage.SNAPSHOT).count() + " left)")
                    .orElse("Resume…"));
            buttons();
        }));
    }
}
