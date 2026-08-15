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
            "Missing evidence must be reported as missing; it must not be filled with examples, conventions, or model knowledge.",
            "Missing evidence and capability limits are relevant only when they block an explicitly requested deliverable; they must never expand the task scope.",
            "A tool contract's unsupported or not-assessed claims describe its capability boundary, not additional user requirements or automatic follow-up work.",
            "Partial data is a normal result: analyze every returned dataset first and report unavailable dimensions separately.",
            "A non-empty truncated preview is partial returned evidence, not missing evidence: analyze every visible fact and limit only claims that require the omitted portion.",
            "Current-turn tool evidence has priority over historical conversation evidence; historical output must not be presented as the current execution result.",
            "Successful empty results, unexecuted calls, blocked calls, and failed calls are distinct states and must never be collapsed into an error or into one another.",
            "Template execution is failure-isolated: one child error must be reported on that child and must not erase, stop, or downgrade successfully returned sibling template data.",
            "Execution failure causes must preserve the observed error code and message. Never replace a transport, binding, registration, policy, or downstream-address error with a different inferred cause.",
            "Dates and ranges must be copied from executed parameters or returned data without changing year, month, or day.",
            "SQL, shell commands, scripts, and validation snippets must not be presented as executed, authorized, or retrieved unless their exact text was returned as tool evidence.",
            "When the user explicitly asks to draft SQL, DDL, commands, or scripts, the model may generate a clearly labeled non-executed draft using observed facts plus explicit assumptions for human review.",
            "Asset displayName, name, id, and toolName are distinct contract fields and must never be relabeled or substituted."
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
            - Scope is defined by the current user request. Mention missing evidence or capability limits only when they materially block an explicitly requested deliverable; never expand the answer, hypothesis set, or follow-up plan merely because a tool advertises additional unsupported dimensions.
            - Tool fields such as unsupportedClaims, notAssessedClaims, capability limits, and coverage exclusions are guardrails, not a task checklist. Do not copy them into missing evidence, limitations, hypotheses, recommendations, or next actions unless the current user request explicitly requires that claim.
            - Partial-result presentation contract: analyze every successfully returned dataset first. Put missing dimensions in a short coverage/limitation section after the available-data analysis; do not replace the requested report with an API inventory or capability analysis.
            - Truncated-preview contract: outputTruncated=true, summaryTruncated=true, or a non-empty preview means DATA_RETURNED_PARTIAL, not NO_DATA. Analyze and report every fact visible in stdout, rows, items, or preview; qualify the result as partial and withhold only claims that depend on omitted content. Never replace visible-data analysis with "read the external document".
            - If Runtime resolved and supplied verified externalized output, analyze that full output. A documentId by itself is only a reference and does not prove facts that are absent from the supplied evidence.
            - Current-turn evidence contract: facts from the current execution outrank conversation history. Never describe a historical command result, template id, documentId, timestamp, or metric as the current run unless current-turn evidence returns the same fact; label intentional comparisons explicitly.
            - Keep these states distinct: DATA_RETURNED, EMPTY_RESULT, NOT_EXECUTED, BLOCKED, and FAILED. A successful empty result is a valid observation for the exact executed parameters, not a system error; do not invent a business cause for the empty result.
            - Template child failures are isolated execution results. Continue analyzing successful and empty sibling results, and list each failed child's returned error compactly instead of declaring the whole report unavailable.
            - Preserve failure identity exactly. If an executor produced a tool trace, it was registered for that invocation; do not call it unregistered. Do not rewrite NameResolver/UNAVAILABLE transport evidence as a parameter error, or a parameter-evidence rejection as a template-schema name mismatch unless schema evidence explicitly proves that mismatch.
            - Discovery proves that an asset/template exists, not that target records were retrieved. Never label a discovered-but-unexecuted metric as "covered", "obtained", or successful data acquisition.
            - Copy query dates and date ranges exactly from executed parameters or returned records. Convert YYYYMMDD to YYYY-MM-DD without changing any digit; never substitute an illustrative range or a different year.
            - Do not claim the runtime or environment only permits discovery when an executor actually ran. Do not recommend manual one-by-one calls when the intended runtime workflow is an ordered batch; report the missing batch execution compactly.
            - Never present illustrative/manual SQL, shell commands, scripts, or validation snippets as executed, authorized, retrieved, or factual tool output unless their exact text was returned by an authorized tool.
            - If the user explicitly asks to draft SQL, DDL, commands, or scripts, you may generate a clearly labeled non-executed draft for human review. Use observed facts where available, mark assumptions and unresolved choices, and never imply Runtime executed or approved it.
            - Preserve asset contract semantics exactly: displayName/name is the asset label, assetId/id is the asset identifier, and toolName is the bound execution tool. Never relabel toolName as displayName.
            - Runtime must validate the final answer and rewrite it from original tool evidence when fact mutation is detected.

            """;
    }
}
