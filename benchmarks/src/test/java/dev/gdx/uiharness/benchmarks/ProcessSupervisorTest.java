package dev.gdx.uiharness.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ProcessSupervisorTest {
    @Test void timeoutKillsChildBeforeJoiningItsOpenOutputPipe() {
        ProcessSupervisor.Result result = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> ProcessSupervisor.run(command("hang"), null,
                        Duration.ofMillis(500), 1_024));

        assertTrue(result.timedOut());
        assertTrue(result.output().contains("started"));
    }

    @Test void outputCollectionIsBounded() throws Exception {
        ProcessSupervisor.Result result = ProcessSupervisor.run(command("flood"), null,
                Duration.ofSeconds(3), 1_024);

        assertEquals(0, result.exitCode());
        assertEquals(1_024, result.output().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertTrue(result.outputTruncated());
    }

    private static List<String> command(String mode) {
        return List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                Child.class.getName(), mode);
    }

    public static final class Child {
        private Child() {}

        public static void main(String[] args) throws Exception {
            if ("hang".equals(args[0])) {
                System.out.print("started");
                System.out.flush();
                Thread.sleep(Duration.ofSeconds(30));
                return;
            }
            System.out.print("x".repeat(100_000));
        }
    }
}
