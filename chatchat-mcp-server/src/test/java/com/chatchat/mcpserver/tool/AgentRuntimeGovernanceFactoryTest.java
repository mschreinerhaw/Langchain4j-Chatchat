package com.chatchat.mcpserver.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimeGovernanceFactoryTest {

    private final AgentRuntimeGovernanceFactory factory =
        new AgentRuntimeGovernanceFactory(new ObjectMapper());

    @Test
    void normalizesImmutableNestedMapsWithoutMutatingTheInput() {
        Map<String, Object> confirmation = Map.of("default", "auto_execute");
        Map<String, Object> governance = Map.of(
            "risk_level", "medium",
            "confirmation", confirmation,
            "audit", Map.of("log_params", true));

        Map<String, Object> meta = factory.toMeta("test", "immutable", governance);

        assertThat(stringObjectMap(meta.get("confirmation")))
            .containsEntry("default", "auto_execute")
            .containsEntry("allow_user_override", true);
        assertThat(stringObjectMap(meta.get("audit")))
            .containsEntry("enabled", true)
            .containsEntry("log_params", true);
        assertThat(confirmation).doesNotContainKey("allow_user_override");
    }

    @Test
    void deepMergesJsonIntoImmutableNestedMaps() {
        Map<String, Object> governance = Map.of(
            "confirmation", Map.of("default", "ask_before_execute"),
            "output_policy", Map.of("mask_fields", java.util.List.of("account_no")));

        Map<String, Object> meta = factory.toMeta("test", "json", governance,
            "{\"confirmation\":{\"allow_user_override\":false},"
                + "\"output_policy\":{\"max_rows\":100}}");

        assertThat(stringObjectMap(meta.get("confirmation")))
            .containsEntry("default", "ask_before_execute")
            .containsEntry("allow_user_override", false);
        assertThat(stringObjectMap(meta.get("output_policy")))
            .containsEntry("max_rows", 100);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stringObjectMap(Object value) {
        return (Map<String, Object>) value;
    }
}
