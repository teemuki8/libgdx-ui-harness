package dev.gdx.uiharness.fixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Builds a fresh JVM command that starts LWJGL on the required platform thread. */
public final class ReferenceJvmCommand {
    private ReferenceJvmCommand() {}

    /** Builds the command for the supplied Java executable, classpath, OS, and application args. */
    public static List<String> build(
            String java, String classpath, String osName, String... applicationArguments) {
        Objects.requireNonNull(java, "java");
        Objects.requireNonNull(classpath, "classpath");
        Objects.requireNonNull(osName, "osName");
        Objects.requireNonNull(applicationArguments, "applicationArguments");
        var command = new ArrayList<String>(7 + applicationArguments.length);
        command.add(java);
        command.add("--enable-native-access=ALL-UNNAMED");
        if (osName.toLowerCase(Locale.ROOT).startsWith("mac")) {
            command.add("-XstartOnFirstThread");
        }
        command.add("-cp");
        command.add(classpath);
        command.add(ReferenceUiApplication.class.getName());
        command.addAll(List.of(applicationArguments));
        return List.copyOf(command);
    }
}
