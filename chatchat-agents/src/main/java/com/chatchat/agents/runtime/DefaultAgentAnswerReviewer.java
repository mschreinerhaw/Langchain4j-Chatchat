package com.chatchat.agents.runtime;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class DefaultAgentAnswerReviewer implements AgentAnswerReviewer {

    private final ObjectMapper objectMapper;

    public DefaultAgentAnswerReviewer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public AgentAnswerReview review(ChatModel chatModel,
                                    String query,
                                    String systemPrompt,
                                    List<String> observations,
                                    String answer) {
        if (answer == null || answer.isBlank()) {
            return new AgentAnswerReview(AgentAnswerReview.REVISED, "", "Empty answer generated");
        }
        if (chatModel == null) {
            return new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "Answer review unavailable");
        }

        String raw = chatModel.chat(buildPrompt(query, systemPrompt, observations, answer));
        log.info("agentModelRawOutput phase=review raw=\n{}", ModelProtocolJson.prettyJsonForLog(raw));
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(extractJson(raw), Map.class);
            boolean accepted = booleanValue(payload.get("accepted"));
            String feedback = stringValue(payload.get("feedback"));
            String revisedAnswer = stringValue(payload.get("revisedAnswer"));
            if (accepted) {
                return new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, feedback);
            }
            if (containsObservableEvidence(observations) && reviewerClaimsEvidenceInvisible(feedback, revisedAnswer)) {
                return new AgentAnswerReview(
                    AgentAnswerReview.ACCEPTED,
                    answer,
                    "Reviewer downgrade blocked: canonical evidence content is present in observations. Reviewer feedback: "
                        + firstNonBlank(feedback, "none")
                );
            }
            if (revisedAnswer != null && !revisedAnswer.isBlank()) {
                return new AgentAnswerReview(AgentAnswerReview.REVISED, revisedAnswer, feedback);
            }
            return new AgentAnswerReview(AgentAnswerReview.REJECTED, "",
                firstNonBlank(feedback, "Review rejected without revised answer"));
        } catch (Exception ex) {
            log.debug("Failed to parse answer review: {}", raw, ex);
            return new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "Answer review unavailable");
        }
    }

    String buildPrompt(String query, String systemPrompt, List<String> observations, String answer) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are the final answer quality reviewer for an enterprise AI assistant.\n");
        prompt.append("Decide whether the candidate answer satisfies the user's actual formal request.\n");
        prompt.append("Reject answers that only point to documents/tools, summarize where information may be, or avoid giving the concrete requested result.\n");
        prompt.append("A good answer must directly address the user request, use the available observations as evidence, and clearly state missing evidence when the observations are insufficient.\n");
        prompt.append("Do not invent facts that are absent from the observations.\n");
        prompt.append("If both document_search and web_search observations are available, the answer must distinguish internal document evidence from web verification evidence and explicitly handle conflicts.\n");
        prompt.append("If an observation says a tool failed, the answer must not claim that the failed tool provided supporting evidence.\n");
        prompt.append("If an observation says the Evidence trust policy requests more evidence, reject answers that present unsupported strong claims.\n");
        prompt.append("If observations include evidence_canonical_v1 Canonical evidence store, treat rawContent and normalizedContent as observable tool evidence; do not claim observations lack actual content unless every canonical evidence item is empty.\n");
        prompt.append("If observations include evidence_graph_v1 Evidence graph execution, treat graph nodes, valid paths, and sqlLineage as traceable evidence; do not downgrade SQL answers when TRUSTED_SQL paths are present.\n");
        prompt.append("If observations include evidence_os_execution_v2, enforce its decision and answerContract: ANSWER_ALLOWED may answer only from evidencePath, EMPTY_RESULT must not be replaced with generic knowledge, and SQL requires EXECUTION_VERIFIED.\n");
        prompt.append("If observations include evidence_execution_contract_v2_2 Deterministic answer lock, accept answers that reproduce the lockedAnswer and never claim the observations lack actual content.\n");
        prompt.append("If observations include evidence_v1 Unified evidence context, reject answers that rely on EvidenceChunk content but omit the matching doc:// or web:// citation.\n");
        prompt.append("If observations include evidence_v1, reject answers that cannot be represented as EvidenceAnswer with answer, citations, confidence, and missingInfo.\n");
        prompt.append("If observations include document_evidence_v1, document evidence context, or document citations, reject and revise answers that rely on document evidence but omit the matching document citation.\n");
        prompt.append("If observations include document_evidence_v1, reject answers that cannot be represented as EvidenceAnswer with answer, citations, confidence, and missingInfo.\n");
        prompt.append("If observations include answer_assembly_policy_v1, enforce its mode, citation placement, partial-answer, conflict-handling, and missingInfo requirements.\n");
        prompt.append("If observations include web citation labels such as [网页1], web-derived claims in the answer must keep the matching labels; reject and revise answers that omit those labels.\n");
        prompt.append("Do not remove citation markers that prove which web page supports a statement.\n");
        prompt.append("If the user's request is in Chinese, the revised answer must be in Chinese.\n");
        prompt.append("If you provide revisedAnswer, it must be a polished Markdown document, not a single plain paragraph. Do not wrap it in code fences.\n\n");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append("System instruction:\n").append(systemPrompt).append("\n\n");
        }
        prompt.append("User request:\n").append(query).append("\n\n");
        prompt.append("Available observations:\n");
        if (observations == null || observations.isEmpty()) {
            prompt.append("- (none)\n");
        } else {
            observations.forEach(item -> prompt.append("- ").append(item).append("\n"));
        }
        prompt.append("\nCandidate answer:\n").append(answer).append("\n\n");
        prompt.append("Respond with strict JSON only:\n");
        prompt.append("{\"accepted\":true|false,\"feedback\":\"brief reason\",\"revisedAnswer\":\"if rejected, provide the improved final Markdown answer; otherwise empty string\"}");
        return prompt.toString();
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        String text = raw.trim();
        int blockStart = text.indexOf("```");
        if (blockStart >= 0) {
            int firstBrace = text.indexOf('{', blockStart);
            int lastBrace = text.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                return text.substring(firstBrace, lastBrace + 1);
            }
        }
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1);
        }
        return text;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private boolean containsObservableEvidence(List<String> observations) {
        if (observations == null || observations.isEmpty()) {
            return false;
        }
        return observations.stream()
            .filter(value -> value != null && !value.isBlank())
            .anyMatch(value -> (value.contains("Canonical evidence store (contractVersion=evidence_canonical_v1)")
                && (value.contains("rawContent:") || value.contains("normalizedContent:")))
                || (value.contains("Evidence graph execution (contractVersion=evidence_graph_v1)")
                && (value.contains("Valid evidence paths:") || value.contains("TRUSTED_SQL")))
                || (value.contains("Evidence OS execution (contractVersion=evidence_os_execution_v2)")
                && value.contains("decision: ANSWER_ALLOWED"))
                || (value.contains("Deterministic answer lock (contractVersion=evidence_execution_contract_v2_2)")
                && value.contains("---BEGIN_LOCKED_ANSWER---"))
                || (value.contains("Unified evidence context (contractVersion=evidence_v1)")
                && value.contains("content:"))
                || value.contains("doc://")
                || value.contains("web://"));
    }

    private boolean reviewerClaimsEvidenceInvisible(String feedback, String revisedAnswer) {
        String text = ((feedback == null ? "" : feedback) + "\n" + (revisedAnswer == null ? "" : revisedAnswer)).toLowerCase();
        return text.contains("observations do not contain actual content")
            || text.contains("do not contain actual content")
            || text.contains("no actual content")
            || text.contains("not contain the actual content")
            || (text.contains("tool output")
            && (text.contains("unavailable") || text.contains("missing") || text.contains("not returned")))
            || text.contains("\u672a\u8fd4\u56de\u4efb\u4f55\u6587\u6863\u6587\u672c")
            || text.contains("\u6ca1\u6709\u5b9e\u9645\u5185\u5bb9")
            || text.contains("\u65e0\u6cd5\u83b7\u53d6")
            || text.contains("\u672a\u83b7\u53d6");
    }
}
