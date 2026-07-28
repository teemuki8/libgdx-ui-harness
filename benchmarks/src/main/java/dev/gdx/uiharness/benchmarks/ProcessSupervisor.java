package dev.gdx.uiharness.benchmarks;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Runs a finite child with concurrent bounded output drains and kill-before-join timeout. */
final class ProcessSupervisor {
    private static final Duration KILL_GRACE = Duration.ofSeconds(2);
    private static final Duration DRAIN_JOIN = Duration.ofSeconds(5);

    private ProcessSupervisor() {}

    static Result run(
            List<String> command, Path workingDirectory, Duration timeout, int maxOutputBytes)
            throws Exception {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(timeout, "timeout");
        if (command.isEmpty() || timeout.isZero() || timeout.isNegative()
                || maxOutputBytes <= 0) {
            throw new IllegalArgumentException("Invalid supervised process bounds");
        }

        ProcessBuilder builder = new ProcessBuilder(List.copyOf(command));
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        Process process = builder.start();
        BoundedOutput output = new BoundedOutput(maxOutputBytes);
        Thread stdout = Thread.ofVirtual().name("benchmark-child-stdout")
                .start(() -> drain(process.getInputStream(), output));
        Thread stderr = Thread.ofVirtual().name("benchmark-child-stderr")
                .start(() -> drain(process.getErrorStream(), output));

        boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) {
            terminate(process, false);
            if (!process.waitFor(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process, true);
                if (!process.waitFor(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("Timed-out child resisted forced termination");
                }
            }
        }
        joinDrain(stdout);
        joinDrain(stderr);
        return new Result(exited ? process.exitValue() : -1, !exited,
                output.text(), output.truncated());
    }

    private static void drain(InputStream stream, BoundedOutput output) {
        byte[] buffer = new byte[8_192];
        try (stream) {
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                output.append(buffer, read);
            }
        } catch (IOException failure) {
            output.append(("\n[output drain failed: " + failure + "]")
                    .getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void terminate(Process process, boolean forcibly) {
        List<ProcessHandle> descendants = process.descendants().toList();
        for (ProcessHandle descendant : descendants.reversed()) {
            if (forcibly) {
                descendant.destroyForcibly();
            } else {
                descendant.destroy();
            }
        }
        if (forcibly) {
            process.destroyForcibly();
        } else {
            process.destroy();
        }
    }

    private static void joinDrain(Thread thread) throws InterruptedException {
        thread.join(DRAIN_JOIN);
        if (thread.isAlive()) {
            throw new IllegalStateException("Child output drain did not terminate");
        }
    }

    /** Bounded child result; timed-out processes use exit code -1. */
    record Result(int exitCode, boolean timedOut, String output, boolean outputTruncated) {}

    private static final class BoundedOutput {
        private final byte[] bytes;
        private int length;
        private boolean truncated;

        private BoundedOutput(int capacity) {
            bytes = new byte[capacity];
        }

        private synchronized void append(byte[] source) {
            append(source, source.length);
        }

        private synchronized void append(byte[] source, int sourceLength) {
            int copied = Math.min(sourceLength, bytes.length - length);
            if (copied > 0) {
                System.arraycopy(source, 0, bytes, length, copied);
                length += copied;
            }
            if (copied < sourceLength) {
                truncated = true;
            }
        }

        private synchronized String text() {
            return new String(bytes, 0, length, StandardCharsets.UTF_8);
        }

        private synchronized boolean truncated() {
            return truncated;
        }
    }
}
