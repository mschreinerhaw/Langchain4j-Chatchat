package com.chatchat.api.runtime.coding.knowledge;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.conversation.model.Conversation;
import com.chatchat.common.response.ApiResponse;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.server.*;
import org.springframework.mock.web.*;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationContentDisclosureTest {
    final ObjectMapper json=new ObjectMapper();
    final KnowledgeDisclosureGuard guard=mock(KnowledgeDisclosureGuard.class);
    final KnowledgeResponseAdvice advice=new KnowledgeResponseAdvice(guard,json);
    final MockHttpServletResponse response=new MockHttpServletResponse();
    final ServletServerHttpResponse output=new ServletServerHttpResponse(response);
    Object write(String route,Object body) {
        var request=new MockHttpServletRequest("GET",route);
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID,"tenant");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,"owner");
        return advice.beforeBodyWrite(body,null,MediaType.APPLICATION_JSON,null,
            new ServletServerHttpRequest(request),output);
    }
    Conversation.Message message(String id,String content) {
        var m=new Conversation.Message();m.setId(id);m.setRole("assistant");m.setContent(content);return m;
    }
    @Test void unavailableAnswerCannotBlockOtherMessagesOrExposeAnyOfItsRichFields() {
        var valid=message("valid","Public answer");valid.setUiResponse(Map.of("chart","available"));
        var denied=message("denied","runtime-evidence:knowledge:revoked-source");denied.setTaskId("blocked-task");
        denied.setUiResponse(Map.of("futureSecret","private-answer"));denied.setSources(List.of(Map.of("secret","private-source")));
        denied.setMemoryContext(Map.of("secret","private-memory"));denied.setVisualizationSpec(Map.of("secret","private-chart"));
        var c=new Conversation();c.setId("conversation");c.setTitle("Saved question");c.setMessages(List.of(valid,denied));
        c.setAnalysisTree(Map.of("truncatedFacts","private-tree"));
        doAnswer(call->{if(call.getArgument(2,String.class).contains("revoked-source"))throw new SecurityException("Resource source changed; start a new task to acquire current evidence");return null;})
            .when(guard).check(eq("tenant"),eq("owner"),anyString());
        var result=(JsonNode)write("/api/v1/conversations/conversation",ApiResponse.success(c));
        assertThat(result.at("/data/messages/0")).isEqualTo(json.valueToTree(valid));
        var hidden=result.at("/data/messages/1");assertThat(hidden.path("contentRestricted").asBoolean()).isTrue();
        assertThat(hidden.path("contentRestrictionReason").asText()).isEqualTo("SOURCE_CHANGED");
        assertThat(hidden.path("content").asText()).contains("来源已更新");
        assertThat(result.toString()).doesNotContain("private-","blocked-task","revoked-source");
        assertThat(output.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(c.getMessages().get(1).getContent()).contains("revoked-source");assertThat(c.getAnalysisTree()).containsKey("truncatedFacts");
    }
    @Test void producerRunStillProtectsTruncatedMessagesWithoutCitationMarkers() {
        var m=message("truncated","private truncated fact");m.setUiResponse(Map.of("runId","revoked","request",Map.of(),"result",Map.of()));
        var c=new Conversation();c.setId("conversation");c.setMessages(List.of(m));
        when(guard.checkRun("tenant","owner","revoked")).thenThrow(new SecurityException("Source cannot be revalidated; retained content is withheld"));
        var result=(JsonNode)write("/api/v1/conversations/conversation",ApiResponse.success(c));
        assertThat(result.at("/data/messages/0/contentRestrictionReason").asText()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(result.toString()).doesNotContain("private truncated fact");
    }
    @Test void fakeConversationShapeAndDirectSourceEndpointsCannotOptIntoRedaction() {
        doThrow(new SecurityException("denied")).when(guard).check(eq("tenant"),eq("owner"),anyString());
        var forged=ApiResponse.success(Map.of("messages",List.of(Map.of("content","runtime-evidence:knowledge:private"))));
        assertThatThrownBy(()->write("/api/v1/conversations/conversation",forged)).isInstanceOf(ResponseStatusException.class);
        var c=new Conversation();c.setMessages(List.of(message("m","runtime-evidence:knowledge:private")));
        assertThatThrownBy(()->write("/api/v1/agent/knowledge/citations/private",ApiResponse.success(c))).isInstanceOf(ResponseStatusException.class);
    }
}
