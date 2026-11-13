package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.Version;
import com.botmaker.sluice.desktop.model.Io;
import com.botmaker.sluice.desktop.model.Links;
import com.botmaker.sluice.desktop.model.Umbrella;
import com.botmaker.sluice.github.GitHub;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.spi.PublishTarget;
import com.botmaker.sluice.status.ModuleStatus;
import com.botmaker.sluice.status.ModuleStatus.PinState;
import com.botmaker.sluice.target.Targets;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Every module of the umbrella and where it stands: its version on the branch, its newest tag, CI, whether its
 * publish target serves that tag, and whether its pins name its upstreams' versions. Each cell fills when its
 * answer comes; nothing here changes anything.
 */
public final class ModulesTab extends BorderPane {

    /** What a cell's text says of the state, for its colour. */
    enum Tone {
        OK("cell--ok"), BROKEN("cell--broken"), PENDING("cell--pending"), DIM("cell--dim");

        private final String styleClass;

        Tone(String styleClass) {
            this.styleClass = styleClass;
        }

        String styleClass() {
            return styleClass;
        }
    }

    /** A cell's words and colour. */
    record Cell(String text, Tone tone, String detail) {
        static final Cell ASKING = new Cell("…", Tone.DIM, "");
    }

    static final class Row {
        final Module module;
        final Optional<Links> links;
        final StringProperty onMain = new SimpleStringProperty("");
        Cell tag = Cell.ASKING;
        Cell ci = Cell.ASKING;
        Cell build = Cell.ASKING;
        Cell pins = Cell.ASKING;

        Row(Module module) {
            this.module = module;
            this.links = Links.of(module);
            onMain.set(module.version().orElse("—"));
        }
    }

    private Umbrella umbrella;
    private final Consumer<String> open;
    private final TableView<Row> table = new TableView<>();
    private final Label status = new Label();
    private final Button refresh = new Button("Refresh");

    public ModulesTab(Umbrella umbrella, Consumer<String> open) {
        this.umbrella = umbrella;
        this.open = open;
        getStyleClass().add("modules-tab");

        TableColumn<Row, String> name = new TableColumn<>("Module");
        name.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().module.name()));
        name.setPrefWidth(220);
        TableColumn<Row, String> kind = new TableColumn<>("Kind");
        kind.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().module.kind().id()));
        TableColumn<Row, String> onMain = new TableColumn<>("On " + umbrella.config().branch());
        onMain.setCellValueFactory(c -> c.getValue().onMain);
        onMain.setPrefWidth(130);
        table.getColumns().setAll(List.of(name, kind, onMain,
                stateColumn("Newest tag", row -> row.tag, 120),
                stateColumn("CI", row -> row.ci, 110),
                stateColumn("Target build", row -> row.build, 140),
                stateColumn("Pins", row -> row.pins, 110),
                linksColumn()));
        table.setPlaceholder(new Label("No module in sluice.yml's umbrella."));
        umbrella.modules().forEach(module -> table.getItems().add(new Row(module)));

        refresh.setOnAction(e -> refresh());
        status.getStyleClass().add("status-line");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, refresh, spacer, status);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 10, 8, 10));
        setTop(bar);
        setCenter(table);
        refresh();
    }

    /** Reads the umbrella again, its versions and pins as they are now, then asks every cell again. */
    public void refresh() {
        refresh.setDisable(true);
        status.setText("Reading the umbrella…");
        Path root = umbrella.root();
        Io.async(() -> Umbrella.open(root)).whenComplete((reread, unreadable) -> Platform.runLater(() -> {
            if (unreadable == null) {
                umbrella = reread;
                table.getItems().setAll(reread.modules().stream().map(Row::new).toList());
                ask();
            } else {
                refresh.setDisable(false);
                status.setText("Could not read the umbrella again: " + Io.message(unreadable));
            }
        }));
    }

    private void ask() {
        status.setText("Asking each module's remote, CI and target…");
        Function<Module, PublishTarget> targets = Targets.of(umbrella.config());
        Io.async(GitHub::fromEnvironment).whenComplete((github, error) -> {
            Optional<GitHub> gh = error == null ? github : Optional.empty();
            List<Row> rows = List.copyOf(table.getItems());
            Platform.runLater(() -> rows.forEach(row -> {
                row.tag = row.ci = row.build = Cell.ASKING;
                row.pins = pins(row.module);
            }));
            Platform.runLater(table::refresh);
            List<CompletableFuture<Void>> asked = rows.stream().map(row -> Io.run(() -> {
                Cell ci = ci(row.module, gh);
                post(() -> row.ci = ci);
                Optional<Version> tag = ModuleStatus.latestTag(row.module, umbrella.config());
                Cell tagCell = tag.map(v -> new Cell(v.tag(umbrella.config().tagPattern()), Tone.OK, ""))
                        .orElse(new Cell("never tagged", Tone.DIM, ""));
                Cell build = tag.map(v -> build(row.module, v, targets.apply(row.module)))
                        .orElse(new Cell("—", Tone.DIM, ""));
                post(() -> {
                    row.tag = tagCell;
                    row.build = build;
                });
            }).exceptionally(failure -> {
                Cell broken = new Cell("failed", Tone.BROKEN, Io.message(failure));
                post(() -> {
                    if (row.tag == Cell.ASKING) {
                        row.tag = broken;
                    }
                    if (row.ci == Cell.ASKING) {
                        row.ci = broken;
                    }
                    if (row.build == Cell.ASKING) {
                        row.build = broken;
                    }
                });
                return null;
            })).toList();
            CompletableFuture.allOf(asked.toArray(CompletableFuture[]::new))
                    .whenComplete((done, failed) -> Platform.runLater(() -> {
                        refresh.setDisable(false);
                        status.setText(gh.isEmpty() ? "No GitHub token (GITHUB_TOKEN, or gh auth token): CI is unknown."
                                : "");
                    }));
        });
    }

    Row row(String module) {
        return table.getItems().stream().filter(r -> r.module.name().equals(module)).findFirst().orElseThrow();
    }

    private void post(Runnable change) {
        Platform.runLater(() -> {
            change.run();
            table.refresh();
        });
    }

    private Cell pins(Module module) {
        List<PinState> pins = ModuleStatus.pins(umbrella.graph(), module);
        if (pins.isEmpty()) {
            return new Cell("—", Tone.DIM, "pins no upstream");
        }
        List<PinState> behind = pins.stream().filter(pin -> !pin.current()).toList();
        if (behind.isEmpty()) {
            return new Cell("current", Tone.OK, pins.size() + " pin(s) at their upstreams' versions");
        }
        return new Cell(behind.size() + " behind", Tone.PENDING, String.join("\n", behind.stream()
                .map(pin -> pin.edge().upstream().name() + ": " + pin.held().orElse("?") + ", its version "
                        + pin.upstreamVersion().orElse("?")).toList()));
    }

    private Cell ci(Module module, Optional<GitHub> github) {
        return ci(ModuleStatus.ci(module, umbrella.config(), github));
    }

    private static Cell ci(ModuleStatus.Ci ci) {
        Tone tone = switch (ci.state()) {
            case GREEN -> Tone.OK;
            case RED -> Tone.BROKEN;
            case UNKNOWN -> Tone.DIM;
        };
        String text = ci.state().name().toLowerCase() + (ci.running() ? " ⟳" : "");
        return new Cell(text, tone, ci.line());
    }

    private Cell build(Module module, Version version, PublishTarget target) {
        ModuleStatus.TargetBuild build = ModuleStatus.build(module, version, umbrella.config(), target);
        Tone tone = switch (build.state()) {
            case SERVED -> Tone.OK;
            case FAILED -> Tone.BROKEN;
            case NOT_YET -> Tone.PENDING;
            case UNREACHABLE, NOT_PUBLISHED -> Tone.DIM;
        };
        return new Cell(build.state().name().toLowerCase().replace('_', ' '), tone, build.line());
    }

    private static TableColumn<Row, Cell> stateColumn(String title, Function<Row, Cell> cell, double width) {
        TableColumn<Row, Cell> column = new TableColumn<>(title);
        column.setCellValueFactory(c -> new SimpleObjectProperty<>(cell.apply(c.getValue())));
        column.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Cell item, boolean empty) {
                super.updateItem(item, empty);
                for (Tone tone : Tone.values()) {
                    getStyleClass().remove(tone.styleClass());
                }
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                setText(item.text());
                getStyleClass().add(item.tone().styleClass());
                setTooltip(item.detail().isBlank() ? null : new Tooltip(item.detail()));
            }
        });
        column.setPrefWidth(width);
        return column;
    }

    private TableColumn<Row, Row> linksColumn() {
        TableColumn<Row, Row> column = new TableColumn<>("Links");
        column.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue()));
        column.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Row row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null || row.links.isEmpty()) {
                    setGraphic(null);
                    setText(empty || row == null ? null : "not on GitHub");
                    return;
                }
                HBox box = new HBox(6);
                box.getStyleClass().add("link-bar");
                for (Links.Link link : row.links.get().forModule(umbrella.config().branch())) {
                    Hyperlink button = new Hyperlink(link.label());
                    button.getStyleClass().add("link-button");
                    button.setOnAction(e -> open.accept(link.url()));
                    box.getChildren().add(button);
                }
                setText(null);
                setGraphic(box);
            }
        });
        column.setPrefWidth(200);
        return column;
    }
}
