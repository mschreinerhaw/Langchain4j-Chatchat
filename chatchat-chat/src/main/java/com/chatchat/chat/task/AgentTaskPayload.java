package com.chatchat.chat.task;

import com.chatchat.chat.interaction.model.InteractionRequest;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentTaskPayload {

    private AgentTaskSubmitRequest request;

    /**
     * Converts the value to interaction request.
     *
     * @return the converted interaction request
     */
    InteractionRequest toInteractionRequest() {
        String skillId = firstText(request.getSkillId(), request.getAgentId());
        return InteractionRequest.builder()
            .conversationId(request.getSessionId())
            .tenantId(request.getTenantId())
            .userId(firstText(request.getUserId(), "anonymous"))
            .mode(firstText(request.getMode(), "agent_chat"))
            .query(request.getQuery())
            .systemPrompt(request.getSystemPrompt())
            .modelName(request.getModelName())
            .skillId(skillId)
            .availableTools(request.getAvailableTools() == null ? new ArrayList<>() : request.getAvailableTools())
            .toolInput(request.getToolInput())
            .maxResults(request.getMaxResults())
            .historyWindow(request.getHistoryWindow())
            .stream(request.getStream())
            .build();
    }

    /**
     * Converts the value to submit request.
     *
     * @return the converted submit request
     */
    AgentTaskSubmitRequest toSubmitRequest() {
        return request == null ? new AgentTaskSubmitRequest() : request;
    }

    /**
     * Performs the first text operation.
     *
     * @param value the value value
     * @param fallback the fallback value
     * @return the operation result
     */
    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
