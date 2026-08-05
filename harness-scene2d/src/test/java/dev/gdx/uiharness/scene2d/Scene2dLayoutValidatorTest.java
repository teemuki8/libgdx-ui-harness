package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.layout.LayoutValidationConfig;
import dev.gdx.uiharness.core.layout.LayoutValidationReason;
import dev.gdx.uiharness.core.layout.LayoutValidationResult;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import org.junit.jupiter.api.Test;

final class Scene2dLayoutValidatorTest {
    @Test void fullStageValidationCapturesOneAtomicObservation() {
        try (Fixture fixture = new Fixture()) {
            fixture.button("good", "Good", 100, 100);
            TextButton zero = fixture.button("zero", "Zero", 300, 100);
            zero.setBounds(300, 100, 0, 0);
            SemanticSnapshot snapshot =
                    fixture.session.snapshot(fixture.clock.revision(), fixture.clock.frame());

            LayoutValidationResult result = fixture.validate(snapshot, null);

            String zeroNodeId = snapshot.nodes().values().stream()
                    .filter(node -> "zero".equals(node.testId()))
                    .findFirst().orElseThrow().id();
            assertTrue(result.findings().stream()
                    .anyMatch(finding -> finding.reason() == LayoutValidationReason.ZERO_SIZE
                            && finding.nodeId().equals(zeroNodeId)));
        }
    }

    @Test void subtreeModeValidatesOnlyTheStrictlyResolvedSubtree() {
        try (Fixture fixture = new Fixture()) {
            TextButton outside = fixture.button("outside", "Outside", 100, 100);
            outside.setBounds(100, 100, 0, 0);
            fixture.button("inside", "Inside", 300, 100);
            SemanticSnapshot snapshot =
                    fixture.session.snapshot(fixture.clock.revision(), fixture.clock.frame());
            String outsideNodeId = snapshot.nodes().values().stream()
                    .filter(node -> "outside".equals(node.testId()))
                    .findFirst().orElseThrow().id();

            LayoutValidationResult full = fixture.validate(snapshot, null);
            assertTrue(full.findings().stream()
                    .anyMatch(finding -> finding.reason() == LayoutValidationReason.ZERO_SIZE
                            && finding.nodeId().equals(outsideNodeId)));

            LayoutValidationResult subtree = fixture.validate(
                    snapshot, Locator.testId("inside"));
            assertFalse(subtree.findings().stream()
                    .anyMatch(finding -> finding.reason() == LayoutValidationReason.ZERO_SIZE
                            && finding.nodeId().equals(outsideNodeId)),
                    "subtree validation must not report nodes outside the subtree");
        }
    }

    @Test void subtreeResolutionStaysStrictWithDistinctZeroAndMultipleErrors() {
        try (Fixture fixture = new Fixture()) {
            HarnessException missing = assertThrows(HarnessException.class,
                    () -> fixture.validate(
                            fixture.session.snapshot(
                                    fixture.clock.revision(), fixture.clock.frame()),
                            Locator.testId("absent")));
            assertEquals(ErrorCode.NOT_FOUND, missing.code());

            fixture.button("dup", "First", 100, 100);
            fixture.button("dup", "Second", 300, 100);
            HarnessException multiple = assertThrows(HarnessException.class,
                    () -> fixture.validate(
                            fixture.session.snapshot(
                                    fixture.clock.revision(), fixture.clock.frame()),
                            Locator.testId("dup")));
            assertEquals(ErrorCode.STRICTNESS_VIOLATION, multiple.code());
        }
    }

    @Test void repeatedValidationIsDeterministic() {
        try (Fixture fixture = new Fixture()) {
            fixture.button("missing", "No test id", 100, 100);
            SemanticSnapshot snapshot =
                    fixture.session.snapshot(fixture.clock.revision(), fixture.clock.frame());
            LayoutValidationResult first = fixture.validate(snapshot, null);
            LayoutValidationResult second = fixture.validate(snapshot, null);
            assertEquals(first.findings(), second.findings());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Stage stage = Scene2dTestSupport.stage();
        final ControlledStageClock clock = new ControlledStageClock(stage,
                java.time.Duration.ofMillis(16));
        final Scene2dSession session = new Scene2dSession(stage);
        final Scene2dLayoutValidator validator =
                new Scene2dLayoutValidator(session, new StrictResolution());

        TextButton button(String testId, String label, float x, float y) {
            TextButton button = new TextButton(label, WidgetStyles.textButton());
            button.setBounds(x, y, 160, 40);
            stage.addActor(button);
            if (testId != null) {
                session.semantics().setTestId(button, testId);
            }
            return button;
        }

        LayoutValidationResult validate(SemanticSnapshot snapshot, Locator subtree) {
            return validator.validate(
                    snapshot.revision(), snapshot.frame(), subtree,
                    LayoutValidationConfig.defaults(), null);
        }

        @Override public void close() {
            session.close();
            clock.close();
            stage.dispose();
        }
    }
}
