package com.chatchat.common.runtime.capability;

import com.chatchat.common.runtime.agent.AgentDescriptor;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityIdTest {
    @Test void parsesStableBusinessCapabilityIdentity() {
        CapabilityId value = CapabilityId.parse("finance.portfolio-attribution.v2");
        assertThat(value.namespace()).isEqualTo("finance");
        assertThat(value.name()).isEqualTo("portfolio-attribution");
        assertThat(value.version()).isEqualTo("v2");
        assertThat(value.value()).isEqualTo("finance.portfolio-attribution.v2");
    }

    @Test void rejectsUnsafeSegments() {
        assertThatThrownBy(() -> CapabilityId.parse("finance.portfolio attribution.v1"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void remoteDescriptorsRejectInsecureEndpointsAndEmbeddedSecrets() {
        assertThatThrownBy(() -> remote(URI.create("http://agents.example/a2a"), Map.of()))
            .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> remote(URI.create("https://agents.example/a2a"),
            Map.of("accessToken", "must-not-be-here")))
            .hasMessageContaining("credentialRef");
    }

    private AgentDescriptor remote(URI endpoint, Map<String, Object> metadata) {
        return new AgentDescriptor("group.test", "v1", AgentDescriptor.Origin.GROUP,
            AgentDescriptor.Protocol.A2A_HTTP_JSON, endpoint,
            Set.of(CapabilityId.parse("test.analysis.v1")), AgentDescriptor.TrustLevel.GROUP_TRUSTED,
            AgentDescriptor.DataAccessMode.RUNTIME_MANAGED, Set.of("test"), Set.of("ToolAnalysisEvidence"),
            null, "env:TEST_TOKEN", 1, true, metadata);
    }
}
