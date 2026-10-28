package com.chatchat.mcpserver.templatepublication.admin;
import com.chatchat.mcpserver.templatepublication.binding.TemplateQueryBindingService;
import com.chatchat.mcpserver.templatepublication.publisher.TemplateQueryMcpToolPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class ControlledTemplateReleaseExecutorTest {
    @Test void contractTestUsesTemplateDiscoveryPreviewRatherThanActivatingAnExecutor() throws Exception {
        var bindings = mock(TemplateQueryBindingService.class);
        var publisher = mock(TemplateQueryMcpToolPublisher.class);
        var json = new ObjectMapper();
        var executor = new ControlledTemplateReleaseExecutor(bindings, json, publisher);
        var definition = json.readTree("{\"domainCode\":\"example\",\"parentToolName\":\"data_query_query\",\"roleId\":\"role\",\"templateKeys\":[\"sql_datasource:EXAMPLE\"]}");
        var input = json.readTree("{\"question\":\"find a template\"}");
        when(publisher.preview(any(), anyMap())).thenReturn(java.util.Map.of("templates", java.util.List.of()));

        assertEquals(json.readTree("{\"templates\":[]}"), executor.test(definition, input));
        verify(publisher).preview(any(), eq(java.util.Map.of("question", "find a template")));
        verifyNoMoreInteractions(publisher);
        verifyNoInteractions(bindings);
    }
    @Test void deletionUsesTheGuardedInactiveBindingOperation() {
        var bindings=mock(TemplateQueryBindingService.class);
        new ControlledTemplateReleaseExecutor(bindings,new ObjectMapper(),mock(TemplateQueryMcpToolPublisher.class)).deleteInactive("binding-id");
        verify(bindings).deleteInactive("binding-id");verifyNoMoreInteractions(bindings);
    }
    @Test void businessCategoryRemainsDisplayMetadataWithoutChangingExecutionIdentity() throws Exception {
        var binding=mock(TemplateQueryBindingService.class);var json=new ObjectMapper();
        var executor=new ControlledTemplateReleaseExecutor(binding,json,mock(TemplateQueryMcpToolPublisher.class));
        var first=json.readTree("{\"domainCode\":\"example\",\"parentToolName\":\"api_template_query\",\"roleId\":\"role\",\"subjectType\":\"ROLE\",\"templateKeys\":[\"api:a\"],\"enabled\":false,\"businessCategory\":\"客户服务\"}");
        executor.validate(first);var argument=ArgumentCaptor.forClass(TemplateQueryBindingService.UpsertRequest.class);verify(binding).validateDraft(argument.capture());assertEquals("role",argument.getValue().roleId());assertEquals(java.util.List.of("api:a"),argument.getValue().templateKeys());
        var second=first.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)second).put("businessCategory","数据核验");assertEquals(executor.stableIdentity(first),executor.stableIdentity(second));
    }
}
