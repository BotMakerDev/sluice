package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.desktop.model.ConfigValues;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code after-release}: per module, the pom properties its back-to-snapshot commit sets, {@code {tag}} and
 * {@code {version}} standing for what was released. One row per property.
 */
final class ConfigAfterRelease extends VBox {

    private static final String KEY = "after-release";

    /** One property of one module. */
    record Entry(String module, String property) {
    }

    private final ConfigForm.Host host;
    private final TableView<Entry> table = new TableView<>();
    private final ConfigForm form;
    private final Button remove = new Button("Remove");

    ConfigAfterRelease(ConfigForm.Host host, List<String> modules) {
        super(8);
        this.host = host;
        getStyleClass().add("config-section");
        setPadding(new Insets(10));

        TableColumn<Entry, String> module = new TableColumn<>("Module");
        module.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().module()));
        module.setPrefWidth(200);
        TableColumn<Entry, String> property = new TableColumn<>("Property");
        property.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().property()));
        property.setPrefWidth(240);
        TableColumn<Entry, String> value = new TableColumn<>("Value");
        value.setCellValueFactory(c -> new SimpleStringProperty(ConfigValues.text(host.doc().get(path(c.getValue())))));
        value.setPrefWidth(200);
        table.getColumns().setAll(List.of(module, property, value));
        table.setPlaceholder(new Label("No property set after a release."));
        table.setPrefHeight(240);

        form = new ConfigForm(host);
        form.text("Value", () -> {
            Entry entry = table.getSelectionModel().getSelectedItem();
            return entry == null ? List.of(KEY) : path(entry);
        });
        table.getSelectionModel().selectedItemProperty().addListener((o, was, is) -> showForm());

        ComboBox<String> newModule = new ComboBox<>();
        newModule.getItems().setAll(modules);
        newModule.setPromptText("module");
        TextField newProperty = new TextField();
        newProperty.setPromptText("pom property");
        TextField newValue = new TextField("{tag}");
        HBox.setHgrow(newProperty, Priority.ALWAYS);
        Button add = new Button("Add");
        add.setOnAction(e -> {
            String m = newModule.getValue();
            String p = newProperty.getText().strip();
            String v = newValue.getText().strip();
            if (m == null || p.isEmpty() || v.isEmpty()) {
                return;
            }
            host.edit(d -> d.set(List.of(KEY, m, p), v));
            newProperty.clear();
            table.getSelectionModel().select(new Entry(m, p));
        });
        remove.setOnAction(e -> {
            Entry entry = table.getSelectionModel().getSelectedItem();
            if (entry != null) {
                host.edit(d -> ConfigValues.put(d, path(entry), null));
            }
        });
        HBox adding = new HBox(8, newModule, newProperty, newValue, add);
        adding.setAlignment(Pos.CENTER_LEFT);
        Label heading = new Label("After release");
        heading.getStyleClass().add("placeholder-title");
        HBox bar = new HBox(8, heading, remove);
        bar.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(bar, table, adding, form);
    }

    void refresh() {
        Entry was = table.getSelectionModel().getSelectedItem();
        List<Entry> entries = new ArrayList<>();
        host.doc().get(List.of(KEY)).ifPresent(v -> {
            if (v instanceof Map<?, ?> byModule) {
                byModule.forEach((m, props) -> {
                    if (props instanceof Map<?, ?> map) {
                        map.keySet().forEach(p -> entries.add(new Entry(String.valueOf(m), String.valueOf(p))));
                    }
                });
            }
        });
        table.getItems().setAll(entries);
        if (was != null && entries.contains(was)) {
            table.getSelectionModel().select(was);
        }
        table.refresh();
        showForm();
    }

    private void showForm() {
        boolean any = table.getSelectionModel().getSelectedItem() != null;
        form.setDisable(!any);
        remove.setDisable(!any);
        if (any) {
            form.refresh();
        }
    }

    private static List<Object> path(Entry entry) {
        return List.of(KEY, entry.module(), entry.property());
    }
}
