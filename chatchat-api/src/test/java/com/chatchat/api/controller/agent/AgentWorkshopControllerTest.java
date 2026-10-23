package com.chatchat.api.controller.agent;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.api.license.AgentPublicationLicenseService;
import com.chatchat.chat.skills.domain.application.DomainSkillService;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.chat.skills.release.AgentReleaseService;
import com.chatchat.common.config.ModelResourceRegistry;
import com.chatchat.common.mcp.catalog.McpToolCatalogQueryPort;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.enterprise.service.SkillResourceScopeSynchronizationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWorkshopControllerTest {
    @Test void rejectsNewUnpublishedToolsIncludingWorkflowAndServiceShortcuts() {
        var published=mock(com.chatchat.api.service.PublishedMcpToolCatalog.class);
        var skills=mock(SkillCatalogService.class);
        var controller=new AgentWorkshopController(skills,mock(ToolRegistry.class),mock(McpToolCatalogQueryPort.class),mock(ModelResourceRegistry.class),mock(DocumentLibraryReadPort.class),mock(EnterpriseAdminService.class),mock(AgentPublicationLicenseService.class),mock(AgentReleaseService.class),mock(DomainSkillService.class),mock(ResourceAuthorizationPort.class),mock(SkillResourceScopeSynchronizationService.class),published);
        var servlet=new org.springframework.mock.web.MockHttpServletRequest();
        servlet.setAttribute(com.chatchat.api.security.ApiAuthenticationFilter.CURRENT_USER_ID,"user");
        when(published.registeredTools(servlet)).thenReturn(List.of());
        var input=new AgentWorkshopController.AgentUpsertRequest();
        input.setBoundMcpToolNames(List.of("internal_execute"));
        org.junit.jupiter.api.Assertions.assertThrows(SecurityException.class,()->controller.createAgent(input,servlet));
        input.setBoundMcpToolNames(List.of());input.setWorkflowConfig(java.util.Map.of("steps",List.of(java.util.Map.of("tool","internal_execute"))));
        org.junit.jupiter.api.Assertions.assertThrows(SecurityException.class,()->controller.createAgent(input,servlet));
        input.setWorkflowConfig(java.util.Map.of());input.setBoundMcpServiceIds(List.of("all-tools-service"));
        org.junit.jupiter.api.Assertions.assertThrows(SecurityException.class,()->controller.createAgent(input,servlet));
        org.mockito.Mockito.verify(skills,org.mockito.Mockito.never()).upsert(any(SkillDefinition.class));
    }

    @Test
    void removesMissingDocumentBindingsWhenAgentIsSaved() {
        SkillCatalogService skillCatalogService = mock(SkillCatalogService.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        McpToolCatalogQueryPort mcpCatalog = mock(McpToolCatalogQueryPort.class);
        ModelResourceRegistry modelResources = mock(ModelResourceRegistry.class);
        DocumentLibraryReadPort documentLibrary = mock(DocumentLibraryReadPort.class);
        SkillResourceScopeSynchronizationService scopeSynchronization =
            mock(SkillResourceScopeSynchronizationService.class);

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
            mock(ResourceAuthorizationPort.class),
            scopeSynchronization,
            mock(com.chatchat.api.service.PublishedMcpToolCatalog.class)
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
        verify(scopeSynchronization).synchronize(
            "default", "migrated-agent", List.of("existing-doc"), List.of());
    }
}
