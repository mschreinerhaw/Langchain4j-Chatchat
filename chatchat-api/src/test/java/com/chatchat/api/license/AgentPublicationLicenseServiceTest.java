package com.chatchat.api.license;

import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.chat.skills.SkillDefinition;
import com.chatchat.integration.mcp.service.McpLicenseEntitlementClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentPublicationLicenseServiceTest {

    private final SkillCatalogService catalog = mock(SkillCatalogService.class);
    private final McpLicenseEntitlementClient client = mock(McpLicenseEntitlementClient.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AgentPublicationLicenseService service =
        new AgentPublicationLicenseService(catalog, client, jdbcTemplate);

    @Test
    void blocksPublishingWhenPublishedCountReachedLicenseLimit() {
        SkillDefinition published = agent("agent-one", "published");
        SkillDefinition draft = agent("agent-two", "draft");
        when(catalog.list()).thenReturn(List.of(published, draft));
        when(client.agentPublicationLimit()).thenReturn(
            new McpLicenseEntitlementClient.AgentPublicationLimit(true, "VALID", "ok", 1, true));

        assertThatThrownBy(() -> service.publish("agent-two"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("AGENT_LICENSE_LIMIT_EXCEEDED")
            .hasMessageContaining("仍可新建和编辑 Agent");
        verify(catalog, never()).publishToMarket("agent-two");
    }

    @Test
    void publishesDraftWhenCapacityRemains() {
        SkillDefinition draft = agent("agent-two", "draft");
        SkillDefinition saved = agent("agent-two", "published");
        when(catalog.list()).thenReturn(List.of(draft));
        when(client.agentPublicationLimit()).thenReturn(
            new McpLicenseEntitlementClient.AgentPublicationLimit(true, "VALID", "ok", 2, true));
        when(catalog.publishToMarket("agent-two")).thenReturn(saved);

        assertThat(service.publish("agent-two")).isSameAs(saved);
    }

    @Test
    void failsClosedWhenMcpLicenseCheckIsUnavailable() {
        SkillDefinition draft = agent("agent-two", "draft");
        when(catalog.list()).thenReturn(List.of(draft));
        when(client.agentPublicationLimit()).thenThrow(new IllegalStateException("offline"));

        assertThatThrownBy(() -> service.publish("agent-two"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("AGENT_LICENSE_CHECK_UNAVAILABLE");
        verify(catalog, never()).publishToMarket("agent-two");
    }

    @Test
    void allowsIdempotentRepublishWithoutConsumingAnotherSlot() {
        SkillDefinition published = agent("agent-one", "published");
        when(catalog.list()).thenReturn(List.of(published));
        when(catalog.publishToMarket("agent-one")).thenReturn(published);

        assertThat(service.publish("agent-one")).isSameAs(published);
        verify(client, never()).agentPublicationLimit();
    }

    private SkillDefinition agent(String id, String status) {
        SkillDefinition value = mock(SkillDefinition.class);
        when(value.id()).thenReturn(id);
        when(value.marketStatus()).thenReturn(status);
        return value;
    }
}
