package com.chatchat.agents.runtime;

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
        log.info("agentModelRawOutput phase=review raw=\n{}", raw == null ? "" : raw);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(extractJson(raw), Map.class);
            boolean accepted = booleanValue(payload.get("accepted"));
            String feedback = stringValue(payload.get("feedback"));
            String revisedAnswer = stringValue(payload.get("revisedAnswer"));
            if (accepted) {
                return new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, feedback);
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
        prompt.append("If observations include web citation labels such as [网页1], web-derived claims in the answer must keep the matching labels; reject and revise answers that omit those labels.\n");
        prompt.append("Do not remove citation markers that prove which web page supports a statement.\n");
        prompt.append("If the user's request is in Chinese, the revised answer must be in Chinese.\n\n");
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
        prompt.append("{\"accepted\":true|false,\"feedback\":\"brief reason\",\"revisedAnswer\":\"if rejected, provide the improved final answer; otherwise empty string\"}");
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
}
