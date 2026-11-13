package com.botmaker.sluice.desktop.ui.widgets;

import com.botmaker.sluice.desktop.model.ReleaseProgress.Lane;
import com.botmaker.sluice.desktop.model.ReleaseProgress.NodeState;
import com.botmaker.sluice.desktop.model.ReleaseProgress.Step;
import com.botmaker.sluice.desktop.ui.FxHeadless;
import com.botmaker.sluice.execute.Stage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleLaneTest extends FxHeadless {

    private static Lane lane(NodeState... states) {
        Map<Step, NodeState> steps = new EnumMap<>(Step.class);
        for (int i = 0; i < Step.values().length; i++) {
            steps.put(Step.values()[i], states[i]);
        }
        boolean failed = List.of(states).contains(NodeState.FAILED);
        return new Lane("core", "v1.0.1", Stage.TAGGED, steps, failed ? List.of("push refused") : List.of(),
                Optional.empty());
    }

    @Test
    void aRunningStepPulsesWhileAnimated() {
        List<String> opened = new ArrayList<>();
        ModuleLane view = new ModuleLane(opened::add);
        interact(() -> {
            show(view);
            view.show(lane(NodeState.OK, NodeState.OK, NodeState.RUNNING, NodeState.PENDING, NodeState.PENDING),
                    Optional.empty());
        });

        assertTrue(view.pulsing());
        assertTrue(view.node(Step.PUSH).getStyleClass().contains("step--running"));
        assertFalse(view.details().isVisible());

        interact(() -> view.setAnimated(false));
        assertFalse(view.pulsing());
    }

    @Test
    void aFailedStepShowsWhatStoppedIt() {
        ModuleLane view = new ModuleLane(url -> {
        });
        interact(() -> {
            show(view);
            view.show(lane(NodeState.OK, NodeState.OK, NodeState.FAILED, NodeState.PENDING, NodeState.PENDING),
                    Optional.empty());
        });

        assertTrue(view.details().isVisible());
        assertEquals("push refused", view.errorText().getText());
        assertTrue(view.getStyleClass().contains("module-lane--failed"));
        assertFalse(view.pulsing());
    }
}
