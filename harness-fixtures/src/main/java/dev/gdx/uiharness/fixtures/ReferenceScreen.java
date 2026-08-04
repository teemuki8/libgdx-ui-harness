package dev.gdx.uiharness.fixtures;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.List;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import dev.gdx.uiharness.scene2d.Semantics;
import dev.gdx.uiharness.scene2d.TypographyMetadata;
import dev.gdx.uiharness.scene2d.LayoutMetadata;
import dev.gdx.uiharness.core.typography.EvidenceValue;
import dev.gdx.uiharness.core.typography.UnavailableReason;

import java.util.LinkedHashMap;
import java.util.Map;
/** Fixed-layout Scene2D screen used by the real LWJGL3 process fixture. */
public final class ReferenceScreen implements AutoCloseable {
    private static final Color BACKGROUND = Color.valueOf("172033ff");
    private static final Color PANEL = Color.valueOf("26324aff");
    private static final Color PANEL_ALT = Color.valueOf("303e5aff");
    private static final Color ACCENT = Color.valueOf("69d2e7ff");
    private static final Color PRESSED = Color.valueOf("3d9fb4ff");
    private static final Color TEXT = Color.valueOf("f4f7ffff");
    private static final Color MUTED = Color.valueOf("aebbd0ff");

    private final Stage stage = new Stage(new ScreenViewport());
    private final Skin skin = createSkin();
    private final TextureRegionDrawable pixel = new TextureRegionDrawable(
            new TextureRegion(skin.get("pixel", Texture.class)));
    private final String benchmarkScenario;
    private final float benchmarkDelaySeconds;
    private final Map<Actor, SemanticTag> benchmarkTags = new LinkedHashMap<>();
    private Table signInPanel;
    private Label harnessTitle;
    private Label bodyCaption;
    private TextField username;
    private TextField password;
    private final Array<Label> assertionCandidates = new Array<>();
    private Label assertionState;
    private Runnable withholdAssertionFrames = () -> {};
    private Semantics semantics;

    /** Creates the stable reference workflow without benchmark-only actors. */
    public ReferenceScreen() {
        this(null, 0);
    }

    /** Creates the stable workflow plus actors for one named parity scenario. */
    public ReferenceScreen(String newBenchmarkScenario, int benchmarkDelayMillis) {
        benchmarkScenario = newBenchmarkScenario;
        benchmarkDelaySeconds = benchmarkDelayMillis / 1_000f;
        stage.getRoot().setName("reference-stage");
        stage.getViewport().update(1280, 720, true);
        buildBackground();
        buildSignIn();
        buildSettings();
        buildTransformedOverlap();
        buildAssertionFixture();
        if (benchmarkScenario != null) {
            buildBenchmarkScenario();
        }
    }

    /** Returns the application-owned Stage. */
    public Stage stage() {
        return stage;
    }

    /** Registers the fixture control invoked by the existing Open dialog behavior. */
    public void attachAssertionFrameControl(Runnable control) {
        withholdAssertionFrames = java.util.Objects.requireNonNull(control, "control");
    }

    /** Installs semantic metadata required by stable MCP locators. */
    public void attachSemantics(Semantics newSemantics) {
        semantics = newSemantics;
        tag(username, "username", "Username");
        tag(password, "password", "Password");
        tag(stage.getRoot().findActor("sign-in"), "sign-in", "Sign in");
        tag(stage.getRoot().findActor("settings-list"),
                "settings-list", "Settings choices");
        tag(stage.getRoot().findActor("settings-scroll"),
                "settings-scroll", "Settings list");
        tag(stage.getRoot().findActor("open-dialog"),
                "open-dialog", "Open dialog");
        tag(stage.getRoot().findActor("rotated-card"),
                "rotated-card", "Rotated card");
        tag(stage.getRoot().findActor("overlap-card"),
                "overlap-card", "Overlap card");
        tag(harnessTitle, "harness-title", "Deterministic UI Harness");
        semantics.setTypography(
                harnessTitle, typographyMetadata());
        semantics.setLayout(harnessTitle, new LayoutMetadata("persistent-title"));
        semantics.setLayout(
                stage.getRoot().findActor("settings-list"),
                new LayoutMetadata("scrolling-list"));
        tag(bodyCaption, "body-caption", "Transforms and overlap");
        semantics.setTypography(bodyCaption, typographyMetadata());
        tag(assertionState, "assertion-state", "Assertion state");
        for (Label candidate : assertionCandidates) {
            tag(candidate, "assertion-candidate", "Assertion candidate");
        }
        benchmarkTags.forEach((actor, metadata) ->
                tag(actor, metadata.testId(), metadata.accessibleName()));
    }

    /** Draws the current stage after the harness has advanced its fixed clock. */
    public void draw() {
        stage.draw();
    }

    /** Updates only the viewport; fixture coordinates remain pixel-stable. */
    public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
    }

    @Override public void close() {
        stage.dispose();
        skin.dispose();
    }

    private void buildBackground() {
        Image background = new Image(pixel.tint(BACKGROUND));
        background.setBounds(0, 0, 1280, 720);
        background.setTouchable(Touchable.disabled);
        background.setName("background");
        stage.addActor(background);

        harnessTitle = new Label("Deterministic UI Harness", skin);
        harnessTitle.setBounds(64, 624, 580, 74);
        harnessTitle.setFontScale(2.8f);
        harnessTitle.setTouchable(Touchable.disabled);
        stage.addActor(harnessTitle);
    }

    private void buildSignIn() {
        signInPanel = new Table(skin);
        signInPanel.setName("sign-in-panel");
        signInPanel.setBackground(pixel.tint(PANEL));
        signInPanel.setBounds(64, 300, 500, 300);
        signInPanel.pad(28);
        signInPanel.defaults().growX().height(44).padBottom(12);

        Label title = new Label("Sign in", skin);
        title.setFontScale(1.2f);
        username = new TextField("", skin);
        username.setName("username");
        username.setMessageText("Username");
        password = new TextField("", skin);
        password.setName("password");
        password.setMessageText("Password");
        password.setPasswordMode(true);
        password.setPasswordCharacter('*');
        TextButton submit = new TextButton("Sign in", skin);
        submit.setName("sign-in");
        submit.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                replaceSignInWithWelcome();
                startAssertionTransitions();
            }
        });

        signInPanel.add(title).left().row();
        signInPanel.add(username).row();
        signInPanel.add(password).row();
        signInPanel.add(submit).width(180).left();
        stage.addActor(signInPanel);
    }

    private void buildSettings() {
        Table settings = new Table(skin);
        settings.setName("settings-panel");
        settings.setBackground(pixel.tint(PANEL));
        settings.setBounds(660, 164, 540, 436);
        settings.pad(24);

        Label title = new Label("Settings", skin);
        title.setFontScale(1.2f);
        CheckBox deterministic = new CheckBox(" Fixed-step animations", skin);
        deterministic.setChecked(true);
        deterministic.setDisabled(true);

        List<String> list = new List<>(skin);
        list.setName("settings-list");
        Array<String> items = new Array<>();
        items.addAll("Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta",
                "Eta", "Theta", "Iota", "Kappa", "Lambda", "Mu");
        list.setItems(items);
        list.setSelectedIndex(2);

        ScrollPane pane = new ScrollPane(list, skin);
        pane.setName("settings-scroll");
        pane.setFadeScrollBars(false);
        pane.setSmoothScrolling(false);
        pane.setFlickScroll(false);
        pane.setScrollingDisabled(true, false);

        TextButton openDialog = new TextButton("Open dialog", skin);
        openDialog.setName("open-dialog");
        openDialog.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                showDialog();
            }
        });

        settings.defaults().growX().padBottom(12);
        settings.add(title).left().height(36).row();
        settings.add(deterministic).left().height(36).row();
        settings.add(pane).height(244).row();
        settings.add(openDialog).width(180).height(44).left();
        stage.addActor(settings);
    }

    private void buildTransformedOverlap() {
        Image rotated = new Image(pixel.tint(Color.valueOf("ef767aff")));
        rotated.setName("rotated-card");
        rotated.setBounds(184, 112, 220, 116);
        rotated.setOrigin(110, 58);
        rotated.setRotation(-8f);
        rotated.setTouchable(Touchable.disabled);
        stage.addActor(rotated);

        Image overlap = new Image(pixel.tint(Color.valueOf("7d70b8e6")));
        overlap.setName("overlap-card");
        overlap.setBounds(300, 90, 220, 116);
        overlap.setOrigin(110, 58);
        overlap.setRotation(6f);
        overlap.setTouchable(Touchable.disabled);
        stage.addActor(overlap);

        bodyCaption = new Label("transforms + overlap", skin);
        bodyCaption.setColor(MUTED);
        bodyCaption.setBounds(220, 132, 260, 31);
        bodyCaption.setTouchable(Touchable.disabled);
        stage.addActor(bodyCaption);
    }

    private void buildBenchmarkScenario() {
        switch (benchmarkScenario) {
            case "delayed-enablement" -> buildDelayedEnablement();
            case "moving-target" -> buildMovingTarget();
            case "obscured-target" -> buildObscuredTarget();
            case "scroll-and-select" -> buildScrollSelection();
            case "sign-in", "ambiguous-locator-recovery", "modal-dialog",
                    "actor-replacement", "screenshot-diagnosis",
                    "intentional-failure-trace" -> {
                // The stable reference workflow already contains this scenario's actors.
            }
            default -> throw new IllegalArgumentException(
                    "Unknown benchmark scenario: " + benchmarkScenario);
        }
    }

    private void buildDelayedEnablement() {
        Label status = benchmarkStatus();
        TextButton target = benchmarkButton(
                "Delayed target", "delayed-target", "Delayed target", 740);
        target.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                status.setText("Delayed ready");
            }
        });
        TextButton trigger = benchmarkButton(
                "Start delay", "delay-start", "Start delay", 580);
        trigger.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                target.clearActions();
                target.setDisabled(true);
                target.addAction(Actions.sequence(
                        Actions.delay(benchmarkDelaySeconds),
                        Actions.run(() -> target.setDisabled(false))));
            }
        });
    }

    private void buildMovingTarget() {
        Label status = benchmarkStatus();
        TextButton target = benchmarkButton(
                "Moving target", "moving-target", "Moving target", 740);
        target.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                status.setText("Moving clicked");
            }
        });
        TextButton trigger = benchmarkButton(
                "Start movement", "movement-start", "Start movement", 580);
        trigger.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                target.clearActions();
                target.setPosition(740, 72);
                target.addAction(Actions.moveBy(120, 0, benchmarkDelaySeconds));
            }
        });
    }

    private void buildObscuredTarget() {
        Label status = benchmarkStatus();
        TextButton target = benchmarkButton(
                "Obscured target", "obscured-target", "Obscured target", 740);
        target.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                status.setText("Obscured clicked");
            }
        });
        Image cover = new Image(pixel.tint(Color.valueOf("7d70b8ff")));
        cover.setName("benchmark-cover");
        cover.setBounds(740, 72, 150, 44);
        cover.setVisible(false);
        stage.addActor(cover);
        TextButton trigger = benchmarkButton(
                "Start cover", "obscure-start", "Start cover", 580);
        trigger.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                cover.clearActions();
                cover.setVisible(true);
                cover.addAction(Actions.sequence(
                        Actions.delay(benchmarkDelaySeconds),
                        Actions.visible(false)));
            }
        });
    }

    private void buildScrollSelection() {
        Label status = benchmarkStatus();
        Table content = new Table(skin);
        content.defaults().width(190).height(38);
        for (String name : java.util.List.of(
                "Alpha", "Beta", "Gamma", "Delta", "Epsilon",
                "Zeta", "Eta", "Theta", "Iota", "Kappa")) {
            Label item = new Label(name, skin);
            item.setTouchable(Touchable.disabled);
            content.add(item).row();
        }
        TextButton select = new TextButton("Select Lambda", skin);
        select.setName("select-lambda");
        select.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                status.setText("Selected Lambda");
            }
        });
        tagBenchmark(select, "select-lambda", "Select Lambda");
        content.add(select).row();
        content.pack();

        ScrollPane pane = new ScrollPane(content, skin);
        pane.setName("selection-scroll");
        pane.setBounds(580, 22, 230, 120);
        pane.setFadeScrollBars(false);
        pane.setSmoothScrolling(false);
        pane.setFlickScroll(false);
        pane.setScrollingDisabled(true, false);
        tagBenchmark(pane, "selection-scroll", "Selection list");
        stage.addActor(pane);
    }

    private Label benchmarkStatus() {
        Label status = new Label("", skin);
        status.setName("benchmark-status");
        status.setBounds(920, 72, 280, 44);
        status.setTouchable(Touchable.disabled);
        tagBenchmark(status, "benchmark-status", "Benchmark status");
        stage.addActor(status);
        return status;
    }

    private TextButton benchmarkButton(
            String text, String testId, String accessibleName, float x) {
        TextButton button = new TextButton(text, skin);
        button.setName(testId);
        button.setBounds(x, 72, 150, 44);
        tagBenchmark(button, testId, accessibleName);
        stage.addActor(button);
        return button;
    }

    private void tagBenchmark(Actor actor, String testId, String accessibleName) {
        benchmarkTags.put(actor, new SemanticTag(testId, accessibleName));
    }

    private void replaceSignInWithWelcome() {
        if (signInPanel.getStage() == null) {
            return;
        }
        String enteredName = username.getText().strip();
        if (enteredName.isEmpty()) {
            enteredName = "guest";
        }
        signInPanel.remove();

        Table welcome = new Table(skin);
        welcome.setName("welcome-panel");
        welcome.setBackground(pixel.tint(PANEL_ALT));
        welcome.setBounds(64, 300, 500, 300);
        Label message = new Label("Welcome, " + enteredName, skin);
        message.setName("welcome-message");
        message.setFontScale(1.35f);
        welcome.add(message);
        stage.addActor(welcome);
        if (semantics != null) {
            tag(message, "welcome-message", "Welcome message");
        }
    }
    private void buildAssertionFixture() {
        assertionState = assertionLabel("initial");
        stage.addActor(assertionState);
        for (int index = 0; index < 12; index++) {
            Label candidate = new Label("candidate-" + index, skin);
            candidate.setVisible(false);
            assertionCandidates.add(candidate);
            stage.addActor(candidate);
        }
    }

    private void startAssertionTransitions() {
        stage.getRoot().addAction(Actions.sequence(
                Actions.run(() -> replaceAssertionState("changing-1")),
                Actions.delay(0.016f),
                Actions.run(() -> replaceAssertionState("changing-2")),
                Actions.delay(0.016f),
                Actions.run(() -> replaceAssertionState("ready"))));
    }

    private void replaceAssertionState(String text) {
        assertionState.remove();
        Label identitySpacer = new Label("", skin);
        identitySpacer.setVisible(false);
        stage.addActor(identitySpacer);
        assertionState = assertionLabel(text);
        stage.addActor(assertionState);
        if (semantics != null) {
            tag(assertionState, "assertion-state", "Assertion state");
        }
    }

    private Label assertionLabel(String text) {
        Label label = new Label(text, skin);
        label.setName("assertion-state");
        label.setVisible(false);
        return label;
    }


    private void showDialog() {
        withholdAssertionFrames.run();
        Dialog dialog = new Dialog("Reference dialog", skin);
        dialog.setName("reference-dialog");
        dialog.text("All interactions arrived through MCP.");
        dialog.button("Close", true);
        dialog.setModal(true);
        dialog.setMovable(false);
        dialog.show(stage, null);
        dialog.setSize(520, 220);
        dialog.setPosition((1280 - dialog.getWidth()) / 2f,
                (720 - dialog.getHeight()) / 2f);
        dialog.validate();
        if (semantics != null) {
            tag(dialog, "reference-dialog", "Reference dialog");
        }
    }

    private void tag(Actor actor, String testId, String accessibleName) {
        if (actor == null) {
            throw new IllegalStateException("Missing fixture actor " + testId);
        }
        semantics.setTestId(actor, testId);
        semantics.setAccessibleName(actor, accessibleName);
        semantics.setLabel(actor, accessibleName);
    }

    private record SemanticTag(String testId, String accessibleName) {}

    private static TypographyMetadata typographyMetadata() {
        return new TypographyMetadata(
                "classpath:reference-ui/lsans-15.fnt",
                java.util.List.of("classpath:reference-ui/lsans-15.png"),
                15,
                15,
                EvidenceValue.unavailable(
                        UnavailableReason.UNSUPPORTED,
                        "BitmapFont does not expose weight"),
                EvidenceValue.unavailable(
                        UnavailableReason.UNSUPPORTED,
                        "BitmapFont does not expose letter spacing"),
                EvidenceValue.unavailable(
                        UnavailableReason.UNSUPPORTED,
                        "bitmap font has no distance-field smoothing"));
    }

    private static Skin createSkin() {
        Skin skin = new Skin();
        Texture pixelTexture = new Texture(Gdx.files.internal("reference-ui/pixel.png"));
        pixelTexture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        BitmapFont font = new BitmapFont(Gdx.files.internal("reference-ui/lsans-15.fnt"));
        font.getData().markupEnabled = false;
        skin.add("pixel", pixelTexture);
        skin.add("default-font", font);

        TextureRegionDrawable pixel = new TextureRegionDrawable(new TextureRegion(pixelTexture));
        skin.add("default", new Label.LabelStyle(font, TEXT));

        TextButton.TextButtonStyle button = new TextButton.TextButtonStyle();
        button.up = pixel.tint(ACCENT);
        button.down = pixel.tint(PRESSED);
        button.over = pixel.tint(Color.valueOf("8ce2efff"));
        button.checked = pixel.tint(PRESSED);
        button.font = font;
        button.fontColor = Color.valueOf("10202aff");
        skin.add("default", button);

        TextField.TextFieldStyle field = new TextField.TextFieldStyle();
        field.font = font;
        field.fontColor = TEXT;
        field.messageFont = font;
        field.messageFontColor = MUTED;
        field.background = pixel.tint(PANEL_ALT);
        field.focusedBackground = pixel.tint(Color.valueOf("3a4c6eff"));
        field.cursor = pixel.tint(ACCENT);
        field.cursor.setMinWidth(2f);
        field.selection = pixel.tint(Color.valueOf("477f91ff"));
        skin.add("default", field);

        ScrollPane.ScrollPaneStyle scroll = new ScrollPane.ScrollPaneStyle();
        scroll.background = pixel.tint(Color.valueOf("202a3fff"));
        scroll.vScroll = pixel.tint(Color.valueOf("1a2233ff"));
        scroll.vScrollKnob = pixel.tint(ACCENT);
        skin.add("default", scroll);

        List.ListStyle list = new List.ListStyle();
        list.font = font;
        list.fontColorSelected = Color.valueOf("10202aff");
        list.fontColorUnselected = TEXT;
        list.selection = pixel.tint(ACCENT);
        list.background = pixel.tint(Color.valueOf("202a3fff"));
        skin.add("default", list);

        CheckBox.CheckBoxStyle checkBox = new CheckBox.CheckBoxStyle();
        checkBox.checkboxOff = pixel.tint(Color.valueOf("56647aff"));
        checkBox.checkboxOn = pixel.tint(ACCENT);
        checkBox.checkboxOver = pixel.tint(Color.valueOf("8ce2efff"));
        checkBox.font = font;
        checkBox.fontColor = TEXT;
        checkBox.disabledFontColor = MUTED;
        skin.add("default", checkBox);

        Window.WindowStyle window = new Window.WindowStyle();
        window.titleFont = font;
        window.titleFontColor = TEXT;
        window.background = pixel.tint(Color.valueOf("354562ff"));
        skin.add("default", window);
        return skin;
    }
}
