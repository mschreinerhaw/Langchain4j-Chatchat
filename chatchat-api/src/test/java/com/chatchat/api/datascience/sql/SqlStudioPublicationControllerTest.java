package com.chatchat.api.datascience.sql;

import com.chatchat.api.controller.datascience.SqlStudioPublicationController;
import com.chatchat.api.controller.mcp.TemplateCapabilityController;
import com.chatchat.api.workspace.WorkspaceService;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SqlStudioPublicationControllerTest {
    final SqlStudioService studio=mock(SqlStudioService.class);
    final TemplateCapabilityController capabilities=mock(TemplateCapabilityController.class);
    final ObjectMapper json=new ObjectMapper();
    final MockHttpServletRequest request=new MockHttpServletRequest();
    final SqlStudioPublicationController controller=new SqlStudioPublicationController(studio,capabilities,mock(WorkspaceService.class),json);
    void saved(String kind){when(studio.file(request,"project","file")).thenReturn(new SqlStudioService.File("file","project","a.sql",kind,"select 7",2,true,null));}
    @Test void bodySqlAndIdentityCannotOverrideSavedSource() throws Exception {
        saved("SQL");controller.create("project","file",json.readTree("{\"expectedSourceVersion\":2,\"sqlTemplate\":\"delete from users\",\"tenantId\":\"other\",\"actorId\":\"other\",\"source\":{}}"),request);
        var body=org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(capabilities).sqlAuthoring(eq(request),eq("POST"),eq(""),body.capture(),eq("mcp:publication:edit"));
        assertThat(body.getValue().path("sqlTemplate").asText()).isEqualTo("select 7");
        assertThat(body.getValue().has("tenantId")).isFalse();assertThat(body.getValue().has("actorId")).isFalse();
        assertThat(body.getValue().path("source").path("fileVersion").asInt()).isEqualTo(2);
    }
    @Test void staleSavedVersionRejectsBeforeControlPlane() throws Exception {
        saved("SQL");assertThatThrownBy(()->controller.create("project","file",json.readTree("{\"expectedSourceVersion\":1}"),request)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);verifyNoInteractions(capabilities);
    }
    @Test void nonSqlAndUnauthorizedSourceRejectBeforeControlPlane() throws Exception {
        saved("MAPPING");assertThatThrownBy(()->controller.create("project","file",json.createObjectNode(),request)).isInstanceOf(IllegalArgumentException.class);
        when(studio.file(request,"project","file")).thenThrow(new SecurityException("Not owner"));
        assertThatThrownBy(()->controller.create("project","file",json.createObjectNode(),request)).isInstanceOf(SecurityException.class);verifyNoInteractions(capabilities);
    }
    @Test void rollbackRequiresPublishPermissionAndDoesNotReplaceHistoricalSql() {
        saved("SQL");controller.action("project","file","revision","rollback",json.createObjectNode(),request);
        var body=org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(capabilities).sqlAuthoring(eq(request),eq("POST"),eq("/revision/rollback"),body.capture(),eq("mcp:publication:publish"));
        assertThat(body.getValue().has("sqlTemplate")).isFalse();assertThat(body.getValue().path("source").has("fileVersion")).isFalse();
    }
}
