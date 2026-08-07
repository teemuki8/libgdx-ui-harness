package dev.gdx.uiharness.fixtures;

import com.badlogic.gdx.scenes.scene2d.Stage;
import java.util.Set;

/** Common surface of the real LWJGL3 fixture screens driven by the reference process. */
public interface FixtureScreen extends AutoCloseable {
    /** Returns the application-owned Stage. */
    Stage stage();

    /** Draws the current stage after the harness has advanced its fixed clock. */
    void draw();

    /** Updates the viewport for a new window size. */
    void resize(int width, int height);

    /** Returns the test identifiers of this screen's typography-marked actors. */
    Set<String> typographyControlIds();

    /** Returns the test identifiers of this screen's layout-marked actors. */
    Set<String> layoutControlIds();

    /** Releases screen-owned resources without throwing checked exceptions. */
    @Override void close();
}
