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
        return new CodingEvidenceCollector.Validation(valid, id(), version(),
            Map.of("validationScope", "STRUCTURAL_ONLY", "rowSchemaMatched", valid,
                "resultRows", rows.size(), "businessValidation", "NOT_EVALUATED"), false);
    }
}
