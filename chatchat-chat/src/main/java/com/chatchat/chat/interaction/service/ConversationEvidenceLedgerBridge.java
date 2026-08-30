package com.chatchat.chat.interaction.service;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;


import com.chatchat.agents.evidence.answer.EvidenceAnswer;


import com.chatchat.agents.runtime.governance.McpEvidenceResult;

import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.chat.interaction.model.InteractionSource;
import com.chatchat.common.interaction.InteractionToolTrace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persists compact evidence identity across turns without promoting historical facts to current evidence.
 */
final class ConversationEvidenceLedgerBridge {

    static final String SCHEMA_VERSION = "conversation_evidence_ledger.v1";

    Map<String, Object> capture(InteractionResponse response,
                                String tenantId,
                                String conversationId,
                                String requestId) {
        if (response == null) {
            return Map.of();
        }
        Map<String, Object> metadata = map(response.getMetadata());
        Map<String, Object> agent = map(metadata.get("agent"));
        List<Map<String, Object>> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        Map<String, Object> summary = map(agent.get("analysisSummaryResult"));
        addSummary(entries, seen, summary);
        if (response.getToolTraces() != null) {
            for (InteractionToolTrace trace : response.getToolTraces()) {
                if (trace == null) continue;
                Map<String, Object> descriptor = map(
                    trace.getRuntimeMetadata() == null ? null
                        : trace.getRuntimeMetadata().get("mcpEvidenceResult"));
                addMcpEvidence(entries, seen, descriptor, trace.getToolName());
            }
        }
        Object citations = first(
            nested(agent, "evidenceAnswer", "citations"),
            first(agent.get("availableEvidenceCitations"), response.getSources()));
        addCitations(entries, seen, citations);
        if (entries.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> scope = Map.of(
            "tenantId", text(tenantId, "default"),
            "conversationId", text(conversationId, "unknown-conversation"),
            "authority", "CONVERSATION_RUNTIME_CONTEXT"
        );
        Map<String, Object> ledger = new LinkedHashMap<>();
        ledger.put("schemaVersion", SCHEMA_VERSION);
        ledger.put("scope", scope);
        ledger.put("sourceRequestId", text(requestId, "unknown-request"));
        ledger.put("sourceTurnId", text(requestId, "unknown-request"));
        ledger.put("status", "HISTORICAL_ON_NEXT_TURN");
        ledger.put("entries", List.copyOf(entries));
        ledger.put("reusePolicy", Map.of(
            "currentFact", false,
            "revalidationRequired", true,
            "freshness", "UNKNOWN_ON_NEXT_TURN",
            "expiresAt", "UNKNOWN",
            "crossTenantReuseAllowed", false,
            "crossConversationReuseAllowed", false,
            "rawPayloadPersisted", false
        ));
        return Map.copyOf(ledger);
    }

    String project(List<ConversationMemoryService.MessageSnapshot> messages,
                   String tenantId,
                   String conversationId,
                   int maxEntries) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        List<Map<String, Object>> projected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int index = messages.size() - 1; index >= 0 && projected.size() < maxEntries; index--) {
            ConversationMemoryService.MessageSnapshot message = messages.get(index);
            Map<String, Object> ledger = map(message == null || message.memoryContext() == null
                ? null : message.memoryContext().get("conversationEvidenceLedger"));
            if (!SCHEMA_VERSION.equals(string(ledger.get("schemaVersion")))) continue;
            Map<String, Object> scope = map(ledger.get("scope"));
            if (!text(tenantId, "default").equals(string(scope.get("tenantId")))
                || !text(conversationId, "unknown-conversation").equals(string(scope.get("conversationId")))) {
                continue;
            }
            Object rawEntries = ledger.get("entries");
            if (!(rawEntries instanceof List<?> list)) continue;
            for (Object value : list) {
                Map<String, Object> entry = map(value);
                String refId = string(entry.get("refId"));
                if (refId == null || !seen.add(refId)) continue;
                projected.add(entry);
                if (projected.size() >= maxEntries) break;
            }
        }
        if (projected.isEmpty()) return "";
        return "conversation_evidence_projection.v1; status=HISTORICAL_CONTEXT_ONLY; "
            + "currentFact=false; revalidationRequired=true; entries=" + projected;
    }

    private void addSummary(List<Map<String, Object>> entries, Set<String> seen, Map<String, Object> summary) {
        String id = string(summary.get("resultId"));
        if (id == null || !seen.add(id)) return;
        entries.add(compact("SUMMARY_RESULT", id,
            string(summary.get("outcome")), string(summary.get("scope")),
            "DERIVED_SUMMARY_REQUIRES_CURRENT_EVIDENCE"));
    }

    private void addMcpEvidence(List<Map<String, Object>> entries,
                                Set<String> seen,
                                Map<String, Object> descriptor,
                                String fallbackTool) {
        String id = string(descriptor.get("evidenceId"));
        if (id == null || !seen.add(id)) return;
        entries.add(compact("MCP_EVIDENCE_RESULT", id,
            string(descriptor.get("outcome")),
            firstText(string(descriptor.get("toolName")), fallbackTool),
            "REEXECUTE_OR_EXPLICITLY_LABEL_HISTORICAL"));
    }

    private void addCitations(List<Map<String, Object>> entries, Set<String> seen, Object values) {
        if (!(values instanceof List<?> list)) return;
        for (Object value : list) {
            if (value instanceof InteractionSource source) {
                String id = source.getSource();
                if (id != null && !id.isBlank() && seen.add(id)) {
                    entries.add(compact("CITATION", id, "captured", "interaction_source",
                        "RESOLVE_AND_REAUTHORIZE_BEFORE_FACT_USE"));
                }
                continue;
            }
            Map<String, Object> citation = map(value);
            String id = firstText(string(citation.get("refId")),
                firstText(string(citation.get("sourceRef")), string(citation.get("source"))));
            if (id == null || !seen.add(id)) continue;
            entries.add(compact("CITATION", id, "captured", string(citation.get("type")),
                "RESOLVE_AND_REAUTHORIZE_BEFORE_FACT_USE"));
        }
    }

    private Map<String, Object> compact(String kind,
                                        String refId,
                                        String outcome,
                                        String source,
                                        String reuseRule) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", kind);
        value.put("refId", refId);
        if (outcome != null && !outcome.isBlank()) value.put("outcome", outcome);
        if (source != null && !source.isBlank()) value.put("source", source);
        value.put("reuseRule", reuseRule);
        return Map.copyOf(value);
    }

    private Object nested(Map<String, Object> source, String parent, String child) {
        return map(source.get(parent)).get(child);
    }

    private Object first(Object first, Object second) {
        return first == null ? second : first;
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source) || source.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null && item != null) result.put(String.valueOf(key), item);
        });
        return result;
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstText(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
