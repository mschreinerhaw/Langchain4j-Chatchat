package com.chatchat.api.controller.agent;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.api.license.AgentPublicationLicenseService;
import com.chatchat.chat.skills.domain.DomainSkillService;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.chat.skills.release.AgentReleaseService;
import com.chatchat.common.config.ModelResourceRegistry;
import com.chatchat.common.mcp.catalog.McpToolCatalogQueryPort;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentWorkshopControllerTest {

    @Test
    void removesMissingDocumentBindingsWhenAgentIsSaved() {
        SkillCatalogService skillCatalogService = mock(SkillCatalogService.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        McpToolCatalogQueryPort mcpCatalog = mock(McpToolCatalogQueryPort.class);
        ModelResourceRegistry modelResources = mock(ModelResourceRegistry.class);
        DocumentLibraryReadPort documentLibrary = mock(DocumentLibraryReadPort.class);

        when(modelResources.canonicalName("test-model")).thenReturn("test-model");
        when(documentLibrary.exists("existing-doc")).thenReturn(true);
        when(skillCatalogService.upsert(any(SkillDefinition.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(skillCatalogService.resolveTools(any(SkillDefinition.class), anyCollection(), anyMap()))
            .thenReturn(List.of());
        when(skillCatalogService.editableFields("migrated-agent")).thenReturn(List.of());
        when(toolRegistry.getAllToolNames()).thenReturn(Set.of());
        when(mcpCatalog.registeredTools()).thenReturn(List.of());

        AgentWorkshopController controller = new AgentWorkshopController(
            skillCatalogService,
            toolRegistry,
            mcpCatalog,
            modelResources,
            documentLibrary,
            mock(EnterpriseAdminService.class),
            mock(AgentPublicationLicenseService.class),
            mock(AgentReleaseService.class),
            mock(DomainSkillService.class),
            mock(ResourceAuthorizationPort.class)
        );
        AgentWorkshopController.AgentUpsertRequest request = new AgentWorkshopController.AgentUpsertRequest();
        request.setId("migrated-agent");
        request.setName("Migrated Agent");
        request.setModelName("test-model");
        request.setBoundDocumentIds(List.of("existing-doc", "missing-doc", "deleted-doc", "existing-doc"));

        controller.updateAgent("migrated-agent", request, null);

        ArgumentCaptor<SkillDefinition> savedAgent = ArgumentCaptor.forClass(SkillDefinition.class);
        org.mockito.Mockito.verify(skillCatalogService).upsert(savedAgent.capture());
        assertThat(savedAgent.getValue().boundDocumentIds()).containsExactly("existing-doc");
    }
}
