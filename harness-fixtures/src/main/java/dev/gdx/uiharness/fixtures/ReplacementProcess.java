package dev.gdx.uiharness.fixtures;

import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.lwjgl3.RegisteredLaunchCoordinator;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one fixture-private replacement JVM and its one-request message exchange. */
final class ReplacementProcess implements AutoCloseable {
    private final Process process;
    private final BufferedWriter input;
    private final CompletableFuture<RegisteredLaunchCoordinator.HandoffOutcome> result;
    private final AtomicBoolean closed = new AtomicBoolean();

    static ReplacementProcess launch(ScenarioRequest request) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(ReplacementJvmCommand.build());
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process = builder.start();
        try {
            BufferedWriter input = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
            input.write(ReplacementWire.request(request));
            input.newLine();
            input.flush();
            return new ReplacementProcess(process, input, request);
        } catch (Throwable failure) {
            process.destroyForcibly();
            throw failure;
        }
    }

    private ReplacementProcess(Process process, BufferedWriter input, ScenarioRequest request) {
        this.process = process;
        this.input = input;
        long timeoutNanos = Math.max(1L, request.deadline().remaining().toNanos());
        result = CompletableFuture.supplyAsync(() -> readResult(process))
                .orTimeout(timeoutNanos, TimeUnit.NANOSECONDS);
        result.whenComplete((ignored, failure) -> {
            if (failure != null) {
                close();
            }
        });
    }

    CompletableFuture<RegisteredLaunchCoordinator.HandoffOutcome> result() {
        return result;
    }

    void cancel() {
        if (!process.isAlive()) {
            return;
        }
        try {
            synchronized (input) {
                input.write("CANCEL");
                input.newLine();
                input.flush();
            }
        } catch (IOException ignored) {
            // Closing below remains the authoritative cancellation boundary.
        }
    }

    private static RegisteredLaunchCoordinator.HandoffOutcome readResult(Process process) {
        try (BufferedReader output = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line = output.readLine();
            if (line == null) {
                throw new IOException("replacement process exited without a result (exit "
                        + process.waitFor() + ")");
            }
            if (line.length() > ReplacementWire.MAX_LINE_CHARS) {
                throw new IOException("replacement result exceeds message bound");
            }
            RegisteredLaunchCoordinator.HandoffResult result = ReplacementWire.result(line);
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("replacement process failed with exit " + exit);
            }
            return result;
        } catch (Exception failure) {
            throw new java.util.concurrent.CompletionException(failure);
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // Process termination below is authoritative.
        }
        if (process.isAlive()) {
            process.destroy();
            try {
                process.onExit().get(1, TimeUnit.SECONDS);
            } catch (Exception failure) {
                process.destroyForcibly();
            }
        }
    }
}
