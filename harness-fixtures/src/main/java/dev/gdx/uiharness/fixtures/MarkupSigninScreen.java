package dev.gdx.uiharness.fixtures;

import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import dev.gdx.markup.core.BuiltUi;
import dev.gdx.markup.core.DefaultSkin;
import dev.gdx.markup.core.MarkupBuilder;
import dev.gdx.markup.core.MarkupDocument;
import dev.gdx.markup.core.MarkupParser;
import dev.gdx.markup.core.NoopSink;
import dev.gdx.markup.core.SemanticSink;
import dev.gdx.markup.core.style.CssDocument;
import dev.gdx.markup.core.style.CssParser;
import dev.gdx.markup.harness.HarnessSemanticSink;
import dev.gdx.markup.runtime.MarkupRuntimeSource;
import dev.gdx.uiharness.core.typography.EvidenceValue;
import dev.gdx.uiharness.core.typography.UnavailableReason;
import dev.gdx.uiharness.scene2d.Semantics;
import dev.gdx.uiharness.scene2d.TypographyMetadata;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import java.util.Objects;

/**
 * Sign-in panel built entirely from libgdx-ui-markup markup and rendered through the harness
 * production MCP: markup-declared {@code id}/{@code name}/{@code data-runtime-entity} become
 * harness test identifiers, accessible names, and runtime bindings by construction.
 *
 * <p>The harness {@link Semantics} facade exists only after {@link FixtureControl} is created,
 * so the first build uses {@link NoopSink} and {@link #attachSemantics} rebuilds the scene with
 * the harness sink and registers the markup runtime entities against the fixture's
 * {@link AgentRuntime}.
 */
public final class MarkupSigninScreen implements FixtureScreen {
    private static final String MARKUP = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ui>
              <table id="signin-panel" class="panel" width="500" height="300">
                <window id="signin-window" title="Sign in" expand="true" fill="true">
                  <table id="signin-form">
                    <row/>
                    <label id="signin-title" class="title" text="Sign in"/>
                    <row/>
                    <label id="username-label" text="Username"/>
                    <textfield id="username" label="Username" data-runtime-entity="user"/>
                    <row/>
                    <label id="password-label" text="Password"/>
                    <textfield id="password" label="Password"/>
                    <row/>
                    <checkbox id="remember" text="Remember me" label="Remember me"/>
                    <row/>
                    <button id="save" class="primary" text="Save" name="Save" width="180"
                            align="left"/>
                  </table>
                </window>
              </table>
            </ui>
            """;

    private static final String CSS = """
            .panel { padding: 28px; }
            .title { font-color: accent; }
            button { padding: 12px; }
            button.primary { background: accent; }
            button.primary:hover { background: accent-over; }
            button.primary:pressed { background: accent-down; }
            textfield { background: field; padding: 8px; }
            checkbox { font-color: text; }
            checkbox:hover { font-color: accent; }
            """;

    private final Stage stage = new Stage(new ScreenViewport());
    private final MarkupDocument document = new MarkupParser().parse(MARKUP);
    private final CssDocument css = new CssParser().parse(CSS);
    private final Skin skin = DefaultSkin.create();
    private MarkupRuntimeSource runtimeSource;

    /** Builds the panel with a no-op sink; harness semantics attach later. */
    public MarkupSigninScreen() {
        stage.getRoot().setName("markup-stage");
        stage.getViewport().update(1280, 720, true);
        buildUi(new NoopSink());
    }

    /** Returns the application-owned Stage. */
    @Override public Stage stage() {
        return stage;
    }

    /**
     * Rebuilds the scene with the harness semantics sink and registers every
     * {@code data-runtime-entity} actor as an agent-runtime value source. The sink carries
     * {@link FixtureControl#CORRELATION_TOKEN} so {@code ui_runtime_compare} can prove frames.
     */
    public void attachSemantics(Semantics semantics, AgentRuntime runtime, String uiSessionId) {
        Objects.requireNonNull(semantics, "semantics");
        Objects.requireNonNull(runtime, "runtime");
        BuiltUi built = buildUi(new HarnessSemanticSink(
                semantics, FixtureControl.CORRELATION_TOKEN));
        // The harness typography and layout references require marked actors; mirror the
        // reference screen's title-label metadata.
        com.badlogic.gdx.scenes.scene2d.Actor title = built.root().findActor("signin-title");
        semantics.setTypography(title, typographyMetadata());
        semantics.setLayout(title, new dev.gdx.uiharness.scene2d.LayoutMetadata("persistent-title"));
        if (runtimeSource != null) {
            runtimeSource.close();
        }
        runtimeSource = MarkupRuntimeSource.register(runtime, document, built, uiSessionId);
    }

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

    /** Releases the runtime registrations; call before the owning runtime is closed. */
    public void closeRuntimeSource() {
        if (runtimeSource != null) {
            runtimeSource.close();
            runtimeSource = null;
        }
    }

    private BuiltUi buildUi(SemanticSink sink) {
        stage.clear();
        BuiltUi built = MarkupBuilder.build(document, css, skin, sink);
        built.root().setSize(stage.getViewport().getWorldWidth(),
                stage.getViewport().getWorldHeight());
        stage.addActor(built.root());
        return built;
    }

    /** Draws the current stage after the harness has advanced its fixed clock. */
    @Override public void draw() {
        stage.draw();
    }

    /** Updates only the viewport; fixture coordinates remain pixel-stable. */
    @Override public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
    }

    /** Returns the test identifier of the typography-marked title label. */
    @Override public java.util.Set<String> typographyControlIds() {
        return java.util.Set.of("signin-title");
    }

    /** Returns the test identifier of the layout-marked title label. */
    @Override public java.util.Set<String> layoutControlIds() {
        return java.util.Set.of("signin-title");
    }

    @Override public void close() {
        closeRuntimeSource();
        skin.dispose();
        stage.dispose();
    }
}
