package dev.gdx.uiharness.protocol;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Explicit owner-scoped durable storage for the last terminal recovery record. */
public final class TerminalRecordStore {
    private final Path target;

    /** Binds the store to one caller-owned file; no path is exposed through MCP. */
    public TerminalRecordStore(Path target) {
        this.target = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        if (this.target.getParent() == null) {
            throw new IllegalArgumentException("terminal record requires a parent directory");
        }
    }

    /** Atomically replaces the retained record only after its digest is verified. */
    public void retain(RecoveryWorkflow.TerminalRecord record) throws IOException {
        Objects.requireNonNull(record, "record");
        if (!record.hasValidDigest()) {
            throw new IllegalArgumentException("terminal record digest does not match content");
        }
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(
                target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            ProtocolJson.mapper().writerWithDefaultPrettyPrinter()
                    .writeValue(temporary.toFile(), record);
            try {
                Files.move(
                        temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(
                        temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Reads and digest-verifies the retained terminal record after process restart. */
    public RecoveryWorkflow.TerminalRecord read() throws IOException {
        RecoveryWorkflow.TerminalRecord record = ProtocolJson.mapper().readValue(
                target.toFile(), RecoveryWorkflow.TerminalRecord.class);
        if (!record.hasValidDigest()) {
            throw new IOException("terminal record digest does not match content");
        }
        return record;
    }
}
