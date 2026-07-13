package com.chatchat.agents.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical Agent Runtime contract for preserving tool facts during model reasoning. */
public final class AgentRuntimeFactGroundingContract {

    public static final String CONTRACT_VERSION = "agent_runtime_fact_grounding_v1";

    private AgentRuntimeFactGroundingContract() {
    }

    public static Map<String, Object> metadata() {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("contractVersion", CONTRACT_VERSION);
        contract.put("factAuthority", "TOOL_STRUCTURED_OUTPUT");
        contract.put("modelRole", "INTERPRET_AND_SUMMARIZE_WITHIN_FACT_BOUNDARY");
        contract.put("runtimeRole", "PRESERVE_VALIDATE_AND_REWRITE_ON_FACT_MUTATION");
        contract.put("invariants", List.of(
            "Tool structured output defines the immutable fact boundary.",
            "The model may explain, relate, and summarize facts, but must not add, rename, replace, or contradict them.",
            "Identifiers, counts, statuses, completeness flags, database layers, schemas, tables, fields, and execution results remain exact.",
            "Inferences and recommendations must be explicitly separated from observed facts and must never be presented as retrieved objects.",
            "Missing evidence must be reported as missing; it must not be filled with examples, conventions, or model knowledge."
        ));
        contract.put("enforcementStages", List.of(
            "planning",
            "tool_result_review",
            "final_synthesis",
            "answer_review"
        ));
        contract.put("onViolation", "REWRITE_FROM_ORIGINAL_TOOL_EVIDENCE_OR_RETURN_SAFE_LIMITATION");
        return Map.copyOf(contract);
    }

    public static String promptSection() {
        return """
            Agent Runtime fact-grounding contract (contractVersion=agent_runtime_fact_grounding_v1):
            - Structured tool output is the immutable fact boundary and has priority over model assumptions or prior knowledge.
            - The model may interpret, connect, and summarize observed facts, but must not add, rename, replace, omit material qualifiers from, or contradict them.
            - Preserve exact identifiers, counts, statuses, completeness/truncation flags, database layers, schemas, tables, fields, and execution outcomes.
            - Keep explicit inference/recommendation separate from observed facts. Never present inferred examples or naming conventions as retrieved results.
            - When evidence is missing, state the missing evidence; do not fill the gap with model knowledge.
            - Runtime must validate the final answer and rewrite it from original tool evidence when fact mutation is detected.

            """;
    }
}
