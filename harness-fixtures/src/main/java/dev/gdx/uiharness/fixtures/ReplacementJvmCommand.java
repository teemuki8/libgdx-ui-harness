package dev.gdx.uiharness.fixtures;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Constructs the fixture-private JVM command for exactly one replacement scenario host. */
final class ReplacementJvmCommand {
    private ReplacementJvmCommand() {}

    static List<String> build() {
        String javaExecutable =
                Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("reference.app.classpath", System.getProperty("java.class.path"));
        var command = new ArrayList<String>();
        command.add(javaExecutable);
        if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
            command.add("-XstartOnFirstThread");
        }
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-cp");
        command.add(classpath);
        command.add(ReplacementScenarioHost.class.getName());
        return List.copyOf(command);
    }
}
