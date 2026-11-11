package com.chatchat.api.runtime.coding;

import com.chatchat.api.controller.agent.NativeRuntimeMcpController;
import com.chatchat.api.runtime.coding.nativecline.*;
import com.chatchat.api.runtime.coding.knowledge.CodingInternetAccess;
import com.chatchat.agents.runtime.coding.*;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class NativeRuntimeMcpControllerTest {
    @TempDir Path directory;
    ObjectMapper json=new ObjectMapper();DurableCodingTaskService tasks=mock(DurableCodingTaskService.class);
    CodingInternetAccess internet=mock(CodingInternetAccess.class);CodingSkillReader skills=mock(CodingSkillReader.class);
    CodingTaskContract contract=mock(CodingTaskContract.class,RETURNS_DEEP_STUBS);
    CodingTaskOutcome outcome=mock(CodingTaskOutcome.class,RETURNS_DEEP_STUBS);
    MockMvc mvc;NativeS3Settings settings;String token;
    @BeforeEach void setup()throws Exception{
        var file=directory.resolve("private.json");Files.writeString(file,json.writeValueAsString(Map.of("issuerKey","k".repeat(64),"endpoint","http://127.0.0.1:1","defaultModel","model","modelProfiles",Map.of("model","fixed"))));
        settings=new NativeS3Settings(file.toString(),json);token=grant(System.currentTimeMillis()+60000,"run");
        when(contract.schemaVersion()).thenReturn(CodingProtocol.WORKSPACE_VERSION);
        when(contract.outputContract().resultFormat()).thenReturn(CodingTaskContract.ResultFormat.WORKSPACE_FILES);
        when(contract.metadataRefs()).thenReturn(List.of(NativeSiloTaskService.PROFILE));
        when(contract.authorization().allowedTools()).thenReturn(List.of("web_search","web_download"));
        when(contract.budget().deadlineAt()).thenReturn(Instant.now().plusSeconds(60));
        when(tasks.contract(any(),eq("task"))).thenReturn(contract);
        when(tasks.privateState(any(),eq("task"),eq("native-s3:binding"))).thenReturn(json.valueToTree(Map.of("grant",token)));
        when(tasks.get(any(),eq("task"))).thenReturn(outcome);
        when(outcome.executionControl().stopReason()).thenReturn(null);
        when(contract.taskId()).thenReturn("task");when(outcome.status()).thenReturn(CodingTaskOutcome.Status.RUNNING);
        mvc=standaloneSetup(new NativeRuntimeMcpController(settings,json,new CodingInvocationContext(),tasks,internet,skills,mock(NativeS3Evidence.class))).setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(json)).build();
    }
    String grant(long expiry,String run){return settings.client.signGrant(Map.of("version",2,"tenantId","tenant","userId","user","taskId","task","expiresAt",expiry,"deliveryBinding",Map.of("runId",run)),settings.issuerKey);}
    ResultActions request(String bearer,String body)throws Exception{return mvc.perform(post("/internal/runtime/native-coding/mcp").header("Authorization","Bearer "+bearer).contentType("application/json").content(body));}
    @Test void invalidAndExpiredTaskCapabilitiesCannotReachAnyRuntimeTool()throws Exception{
        request("invalid","{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").andExpect(status().isUnauthorized());
        request(grant(System.currentTimeMillis()-1,"run"),"{}").andExpect(status().isUnauthorized());
        verifyNoInteractions(tasks,internet,skills);
    }
    @Test void signedCrossRunCapabilityStillNeedsExactDurableBinding()throws Exception{
        request(grant(System.currentTimeMillis()+60000,"other"),"{}").andExpect(status().isForbidden());verifyNoInteractions(internet,skills);
    }
    @Test void listsOnlyTaskAuthorizedToolsAndHandlesNotificationWithoutFakeReceipt()throws Exception{
        request(token,"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").andExpect(status().isOk()).andExpect(jsonPath("$.result.tools.length()").value(2));
        request(token,"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").andExpect(status().isAccepted());
        verifyNoInteractions(internet,skills);
    }
    @Test void closedTasksAndUnknownToolsCannotDispatch()throws Exception{
        request(token,"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"private_sql\",\"arguments\":{}}}").andExpect(status().isForbidden());
        when(outcome.status()).thenReturn(CodingTaskOutcome.Status.CANCELLED);
        request(token,"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").andExpect(status().isConflict());
        verifyNoInteractions(internet,skills);
    }
    @Test void anUnknownScopedTaskIsAnAuthorizationDenialNotAServerFailure()throws Exception{
        when(tasks.contract(any(),eq("task"))).thenThrow(new CodingContractException(CodingErrorCode.NOT_FOUND,"Fixture task not found"));
        request(token,"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").andExpect(status().isForbidden());
        verifyNoInteractions(internet,skills);
    }

}
