package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import dev.gdx.uiharness.core.contract.ConditionalRule;
import dev.gdx.uiharness.core.contract.ContractValue;
import dev.gdx.uiharness.core.contract.ControlKind;
import dev.gdx.uiharness.core.contract.ControlOption;
import dev.gdx.uiharness.core.contract.TransitionKind;
import dev.gdx.uiharness.core.contract.ValidationRule;
import dev.gdx.uiharness.core.contract.ValidationStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings("auxiliaryclass")
final class Scene2dContractSnapshotterTest {
    @Test
    void assemblesStableTypedContractFromExplicitDefinitionsAndLiveWidgets() {
        Stage stage = Scene2dTestSupport.stage();
        SelectBox<String> victory = new SelectBox<>(WidgetStyles.selectBox());
        victory.setItems("Total conquest", "Rival target");
        victory.setSelected("Rival target");
        victory.setBounds(10, 500, 200, 40);
        TextField seed = new TextField("4294967295", WidgetStyles.textField());
        seed.setOnlyFontChars(false);
        seed.setText("4294967295");
        seed.setBounds(10, 440, 200, 40);
        stage.addActor(victory);
        stage.addActor(seed);
        stage.setKeyboardFocus(seed);

        try (Scene2dSession session = new Scene2dSession(stage)) {
            session.semantics().setControl(victory, new ControlMetadata(
                    "victoryCondition", 0, 0, ControlKind.SELECT,
                    List.of(
                            new ControlOption(
                                    ContractValue.text("conquest"), "Total conquest"),
                            new ControlOption(
                                    ContractValue.text("rival-target"), "Rival target")),
                    ContractValue.text("conquest"),
                    new ValidationRule("choice", null, null, null),
                    new ValidationStatus(true, List.of())));
            session.semantics().setCurrentValue(
                    victory, ContractValue.text("rival-target"));
            session.semantics().setControl(seed, new ControlMetadata(
                    "seed", 1, 1, ControlKind.TEXT, List.of(),
                    ContractValue.text("generatedUint32"),
                    new ValidationRule(
                            "uint32-decimal", ContractValue.integer(0),
                            ContractValue.integer(4_294_967_295L), ContractValue.integer(1)),
                    new ValidationStatus(true, List.of())));
            session.semantics().addCondition(new ConditionalRule(
                    "victoryCondition", ContractValue.text("rival-target"),
                    "seed", true, true, "victoryCondition"));
            session.semantics().setViewport(stage.getRoot(), "configuration");
            session.semantics().setTransition(new TransitionObservation(
                    "start-battle", true, null,
                    new ValidationStatus(true, List.of()),
                    TransitionKind.CONFIRMATION, null,
                    Map.of("seed", ContractValue.integer(4_294_967_295L))));

            var first = session.stateActionContract(7, 11);
            var repeated = session.stateActionContract(8, 12);

            assertEquals(List.of("victoryCondition", "seed"),
                    first.controls().stream().map(control -> control.id()).toList());
            assertEquals(List.of("victoryCondition", "seed"), first.focusOrder());
            assertEquals("seed", first.focusedControlId());
            assertEquals(ContractValue.text("rival-target"),
                    first.controls().getFirst().currentValue());
            assertEquals(ContractValue.text("4294967295"),
                    first.controls().get(1).currentValue());
            assertEquals("configuration", first.viewports().getFirst().id());
            assertTrue(first.viewports().getFirst().visibleControlIds().contains("seed"));
            assertTrue(first.transition().accepted());
            assertEquals(TransitionKind.CONFIRMATION, first.transition().kind());
            assertFalse(first.transition().acceptedPayload().isEmpty());
            assertEquals(first.stateId(), repeated.stateId());
        } finally {
            stage.dispose();
        }
    }
}
