package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.config.ConfigDocument;
import com.botmaker.sluice.desktop.model.ConfigValues;
import com.botmaker.sluice.desktop.model.ConfigValues.Command;
import com.botmaker.sluice.desktop.model.ConfigValues.Target;
import com.botmaker.sluice.desktop.model.ConfigValues.TargetKind;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * A column of labelled fields, each bound to one path of the edited {@code sluice.yml}. A field shows what the
 * document holds there and writes only when the person changes it, so what nobody touched keeps its spelling. A
 * field's path is read when it is shown: a list's form follows its selected entry.
 */
final class ConfigForm extends GridPane {

    /** The document the tab edits, and the one way to change it. */
    interface Host {
        ConfigDocument doc();

        void edit(UnaryOperator<ConfigDocument> change);
    }

    private final Host host;
    private final List<Runnable> refreshers = new ArrayList<>();
    private boolean refreshing;
    private int row;

    ConfigForm(Host host) {
        this.host = host;
        getStyleClass().add("config-form");
        setHgap(10);
        setVgap(8);
        setPadding(new Insets(10));
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(150);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        getColumnConstraints().setAll(labels, fields);
    }

    /** Every field reads the document again. */
    void refresh() {
        refreshing = true;
        try {
            refreshers.forEach(Runnable::run);
        } finally {
            refreshing = false;
        }
    }

    TextField text(String label, Supplier<List<Object>> path) {
        return textual(label, path, ConfigValues::text, String::strip);
    }

    TextField list(String label, Supplier<List<Object>> path) {
        return textual(label, path, ConfigValues::listText, ConfigValues::list);
    }

    TextField integer(String label, Supplier<List<Object>> path) {
        return textual(label, path, ConfigValues::text, ConfigValues::integer);
    }

    /** A choice among {@code options}, {@code ""} standing for no key; a value outside them is offered too. */
    ComboBox<String> choice(String label, Supplier<List<Object>> path, Supplier<List<String>> options) {
        return choice(label, path, options, ConfigValues::text, s -> s);
    }

    /** {@code first}, {@code last} or {@code after} one of {@code modules}. */
    ComboBox<String> place(String label, Supplier<List<Object>> path, Supplier<List<String>> modules) {
        return choice(label, path, () -> {
            List<String> options = new ArrayList<>(List.of("", "first", "last"));
            modules.get().forEach(m -> options.add("after " + m));
            return options;
        }, ConfigValues::place, ConfigValues::placeYaml);
    }

    CheckBox bool(String label, Supplier<List<Object>> path, boolean byDefault) {
        CheckBox box = new CheckBox();
        box.setId(id(label));
        refreshers.add(() -> box.setSelected(host.doc().get(path.get()).map(v -> v instanceof Boolean b ? b : byDefault)
                .orElse(byDefault)));
        box.setOnAction(e -> {
            List<Object> at = path.get();
            boolean on = box.isSelected();
            host.edit(d -> ConfigValues.put(d, at, on));
        });
        add(label, box);
        return box;
    }

    /** On while the key holds anything but {@code false}; ticked it writes {@code true}, unticked it goes. */
    CheckBox toggle(String label, Supplier<List<Object>> path) {
        CheckBox box = new CheckBox();
        box.setId(id(label));
        refreshers.add(() -> box.setSelected(host.doc().get(path.get()).filter(v -> !Boolean.FALSE.equals(v))
                .isPresent()));
        box.setOnAction(e -> {
            List<Object> at = path.get();
            boolean on = box.isSelected();
            host.edit(d -> on ? d.set(at, true) : ConfigValues.put(d, at, null));
        });
        add(label, box);
        return box;
    }

    /** Where it publishes: its kind and the URL or Maven version beside it. */
    void target(String label, Supplier<List<Object>> path) {
        ComboBox<TargetKind> kind = new ComboBox<>(FXCollections.observableArrayList(TargetKind.values()));
        kind.setId(id(label));
        kind.setButtonCell(targetCell());
        kind.setCellFactory(v -> targetCell());
        TextField detail = new TextField();
        detail.setId(id(label) + "-detail");
        HBox.setHgrow(detail, Priority.ALWAYS);
        Label note = new Label();
        note.getStyleClass().add("cell--dim");
        List<List<Object>> shownPath = new ArrayList<>(List.of(List.of()));
        String[] shownDetail = {""};
        refreshers.add(() -> {
            shownPath.set(0, path.get());
            Optional<Target> target = ConfigValues.target(host.doc().get(shownPath.getFirst()));
            kind.setDisable(target.isEmpty());
            detail.setDisable(target.isEmpty());
            note.setText(target.isEmpty() ? "a shape the form does not know: see the YAML" : "");
            Target shown = target.orElse(new Target(TargetKind.UNSET, ""));
            kind.setValue(shown.kind());
            shownDetail[0] = shown.detail();
            detail.setText(shown.detail());
            detail.setPromptText(switch (shown.kind()) {
                case MAVEN -> "the repository's URL";
                case JITPACK -> "Maven version, optional: 3.9.9";
                default -> "";
            });
        });
        Runnable write = () -> {
            if (refreshing || kind.getValue() == null) {
                return;
            }
            if (kind.getValue() == ConfigValues.target(host.doc().get(shownPath.getFirst())).map(Target::kind)
                    .orElse(null) && detail.getText().equals(shownDetail[0])) {
                return;
            }
            Object yaml = new Target(kind.getValue(), detail.getText()).yaml();
            List<Object> at = shownPath.getFirst();
            host.edit(d -> ConfigValues.put(d, at, yaml));
        };
        kind.setOnAction(e -> write.run());
        commitOn(detail, write);
        add(label, new HBox(8, kind, detail, note));
    }

    /** A command: what runs on Linux and on Windows, the same value written once when both are equal. */
    void command(String label, Supplier<List<Object>> path) {
        TextField linux = new TextField();
        TextField windows = new TextField();
        linux.setId(id(label) + "-linux");
        windows.setId(id(label) + "-windows");
        linux.setPromptText("a shell line, or [program, arg, …]");
        // A map with no windows key runs nothing there: blank is not "the same".
        windows.setPromptText("blank: nothing runs on Windows; type it again to run the same");
        List<List<Object>> shownPath = new ArrayList<>(List.of(List.of()));
        Command[] shown = {new Command("", "")};
        refreshers.add(() -> {
            shownPath.set(0, path.get());
            shown[0] = ConfigValues.command(host.doc().get(shownPath.getFirst()));
            linux.setText(shown[0].linux());
            windows.setText(shown[0].windows());
        });
        Runnable write = () -> {
            if (refreshing || linux.getText().equals(shown[0].linux()) && windows.getText().equals(shown[0].windows())) {
                return;
            }
            shown[0] = new Command(linux.getText(), windows.getText());
            Object yaml = shown[0].yaml();
            List<Object> at = shownPath.getFirst();
            host.edit(d -> ConfigValues.put(d, at, yaml));
        };
        commitOn(linux, write);
        commitOn(windows, write);
        add(label + " — Linux", linux);
        add(label + " — Windows", windows);
    }

    /** A line of text under the fields. */
    void note(String text) {
        Label note = new Label(text);
        note.setWrapText(true);
        note.getStyleClass().add("cell--dim");
        add(note, 0, row++, 2, 1);
    }

    /** A read-only line, its text from {@code value} each time the form is shown. */
    void shown(String label, Supplier<String> value) {
        Label shown = new Label();
        shown.setWrapText(true);
        refreshers.add(() -> shown.setText(value.get()));
        add(label, shown);
    }

    private ComboBox<String> choice(String label, Supplier<List<Object>> path, Supplier<List<String>> options,
                                    Function<Optional<Object>, String> read, Function<String, Object> write) {
        ComboBox<String> box = new ComboBox<>();
        box.setId(id(label));
        box.setButtonCell(choiceCell());
        box.setCellFactory(v -> choiceCell());
        List<List<Object>> shownPath = new ArrayList<>(List.of(List.of()));
        refreshers.add(() -> {
            shownPath.set(0, path.get());
            String value = read.apply(host.doc().get(shownPath.getFirst()));
            List<String> offered = new ArrayList<>(options.get());
            if (!offered.contains(value)) {
                offered.add(value);
            }
            box.getItems().setAll(offered);
            box.setValue(value);
        });
        box.setOnAction(e -> {
            if (refreshing || box.getValue() == null) {
                return;
            }
            List<Object> at = shownPath.getFirst();
            if (box.getValue().equals(read.apply(host.doc().get(at)))) {
                return;
            }
            Object yaml = write.apply(box.getValue());
            host.edit(d -> ConfigValues.put(d, at, yaml));
        });
        add(label, box);
        return box;
    }

    private TextField textual(String label, Supplier<List<Object>> path, Function<Optional<Object>, String> read,
                              Function<String, Object> write) {
        TextField field = new TextField();
        field.setId(id(label));
        List<List<Object>> shownPath = new ArrayList<>(List.of(List.of()));
        String[] shown = {""};
        refreshers.add(() -> {
            shownPath.set(0, path.get());
            shown[0] = read.apply(host.doc().get(shownPath.getFirst()));
            field.setText(shown[0]);
        });
        commitOn(field, () -> {
            if (refreshing || field.getText().equals(shown[0])) {
                return;
            }
            shown[0] = field.getText();
            Object yaml = write.apply(field.getText());
            List<Object> at = shownPath.getFirst();
            host.edit(d -> ConfigValues.put(d, at, yaml));
        });
        add(label, field);
        return field;
    }

    /** Enter, or leaving the field, writes it: a value is written whole, never a keystroke at a time. */
    private static void commitOn(TextField field, Runnable write) {
        field.setOnAction(e -> write.run());
        field.focusedProperty().addListener((o, was, is) -> {
            if (!is) {
                write.run();
            }
        });
    }

    private void add(String label, Node field) {
        Label name = new Label(label);
        name.setLabelFor(field);
        add(name, 0, row);
        add(field, 1, row++);
    }

    /** {@code field-tag-pattern} for {@code Tag pattern}: how a test finds a field. */
    static String id(String label) {
        return "field-" + String.join("-", Arrays.stream(label.toLowerCase().split("[^a-z0-9]+"))
                .filter(s -> !s.isEmpty()).toList());
    }

    private static ListCell<String> choiceCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.isEmpty() ? "—" : item);
            }
        };
    }

    private static ListCell<TargetKind> targetCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(TargetKind item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label());
            }
        };
    }
}
