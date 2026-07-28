package dev.gdx.uiharness.mcp;

import dev.gdx.uiharness.protocol.HarnessProtocolService;
import java.util.Map;
import java.util.concurrent.Executors;

/** Stdio-only production entry point. */
public final class Main {
    private Main() {}

    /** Starts one stdio MCP connection and exits cleanly when stdin closes. */
    public static void main(String[] args) {
        if (args.length != 0) {
            throw new IllegalArgumentException("The MCP server accepts no command-line parameters");
        }
        try (var protocolExecutor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessMcpServer server = HarnessMcpServer.open(
                        new HarnessProtocolService(
                                Map.of(), System::nanoTime, protocolExecutor),
                        ArtifactReference.Publisher.unavailable(), System.in, System.out)) {
            server.awaitTermination();
        }
    }
}
