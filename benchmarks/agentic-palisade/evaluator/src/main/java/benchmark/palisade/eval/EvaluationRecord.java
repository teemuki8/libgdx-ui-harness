package benchmark.palisade.eval;

import java.util.List;
import java.util.Objects;

/** Schema-versioned hidden evaluator evidence with separate functional and visual channels. */
public record EvaluationRecord(
        String schemaVersion,
        String status,
        CandidateIdentity candidate,
        CorpusIdentity corpus,
        FunctionalOutcome functional,
        List<VisualOutcome> visual,
        List<Artifact> artifacts,
        List<String> diagnostics) {
    public static final String SCHEMA_VERSION = "agentic-palisade-evaluation/v1";

    public EvaluationRecord {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported evaluation schema");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(corpus, "corpus");
        Objects.requireNonNull(functional, "functional");
        visual = List.copyOf(visual);
        artifacts = List.copyOf(artifacts);
        diagnostics = List.copyOf(diagnostics);
    }

    static EvaluationRecord forTesting(FunctionalContract.Result result) {
        return new EvaluationRecord(SCHEMA_VERSION, "complete",
                new CandidateIdentity("fixture", "0".repeat(64)),
                new CorpusIdentity("agentic-palisade/v1", "0".repeat(64)),
                FunctionalOutcome.from(result), List.of(), List.of(), List.of());
    }

    /** External run identity and immutable candidate-tree digest. */
    public record CandidateIdentity(String id, String sha256) {
        public CandidateIdentity {
            Objects.requireNonNull(id, "id");
            requireSha256(sha256);
        }
    }

    /** Frozen corpus schema and complete tree digest. */
    public record CorpusIdentity(String schemaVersion, String sha256) {
        public CorpusIdentity {
            Objects.requireNonNull(schemaVersion, "schemaVersion");
            requireSha256(sha256);
        }
    }

    /** Exact functional outcome; no visual score is folded into it. */
    public record FunctionalOutcome(int passed, int total, List<FunctionalContract.Assertion> assertions) {
        public FunctionalOutcome {
            assertions = List.copyOf(assertions);
            if (passed < 0 || total < 0 || passed > total || total != assertions.size()) {
                throw new IllegalArgumentException("Invalid functional counts");
            }
        }

        static FunctionalOutcome from(FunctionalContract.Result result) {
            return new FunctionalOutcome(result.passedCount(), result.assertions().size(), result.assertions());
        }
    }

    /** One exact reference/candidate visual comparison. */
    public record VisualOutcome(String referenceId, String viewportId, String referenceSha256,
            List<String> captureSha256, VisualMetrics.Result metrics) {
        public VisualOutcome {
            Objects.requireNonNull(referenceId, "referenceId");
            Objects.requireNonNull(viewportId, "viewportId");
            requireSha256(referenceSha256);
            captureSha256 = List.copyOf(captureSha256);
            if (captureSha256.size() != 5) {
                throw new IllegalArgumentException("Visual outcome requires five capture hashes");
            }
            captureSha256.forEach(EvaluationRecord::requireSha256);
            Objects.requireNonNull(metrics, "metrics");
        }
    }

    /** Hash-bound retained evidence identity. */
    public record Artifact(String path, long bytes, String sha256) {
        public Artifact {
            Objects.requireNonNull(path, "path");
            if (path.isBlank() || path.startsWith("/") || path.contains("..") || bytes < 0) {
                throw new IllegalArgumentException("Invalid artifact identity");
            }
            requireSha256(sha256);
        }
    }

    private static void requireSha256(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid SHA-256 digest");
        }
    }
}
