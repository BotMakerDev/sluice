package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.desktop.model.ConfigValues;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * A list of {@code sluice.yml} ({@code gates}, {@code steps}, {@code edges}, {@code text-pins}): a table of its
 * entries, Add and Remove, and the selected entry's form under it.
 */
final class ConfigListSection extends VBox {

    private final ConfigForm.Host host;
    private final String key;
    private final TableView<Integer> table = new TableView<>();
    private final ConfigForm form;
    private final Button remove = new Button("Remove");

    /**
     * @param key      the list's top-level key
     * @param columns  the table's columns: a header and the entry's key it shows
     * @param template what Add appends
     * @param fields   fills the form, given it and a path maker for the selected entry's keys
     */
    ConfigListSection(ConfigForm.Host host, String title, String key, List<Map.Entry<String, String>> columns,
                      Map<String, Object> template, BiConsumer<ConfigForm, Function<String, Supplier<List<Object>>>> fields) {
        super(8);
        this.host = host;
        this.key = key;
        getStyleClass().add("config-section");
        setPadding(new Insets(10));

        for (Map.Entry<String, String> column : columns) {
            TableColumn<Integer, String> shown = new TableColumn<>(column.getKey());
            shown.setCellValueFactory(c -> new SimpleStringProperty(
                    ConfigValues.text(host.doc().get(List.of(key, c.getValue(), column.getValue())))));
            shown.setPrefWidth(180);
            table.getColumns().add(shown);
        }
        table.setPlaceholder(new Label("No entry under " + key + "."));
        table.setPrefHeight(200);

        form = new ConfigForm(host);
        fields.accept(form, entryKey -> () -> List.of(key, selected(), entryKey));
        table.getSelectionModel().selectedItemProperty().addListener((o, was, is) -> showForm());

        Button add = new Button("Add");
        add.setId("add-" + key);
        add.setOnAction(e -> {
            int at = size();
            host.edit(d -> d.set(List.of(key, at), template));
            table.getSelectionModel().select(Integer.valueOf(at));
        });
        remove.setId("remove-" + key);
        remove.setOnAction(e -> {
            Integer at = table.getSelectionModel().getSelectedItem();
            if (at != null) {
                host.edit(d -> ConfigValues.prune(d.remove(List.of(key, at)), List.of(key)));
            }
        });
        Label heading = new Label(title);
        heading.getStyleClass().add("placeholder-title");
        HBox bar = new HBox(8, heading, add, remove);
        bar.setAlignment(Pos.CENTER_LEFT);

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(bar, table, scroll);
    }

    /** Reads the list again, keeping the selected entry when it is still there. */
    void refresh() {
        Integer was = table.getSelectionModel().getSelectedItem();
        int size = size();
        table.getItems().setAll(IntStream.range(0, size).boxed().toList());
        if (was != null && was < size) {
            table.getSelectionModel().select(was);
        } else if (size > 0) {
            table.getSelectionModel().select(Integer.valueOf(size - 1));
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

    private int selected() {
        Integer at = table.getSelectionModel().getSelectedItem();
        return at == null ? 0 : at;
    }

    private int size() {
        return host.doc().get(List.of(key)).map(v -> v instanceof List<?> list ? list.size() : 0).orElse(0);
    }
}
