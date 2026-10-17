package com.chatchat.api.controller.agent;

import com.chatchat.agents.runtime.AgentRuntime;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.run.AgentRun;
import com.chatchat.agents.runtime.observation.AgentObservation;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CodingTraceArtifactControllerTest {
    private final AgentRuntime runtime=mock(AgentRuntime.class);
    private final McpPythonControlPlanePort python=mock(McpPythonControlPlanePort.class);
    private final CodingTraceArtifactController controller=new CodingTraceArtifactController(runtime,python);
    private final String operation="cline-"+"a".repeat(64);
    @Test void httpRouteBindsArtifactIdentityWithoutCompilerParameterMetadata() throws Exception {
        prepare("tenant","owner");
        when(python.artifact("tenant","owner","run","task",operation,"code","main.py"))
            .thenReturn("print(1)".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        String path="/api/v1/agent/runtime/runs/run/coding/task/"+operation+"/code";
        mvc.perform(get(path).accept(org.springframework.http.MediaType.APPLICATION_JSON).requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID,"tenant")
                .requestAttr(ApiAuthenticationFilter.CURRENT_USER_ID,"owner"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").value("print(1)"));
        mvc.perform(get(path).accept(org.springframework.http.MediaType.APPLICATION_JSON).requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID,"other")
                .requestAttr(ApiAuthenticationFilter.CURRENT_USER_ID,"owner"))
            .andExpect(status().isForbidden());
        verify(python,times(1)).artifact(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
    }
    private MockHttpServletRequest prepare(String tenant,String user) {
        var request=new MockHttpServletRequest();
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID,tenant);
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,user);
        when(runtime.find("run")).thenReturn(Optional.of(AgentRun.builder().runId("run")
            .request(AgentRunRequest.builder().tenantId("tenant").userId("owner").build()).build()));
        when(runtime.observations("run")).thenReturn(List.of(AgentObservation.builder().metadata(Map.of(
            "eventKind","CODING_TRACE","taskId","task","stepId",operation)).build()));
        return request;
    }
    @Test void otherTenantOrUserCannotReadEvenKnownArtifact() {
        for(var identity:List.of(List.of("other","owner"),List.of("tenant","other"))) {
            var request=prepare(identity.get(0),identity.get(1));
            assertThatThrownBy(()->controller.artifact("run","task",operation,"code",request))
                .isInstanceOf(ResponseStatusException.class);
        }
        verifyNoInteractions(python);
    }
    @Test void onlyReferencedOperationsAndFixedArtifactKindsAreReadable() {
        var request=prepare("tenant","owner");
        assertThatThrownBy(()->controller.artifact("run","other-task",operation,"code",request))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.artifact("run","task",operation,"../../input.jsonl",request))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(python);
    }
    @Test void logsAreBoundedAndReadUsingAuthenticatedOwnerIdentity() {
        var request=prepare("tenant","owner");
        when(python.artifact("tenant","owner","run","task",operation,"logs","stderr.txt"))
            .thenReturn("x".repeat(9000).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var data=(Map<?,?>)controller.artifact("run","task",operation,"stderr",request).getData();
        assertThat(data.get("content").toString()).hasSize(8192);
        assertThat(data.get("truncated")).isEqualTo(true);
    }
    @Test void credentialPresentationIsRedactedWithoutChangingArchivedBytes() {
        var request=prepare("tenant","owner");
        String original="password='private password'\n{\"api_key\":\"private-key\"}\nAuthorization: Bearer abc.def.ghi\nhttps://user:private@host/path\ncount=42";
        byte[] archive=original.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(python.artifact("tenant","owner","run","task",operation,"logs","stdout.txt")).thenReturn(archive);
        var data=(Map<?,?>)controller.artifact("run","task",operation,"stdout",request).getData();
        assertThat(data.get("redacted")).isEqualTo(true);
        assertThat(data.get("content").toString()).contains("[REDACTED]","count=42")
            .doesNotContain("private password","private-key","abc.def.ghi","user:private");
        assertThat(new String(archive,java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(original);
    }
    @Test void truncationCannotExposeThePrefixOfAQuotedSecret() {
        var request=prepare("tenant","owner");
        when(python.artifact("tenant","owner","run","task",operation,"logs","stdout.txt"))
            .thenReturn(("x".repeat(8150)+" password='"+"PRIVATE_SECRET".repeat(40)+"'").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var data=(Map<?,?>)controller.artifact("run","task",operation,"stdout",request).getData();
        assertThat(data.get("content").toString()).doesNotContain("PRIVATE_SECRET");
        assertThat(data.get("redacted")).isEqualTo(true);
    }
    @Test void escapedQuotesAndMultilineSecretsAreFullyRedacted() throws Exception {
        var request=prepare("tenant","owner");
        String log=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("api_key","prefix\"PRIVATE_SUFFIX"))
            +"\npassword=\"\"\"MULTILINE_PRIVATE\nPRIVATE_SUFFIX\"\"\"";
        when(python.artifact("tenant","owner","run","task",operation,"logs","stdout.txt"))
            .thenReturn(log.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var data=(Map<?,?>)controller.artifact("run","task",operation,"stdout",request).getData();
        assertThat(data.get("content").toString()).doesNotContain("PRIVATE_SUFFIX","MULTILINE_PRIVATE");
        assertThat(data.get("redacted")).isEqualTo(true);
    }
    @Test void largeOrUnterminatedQuotedSecretsDoNotOverflowTheStack() {
        String value="api_key=\""+"private\\\"fragment".repeat(20000);
        assertThat(CodingArtifactRedactor.redact(value)).isEqualTo("api_key=\"[REDACTED]\"");
    }
}
