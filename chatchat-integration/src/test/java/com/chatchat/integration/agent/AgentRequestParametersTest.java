package com.chatchat.integration.agent;

import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.capability.CapabilityId;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRequestParametersTest {
    @Test void encodesOperatorApprovedQueryParameters() {
        var descriptor = descriptor(Map.of("requestQueryParameters", Map.of("region", "north east", "year", 2026)));
        assertThat(AgentRequestParameters.withQuery(descriptor.endpoint(), AgentRequestParameters.query(descriptor))
            .toString()).contains("region=north+east", "year=2026");
    }

    @Test void refusesCredentialsAndNestedExecutableParameters() {
        assertThatThrownBy(() -> AgentRequestParameters.query(descriptor(Map.of(
            "requestQueryParameters", Map.of("authToken", "secret")))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentRequestParameters.body(descriptor(Map.of(
            "requestBodyParameters", Map.of("sql", Map.of("query", "select *"))))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private AgentDescriptor descriptor(Map<String, Object> metadata) {
        return new AgentDescriptor("group.test", "v1", AgentDescriptor.Origin.GROUP,
            AgentDescriptor.Protocol.A2A_HTTP_JSON, URI.create("https://agent.example.com/a2a"),
            Set.of(CapabilityId.parse("finance.test.v1")), AgentDescriptor.TrustLevel.GROUP_TRUSTED,
            AgentDescriptor.DataAccessMode.RUNTIME_MANAGED, Set.of(), Set.of(), null, "", 50, true, metadata);
    }
}
