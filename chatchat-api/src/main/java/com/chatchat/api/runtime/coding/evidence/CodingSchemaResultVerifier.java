package com.chatchat.api.runtime.coding.evidence;

import com.chatchat.agents.orchestration.analysis.graph.WorkspaceResultSchema;
import com.chatchat.api.runtime.coding.data.CodingInputSnapshotService;
import com.chatchat.common.kernel.KernelDataScope;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Structural validation only. Runtime does not certify model-selected business conclusions. */
@Component
public final class CodingSchemaResultVerifier implements CodingResultVerifier {
    public static final String REPORT_SCOPE_RULE = "For report schemas declaring analysisStatus and fieldProvenance: "
        + "any UNCONFIRMED interpreted field requires analysisStatus INPUT_REQUIRED and an empty visualReport.charts array. "
        + "Keep verifiable source structure and missing definitions; remove unsupported business charts and their narrative. "
        + "This checks the report's own declarations, not independent business truth.";
    public String id() { return "runtime-result-schema"; }
    public String version() { return "v1"; }
    public void validateRules(Map<String, Object> rules) {
        if (!rules.keySet().equals(java.util.Set.of("rowSchema")))
            throw new IllegalArgumentException("Only rowSchema is supported by the structural verifier");
        var schema = WorkspaceResultSchema.object(rules.get("rowSchema"));
        if (!"object".equals(schema.get("type"))) throw new IllegalArgumentException("JSONL rows must be objects");
        WorkspaceResultSchema.checkContract(schema);
    }
    public CodingEvidenceCollector.Validation verify(KernelDataScope scope,
            CodingInputSnapshotService.Snapshot input, Map<String, Object> rules, List<Map<String, Object>> rows) {
        validateRules(rules);
        var schema = WorkspaceResultSchema.object(rules.get("rowSchema"));
        boolean valid = true;
        for (var row : rows) {
            try { WorkspaceResultSchema.validate(row, schema); }
            catch (IllegalArgumentException mismatch) { valid = false; break; }
        }
        var properties = WorkspaceResultSchema.object(schema.getOrDefault("properties", Map.of()));
        boolean reportContract = properties.containsKey("analysisStatus") && properties.containsKey("fieldProvenance");
        boolean consistent = !reportContract || rows.stream().allMatch(row -> {
            boolean unknown = row.get("fieldProvenance") instanceof List<?> fields && fields.stream().anyMatch(field ->
                field instanceof Map<?,?> binding && "UNCONFIRMED".equals(binding.get("status")));
            if (!unknown) return true;
            if (!"INPUT_REQUIRED".equals(row.get("analysisStatus"))) return false;
            return !(row.get("visualReport") instanceof Map<?,?> visual)
                || !(visual.get("charts") instanceof List<?> charts) || charts.isEmpty();
        });
        return new CodingEvidenceCollector.Validation(valid && consistent, id(), version(),
            Map.of("validationScope", "STRUCTURAL_ONLY", "rowSchemaMatched", valid,
                "declaredReportScopeConsistent", consistent,
                "sourceCopyValidation", "COLLECTOR_IMMUTABLE_INPUT_COMPARISON",
                "resultRows", rows.size(), "businessValidation", "NOT_EVALUATED"), false);
    }
}
