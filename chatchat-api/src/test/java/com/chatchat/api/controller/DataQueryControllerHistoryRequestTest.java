package com.chatchat.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataQueryControllerHistoryRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsSingletonStructuredEvidenceAsOneElementCollections() throws Exception {
        String json = """
            {
              "messages": [{
                "role": "assistant",
                "content": "done",
                "sources": {"id": "source-1"},
                "traces": {"toolName": "generic_executor"},
                "steps": {"stepId": 4},
                "evidencePremises": {"evidenceId": "evidence-1"}
              }]
            }
            """;

        DataQueryController.HistoryRequest request = objectMapper.readValue(
            json, DataQueryController.HistoryRequest.class);

        DataQueryController.ConversationMessage message = request.getMessages().get(0);
        assertThat(message.getSources()).extracting(value -> value.get("id"))
            .containsExactly("source-1");
        assertThat(message.getTraces()).extracting(value -> value.get("toolName"))
            .containsExactly("generic_executor");
        assertThat(message.getSteps()).extracting(value -> value.get("stepId"))
            .containsExactly(4);
        assertThat(message.getEvidencePremises()).extracting(value -> value.get("evidenceId"))
            .containsExactly("evidence-1");
    }
}
