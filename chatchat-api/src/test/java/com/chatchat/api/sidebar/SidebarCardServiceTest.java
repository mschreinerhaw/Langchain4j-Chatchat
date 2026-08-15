package com.chatchat.api.sidebar;

import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.chat.skills.SkillDefinition;
import com.chatchat.integration.mcp.service.McpServiceConfigService;
import com.chatchat.integration.mcp.service.McpToolRegistryBridge;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SidebarCardServiceTest {

    @Test
    void sidebarUsesOnlyRegisteredServicesAndSkillMetadata() {
        SkillCatalogService catalog = mock(SkillCatalogService.class);
        McpServiceConfigService services = mock(McpServiceConfigService.class);
        McpToolRegistryBridge tools = mock(McpToolRegistryBridge.class);
        SkillDefinition skill = skill(Map.of("sidebar", Map.of(
            "quickActions", List.of(Map.of(
                "actionId", "inspect_result",
                "label", "Inspect result",
                "iconType", "inspect",
                "clientAction", "send_query",
                "query", "Inspect the current result",
                "payload", Map.of("view", "details")
            )),
            "recommendations", List.of(Map.of(
                "id", "configured-recommendation",
                "label", "Configured recommendation",
                "description", "Declared by the skill",
                "queryTemplate", "Run the configured recommendation"
            ))
        )));
        when(catalog.resolve("generic")).thenReturn(skill);
        when(services.listEnabled()).thenReturn(List.of());
        when(tools.listRegisteredTools()).thenReturn(List.of());
        SidebarCardService service = new SidebarCardService(catalog, services, tools);

        SidebarCardService.SidebarPayload payload = service.buildSidebar("generic", "conversation-1");

        assertThat(payload.serviceUsage().items()).isEmpty();
        assertThat(payload.dataSources().items()).isEmpty();
        assertThat(payload.quickActions()).containsExactly(
            new SidebarCardService.QuickActionItem("inspect_result", "Inspect result", "inspect"));
        assertThat(payload.recommendedServices()).extracting(SidebarCardService.RecommendationItem::id)
            .containsExactly("configured-recommendation");
    }

    @Test
    void actionExecutionNeverInventsAnUnconfiguredBackendSuccess() {
        SkillCatalogService catalog = mock(SkillCatalogService.class);
        when(catalog.resolve("generic")).thenReturn(skill(Map.of()));
        SidebarCardService service = new SidebarCardService(
            catalog, mock(McpServiceConfigService.class), mock(McpToolRegistryBridge.class));

        SidebarCardService.SidebarActionResult result = service.executeAction(
            new SidebarCardService.SidebarActionRequest(
                "notify_owner", "generic", "conversation-1", "question", "answer"));

        assertThat(result.payload()).containsEntry("status", "UNSUPPORTED");
        assertThat(result.message()).contains("未在当前技能中配置");
    }

    private SkillDefinition skill(Map<String, Object> workflowConfig) {
        return new SkillDefinition(
            "generic", "Generic", "Generic skill", List.of(), List.of(), "agent_chat", null,
            "", "", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
            workflowConfig, null, null, List.of("Configured quick question"), "published", false
        );
    }
}
