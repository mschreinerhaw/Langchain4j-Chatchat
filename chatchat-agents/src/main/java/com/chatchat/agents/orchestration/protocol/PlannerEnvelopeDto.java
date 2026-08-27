package com.chatchat.agents.orchestration.protocol;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/** Tolerant model-boundary DTO. It is never passed into Runtime execution. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlannerEnvelopeDto(
    JsonNode planning,
    @JsonProperty("candidate_answer")
    @JsonAlias("candidateAnswer")
    CandidateAnswerDto candidateAnswer,
    @JsonIgnore
    JsonNode rootPayload
) {

    public static PlannerEnvelopeDto from(JsonNode root, ObjectMapper objectMapper) throws IOException {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Planner response must be a JSON object");
        }
        PlannerEnvelopeDto decoded = objectMapper.treeToValue(root, PlannerEnvelopeDto.class);
        return new PlannerEnvelopeDto(decoded.planning(), decoded.candidateAnswer(), root);
    }

    /** Supports both the wrapped {planning: ...} protocol and the direct InterpretationPlan form. */
    public JsonNode planningPayload() {
        return planning != null && planning.isObject() ? planning : rootPayload;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CandidateAnswerDto(
        @JsonAlias("answer")
        String content,
        @JsonAlias("answerType")
        String type
    ) {
    }
}
