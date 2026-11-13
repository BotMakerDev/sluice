package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.desktop.Fixtures;
import com.botmaker.sluice.testing.FixtureUmbrella;
import javafx.event.ActionEvent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTabTest extends FxHeadless {

    private static final String YAML = """
            # How this umbrella releases.
            branch: main
            changelog: true
            """;

    @TempDir
    Path tmp;

    private FixtureUmbrella fixture;
    private ConfigTab tab;

    @BeforeEach
    void open() {
        fixture = Fixtures.pinned(tmp);
        Fixtures.config(fixture, YAML);
        interact(() -> {
            tab = new ConfigTab(Fixtures.open(fixture));
            show(tab);
        });
        waitFor(() -> tab.graphView().isPresent());
    }

    @Test
    void aFormEditReachesTheYamlKeepsTheCommentAndArmsSave() {
        assertTrue(tab.saveButton().isDisabled(), "nothing edited yet");

        interact(() -> tab.select("General"));
        interact(() -> {
            TextField branch = lookup("#field-branch").queryAs(TextField.class);
            branch.setText("release");
            branch.fireEvent(new ActionEvent());
        });
        waitFor(() -> tab.graphView().isPresent());

        assertEquals(YAML.replace("branch: main", "branch: release"), tab.doc().get().text());
        interact(() -> tab.select(ConfigTab.YAML));
        assertEquals(YAML.replace("branch: main", "branch: release"), tab.yamlArea().getText());
        assertFalse(tab.saveButton().isDisabled());
        assertTrue(tab.dirty());
    }

    @Test
    void aModuleFormWritesUnderItsModuleAndTheGraphFollows() {
        // A scroll pane's content joins the scene once it is laid out: the field is looked up after.
        interact(() -> tab.select("Modules"));
        interact(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<String> place = lookup("#field-place").queryAs(ComboBox.class);
            place.setValue("first");
            place.fireEvent(new ActionEvent());
        });
        waitFor(() -> tab.graphView().isPresent());

        assertTrue(tab.doc().get().text().contains("modules:\n  a:\n    place: first"), tab.doc().get().text());
        assertTrue(tab.graphView().orElseThrow().refusal().isEmpty(), tab.graphView().toString());
    }

    @Test
    void aProblemInTheTextIsListedRefusesSaveAndLeadsToItsLine() {
        interact(() -> {
            tab.select(ConfigTab.YAML);
            tab.yamlArea().setText("# How this umbrella releases.\nbranch: main\nchangelog: perhaps\n");
        });
        waitFor(() -> tab.graphView().isPresent());

        assertEquals(1, tab.problemList().getItems().size(), tab.problemList().getItems().toString());
        assertTrue(tab.saveButton().isDisabled());
        interact(() -> tab.problemList().getSelectionModel().select(0));
        assertEquals("changelog: perhaps", tab.yamlArea().getSelectedText());
    }

    @Test
    void aNameThatIsNoModuleIsAProblemToo() {
        interact(() -> {
            tab.select(ConfigTab.YAML);
            tab.yamlArea().setText(YAML + "modules:\n  nowhere:\n    alias: n\n");
        });
        waitFor(() -> tab.graphView().isPresent());

        assertEquals(1, tab.problemList().getItems().size(), tab.problemList().getItems().toString());
        assertTrue(tab.saveButton().isDisabled());
    }

    @Test
    void revertGoesBackToTheFile() throws Exception {
        interact(() -> {
            tab.select(ConfigTab.YAML);
            tab.yamlArea().setText(YAML + "tag: \"r{version}\"\n");
        });
        assertTrue(tab.dirty());
        interact(() -> lookup("Revert").queryButton().fire());

        assertFalse(tab.dirty());
        assertEquals(YAML, tab.yamlArea().getText());
        assertEquals(YAML, Files.readString(fixture.root().resolve("sluice.yml")));
    }

    @Test
    void saveRefusesToWriteOverAFileChangedOnDisk() throws Exception {
        String outside = YAML + "tag: \"outside{version}\"\n";
        Files.writeString(fixture.root().resolve("sluice.yml"), outside);
        interact(() -> {
            tab.select(ConfigTab.YAML);
            tab.yamlArea().setText(YAML.replace("main", "release"));
        });
        waitFor(() -> tab.graphView().isPresent());
        interact(() -> tab.saveButton().fire());
        waitFor(() -> !tab.saveButton().isDisabled());

        assertEquals(outside, Files.readString(fixture.root().resolve("sluice.yml")));
        assertTrue(tab.dirty());
    }

    private static void waitFor(BooleanSupplier condition) {
        try {
            WaitForAsyncUtils.waitFor(15, TimeUnit.SECONDS, condition::getAsBoolean);
        } catch (TimeoutException e) {
            throw new AssertionError("timed out", e);
        }
        WaitForAsyncUtils.waitForFxEvents();
    }
}
