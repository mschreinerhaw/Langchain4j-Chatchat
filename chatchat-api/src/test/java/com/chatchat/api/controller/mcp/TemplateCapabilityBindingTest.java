package com.chatchat.api.controller.mcp;

import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.api.service.McpRuntimeAccessService;
import com.chatchat.api.workspace.WorkspaceService;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.retrieval.ResourceGrantProvisioningPort;
import com.chatchat.enterprise.entity.audit.SysAuditLog;
import com.chatchat.enterprise.repository.audit.SysAuditLogRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TemplateCapabilityBindingTest {
    @Test void validLongAgentAndToolNamesDoNotOverflowAuditWhileRetainingFullReference() throws Exception {
        var mapper=new ObjectMapper();var access=mock(ObjectAccessService.class);
        var identities=mock(EnterpriseAdminService.class);var agents=mock(SkillCatalogService.class);
        var client=mock(McpControlPlaneClient.class);var audits=mock(SysAuditLogRepository.class);
        var request=new MockHttpServletRequest();String id="tpcds_sql_studio_acceptance_20261031";
        String tool="tpl_212b88329a673657979943bbeaa7479b";
        when(access.user(request)).thenReturn("owner");when(access.tenant(request)).thenReturn("tenant");
        when(identities.hasPermission("owner","platform:agents:workshop")).thenReturn(true);
        when(identities.hasAllAgentAccess("owner")).thenReturn(true);
        when(agents.resolve(id)).thenReturn(mapper.convertValue(Map.of("id",id,"boundMcpToolNames",List.of(),"toolConfigs",List.of()),SkillDefinition.class));
        when(client.request(eq("GET"),contains("/publications"),isNull())).thenReturn(mapper.readTree("[{\"toolName\":\""+tool+"\",\"state\":\"RUNNING\",\"revision\":\"current\"}]"));
        when(client.request(eq("GET"),contains("/templates"),isNull())).thenReturn(mapper.readTree("[{\"revision\":\"current\"}]"));
        var controller=new TemplateCapabilityController(client,access,mock(WorkspaceService.class),identities,agents,
            mapper,mock(ResourceGrantProvisioningPort.class),mock(McpRuntimeAccessService.class),audits);
        controller.bind(request,id,tool);
        var captured=ArgumentCaptor.forClass(SysAuditLog.class);verify(audits).saveAndFlush(captured.capture());
        assertThat(captured.getValue().getResourceId()).hasSize(64).matches("[a-f0-9]{64}");
        assertThat(captured.getValue().getDetail()).isEqualTo(id+":"+tool);
        verify(agents).upsert(argThat(agent->agent.boundMcpToolNames().stream().anyMatch(name->name.endsWith(tool))));
    }
}
