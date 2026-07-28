package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ReferenceJvmCommandTest {
    @Test void macOsStartsLwjglOnTheProcessFirstThread() {
        List<String> command = ReferenceJvmCommand.build(
                "/jdk/bin/java", "app.jar", "Mac OS X", "/tmp/session");

        assertEquals(List.of(
                "/jdk/bin/java",
                "--enable-native-access=ALL-UNNAMED",
                "-XstartOnFirstThread",
                "-cp", "app.jar",
                ReferenceUiApplication.class.getName(),
                "/tmp/session"), command);
    }

    @Test void nonMacOsDoesNotReceiveTheAppleOnlyFlag() {
        List<String> command = ReferenceJvmCommand.build(
                "/jdk/bin/java", "app.jar", "Linux", "/tmp/session");

        assertFalse(command.contains("-XstartOnFirstThread"));
    }
}
