package com.chatchat.common.runtime.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;

final class CodingContractTestSupport {
    static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static ObjectNode fixture(String name) throws IOException {
        try (var stream = CodingContractTestSupport.class.getResourceAsStream("/runtime/coding/" + name + ".json")) {
            if (stream == null) throw new IOException("Missing coding fixture: " + name);
            return (ObjectNode) JSON.readTree(stream);
        }
    }
    static void set(ObjectNode object, String pointer, Object value) {
        int slash = pointer.lastIndexOf('/');
        ObjectNode parent = (ObjectNode) object.at(pointer.substring(0, slash));
        parent.set(pointer.substring(slash + 1), JSON.valueToTree(value));
    }
    static CodingTaskOutcome outcome(CodingTaskOutcome.Status status, long revision) throws IOException {
        ObjectNode node = fixture("outcome");
        set(node, "/revision", revision); set(node, "/lastEventSequence", revision);
        set(node, "/status", status.name());
        if (!status.terminal()) {
            set(node, "/endedAt", null); set(node, "/executionControl/executorStopped", false);
        }
        if (status == CodingTaskOutcome.Status.CREATED) set(node, "/startedAt", null);
        if (status.terminal() && status != CodingTaskOutcome.Status.COMPLETED) set(node, "/reasonCode", "EXECUTION_FAILED");
        if (status == CodingTaskOutcome.Status.CANCELLED || status == CodingTaskOutcome.Status.BUDGET_EXCEEDED) {
            String reason = status == CodingTaskOutcome.Status.CANCELLED ? "CANCEL_REQUESTED" : "BUDGET_EXCEEDED";
            set(node, "/reasonCode", reason); set(node, "/executionControl/stopReason", reason);
            set(node, "/executionControl/stopRequestedAt", "2026-10-15T10:00:00Z");
            set(node, "/cancellationRequested", status == CodingTaskOutcome.Status.CANCELLED);
        }
        return JSON.treeToValue(node, CodingTaskOutcome.class);
    }
    static ObjectNode tree(Object value) { return JSON.valueToTree(value); }
    static CodingTaskOutcome readOutcome(JsonNode value) throws IOException { return JSON.treeToValue(value, CodingTaskOutcome.class); }
}
