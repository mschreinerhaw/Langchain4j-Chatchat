package com.chatchat.api.runtime.coding.knowledge;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeResponseAdviceProvenanceTest {
    @org.junit.jupiter.api.Test void onlyTheTypedSourceFreeProjectionCanAvoidSourceRevalidation() {
        var guard=mock(KnowledgeDisclosureGuard.class);
        when(guard.checkRun("tenant","owner","run-1")).thenThrow(new SecurityException("Source cannot be revalidated; retained content is withheld"));
        var advice=new KnowledgeResponseAdvice(guard,new ObjectMapper());
        var response=new ServletServerHttpResponse(new MockHttpServletResponse());
        var body=com.chatchat.common.response.ApiResponse.success(new com.chatchat.api.controller.agent.CodingOperationalStatus("run-1",false,java.util.List.of()));
        assertThat(advice.beforeBodyWrite(body,null,MediaType.APPLICATION_JSON,null,new ServletServerHttpRequest(request("run-1/coding/status")),response)).isSameAs(body);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");verifyNoInteractions(guard);
        var forged=com.chatchat.common.response.ApiResponse.success(Map.of("runId","run-1","tasks",java.util.List.of(),"source","CODING_OPERATIONS"));
        assertThatThrownBy(()->advice.beforeBodyWrite(forged,null,MediaType.APPLICATION_JSON,null,new ServletServerHttpRequest(request("run-1/coding/status")),response)).isInstanceOf(ResponseStatusException.class);
    }
    @ParameterizedTest @ValueSource(strings={"run-1/coding","run%2D1/coding/task/events","run-1"})
    void truncatedRunSummariesCannotBypassRevocationByOmittingCitationText(String route) {
        var guard=mock(KnowledgeDisclosureGuard.class);
        when(guard.checkRun("tenant","owner","run-1")).thenThrow(new SecurityException("Data resource authorization was revoked or not requested"));
        var advice=new KnowledgeResponseAdvice(guard,new ObjectMapper());
        var request=request(route);var body=Map.of("tasks",Map.of("summary","Previously observed private facts without a citation marker"));
        assertThatThrownBy(()->advice.beforeBodyWrite(body,null,MediaType.APPLICATION_JSON,null,new ServletServerHttpRequest(request),
            new ServletServerHttpResponse(new MockHttpServletResponse()))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        verify(guard).checkRun("tenant","owner","run-1");
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void runMetadataRetainsOrdinaryFlowAndResourceResponsesDisableBrowserCaching(boolean resourceRun) {
        var guard=mock(KnowledgeDisclosureGuard.class);when(guard.checkRun("tenant","owner","run-1")).thenReturn(resourceRun);
        var advice=new KnowledgeResponseAdvice(guard,new ObjectMapper());var response=new ServletServerHttpResponse(new MockHttpServletResponse());
        var body=Map.of("state","COMPLETED");
        assertThat(advice.beforeBodyWrite(body,null,MediaType.APPLICATION_JSON,null,new ServletServerHttpRequest(request("run-1/coding")),response)).isSameAs(body);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo(resourceRun?"no-store":null);
    }
    private MockHttpServletRequest request(String route) {
        var request=new MockHttpServletRequest("GET","/api/v1/agent/runtime/runs/"+route);
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID,"tenant");request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,"owner");return request;
    }
}
