package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.desktop.model.ConfigValues;
import com.botmaker.sluice.desktop.model.Umbrella;
import com.botmaker.sluice.model.Edge;
import com.botmaker.sluice.model.Module;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Every module the umbrella holds, with what {@code sluice.yml} says of it under {@code modules}: its alias, kind,
 * place in the tag order and target. Its pins are its poms', shown and never edited here: a version moves only by a
 * release.
 */
final class ConfigModulesSection extends VBox {

    private final ConfigForm.Host host;
    private final TableView<Module> table = new TableView<>();
    private final ConfigForm form;

    ConfigModulesSection(ConfigForm.Host host, Umbrella umbrella) {
        super(8);
        this.host = host;
        getStyleClass().add("config-section");
        setPadding(new Insets(10));
        List<String> names = umbrella.modules().stream().map(Module::name).toList();

        TableColumn<Module, String> name = new TableColumn<>("Module");
        name.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        name.setPrefWidth(220);
        table.getColumns().add(name);
        for (String key : List.of("alias", "kind", "place", "target")) {
            TableColumn<Module, String> column = new TableColumn<>(key.substring(0, 1).toUpperCase() + key.substring(1));
            column.setCellValueFactory(c -> new SimpleStringProperty(
                    ConfigValues.text(host.doc().get(List.of("modules", c.getValue().name(), key)))));
            column.setPrefWidth(key.equals("alias") ? 140 : 110);
            table.getColumns().add(column);
        }
        TableColumn<Module, String> pins = new TableColumn<>("Pins");
        pins.setCellValueFactory(c -> new SimpleStringProperty(pins(umbrella, c.getValue())));
        pins.setPrefWidth(220);
        table.getColumns().add(pins);
        table.getItems().setAll(umbrella.modules());
        table.setPlaceholder(new Label("The umbrella has no module."));
        table.setPrefHeight(320);

        form = new ConfigForm(host);
        Function<String, Supplier<List<Object>>> at = key -> () -> List.of("modules", selected(), key);
        form.text("Alias", at.apply("alias"));
        form.choice("Kind", at.apply("kind"), () -> List.of("", "maven", "tag-only"));
        form.place("Place", at.apply("place"), () -> names.stream().filter(n -> !n.equals(selected())).toList());
        form.target("Target", at.apply("target"));
        form.shown("Pins", () -> table.getSelectionModel().getSelectedItem() == null ? ""
                : pins(umbrella, table.getSelectionModel().getSelectedItem()));
        form.note("— leaves the key out: kind maven, the place the graph gives, the umbrella's target. "
                + "Pins are the poms', moved by a release.");
        table.getSelectionModel().selectedItemProperty().addListener((o, was, is) -> showForm());

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(table, scroll);
        if (!table.getItems().isEmpty()) {
            table.getSelectionModel().selectFirst();
        }
    }

    void refresh() {
        table.refresh();
        showForm();
    }

    void select(String module) {
        table.getItems().stream().filter(m -> m.name().equals(module)).findFirst()
                .ifPresent(m -> table.getSelectionModel().select(m));
    }

    private void showForm() {
        boolean any = table.getSelectionModel().getSelectedItem() != null;
        form.setDisable(!any);
        if (any) {
            form.refresh();
        }
    }

    private String selected() {
        Module module = table.getSelectionModel().getSelectedItem();
        return module == null ? "" : module.name();
    }

    /** {@code a 1.0.0, b 0.2.0}: the upstreams a pom of the module pins, as the umbrella was read. */
    private static String pins(Umbrella umbrella, Module module) {
        return String.join(", ", umbrella.graph().upstreams(module).stream().filter(Edge::pinnedInAPom)
                .map(e -> e.upstream().name()).toList());
    }
}
