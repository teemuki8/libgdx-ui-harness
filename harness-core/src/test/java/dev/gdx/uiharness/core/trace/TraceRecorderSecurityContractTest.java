package dev.gdx.uiharness.core.trace;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Security contract that must hold on every provider. Unlike the recorder and
 * replayer behavioral tests, this class has no secure-directory-stream
 * assumption, so it also runs on providers without one (for example Windows):
 * the recorder is required to fail closed at construction there, because no
 * child operation can be anchored to a verified directory handle.
 */
final class TraceRecorderSecurityContractTest {
    @TempDir Path temporaryDirectory;

    @Test void recorderConstructionFailsClosedWithoutSecureDirectoryStreams() {
        if (secureDirectoryStreamsAvailable()) {
            assertDoesNotThrow(
                    () -> new TraceRecorder(temporaryDirectory, Clock.systemUTC()));
            return;
        }
        assertThrows(IllegalArgumentException.class,
                () -> new TraceRecorder(temporaryDirectory, Clock.systemUTC()));
    }

    private static boolean secureDirectoryStreamsAvailable() {
        try {
            Path probe = Files.createTempDirectory("secure-stream-probe");
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(probe)) {
                return stream instanceof SecureDirectoryStream;
            } finally {
                Files.deleteIfExists(probe);
            }
        } catch (IOException exception) {
            return false;
        }
    }
}
