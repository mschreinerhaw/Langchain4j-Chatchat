package com.chatchat.mcpserver.templatepublication.admin;
import com.chatchat.mcpserver.templatepublication.binding.TemplateQueryBindingService;
import com.chatchat.mcpserver.templatepublication.publisher.TemplateQueryMcpToolPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class ControlledTemplateReleaseExecutorTest {
    @Test void businessCategoryRemainsDisplayMetadataWithoutChangingExecutionIdentity() throws Exception {
        var binding=mock(TemplateQueryBindingService.class);var json=new ObjectMapper();
        var executor=new ControlledTemplateReleaseExecutor(binding,json,mock(TemplateQueryMcpToolPublisher.class));
        var first=json.readTree("{\"domainCode\":\"example\",\"parentToolName\":\"api_template_query\",\"roleId\":\"role\",\"subjectType\":\"ROLE\",\"templateKeys\":[\"api:a\"],\"enabled\":false,\"businessCategory\":\"客户服务\"}");
        executor.validate(first);var argument=ArgumentCaptor.forClass(TemplateQueryBindingService.UpsertRequest.class);verify(binding).validateDraft(argument.capture());assertEquals("role",argument.getValue().roleId());assertEquals(java.util.List.of("api:a"),argument.getValue().templateKeys());
        var second=first.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)second).put("businessCategory","数据核验");assertEquals(executor.stableIdentity(first),executor.stableIdentity(second));
    }
}
