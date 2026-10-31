package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.protocol.AgentProtocolCatalog;
import com.chatchat.agents.runtime.toolcall.TemplateInvocationBridge;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ReviewedTemplateParameterProtocolTest {
    private final List<Map<String, Object>> rows = java.util.stream.IntStream.range(0, 11)
        .mapToObj(i -> Map.<String, Object>of("amount", i)).toList();
    private final Map<String, Object> output = Map.of("data", Map.of("data", Map.of("data", Map.of("rows", rows))));
    private final Map<String, Object> template = Map.of("templateId", "published", "parameterSchema", Map.of(
        "type", "object", "properties", Map.of("rows", Map.of("type", "array")), "required", List.of("rows")));

    private Map<String, Object> protocol(String path) {
        return Map.of("protocol_version", AgentProtocolCatalog.TEMPLATE_PARAMETER, "template_id", "published",
            "binding_status", "READY", "arguments", Map.of("rows", Map.of("value", rows, "source", "tool_result",
                "evidence", Map.of("step_id", 1, "output_path", path))), "unresolved_parameters", List.of());
    }
    private InterpretationPlanRuntime.StepExecution source(boolean success, boolean reviewed, List<?> protocols) {
        return new InterpretationPlanRuntime.StepExecution(5, "mcp_tool", "discovery", success,
            Map.of("templates", List.of(template)), null, null, null, 1,
            Map.of("semanticCandidateReviewSatisfied", reviewed, "parameterProtocols", protocols));
    }
    private TemplateInvocationBridge.BridgeResult execute(Map<String, Object> protocol) {
        return new TemplateInvocationBridge().prepare(new TemplateInvocationBridge.BridgeRequest("executor", 4,
            "published", template, Map.of(), protocol, false, true,
            new TemplateInvocationBridge.EvidenceContext("Analyze", Map.of(1, output))));
    }

    @Test void preservesReviewedExactPathInsteadOfReextractingItFromThePreview() {
        var controller = new LinkedHashMap<>(protocol("$.data.data.rows")); controller.put("step_id", 4);
        assertThatThrownBy(() -> execute(controller)).hasMessageContaining("EVIDENCE_MISMATCH");
        var binding = ReviewedTemplateParameterProtocol.bind(source(true, true,
            List.of(protocol("$.data.data.data.rows"))), "published", 4);
        assertThat(binding).containsEntry("step_id", 4).containsEntry("source_discovery_step_id", 5);
        assertThat(execute(binding).parameters()).containsEntry("rows", rows);
    }

    @Test void missingFailedUnreviewedOrAmbiguousSelectionsCannotPinBindings() {
        var p = protocol("$.data.data.data.rows");
        assertThat(ReviewedTemplateParameterProtocol.bind(source(false, true, List.of(p)), "published", 4)).isEmpty();
        assertThat(ReviewedTemplateParameterProtocol.bind(source(true, false, List.of(p)), "published", 4)).isEmpty();
        assertThat(ReviewedTemplateParameterProtocol.bind(source(true, true, List.of(p)), "other", 4)).isEmpty();
        assertThat(ReviewedTemplateParameterProtocol.bind(source(true, true, List.of(p, p)), "published", 4)).isEmpty();
    }

    @Test void rejectedProtocolsRemainRejectedAndEvidenceIsStillRecheckedAtExecution() {
        var p = new LinkedHashMap<>(protocol("$.data.data.data.rows"));
        p.put("binding_error", Map.of("code", "INVALID_TOOL_ARGUMENTS"));
        assertThat(ReviewedTemplateParameterProtocol.bind(source(true, true, List.of(p)), "published", 4)).isEmpty();
        var badEvidence = ReviewedTemplateParameterProtocol.bind(source(true, true,
            List.of(protocol("$.data.data.rows"))), "published", 4);
        assertThatThrownBy(() -> execute(badEvidence)).hasMessageContaining("EVIDENCE_MISMATCH");
    }
}
