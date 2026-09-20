package com.chatchat.api.controller.agent;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.api.license.AgentPublicationLicenseService;
import com.chatchat.chat.skills.domain.DomainSkillService;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.chat.skills.release.AgentReleaseService;
import com.chatchat.common.config.ModelResourceRegistry;
import com.chatchat.common.mcp.catalog.McpToolCatalogQueryPort;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.service.SearchService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
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
        SearchService searchService = mock(SearchService.class);

        when(modelResources.canonicalName("test-model")).thenReturn("test-model");
        SearchDocument existingDocument = mock(SearchDocument.class);
        SearchDocument deletedDocument = mock(SearchDocument.class);
        when(deletedDocument.getLifecycleStatus()).thenReturn("DELETED");
        when(searchService.get("existing-doc")).thenReturn(Optional.of(existingDocument));
        when(searchService.get("missing-doc")).thenReturn(Optional.empty());
        when(searchService.get("deleted-doc")).thenReturn(Optional.of(deletedDocument));
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
            searchService,
            mock(EnterpriseAdminService.class),
            mock(AgentPublicationLicenseService.class),
            mock(AgentReleaseService.class),
            mock(DomainSkillService.class)
        );
        AgentWorkshopController.AgentUpsertRequest request = new AgentWorkshopController.AgentUpsertRequest();
        request.setId("migrated-agent");
        request.setName("Migrated Agent");
        request.setModelName("test-model");
        request.setBoundDocumentIds(List.of("existing-doc", "missing-doc", "deleted-doc", "existing-doc"));

        controller.updateAgent("migrated-agent", request);

        ArgumentCaptor<SkillDefinition> savedAgent = ArgumentCaptor.forClass(SkillDefinition.class);
        org.mockito.Mockito.verify(skillCatalogService).upsert(savedAgent.capture());
        assertThat(savedAgent.getValue().boundDocumentIds()).containsExactly("existing-doc");
    }
}
