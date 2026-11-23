// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.desktop.model.Io;
import com.botmaker.sluice.desktop.model.UpdateText;
import com.botmaker.sluice.update.SelfUpdate;
import javafx.application.Platform;
import javafx.beans.property.StringProperty;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Check for updates: asks core whether a newer sluice is out ({@link SelfUpdate#check}) and, on the user's word,
 * runs the same update {@code sluice update} does. The work runs off the FX thread; the window decides nothing.
 */
public final class UpdateDialog {

    private static final ButtonType UPDATE = new ButtonType("Update", ButtonBar.ButtonData.OK_DONE);
    private static final String CHECKING = "Checking for updates…";
    private static final String UPDATING = "Updating sluice…";

    private final Window owner;
    private final Supplier<SelfUpdate> update;
    private final StringProperty status;

    /**
     * @param update the update of this window's install, built when asked (it reads the jar and its manifest)
     * @param status the window's status line, cleared after only while it still says what this wrote
     */
    public UpdateDialog(Window owner, Supplier<SelfUpdate> update, StringProperty status) {
        this.owner = owner;
        this.update = update;
        this.status = status;
    }

    /** Checks, says where this sluice stands, and offers the update when there is one it can install. */
    public void check(Runnable done) {
        status.set(CHECKING);
        Io.async(() -> {
            SelfUpdate self = update.get();
            return new Checked(self, call(self::check));
        }).whenComplete((checked, error) -> Platform.runLater(() -> {
            clear(CHECKING);
            if (error != null) {
                tell(Alert.AlertType.ERROR, "Could not check for updates", Io.message(error));
                done.run();
                return;
            }
            SelfUpdate.Check check = checked.check();
            String words = UpdateText.standing(check) + "\n\n" + UpdateText.install(check);
            if (!check.updatable()) {
                tell(Alert.AlertType.INFORMATION, UpdateText.header(check), words);
                done.run();
                return;
            }
            Alert ask = Themed.dialog(new Alert(Alert.AlertType.CONFIRMATION, words + "\n\nUpdate now? Every "
                    + "download is checked against the release's checksums first.", ButtonType.CANCEL, UPDATE),
                    owner);
            ask.setHeaderText(UpdateText.header(check));
            if (ask.showAndWait().filter(UPDATE::equals).isPresent()) {
                install(checked.self(), done);
            } else {
                done.run();
            }
        }));
    }

    private void install(SelfUpdate self, Runnable done) {
        status.set(UPDATING);
        Io.async(() -> call(self::update)).whenComplete((outcome, error) -> Platform.runLater(() -> {
            clear(UPDATING);
            done.run();
            if (error != null) {
                tell(Alert.AlertType.ERROR, "sluice was not updated", Io.message(error));
            } else {
                tell(Alert.AlertType.INFORMATION, "Update", UpdateText.outcome(outcome));
            }
        }));
    }

    private void clear(String mine) {
        if (mine.equals(status.get())) {
            status.set("");
        }
    }

    private void tell(Alert.AlertType type, String header, String words) {
        Alert alert = Themed.dialog(new Alert(type, words, ButtonType.OK), owner);
        alert.setHeaderText(header);
        alert.showAndWait();
    }

    private static <T> T call(Callable<T> work) {
        try {
            return work.call();
        } catch (IOException e) {
            throw new UncheckedIOException(e.getMessage(), e);
        } catch (Exception e) {
            throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
        }
    }

    private record Checked(SelfUpdate self, SelfUpdate.Check check) {
    }
}
