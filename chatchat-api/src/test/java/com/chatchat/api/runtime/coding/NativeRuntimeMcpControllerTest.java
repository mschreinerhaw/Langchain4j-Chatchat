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
    com.chatchat.api.runtime.coding.knowledge.CodingDocumentSearchAccess documents=mock(com.chatchat.api.runtime.coding.knowledge.CodingDocumentSearchAccess.class);
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
    @Test void nativeDocumentToolUsesTheExistingDocumentAdapterWithoutEnablingInternet()throws Exception{
        var evidence=mock(NativeS3Evidence.class);when(evidence.bytes(any())).thenAnswer(call->json.writeValueAsBytes(call.getArgument(0)));
        var controller=new NativeRuntimeMcpController(settings,json,new CodingInvocationContext(),tasks,internet,skills,evidence);
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"documents",documents);
        mvc=standaloneSetup(controller).setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(json)).build();
        when(contract.authorization().allowedTools()).thenReturn(List.of("document_search"));
        when(contract.runtimeContext()).thenReturn(Map.of("documentSearch",Map.of("agentId","agent")));
        request(token,"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").andExpect(status().isOk()).andExpect(jsonPath("$.result.tools[0].name").value("document_search")).andExpect(jsonPath("$.result.tools.length()").value(1));
        when(documents.read(eq(contract),eq("native-mcp-2"),any())).thenReturn(new LinkedHashMap<>(Map.of("status","MATCHES","results",List.of(Map.of("fileId","assigned-doc","content","Real source text")))));
        request(token,"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"document_search\",\"arguments\":{\"query\":\"terms\"}}}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.result.isError").value(false)).andExpect(jsonPath("$.result.content[0].text").value(org.hamcrest.Matchers.containsString("assigned-doc")));
        verifyNoInteractions(internet);
        when(outcome.status()).thenReturn(CodingTaskOutcome.Status.CANCELLED);
        request(token,"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"document_search\",\"arguments\":{\"query\":\"terms\"}}}").andExpect(status().isConflict());
        verify(documents,times(1)).read(any(),anyString(),any());
    }
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

    @Test void dynamicModelAuthorityRequiresBoundLiveGrantAndNeverCachesCredentials()throws Exception{
        var file=directory.resolve("dynamic.json");Files.writeString(file,json.writeValueAsString(Map.of("issuerKey","k".repeat(64),"endpoint","http://127.0.0.1:1","defaultModel","model","dynamicModels",true)));
        settings=new NativeS3Settings(file.toString(),json);
        var catalog=mock(com.chatchat.api.model.PlatformModelCatalogService.class);settings.setCatalog(catalog);
        var config=new com.chatchat.common.config.ModelsConfig.ModelConnectionConfig();config.setModelName("actual-provider");config.setBaseUrl("https://provider.example/v1/chat/completions");config.setProtocol("openai");config.setApiKey("fixture-only-secret");
        when(catalog.chatModelNames()).thenReturn(List.of("selected"));when(catalog.resolveChatModel("selected")).thenReturn(new com.chatchat.common.config.ModelsConfig.ResolvedModelConnection("selected","selected",config,com.chatchat.common.config.ModelsConfig.ModelMatchType.CONFIG_KEY));
        var inputs=mock(NativeSiloTaskService.class);when(inputs.requireAuthorized(contract)).thenReturn(Map.of("selectedModel","selected"));
        var controller=new NativeRuntimeMcpController(settings,json,new CodingInvocationContext(),tasks,internet,skills,mock(NativeS3Evidence.class));org.springframework.test.util.ReflectionTestUtils.setField(controller,"modelInputs",inputs);
        mvc=standaloneSetup(controller).setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(json)).build();
        var values=new LinkedHashMap<String,Object>();values.put("version",2);values.put("tenantId","tenant");values.put("userId","user");values.put("taskId","task");values.put("expiresAt",System.currentTimeMillis()+60000);values.put("deliveryBinding",Map.of("runId","run"));values.put("modelProfile",settings.profile("selected"));values.put("runtimeModel",settings.descriptor("selected"));
        token=settings.client.signGrant(values,settings.issuerKey);when(tasks.privateState(any(),eq("task"),eq("native-s3:binding"))).thenReturn(json.valueToTree(Map.of("grant",token)));
        mvc.perform(post("/internal/runtime/native-coding/model").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/runtime/native-coding/model").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.model").value("actual-provider")).andExpect(jsonPath("$.baseUrl").value("https://provider.example/v1"));
        values.put("runtimeModel",Map.of("logicalName","selected","providerModel","other"));token=settings.client.signGrant(values,settings.issuerKey);when(tasks.privateState(any(),eq("task"),eq("native-s3:binding"))).thenReturn(json.valueToTree(Map.of("grant",token)));
        mvc.perform(post("/internal/runtime/native-coding/model").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        when(outcome.status()).thenReturn(CodingTaskOutcome.Status.CANCELLED);
        mvc.perform(post("/internal/runtime/native-coding/model").header("Authorization","Bearer "+token)).andExpect(status().isConflict());
    }

}
