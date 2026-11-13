package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.decide.Plan;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;

/**
 * The confirmation in front of every release this window cuts: what will be tagged, why it cannot be undone, and
 * a word to type. It lists the plan rather than the flags, since a release cuts what the decide pass decided.
 */
final class ReleaseConfirm {

    /** The word the operator types. A dialog one click from done is a dialog people dismiss. */
    static final String WORD = "release";

    private static final ButtonType CUT = new ButtonType("Cut the release", ButtonBar.ButtonData.OK_DONE);

    private ReleaseConfirm() {
    }

    /** One line per tag the plan would cut, indented for the list. Empty when the plan releases nothing. */
    static List<String> tags(Plan plan, String tagPattern) {
        return plan.releasing().entrySet().stream()
                .map(cut -> "    " + cut.getKey().name() + "  " + cut.getValue().tag(tagPattern))
                .toList();
    }

    /**
     * Shows the dialog and answers whether the operator typed the word and pressed the button.
     *
     * @param header  the command line being run, as the dialog's header
     * @param tags    {@link #tags}; the caller has already refused an empty list
     * @param warning what this release does; the typing instruction is added here
     */
    static boolean ask(Window owner, String header, List<String> tags, String warning) {
        TextArea list = new TextArea(String.join("\n", tags));
        list.setEditable(false);
        list.getStyleClass().add("output-text");
        list.setPrefRowCount(Math.min(12, tags.size() + 1));

        Label words = new Label(warning + "\n\nType " + WORD + " to enable the button.");
        words.setWrapText(true);

        TextField typed = new TextField();
        typed.setPromptText(WORD);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Cut this release");
        dialog.setHeaderText(header);
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().setAll(ButtonType.CANCEL, CUT);
        VBox body = new VBox(10, list, words, typed);
        body.setPadding(new Insets(4));
        pane.setContent(body);
        pane.lookupButton(CUT).setDisable(true);
        typed.textProperty().addListener((o, was, is) -> pane.lookupButton(CUT).setDisable(!WORD.equals(is.strip())));
        Themed.dialog(dialog, owner);
        return dialog.showAndWait().filter(CUT::equals).isPresent();
    }
}
