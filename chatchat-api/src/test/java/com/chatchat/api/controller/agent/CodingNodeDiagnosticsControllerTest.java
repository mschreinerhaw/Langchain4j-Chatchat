package com.chatchat.api.controller.agent;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class CodingNodeDiagnosticsControllerTest {
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper();
    CodingNodeDiagnosticsController controller;
    @BeforeEach void setup()throws Exception{
        controller=new CodingNodeDiagnosticsController(root.toString(),json);
        Path dir=root.resolve("state/workers/12345678-1234-4123-8123-123456789012");Files.createDirectories(dir);
        Files.writeString(dir.resolve("registry.json"),json.writeValueAsString(Map.of("workerId",dir.getFileName().toString(),"workerState","READY","taskState","ACTIVE",
            "apiToken","private-control-token","contract",Map.of("tenantId","tenant-a","userId","user-a","taskId","task-a","sessionId","session-a","deliveryBinding",Map.of("runId","run-a")))));
        Files.createDirectories(root.resolve("workers/"+dir.getFileName()));Files.createDirectories(root.resolve("private"));
        Files.writeString(root.resolve("private/gateway.json"),"{\"signingKey\":\"issuer-secret\",\"executor\":{},\"modelProfiles\":{}}");
    }
    MockHttpServletRequest actor(String tenant,String user){var request=new MockHttpServletRequest();request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID,tenant);request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,user);return request;}
    @Test void anotherTenantOrUserCannotReadTheCandidateEvenWithKnownTaskIds(){
        for(var actor:List.of(actor("tenant-b","user-a"),actor("tenant-a","user-b"),new MockHttpServletRequest()))
            assertThatThrownBy(()->controller.detail(actor,"run-a","task-a")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void diagnosticsDoNotExposeControlSecretsOrClaimBusinessAcceptance()throws Exception{
        String result=json.writeValueAsString(controller.detail(actor("tenant-a","user-a"),"run-a","task-a"));
        assertThat(result).contains("NOT_RUN","diagnosticOnly","task-a").doesNotContain("private-control-token","issuer-secret","COMPLETED");
    }
    @Test void nativeTracebackIsRetainedAndSecretsAreRedacted()throws Exception{
        Path registry=root.resolve("state/workers/12345678-1234-4123-8123-123456789012/registry.json");var record=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Files.readString(registry));record.put("nativeSessionId","native-a");Files.writeString(registry,json.writeValueAsString(record));
        Path dir=root.resolve("workers/12345678-1234-4123-8123-123456789012/state/native-a");Files.createDirectories(dir);
        Files.writeString(dir.resolve("events.jsonl"),json.writeValueAsString(Map.of("eventId","1","timestamp","2026-11-11T00:00:00Z","eventType","tool.failed","payload",Map.of("toolName","run_commands","error","TypeError: private-control-token")))+"\n");
        String result=json.writeValueAsString(controller.events(actor("tenant-a","user-a"),"run-a","task-a",0,128));
        assertThat(result).contains("TypeError","REDACTED","ERROR_OCCURRED").doesNotContain("private-control-token");
        assertThat(json.writeValueAsString(controller.events(actor("tenant-a","user-a"),"run-a","task-a",1,128))).doesNotContain("TypeError");
    }
    @Test void listAndEventReadsRequireBoundedPaging(){
        assertThatThrownBy(()->controller.list(actor("tenant-a","user-a"),0,1000,"","")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->controller.events(actor("tenant-a","user-a"),"run-a","task-a",-1,128)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void springMvcBindsQueryAndPathParametersWithoutCompilerParameterMetadata()throws Exception{
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        for(String path:List.of("/tasks?size=20&page=0","/tasks/run-a/task-a","/tasks/run-a/task-a/events?afterSequence=0","/tasks/run-a/task-a/artifacts"))
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/coding-node-diagnostics"+path)
                .requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID,"tenant-a").requestAttr(ApiAuthenticationFilter.CURRENT_USER_ID,"user-a"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }
    @Test void sealingRevokesExecutionWithoutMisreportingACompletedDeliveryAsCancelled()throws Exception{
        Path file=root.resolve("state/workers/12345678-1234-4123-8123-123456789012/registry.json");
        var record=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Files.readString(file));
        record.put("revoked",true);record.put("workerState","STOPPED");record.set("delivery",json.valueToTree(Map.of("artifacts",List.of())));
        record.set("stopProof",json.valueToTree(Map.of("running",false,"finishedAt","2026-11-11T01:02:03Z")));Files.writeString(file,json.writeValueAsString(record));
        String result=json.writeValueAsString(controller.detail(actor("tenant-a","user-a"),"run-a","task-a"));
        assertThat(result).contains("SEALED_PENDING_VALIDATION","2026-11-11T01:02:03Z","NOT_RUN").doesNotContain("CANCELLED");
    }
}
