package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.Level;
import com.botmaker.sluice.decide.Decider;
import com.botmaker.sluice.decide.Plan;
import com.botmaker.sluice.desktop.Fixtures;
import com.botmaker.sluice.desktop.model.ReleaseSpec;
import com.botmaker.sluice.desktop.model.Umbrella;
import com.botmaker.sluice.execute.Event;
import com.botmaker.sluice.execute.Release;
import com.botmaker.sluice.execute.ReleaseState;
import com.botmaker.sluice.gate.GateVerdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseTabTest extends FxHeadless {

    @TempDir
    Path tmp;

    private Umbrella umbrella;
    private Fake backend;
    private ReleaseTab tab;

    /** Answers a preview with the real decide pass and the verdicts it is given; runs nothing. */
    private static final class Fake implements ReleaseBackend {
        final List<ReleaseSpec> previewed = new ArrayList<>();
        List<GateVerdict> verdicts = List.of(new GateVerdict("clean", GateVerdict.Status.OK, "every tree clean", ""));
        Optional<ReleaseState> resumable = Optional.empty();

        @Override
        public Release.Preview preview(Path root, ReleaseSpec spec) {
            previewed.add(spec);
            Umbrella read = Umbrella.open(root);
            Plan plan = Decider.decide(read.graph(), read.config(), spec.requests(), spec.withDependents());
            return new Release.Preview(plan, verdicts);
        }

        @Override
        public Release.Outcome run(Path root, ReleaseSpec spec, BooleanSupplier stop, Consumer<Event> events) {
            throw new AssertionError("no run in this test");
        }

        @Override
        public Release.Outcome resume(Path root, ReleaseSpec spec, BooleanSupplier stop, Consumer<Event> events) {
            throw new AssertionError("no run in this test");
        }

        @Override
        public Optional<ReleaseState> resumable(Path root) {
            return resumable;
        }
    }

    @BeforeEach
    void open() {
        umbrella = Fixtures.open(Fixtures.pinned(tmp));
        backend = new Fake();
        interact(() -> {
            tab = new ReleaseTab(umbrella, backend, url -> {
            });
            show(tab);
        });
        settle();
    }

    private static void settle() {
        WaitForAsyncUtils.sleep(200, TimeUnit.MILLISECONDS);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void previewAllPatch() throws TimeoutException {
        interact(() -> tab.all().setSelected(true));
        interact(() -> tab.previewButton().fire());
        // Preview is disabled while it runs, and enabled again once its answer is shown.
        WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS, () -> !tab.previewButton().isDisabled());
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void nothingAskedPreviewsNothing() {
        assertTrue(tab.previewButton().isDisabled());
        assertTrue(tab.executeButton().isDisabled());
        assertTrue(tab.resumeButton().isDisabled());
    }

    @Test
    void aCleanPreviewArmsExecuteAndSaysWhatItWouldCut() throws TimeoutException {
        previewAllPatch();

        assertEquals("sluice --all patch", tab.commandLine());
        assertFalse(tab.executeButton().isDisabled());
        assertEquals("1.0.0 → 1.0.1", tab.cut("a"));
        assertTrue(tab.output().startsWith("decided:\n  a 1.0.0 -> 1.0.1\n  b 0.1.0 -> 0.1.1\n"), tab.output());
        assertTrue(tab.output().contains("[OK] clean: every tree clean"), tab.output());
    }

    @Test
    void changingAFlagAfterThePreviewDisarmsExecute() throws TimeoutException {
        previewAllPatch();

        interact(() -> tab.level().setValue(Level.MINOR));

        assertTrue(tab.executeButton().isDisabled());
        assertEquals("sluice --all minor", tab.commandLine());
    }

    @Test
    void aRefusedPreviewShowsTheGateAndArmsNothing() throws TimeoutException {
        backend.verdicts = List.of(new GateVerdict("clean", GateVerdict.Status.REFUSED, "b is dirty",
                "b: src/B.java modified"));

        previewAllPatch();

        assertTrue(tab.executeButton().isDisabled());
        assertTrue(tab.banner().isVisible());
        assertEquals("A gate refused", tab.banner().title());
    }

    @Test
    void aRowThatIsNoRequestIsRefusedBeforeAnyPreview() {
        interact(() -> tab.request("a", "soon"));
        interact(() -> tab.previewButton().fire());
        settle();

        assertEquals(List.of(), backend.previewed);
        assertEquals("Not a request", tab.banner().title());
    }
}
