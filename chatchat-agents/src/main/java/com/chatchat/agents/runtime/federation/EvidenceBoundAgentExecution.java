package com.chatchat.agents.runtime.federation;

import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.orchestration.AgentOrchestrator;
import com.chatchat.common.runtime.agent.AgentExecutionOutcome;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Evidence-bound child execution; the owning Runtime persists its lifecycle and the parent verifies claims. */
public final class EvidenceBoundAgentExecution {
    public static final String OUTPUT_CONTRACT = "runtimeOutputContract";
    private static final ObjectMapper JSON = new ObjectMapper();
    private EvidenceBoundAgentExecution() { }
    public static boolean supports(AgentRunRequest request) {
        return request.getAttributes() != null
            && AgentExecutionOutcome.SCHEMA_VERSION.equals(request.getAttributes().get(OUTPUT_CONTRACT));
    }
    public static AgentOrchestrator.AgentExecutionResult execute(AgentRunRequest request, ChatModel model,
                                                                 BooleanSupplier cancelled) {
        if (!(request.getAttributes().get("evidenceBundle") instanceof EvidenceBundle bundle)
            || (request.getAvailableTools() != null && !request.getAvailableTools().isEmpty())
            || (request.getBoundDocumentIds() != null && !request.getBoundDocumentIds().isEmpty()))
            throw new IllegalArgumentException("Evidence-bound execution requires supplied evidence and no acquisition capabilities");
        var ids = bundle.evidence().stream().map(item -> item.evidenceId()).collect(java.util.stream.Collectors.toSet());
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(Objects.toString(request.getSystemPrompt(), "")
            + "\nThis invocation is an evidence-bound child task. Return the required JSON contract only. "
            + "Do not generate a Markdown report, retrieve data, or start another workflow. "
            + "Use existing evidenceIds exactly; claims must not exceed what their cited evidence supports."));
        messages.add(UserMessage.from(request.getQuery()));
        for (int attempt = 0; attempt < 2; attempt++) {
            check(cancelled);
            var response = model.chat(ChatRequest.builder().messages(List.copyOf(messages)).build());
            check(cancelled);
            String answer = response.aiMessage().text();
            if (valid(answer, ids)) return new AgentOrchestrator.AgentExecutionResult(answer, List.of(),
                Map.of("runStatus", "COMPLETED", "stopReason", "contract_completed", OUTPUT_CONTRACT,
                    AgentExecutionOutcome.SCHEMA_VERSION, "modelCalls", attempt + 1,
                    "evidenceItemCount", bundle.evidence().size(), "modelName", Objects.toString(request.getModelName(), "")));
            if (answer == null || answer.length() > 100_000) break;
            messages.add(response.aiMessage());
            messages.add(UserMessage.from("The response violated the required JSON contract. Return only one JSON object with "
                + "status COMPLETED and 1..10 claims (claimId, text, existing evidenceIds, confidence), and limitations. "
                + "For an explicitly permitted supplement request use SUPPLEMENT_EVIDENCE and toolRequests. "
                + "Do not invent evidence IDs or claim facts absent from the supplied evidence."));
        }
        throw new IllegalStateException("AGENT_OUTPUT_CONTRACT_INVALID");
    }
    private static boolean valid(String text, Set<String> ids) {
        if (text == null || text.isBlank() || text.length() > 100_000) return false;
        try {
            var node = JSON.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
            if (!node.isObject()) return false;
            if ("SUPPLEMENT_EVIDENCE".equals(node.path("status").asText()))
                return node.path("toolRequests").isArray() && !node.path("toolRequests").isEmpty();
            if (!"COMPLETED".equals(node.path("status").asText()) || !node.path("claims").isArray()
                || node.path("claims").isEmpty() || node.path("claims").size() > 10) return false;
            Set<String> claims = new HashSet<>();
            for (var claim : node.path("claims")) {
                if (claim.path("claimId").asText().isBlank() || !claims.add(claim.path("claimId").asText())
                    || claim.path("text").asText().isBlank() || !claim.path("evidenceIds").isArray()
                    || claim.path("evidenceIds").isEmpty() || !claim.path("confidence").isNumber()
                    || claim.path("confidence").asDouble() < 0 || claim.path("confidence").asDouble() > 1) return false;
                for (var id : claim.path("evidenceIds")) if (!id.isTextual() || !ids.contains(id.asText())) return false;
            }
            return true;
        } catch (java.io.IOException invalid) { return false; }
    }
    private static void check(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
            throw new java.util.concurrent.CancellationException("Evidence-bound Agent cancelled");
    }
}
