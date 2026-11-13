package com.botmaker.sluice.desktop.ui;

import javafx.scene.Node;
import javafx.scene.control.Tab;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** A tab whose content is built the first time it is selected: a tab nobody opens reads nothing. */
public final class LazyTab<T extends Node> {

    private final Tab tab;
    private final Supplier<T> build;
    private T content;

    public LazyTab(String title, Supplier<T> build) {
        this.tab = new Tab(title);
        this.build = build;
        tab.selectedProperty().addListener((o, was, is) -> {
            if (is) {
                get();
            }
        });
    }

    public Tab tab() {
        return tab;
    }

    /** The content, built now if it was not yet. FX thread only. */
    public T get() {
        if (content == null) {
            content = build.get();
            tab.setContent(content);
        }
        return content;
    }

    /** Runs {@code action} on the content if the tab was opened. */
    public void ifBuilt(Consumer<T> action) {
        if (content != null) {
            action.accept(content);
        }
    }
}
