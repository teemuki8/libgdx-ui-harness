package benchmark.palisade.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/** Produces the fixed candidate-visible feedback projection without hidden assertion details. */
public final class PublicFeedback {
    public static final String SCHEMA_VERSION = "agentic-palisade-feedback/v1";
    public static final String COMMAND = "benchmark-feedback";
    private static final ObjectMapper JSON = new ObjectMapper();

    private PublicFeedback() {
    }

    /** Serializes only aggregate corpus checks and declared automated visual metrics. */
    public static String toJson(EvaluationRecord record) {
        Objects.requireNonNull(record, "record");
        ObjectNode output = JSON.createObjectNode();
        output.put("schemaVersion", SCHEMA_VERSION);
        output.put("command", COMMAND);
        ObjectNode behavioral = output.putObject("behavioral");
        behavioral.put("passed", record.functional().passed());
        behavioral.put("total", record.functional().total());
        behavioral.put("conforming", record.functional().passed() == record.functional().total());
        ArrayNode visual = output.putArray("visual");
        for (EvaluationRecord.VisualOutcome outcome : record.visual()) {
            ObjectNode item = visual.addObject();
            item.put("referenceId", outcome.referenceId());
            item.put("viewportId", outcome.viewportId());
            VisualMetrics.Result metrics = outcome.metrics();
            item.put("rgbMae", metrics.rgbMae());
            ObjectNode ssim = item.putObject("luminanceSsim");
            ssim.put("scale1", metrics.luminanceSsimScale1());
            ssim.put("scale2", metrics.luminanceSsimScale2());
            ssim.put("scale4", metrics.luminanceSsimScale4());
            item.put("sobelEdgeF1", metrics.sobelEdgeF1());
            item.put("paletteDelta", metrics.paletteDelta());
            item.put("boundsDisplacement", metrics.boundsDisplacement());
            ObjectNode clipping = item.putObject("clipping");
            clipping.put("left", metrics.clipping().left());
            clipping.put("right", metrics.clipping().right());
            clipping.put("top", metrics.clipping().top());
            clipping.put("bottom", metrics.clipping().bottom());
            item.put("repeatability", metrics.repeatability());
            item.put("fontRasterResidual", metrics.fontRasterResidual());
        }
        ArrayNode structural = output.putArray("structuralUsability");
        for (StructuralUsability.Result outcome : record.structural()) {
            structural.add(JSON.valueToTree(outcome));
        }
        try {
            return JSON.writeValueAsString(output);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Could not serialize public feedback", impossible);
        }
    }

    static void validateEvaluationJson(JsonNode root) {
        if (!root.isObject() || !EvaluationRecord.SCHEMA_VERSION.equals(root.path("schemaVersion").textValue())) {
            throw new IllegalArgumentException("Unsupported evaluation evidence");
        }
    }
}
