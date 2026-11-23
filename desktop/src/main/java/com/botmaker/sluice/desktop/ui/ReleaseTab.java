// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.Level;
import com.botmaker.sluice.decide.Decider;
import com.botmaker.sluice.decide.Decision;
import com.botmaker.sluice.desktop.model.Io;
import com.botmaker.sluice.desktop.model.Links;
import com.botmaker.sluice.desktop.model.ReleaseProgress;
import com.botmaker.sluice.desktop.model.ReleaseSpec;
import com.botmaker.sluice.desktop.model.ReleaseSpec.DependentsFlag;
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
import javafx.collections.FXCollections;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Cuts a release: flags and a request per module, the same words the command takes; Preview runs the decide pass
 * and every gate; Execute… is armed only by a clean preview of exactly what the flags say now, and asks before it
 * tags. The run is the library's, in this process: its events draw the board, Stop asks it to stop between steps,
 * and Resume goes on with a run that did not finish.
 */
public final class ReleaseTab extends BorderPane {

    private static final int OUTPUT_CAP = 400_000;

    private static final String WARNING = "Each tag is pushed and published. A pushed tag is final: a mistake is "
            + "repaired by the next release, never by moving the tag.";

    /** Read again after each run, which moves versions and pins. */
    private Umbrella umbrella;
    private final ReleaseBackend backend;

    private final CheckBox all = new CheckBox("--all");
    private final ComboBox<Level> level = new ComboBox<>();
    private final CheckBox force = new CheckBox("--force");
    private final ComboBox<DependentsFlag> dependents = new ComboBox<>();
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
    private BooleanSupplier blocked = () -> false;

    /** The spec the last clean preview was of; Execute… cuts only that. */
    private Optional<ReleaseSpec> armed = Optional.empty();
    private Optional<Release.Preview> previewed = Optional.empty();
    /** A preview is running: no other starts until it answers or Stop drops it. */
    private boolean previewing;
    /** Which preview is the current one: an answer of an earlier, dropped one is ignored. */
    private long previewRun;
    /**
     * A dropped preview's gates still run (git fetches, builds in the shared gate repository): nothing else starts
     * until they end, or two would build in one repository at once.
     */
    private boolean droppedRunning;
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
        Io.async(() -> Links.of(umbrella.modules()))
                .thenAccept(links -> Platform.runLater(() -> board.setLinks(links)));
        getStyleClass().add("release-tab");

        level.getItems().setAll(Level.values());
        level.setValue(Level.PATCH);
        level.setDisable(true);
        all.selectedProperty().addListener((o, was, is) -> {
            level.setDisable(!is);
            specChanged();
        });
        level.valueProperty().addListener((o, was, is) -> specChanged());
        dependents.getItems().setAll(DependentsFlag.values());
        dependents.setValue(DependentsFlag.CONFIG);
        dependents.valueProperty().addListener((o, was, is) -> specChanged());
        for (CheckBox box : List.of(force, noWait)) {
            box.selectedProperty().addListener((o, was, is) -> specChanged());
        }
        force.getStyleClass().add("danger");
        FlowPane flags = new FlowPane(12, 8, all, level, dependents, force, noWait);
        flags.setAlignment(Pos.CENTER_LEFT);
        flags.getStyleClass().add("flag-box");

        TableColumn<Row, String> name = new TableColumn<>("Module");
        name.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().module.name()));
        name.setPrefWidth(220);
        TableColumn<Row, String> kind = new TableColumn<>("Kind");
        kind.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().module.kind().id()));
        TableColumn<Row, String> request = new TableColumn<>("Request");
        request.setCellValueFactory(c -> c.getValue().request);
        // A level picked from the list, or a version typed into it.
        request.setCellFactory(column -> {
            ComboBoxTableCell<Row, String> cell = new ComboBoxTableCell<>(
                    FXCollections.observableArrayList("", "patch", "minor", "major"));
            cell.setComboBoxEditable(true);
            return cell;
        });
        request.setOnEditCommit(e -> {
            e.getRowValue().request.set(e.getNewValue() == null ? "" : e.getNewValue().strip());
            specChanged();
        });
        request.setPrefWidth(140);
        TableColumn<Row, String> cut = new TableColumn<>("Would cut");
        cut.setCellValueFactory(c -> c.getValue().cut);
        cut.setPrefWidth(260);
        table.getColumns().setAll(List.of(name, kind, request, cut));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
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

        // Twenty verdicts would push the table off screen: they scroll in a bounded box.
        ScrollPane verdictScroll = new ScrollPane(verdicts);
        verdictScroll.setFitToWidth(true);
        verdictScroll.setMaxHeight(160);
        verdictScroll.getStyleClass().add("edge-to-edge");
        status.setWrapText(true);
        status.setMinWidth(0);
        VBox top = new VBox(8, flags, table, commandLine, buttons, banner, verdictScroll);
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
        if (showing && !running()) {
            // A run in a terminal may have left, or finished, a resumable one since.
            refreshResumable();
        }
    }

    /** Reads the umbrella again after a run: its rows start over, and any preview is of the old state. */
    public void reopen() {
        Path root = umbrella.root();
        Io.async(() -> Umbrella.open(root)).whenComplete((reread, error) -> Platform.runLater(() -> {
            if (error != null) {
                status.setText("Could not read the umbrella again: " + Io.message(error));
                return;
            }
            umbrella = reread;
            table.getItems().setAll(reread.modules().stream().map(Row::new).toList());
            Io.async(() -> Links.of(reread.modules()))
                    .thenAccept(links -> Platform.runLater(() -> board.setLinks(links)));
            armed = Optional.empty();
            previewed = Optional.empty();
            specChanged();
        }));
    }

    public boolean running() {
        return watch != null && watch.running();
    }

    /** While this says so, no release or resume starts: an update may be replacing the files it would run on. */
    public void setBlocked(BooleanSupplier blocked) {
        this.blocked = blocked;
    }

    /** Asks the run to stop after the step it is on. */
    public void stop() {
        if (previewing) {
            // A preview tags nothing: dropped at once, its answer ignored when it comes.
            previewRun++;
            previewing = false;
            droppedRunning = true;
            status.setText("Preview dropped; its current gate ends in the background first.");
            buttons();
            return;
        }
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
                force.isSelected(), dependents.getValue(), noWait.isSelected());
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
        preview.setDisable(busy || previewing || droppedRunning || spec.empty());
        execute.setDisable(busy || previewing || droppedRunning || armed.isEmpty() || !armed.get().equals(spec));
        stop.setDisable(!(busy || previewing) || stopAsked.get());
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
        long run = ++previewRun;
        banner.hide();
        verdicts.getChildren().clear();
        status.setText("Deciding and running the gates…");
        buttons();
        Io.async(() -> backend.preview(umbrella.root(), spec)).whenComplete((result, error) -> Platform.runLater(() -> {
            if (run != previewRun) {
                droppedRunning = false;
                status.setText("The dropped preview ended.");
                buttons();
                return;
            }
            previewing = false;
            if (!spec.equals(spec())) {
                // The rows or flags changed while it ran: its plan is of a request no longer on screen.
                status.setText("The flags changed while it ran: preview again.");
            } else if (error != null) {
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
                    .map(v -> String.join("\n", Stream.concat(Stream.of(v.gate() + ": " + v.line()),
                            v.repair().stream()).toList()))
                    .toList());
            status.setText("Refused: nothing would be tagged.");
        } else if (spec.equals(spec())) {
            armed = Optional.of(spec);
            List<String> added = added(result);
            status.setText(tags.size() + " tag" + (tags.size() == 1 ? "" : "s") + " would be cut."
                    + (added.isEmpty() ? "" : " Added because a release owes them: " + String.join(", ", added)
                    + " (pick --no-dependents to leave them out)."));
        }
    }

    private static String cutText(Decision decision) {
        // An owed dependent the run adds says so, so the operator sees it was not asked for.
        String added = decision.reason().startsWith(Decider.ADDED) ? "  (" + decision.reason() + ")" : "";
        return decision.release().map(version -> decision.base() + " → " + version + added)
                .orElse("left (" + decision.reason() + ")");
    }

    /** The modules the plan adds because a release owes them, by name. */
    private static List<String> added(Release.Preview result) {
        return result.plan().releasing().keySet().stream()
                .filter(module -> result.plan().decision(module)
                        .filter(d -> d.reason().startsWith(Decider.ADDED)).isPresent())
                .map(Module::name).toList();
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
                ReleaseConfirm.forced(previewed.get().verdicts()), WARNING)) {
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
                        + "  (" + entry.stage().name().toLowerCase().replace('_', ' ') + ")"
                        + (entry.detail().isBlank() ? "" : ": " + entry.detail()))
                .toList();
        if (!ReleaseConfirm.ask(getScene() == null ? null : getScene().getWindow(), spec.resumeCommandLine(),
                left, "Goes on with the newest run that did not finish, each module from the stage it reached; "
                        + "the stage says where it stopped, and why when the run said. The request rows and "
                        + "--all are ignored: a resumed run releases what it decided. " + WARNING)) {
            return;
        }
        start(spec, (stopFlag, events) -> backend.resume(umbrella.root(), spec, stopFlag, events));
    }

    @FunctionalInterface
    private interface Runs {
        Release.Outcome run(BooleanSupplier stop, Consumer<Event> events);
    }

    private void start(ReleaseSpec spec, Runs runs) {
        if (blocked.getAsBoolean()) {
            status.setText("sluice is updating itself: start the release once the update has ended.");
            return;
        }
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
        // A build streams hundreds of lines a second: they queue here and the FX thread takes them in one go.
        Queue<Event> pending = new ConcurrentLinkedQueue<>();
        AtomicBoolean drawing = new AtomicBoolean();
        Io.async(() -> runs.run(stopAsked::get, event -> {
            started.accept(event, Instant.now());
            pending.add(event);
            if (drawing.compareAndSet(false, true)) {
                Platform.runLater(() -> {
                    drawing.set(false);
                    shown(pending);
                });
            }
        })).whenComplete((outcome, error) -> {
            if (error != null) {
                started.threw(Io.message(error), Instant.now());
            } else {
                started.ended(outcome.status(), Instant.now());
            }
            Platform.runLater(() -> ended(outcome, error));
        });
    }

    private void shown(Queue<Event> pending) {
        StringBuilder lines = new StringBuilder();
        for (Event event; (event = pending.poll()) != null; ) {
            ConsoleText.event(event).forEach(line -> lines.append(line).append('\n'));
            if (event instanceof Event.Verdict v) {
                verdict(v.verdict());
            }
        }
        append(lines.toString());
        redraw();
    }

    /** The output keeps its last {@value #OUTPUT_CAP} characters: the whole run is in its log file. */
    private void append(String text) {
        if (text.isEmpty()) {
            return;
        }
        output.appendText(text);
        int over = output.getLength() - OUTPUT_CAP;
        if (over > 0) {
            int cut = output.getText().indexOf('\n', over);
            output.deleteText(0, cut < 0 ? over : cut + 1);
        }
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
            if (error != null) {
                status.setText("Could not read the last run's state: " + Io.message(error));
            }
            resume.setText(resumable.map(s -> "Resume… (" + s.modules().stream()
                    .filter(e -> e.stage() != Stage.SNAPSHOT).count() + " left)")
                    .orElse("Resume…"));
            buttons();
        }));
    }
}
