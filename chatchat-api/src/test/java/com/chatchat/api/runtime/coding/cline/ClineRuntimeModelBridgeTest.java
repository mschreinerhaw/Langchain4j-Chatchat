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
            assertThat(error.receipt()).containsKey("modelInfo").containsKey("error");
        }
        verify(parent, times(1)).chat(any(ChatRequest.class));
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
        when(factory.create(eq("registered-model"), eq(Duration.ofSeconds(90)), eq(2048))).thenReturn(model);
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
        verify(factory).create("registered-model", Duration.ofSeconds(90), 2048);
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
            .isInstanceOf(SecurityException.class);
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
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("reconcile before dispatch");
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
