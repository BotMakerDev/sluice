package com.botmaker.sluice.desktop.ui.widgets;

import javafx.animation.PauseTransition;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.util.Duration;

import java.util.function.Supplier;

/** Copies a text to the clipboard, and says so for a moment: the clipboard itself is invisible. */
public final class CopyButton extends Button {

    private static final Duration SAID = Duration.seconds(1.5);

    private final String label;

    public CopyButton(String label, Supplier<String> text) {
        super(label);
        this.label = label;
        getStyleClass().add("copy-button");
        setFocusTraversable(false);
        setTooltip(new Tooltip("Copy this to the clipboard"));
        setOnAction(e -> copy(text.get()));
    }

    private void copy(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        setText("Copied");
        PauseTransition back = new PauseTransition(SAID);
        back.setOnFinished(done -> setText(label));
        back.play();
    }
}
