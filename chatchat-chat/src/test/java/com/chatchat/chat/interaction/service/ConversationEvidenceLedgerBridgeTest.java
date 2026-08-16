package com.chatchat.chat.interaction.service;

import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.common.interaction.InteractionToolTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationEvidenceLedgerBridgeTest {

    private final ConversationEvidenceLedgerBridge bridge = new ConversationEvidenceLedgerBridge();

    @Test
    void capturesReferencesAndProjectsOnlyWithinTheTrustedConversationPartition() {
        Map<String, Object> evidenceDescriptor = Map.of(
            "schemaVersion", "mcp_evidence_result.v1",
            "evidenceId", "tenant-a:run-a:portfolio-query:abc",
            "toolName", "portfolio_query",
            "outcome", "success"
        );
        InteractionToolTrace trace = InteractionToolTrace.builder()
            .toolName("portfolio_query")
            .success(true)
            .runtimeMetadata(Map.of("mcpEvidenceResult", evidenceDescriptor))
            .build();
        InteractionResponse response = InteractionResponse.builder()
            .answer("answer")
            .toolTraces(List.of(trace))
            .metadata(Map.of("agent", Map.of(
                "analysisSummaryResult", Map.of(
                    "resultId", "tenant-a:run-a:final-summary#answer_finalization",
                    "scope", "FINAL_SYNTHESIS",
                    "outcome", "FINAL_ANSWER_ASSEMBLY"),
                "evidenceAnswer", Map.of("citations", List.of(
                    Map.of("refId", "doc://policy#chunk=2"))))))
            .build();

        Map<String, Object> ledger = bridge.capture(
            response, "tenant-a", "conversation-a", "request-a");
        ConversationMemoryService.MessageSnapshot message =
            new ConversationMemoryService.MessageSnapshot(
                "assistant", "answer", 1L, Map.of("conversationEvidenceLedger", ledger));

        assertThat(ledger.toString())
            .contains("conversation_evidence_ledger.v1", "tenantId=tenant-a",
                "conversationId=conversation-a", "rawPayloadPersisted=false")
            .contains("SUMMARY_RESULT", "MCP_EVIDENCE_RESULT", "CITATION")
            .doesNotContain("TOTAL_ASSET");
        assertThat(bridge.project(List.of(message), "tenant-a", "conversation-a", 20))
            .contains("conversation_evidence_projection.v1", "HISTORICAL_CONTEXT_ONLY",
                "currentFact=false", "revalidationRequired=true", "portfolio_query");
        assertThat(bridge.project(List.of(message), "tenant-b", "conversation-a", 20)).isEmpty();
        assertThat(bridge.project(List.of(message), "tenant-a", "conversation-b", 20)).isEmpty();
    }
}
