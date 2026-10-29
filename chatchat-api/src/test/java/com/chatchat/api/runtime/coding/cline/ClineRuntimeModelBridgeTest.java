package com.chatchat.api.runtime.coding.cline;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.api.runtime.coding.CodingParentModels;
import com.chatchat.common.config.ModelResourceRegistry;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClineRuntimeModelBridgeTest {
    @Test void fullNativeAnalysisProfileReachesProviderAndDuplicateDeclarationsAreRejected() throws Exception {
        var resources=mock(ModelResourceRegistry.class);var factory=mock(ConfigurableChatModelFactory.class);
        var parent=mock(ChatModel.class);when(resources.defaultChatModel()).thenReturn("registered-model");
        when(parent.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("done"))
            .tokenUsage(new TokenUsage(2,1,3)).build());
        var registry=new CodingParentModels();var bridge=new ClineRuntimeModelBridge(resources,factory,json);bridge.setParentModels(registry);
        json.findAndRegisterModules();var frozen=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(contract());
        frozen.put("schemaVersion",CodingProtocol.ANALYSIS_VERSION);
        var names=List.of("python_execute","input_read","report_submit","artifact_read","sql_validate");
        ((com.fasterxml.jackson.databind.node.ObjectNode)frozen.get("authorization")).set("allowedTools",json.valueToTree(names));
        var contract=json.treeToValue(frozen,CodingTaskContract.class);
        var request=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"explain\"}]}],\"tools\":[]}");
        var tools=(com.fasterxml.jackson.databind.node.ArrayNode)request.get("tools");
        for(var name:names)tools.add(json.valueToTree(Map.of("name",name,"inputSchema",Map.of("type","object","properties",Map.of()))));
        try(var ignored=registry.bind(contract.scope(),contract.taskId(),parent)) {
            bridge.complete(contract,request);
            verify(parent).chat(argThat((ChatRequest r)->r.toolSpecifications().size()==5));
            tools.remove(4);tools.add(tools.get(0).deepCopy());
            assertThatThrownBy(()->bridge.complete(contract,request)).isInstanceOf(ClineRuntimeModelBridge.InvalidModelResponseException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
            verifyNoMoreInteractions(parent);
        }
    }
    @Test void actualClineProviderClientReceivesSelectedModelTimeoutOnEveryRound() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/v1/chat/completions",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            byte[] reply="{\"id\":\"test\",\"model\":\"provider\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"done\"},\"finish_reason\":\"stop\"}]}".getBytes();
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);exchange.close();
        });server.start();
        try {
            var config=new com.chatchat.common.config.ModelsConfig();config.setDefaultChatModel("B");
            var connection=new com.chatchat.common.config.ModelsConfig.ModelConnectionConfig();
            connection.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1");connection.setApiKey("test");connection.setRequestTimeoutSeconds(600);
            config.getChatModels().put("B",connection);
            var resources=new ModelResourceRegistry(config);
            var observed=new java.util.ArrayList<java.time.Duration>();
            var factory=new ConfigurableChatModelFactory(resources,json) {
                @Override public ChatModel create(String name,java.time.Duration remaining,Integer tokens) {
                    ChatModel actual=super.create(name,remaining,tokens);
                    Object client=org.springframework.test.util.ReflectionTestUtils.getField(actual,"client");
                    Object http=org.springframework.test.util.ReflectionTestUtils.getField(client,"httpClient");
                    observed.add((java.time.Duration)org.springframework.test.util.ReflectionTestUtils.getField(http,"readTimeout"));
                    return actual;
                }
            };
            var bridge=new ClineRuntimeModelBridge(resources,factory,json);
            var request=json.readTree("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}],\"tools\":[]}");
            bridge.complete(contract(),request);bridge.complete(contract(),request);
            connection.setRequestTimeoutSeconds(120);
            bridge.complete(contract(),request);
            assertThat(observed).containsExactly(java.time.Duration.ofSeconds(600),java.time.Duration.ofSeconds(600),java.time.Duration.ofSeconds(120));
            assertThat(calls.get()).isEqualTo(3);
            // Native Cline SDK -> existing AgentModel adapter -> HTTP callback -> Java -> real provider.
            var callback=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            connection.setRequestTimeoutSeconds(600);
            callback.createContext("/internal/coding/cline/model",exchange->{
                try {
                    var payload=json.readTree(exchange.getRequestBody());
                    byte[] reply=json.writeValueAsBytes(bridge.complete(contract(),payload.path("request")));
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);
                } finally {exchange.close();}
            });callback.start();
            try {
                var node=java.nio.file.Path.of(System.getProperty("user.dir")).resolve("../coding-agent-node").normalize();
                String source="""
                    import {Agent} from '@cline/agents';
                    import {createRuntimeModel} from './src/runtime-model.js';
                    import {httpGateway} from './src/server.js';
                    const contract={taskId:'test',budget:{deadlineAt:new Date(Date.now()+1200000).toISOString()}};
                    const gateway=httpGateway('http://127.0.0.1:%d','test-secret',{}, {timeoutMs:25});
                    const model=createRuntimeModel({...gateway,atomicModelAttempts:false},contract,0,{nativeSdk:true});
                    const agent=new Agent({model,maxIterations:2});
                    for(let i=0;i<2;i++) {
                      const result=await agent.run('say done');
                      if(result.status!=='completed')throw Error('Native Cline run did not complete');
                    }
                    console.log('NATIVE_CLINE_TIMEOUT_CHAIN_OK');
                    """.formatted(callback.getAddress().getPort());
                var process=new ProcessBuilder("node","--input-type=module","-e",source).directory(node.toFile()).redirectErrorStream(true).start();
                assertThat(process.waitFor(20,java.util.concurrent.TimeUnit.SECONDS)).as("Native SDK callback finishes").isTrue();
                String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                assertThat(process.exitValue()).withFailMessage(output).isZero();
                assertThat(output).contains("NATIVE_CLINE_TIMEOUT_CHAIN_OK");
                assertThat(observed.subList(3,5)).containsOnly(java.time.Duration.ofSeconds(600));
                assertThat(calls.get()).isEqualTo(5);
            } finally {callback.stop(0);}
        } finally {server.stop(0);}
    }
    @Test void incompatibleToolSchemaHasDefinitiveLocalEvidenceAndNeverCallsAProvider() throws Exception {
        var resources=mock(ModelResourceRegistry.class);var factory=mock(ConfigurableChatModelFactory.class);
        when(resources.defaultChatModel()).thenReturn("registered-model");
        var request=json.readTree("""
            {"messages":[],"tools":[{"name":"python_execute","inputSchema":{"type":"object",
             "properties":{"source":{"type":"string"}},"oneOf":[{"required":["source"]}]}}]}
            """);
        var rejected=catchThrowableOfType(()->new ClineRuntimeModelBridge(resources,factory,json).complete(contract(),request),
            ClineRuntimeModelBridge.InvalidModelResponseException.class);
        assertThat(rejected.receipt()).containsEntry("providerDispatched",false)
            .containsEntry("diagnosticCode","MODEL_REQUEST_REJECTED_LOCALLY")
            .containsEntry("usage",Map.of("inputTokens",0,"outputTokens",0));
        verifyNoInteractions(factory);
    }
    @Test void fastReplyLimitCannotIncreaseWorkspaceOrTaskBudget(){
        var bridge=new ClineRuntimeModelBridge(mock(ModelResourceRegistry.class),mock(ConfigurableChatModelFactory.class),json);
        var task=mock(CodingTaskContract.class);var output=mock(CodingTaskContract.OutputContract.class);
        var budget=mock(CodingTaskContract.Budget.class);
        when(task.schemaVersion()).thenReturn(CodingProtocol.WORKSPACE_VERSION);when(task.outputContract()).thenReturn(output);
        when(output.resultFormat()).thenReturn(CodingTaskContract.ResultFormat.WORKSPACE_FILES);
        when(task.metadataRefs()).thenReturn(List.of(com.chatchat.api.runtime.coding.workspace.CodingWorkspaceService.FAST_PATH_REF));
        when(task.budget()).thenReturn(budget);when(budget.maxModelTokens()).thenReturn(262144L);
        bridge.setFastPathMaxOutputTokens(4096);
        assertThat(bridge.maxOutputTokens(task)).isEqualTo(4096);
        assertThat(bridge.modelTokenReservation(task)).isEqualTo(8192);
        bridge.setFastPathMaxOutputTokens(16384);
        assertThat(bridge.maxOutputTokens(task)).isEqualTo(8192);
        when(budget.maxModelTokens()).thenReturn(2048L);
        assertThat(bridge.maxOutputTokens(task)).isEqualTo(1024);
    }
    @Test void workspaceBoundsIndividualRepliesEvenWhenGlobalAnalysisAllowsLargeOutputs(){
        var bridge=new ClineRuntimeModelBridge(mock(ModelResourceRegistry.class),mock(ConfigurableChatModelFactory.class),json);
        var task=mock(CodingTaskContract.class);var output=mock(CodingTaskContract.OutputContract.class);
        var budget=mock(CodingTaskContract.Budget.class);
        when(task.schemaVersion()).thenReturn(CodingProtocol.WORKSPACE_VERSION);when(task.outputContract()).thenReturn(output);
        when(output.resultFormat()).thenReturn(CodingTaskContract.ResultFormat.WORKSPACE_FILES);
        when(task.budget()).thenReturn(budget);when(budget.maxModelTokens()).thenReturn(262144L);
        bridge.setMaxOutputTokens(65536);
        assertThat(bridge.maxOutputTokens(task)).isEqualTo(8192);
        assertThat(bridge.modelTokenReservation(task)).isEqualTo(16384L);
        assertThat(budget.maxModelTokens()/bridge.modelTokenReservation(task)).isGreaterThanOrEqualTo(8);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"{\"code\":", "null", "[]"})
    void malformedProviderToolOutputRetainsStopAndUsageReceipt(String arguments) throws Exception {
        var resources = mock(ModelResourceRegistry.class);
        var factory = mock(ConfigurableChatModelFactory.class);
        var parent = mock(ChatModel.class);
        when(resources.defaultChatModel()).thenReturn("registered-model");
        when(parent.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
            .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder().id("call-1")
                .name("python_execute").arguments(arguments).build())))
            .tokenUsage(new TokenUsage(13, 7, 20)).build());
        var bridge = new ClineRuntimeModelBridge(resources, factory, json);
        var registry = new CodingParentModels();
        bridge.setParentModels(registry);
        var contract = contract();
        try (var binding = registry.bind(contract.scope(), contract.taskId(), parent)) {
            var request = json.readTree("{\"systemPrompt\":\"calculate\",\"messages\":[],\"tools\":[]}");
            var error = catchThrowableOfType(() -> bridge.complete(contract, request),
                ClineRuntimeModelBridge.InvalidModelResponseException.class);
            assertThat(error.receipt()).containsEntry("usage", Map.of("inputTokens", 13, "outputTokens", 7));
            assertThat((List<?>) error.receipt().get("toolCalls")).isEmpty();
            assertThat(json.valueToTree(error.receipt()).path("nativeToolCalls").get(0).path("arguments").asText()).isEqualTo(arguments);
            assertThat(error.receipt()).containsKey("modelInfo").containsKey("error");
            assertThat(json.valueToTree(error.receipt()).path("modelResponseFeedback").path("repairAllowed").asBoolean()).isTrue();
        }
        verify(parent, times(1)).chat(any(ChatRequest.class));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void truncatedArgumentsHavePortableDiagnosticsButUnauthorizedToolsNeverGainRepairAuthority(boolean unauthorized) throws Exception {
        var resources=mock(ModelResourceRegistry.class);var factory=mock(ConfigurableChatModelFactory.class);
        var parent=mock(ChatModel.class);when(resources.defaultChatModel()).thenReturn("registered-model");
        when(parent.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
            .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder().id("call")
                .name(unauthorized?"outside_contract":"python_execute").arguments("{\"source\":").build())))
            .tokenUsage(new TokenUsage(123,512,635))
            .finishReason(dev.langchain4j.model.output.FinishReason.TOOL_EXECUTION).build());
        var bridge=new ClineRuntimeModelBridge(resources,factory,json);bridge.setMaxOutputTokens(512);
        var registry=new CodingParentModels();bridge.setParentModels(registry);var task=contract();
        try(var binding=registry.bind(task.scope(),task.taskId(),parent)) {
            var rejected=catchThrowableOfType(()->bridge.complete(task,json.readTree("{\"systemPrompt\":\"calculate\",\"messages\":[],\"tools\":[]}")),
                ClineRuntimeModelBridge.InvalidModelResponseException.class);
            var feedback=json.valueToTree(rejected.receipt()).path("modelResponseFeedback");
            assertThat(feedback.path("diagnosticCode").asText()).isEqualTo(unauthorized?"MODEL_TOOL_NOT_AUTHORIZED":"MODEL_OUTPUT_TRUNCATED");
            assertThat(feedback.path("repairAllowed").asBoolean()).isEqualTo(!unauthorized);
            assertThat(feedback.path("maxRepairAttempts").asInt()).isEqualTo(2);
            assertThat((List<?>)rejected.receipt().get("toolCalls")).isEmpty();
        }
    }
    @Test void rejectedResponseWithoutProviderUsageCannotAuthorizeAnUnmeteredRetry() throws Exception {
        var resources=mock(ModelResourceRegistry.class);var factory=mock(ConfigurableChatModelFactory.class);
        var parent=mock(ChatModel.class);when(resources.defaultChatModel()).thenReturn("registered-model");
        when(parent.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
            .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder().id("call").name("python_execute").arguments("{} {}").build()))).build());
        var bridge=new ClineRuntimeModelBridge(resources,factory,json);var registry=new CodingParentModels();bridge.setParentModels(registry);var task=contract();
        try(var binding=registry.bind(task.scope(),task.taskId(),parent)) {
            var rejected=catchThrowableOfType(()->bridge.complete(task,json.readTree("{\"systemPrompt\":\"calculate\",\"messages\":[],\"tools\":[]}")),
                ClineRuntimeModelBridge.InvalidModelResponseException.class);
            assertThat(json.valueToTree(rejected.receipt()).path("modelResponseFeedback").path("repairAllowed").asBoolean()).isFalse();
        }
    }
    @Test void reportsFrozenActualModelAndContinuesBoundModelAfterDefaultChanges() throws Exception {
        var resources=mock(ModelResourceRegistry.class);var factory=mock(ConfigurableChatModelFactory.class);
        when(resources.defaultChatModel()).thenReturn("changed-default");
        var parent=mock(ChatModel.class);
        when(parent.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().modelName("actual-reported-provider").aiMessage(AiMessage.from("done"))
            .tokenUsage(new TokenUsage(3,2,5)).build());
        var bridge=new ClineRuntimeModelBridge(resources,factory,json);var registry=new CodingParentModels();bridge.setParentModels(registry);
        json.findAndRegisterModules();var node=json.valueToTree(contract());
        ((com.fasterxml.jackson.databind.node.ObjectNode)node).set("runtimeContext",json.valueToTree(Map.of(
            "schemaVersion","runtime_os.coding.context.v1","consistency","TASK_SNAPSHOT","model",
            Map.of("selectedModel","authorized-selected","providerModel","actual-provider","protocol","openai"))));
        var task=json.treeToValue(node,CodingTaskContract.class);
        try(var binding=registry.bind(task.scope(),task.taskId(),parent)) {
            var answer=bridge.complete(task,json.readTree("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"calculate\"}]}],\"tools\":[]}"));
            assertThat(answer.get("modelInfo").toString()).contains("authorized-selected","actual-reported-provider").doesNotContain("changed-default");
            assertThat(((Map<?,?>)answer.get("modelInfo")).get("retryTimeMs")).isNull();
        }
        verifyNoInteractions(factory);verify(resources,never()).require(anyString());
    }
    private final ObjectMapper json = new ObjectMapper();

    @Test void codingReplyLimitIsConfigurableAndReservedWithinTheToolBudget() throws Exception {
        var resources=mock(ModelResourceRegistry.class);var factory=mock(ConfigurableChatModelFactory.class);
        var parent=mock(ChatModel.class);when(resources.defaultChatModel()).thenReturn("registered-model");
        when(parent.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("done"))
            .finishReason(dev.langchain4j.model.output.FinishReason.STOP).build());
        var bridge=new ClineRuntimeModelBridge(resources,factory,json);
        var registry=new CodingParentModels();bridge.setParentModels(registry);
        json.findAndRegisterModules();var frozen=json.valueToTree(contract());
        ((com.fasterxml.jackson.databind.node.ObjectNode)frozen.path("budget")).put("maxModelTokens",65536);
        var contract=json.treeToValue(frozen,CodingTaskContract.class);
        bridge.setMaxOutputTokens(8192);
        assertThat(bridge.modelTokenReservation(contract)).isEqualTo(16384);
        try(var binding=registry.bind(contract.scope(),contract.taskId(),parent)) {
            var result=bridge.complete(contract,json.readTree("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"write a complete program\"}]}],\"tools\":[]}"));
            assertThat(result).containsEntry("maxOutputTokens",8192).containsEntry("finishReason","STOP");
        }
        var captured=org.mockito.ArgumentCaptor.forClass(ChatRequest.class);verify(parent).chat(captured.capture());
        assertThat(captured.getValue().parameters().maxOutputTokens()).isEqualTo(8192);
        verifyNoInteractions(factory);
        bridge.setMaxOutputTokens(32768);
        assertThat(bridge.maxOutputTokens(contract())).isEqualTo(2048);
        assertThat(bridge.modelTokenReservation(contract())).isEqualTo(4096);
    }

    @Test void usesServerModelAndReturnsOnlyAuthorizedToolCallsWithProviderUsage() throws Exception {
        ModelResourceRegistry resources = mock(ModelResourceRegistry.class);
        ConfigurableChatModelFactory factory = mock(ConfigurableChatModelFactory.class);
        ChatModel model = mock(ChatModel.class);
        when(resources.defaultChatModel()).thenReturn("registered-model");
        when(factory.create(eq("registered-model"), any(Duration.class), eq(2048))).thenReturn(model);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
            .id("provider-request").aiMessage(AiMessage.builder().text("run this")
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder().id("call-1")
                    .name("python_execute").arguments("{\"fileName\":\"main.py\",\"source\":\"print(1)\"}").build())).build())
            .tokenUsage(new TokenUsage(9, 4, 13)).build());
        var request = json.readTree("""
            {"systemPrompt":"Only approved tools","messages":[{"role":"user","content":[{"type":"text","text":"calculate"}]}],
             "tools":[{"name":"python_execute","description":"Sandbox","inputSchema":{"type":"object","properties":{}}}]}
            """);
        var result = new ClineRuntimeModelBridge(resources, factory, json).complete(contract(), request);
        assertThat(result.get("requestId")).isEqualTo("provider-request");
        assertThat((List<?>) result.get("toolCalls")).hasSize(1);
        assertThat(((Map<?, ?>) result.get("usage")).get("inputTokens")).isEqualTo(9);
        verify(factory).create(eq("registered-model"), any(Duration.class), eq(2048));
        var captured=org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captured.capture());
        assertThat(captured.getValue().parameters().maxOutputTokens()).isEqualTo(2048);
        assertThat(captured.getValue().toolSpecifications()).hasSize(1);
    }

    @Test void boundAuthorizedModelReceivesCodingCallsAndFactoryCannotReplaceIt() throws Exception {
        var resources=mock(ModelResourceRegistry.class);var factory=mock(ConfigurableChatModelFactory.class);
        var parent=mock(ChatModel.class);when(resources.defaultChatModel()).thenReturn("registered-model");
        when(parent.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("done"))
            .tokenUsage(new TokenUsage(2,1,3)).build());
        var bridge=new ClineRuntimeModelBridge(resources,factory,json);var registry=new CodingParentModels();bridge.setParentModels(registry);
        var contract=contract();
        try(var binding=registry.bind(contract.scope(),contract.taskId(),parent)) {
            bridge.complete(contract,json.readTree("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"calculate\"}]}],\"tools\":[]}"));
        }
        verify(parent).chat(any(ChatRequest.class));verifyNoInteractions(factory);
        assertThat(registry.find(contract.scope(),contract.taskId())).isNull();
    }

    @Test void rejectsUnapprovedToolBeforeCallingModel() throws Exception {
        var resources = mock(ModelResourceRegistry.class);
        var factory = mock(ConfigurableChatModelFactory.class);
        when(resources.defaultChatModel()).thenReturn("registered-model");
        var request = json.readTree("""
            {"messages":[],"tools":[{"name":"host_shell","inputSchema":{"type":"object"}}]}
            """);
        assertThatThrownBy(() -> new ClineRuntimeModelBridge(resources, factory, json).complete(contract(), request))
            .isInstanceOf(ClineRuntimeModelBridge.InvalidModelResponseException.class).hasCauseInstanceOf(SecurityException.class);
        verifyNoInteractions(factory);
    }

    @Test void lostAuthorizedBindingCannotDispatchAnUnaccountedReplacementModel() throws Exception {
        var resources=mock(ModelResourceRegistry.class);var factory=mock(ConfigurableChatModelFactory.class);
        when(resources.defaultChatModel()).thenReturn("registered-model");
        var original=contract();
        json.findAndRegisterModules();
        var frozen=json.valueToTree(original);
        ((com.fasterxml.jackson.databind.node.ObjectNode)frozen).putArray("metadataRefs").add(CodingParentModels.REQUIRED_REF);
        var required=json.treeToValue(frozen,CodingTaskContract.class);
        assertThatThrownBy(()->new ClineRuntimeModelBridge(resources,factory,json).complete(required,
            json.readTree("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"calculate\"}]}],\"tools\":[]}")))
            .isInstanceOf(ClineRuntimeModelBridge.InvalidModelResponseException.class).hasCauseInstanceOf(IllegalStateException.class);
        verifyNoInteractions(factory);
    }

    private CodingTaskContract contract() {
        return new CodingTaskContract(CodingProtocol.VERSION, "task", new KernelDataScope(
            "tenant", "owner", "request", null, "run", null, Map.of()),
            new CodingTaskContract.Objective("calculate", List.of("rows.jsonl"), List.of("criteria")),
            "input:1", List.of(), "workspace:1",
            new CodingTaskContract.Authorization("decision:1", List.of("python_execute"), List.of(),
                List.of(), List.of("rows.jsonl"), CodingTaskContract.NetworkPolicy.NONE,
                List.of(), List.of(CodingTaskContract.SideEffect.CODE_EXECUTION)),
            new CodingTaskContract.Budget("budget:1", Instant.now().plusSeconds(3600)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS), 2, 4096, 2, 1,
                "10", "USD", 10000, 1024 * 1024, 1024 * 1024), "env:1", "validation:1",
            new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.JSON_LINES,
                "schema:1", "schema:2", List.of("rows.jsonl"), List.of("application/x-ndjson"),
                100, 10000, List.of(CodingTaskContract.RequiredEvidence.values())));
    }
}
