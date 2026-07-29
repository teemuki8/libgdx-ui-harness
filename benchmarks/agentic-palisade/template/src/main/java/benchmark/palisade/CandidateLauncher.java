package benchmark.palisade;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.glutils.HdpiMode;
import java.nio.file.Path;

/** First-thread LWJGL3 entry point for a finite local benchmark run. */
public final class CandidateLauncher {
    private static final int INITIAL_WIDTH = 1280;
    private static final int INITIAL_HEIGHT = 720;

    private CandidateLauncher() {
    }

    /** Launches the candidate on the JVM's process-entry thread. */
    public static void main(String[] args) throws Exception {
        LaunchArguments launch = LaunchArguments.parse(args);
        BenchmarkControl control = BenchmarkControl.open(
                launch.commands(), launch.evidence());

        Lwjgl3ApplicationConfiguration configuration =
                new Lwjgl3ApplicationConfiguration();
        configuration.setTitle("Candidate UI");
        configuration.setWindowedMode(INITIAL_WIDTH, INITIAL_HEIGHT);
        configuration.setWindowSizeLimits(1, 1, 4096, 4096);
        configuration.setResizable(false);
        configuration.setInitialVisible(false);
        configuration.setHdpiMode(HdpiMode.Pixels);
        configuration.setBackBufferConfig(8, 8, 8, 8, 24, 8, 0);
        configuration.useVsync(false);
        configuration.setForegroundFPS(60);
        configuration.setIdleFPS(60);
        configuration.disableAudio(true);

        new Lwjgl3Application(new CandidateApplication(control), configuration);
    }

    private record LaunchArguments(Path commands, Path evidence) {
        private static LaunchArguments parse(String[] args) {
            if (args.length != 4) {
                throw usage();
            }
            Path commands = null;
            Path evidence = null;
            for (int index = 0; index < args.length; index += 2) {
                String flag = args[index];
                Path value;
                try {
                    value = Path.of(args[index + 1]);
                } catch (RuntimeException invalidPath) {
                    throw new IllegalArgumentException("Invalid local path", invalidPath);
                }
                switch (flag) {
                    case "--commands" -> {
                        if (commands != null) {
                            throw usage();
                        }
                        commands = value;
                    }
                    case "--evidence" -> {
                        if (evidence != null) {
                            throw usage();
                        }
                        evidence = value;
                    }
                    default -> throw usage();
                }
            }
            if (commands == null || evidence == null) {
                throw usage();
            }
            return new LaunchArguments(commands, evidence);
        }

        private static IllegalArgumentException usage() {
            return new IllegalArgumentException(
                    "Expected --commands <input.ndjson> --evidence <directory>");
        }
    }
}
