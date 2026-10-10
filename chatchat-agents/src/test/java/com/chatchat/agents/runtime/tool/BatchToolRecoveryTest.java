package com.chatchat.agents.runtime.tool;

import com.chatchat.agents.runtime.batch.ToolCallBatchResult;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.evidence.RuntimeExecutionCheckpointPort;
import com.chatchat.common.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BatchToolRecoveryTest {
    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final List<ToolRuntimeService> services = new ArrayList<>();
    private final AtomicInteger remoteCalls = new AtomicInteger();
    private final MemoryCheckpoints checkpoints = new MemoryCheckpoints();
    @BeforeEach void prepare() {
        when(registry.getToolMetadata("gateway")).thenReturn(ToolMetadata.builder().id("gateway")
            .operationType("read").agentCompatible(true)
            .metadata(Map.of("capabilities", List.of("template_execution", "batch_execution"))).build());
        when(registry.executeEnhancedTool(eq("gateway"), any())).thenAnswer(invocation -> {
            remoteCalls.incrementAndGet(); return ToolOutput.success(Map.of("value", "complete-result"));
        });
    }
    @AfterEach void close() { services.forEach(ToolRuntimeService::shutdown); }
    private ToolRuntimeService service() {
        return service(new ToolRuntimeProperties());
    }
    private ToolRuntimeService service(ToolRuntimeProperties properties) {
        var service = new ToolRuntimeService(registry, new ObjectMapper(), properties, List.of(), List.of());
        service.setExecutionCheckpoints(checkpoints); services.add(service); return service;
    }
    private ToolRuntimeRequest request(int count) {
        var calls = new ArrayList<Map<String,Object>>();
        for (int index = 0; index < count; index++) calls.add(Map.of("callId", "call-" + index, "toolName", "gateway",
            "arguments", Map.of("templateId", "contract-" + index)));
        return ToolRuntimeRequest.builder().toolName("gateway").tenantId("tenant").userId("owner").requestId("request")
            .conversationId("conversation").allowedTools(List.of("gateway"))
            .attributes(new LinkedHashMap<>(Map.of("harnessToolRequestId", "intent", "harnessRunId", "run", "toolRetryAttempts", 0)))
            .toolInput(ToolInput.builder().userId("owner").parameters(Map.of("executionMode", "SEQUENTIAL", "calls", calls)).build()).build();
    }
    @Test void committedChildrenRestoreOnAnotherServiceWithoutRemoteCalls() {
        assertThat(service().execute(request(2)).output().isSuccess()).isTrue();
        var restored = service().execute(request(2));
        assertThat(restored.output().isSuccess()).isTrue(); assertThat(remoteCalls).hasValue(2);
        assertThat(((ToolCallBatchResult)restored.output().getData()).results()).hasSize(2);
        assertThat(((ToolCallBatchResult)restored.output().getData()).summary().remoteToolInvocations()).isZero();
    }
    @Test void authorizationRevocationBlocksEveryCachedChild() {
        service().execute(request(2));
        var request = request(2); request.setAllowedTools(List.of("other"));
        assertThat(service().execute(request).output().isSuccess()).isFalse(); assertThat(remoteCalls).hasValue(2);
    }
    @Test void persistenceFailureRetriesRetainedChildResultWithoutRedispatch() {
        var runtime = service(); checkpoints.failResult.set(true);
        var first = runtime.execute(request(2));
        assertThat(first.output().getMetadata()).containsEntry("executionRecoveryPending", true);
        var second = runtime.execute(request(2));
        assertThat(second.output().isSuccess()).isTrue(); assertThat(remoteCalls).hasValue(2);
    }
    @Test void restartAfterUnacknowledgedResultCommitRestoresIt() {
        checkpoints.failResult.set(true); checkpoints.commitBeforeFailure = true;
        assertThat(service().execute(request(2)).output().getMetadata()).containsEntry("executionRecoveryPending", true);
        assertThat(service().execute(request(2)).output().isSuccess()).isTrue(); assertThat(remoteCalls).hasValue(2);
    }
    @Test void exhaustedVolatileRetentionBudgetDoesNotRedispatchAnUncommittedSuccessfulChild() {
        var properties = new ToolRuntimeProperties(); properties.setMaxRecoveryPendingBytes(0);
        var runtime = service(properties); checkpoints.failResult.set(true);
        var first = (ToolCallBatchResult)runtime.execute(request(1)).output().getData();
        assertThat(first.results().get(0).error()).containsEntry("code", "RESULT_NOT_RETAINED");
        var retry = runtime.execute(request(1));
        assertThat(retry.output().getMetadata()).containsEntry("executionOutcomeUnknown", true);
        assertThat(remoteCalls).hasValue(1);
    }
    @Test void restartBeforeChildClaimRestoresPreviousChildrenAndExecutesOnlyUnclaimedOnes() {
        checkpoints.crashClaim = 2;
        assertThatThrownBy(() -> service().execute(request(3))).isInstanceOf(SimulatedCrash.class);
        assertThat(service().execute(request(3)).output().isSuccess()).isTrue(); assertThat(remoteCalls).hasValue(3);
    }
    @Test void restartAfterChildClaimKeepsUnknownChildAndContinuesUnclaimedOnes() {
        checkpoints.crashClaim = 2; checkpoints.commitBeforeFailure = true;
        assertThatThrownBy(() -> service().execute(request(3))).isInstanceOf(SimulatedCrash.class);
        var restored = (ToolCallBatchResult)service().execute(request(3)).output().getData();
        assertThat(restored.results().get(1).error()).containsEntry("code", "EXECUTION_OUTCOME_UNKNOWN");
        assertThat(remoteCalls).hasValue(2);
    }
    @Test void sameChildIdentityCannotReplaceItsArguments() {
        var runtime = service(); runtime.execute(request(1));
        var changed = request(1);
        changed.getToolInput().setParameters(Map.of("calls", List.of(Map.of("callId", "call-0", "toolName", "gateway",
            "arguments", Map.of("templateId", "different-contract")))));
        var result = (ToolCallBatchResult)runtime.execute(changed).output().getData();
        assertThat(result.results().get(0).error()).containsEntry("code", "RECOVERY_IDENTITY_CONFLICT");
        assertThat(remoteCalls).hasValue(1);
    }
    @Test void concurrentBatchRecoveryDoesNotSealAnExecutingChildAsACompletedResult() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(registry.executeEnhancedTool(eq("gateway"), any())).thenAnswer(invocation -> {
            remoteCalls.incrementAndGet(); ToolInput input = invocation.getArgument(1);
            if ("contract-0".equals(input.getParameter("templateId"))) { entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
            return ToolOutput.success(Map.of("value", "complete-result"));
        });
        var first = service(); var second = service(); var executor = Executors.newSingleThreadExecutor();
        try {
            var owner = executor.submit(() -> first.execute(request(2)));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var competing = second.execute(request(2));
            assertThat(competing.output().getMetadata()).containsEntry("executionRecoveryPending", true).containsEntry("executionOutcomeUnknown", true);
            release.countDown(); assertThat(owner.get(5, TimeUnit.SECONDS).output().isSuccess()).isTrue();
            assertThat(second.execute(request(2)).output().getMetadata()).containsEntry("executionRecoveryPending", false);
            assertThat(remoteCalls).hasValue(2);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void readOnlyContinuationCannotSmuggleAnAuthorizedWriteChildThroughAReadGateway() {
        when(registry.getToolMetadata("write_gateway")).thenReturn(ToolMetadata.builder().id("write_gateway").operationType("write")
            .metadata(Map.of("capabilities", List.of("template_execution", "batch_execution"))).build());
        var request = request(1); request.setAllowedTools(List.of("gateway", "write_gateway"));
        request.getAttributes().put("harnessReadOnlyContinuation", true);
        request.getToolInput().setParameters(Map.of("calls", List.of(Map.of("callId", "write", "toolName", "write_gateway", "arguments", Map.of()))));
        var denied = service().execute(request);
        assertThat(denied.output().isSuccess()).isFalse();
        assertThat(denied.audit()).containsEntry("errorCode", "TOOL_OPERATION_NOT_ALLOWED");
        assertThat(remoteCalls).hasValue(0);
    }
    @Test void fullHarnessRetriesPendingBatchAndThenRestoresCommittedOuterReceiptWithoutDispatch() {
        var evidence = mock(com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.class);
        when(evidence.project(any(), anyMap())).thenReturn(new com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Projection(
            List.of(new com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset("batch", Map.of(),
                List.of(Map.of("value", "complete-result")))), List.of()));
        var scope = com.chatchat.agents.runtime.governance.GovernanceIsolationScope.runtime("tenant", "owner", "run", "request", "conversation");
        var runtime = service(); var metadata = new LinkedHashMap<String,Object>();
        var access = new com.chatchat.agents.orchestration.analysis.graph.HarnessToolAccess(registry, List.of("gateway"),
            (name,arguments) -> runtime.execute(request(2)), evidence, Map.of(), metadata, scope,
            com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore.disabled(), 0)
            .withExecutionCheckpoints(checkpoints).withBatchRecovery(true)
            .withRecoveryAdmission((name,arguments) -> runtime.checkRecoveryAdmission(request(2)));
        var intent = Map.<String,Object>of("requestId", "intent", "toolName", "gateway", "arguments", request(2).getToolInput().getParameters());
        var sources = new LinkedHashMap<String,com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset>();
        checkpoints.failResult.set(true);
        assertThat(access.call(intent, sources, "coordinate")).containsEntry("status", "RESULT_PERSISTENCE_PENDING");
        var kernelScope = new KernelDataScope("tenant", "owner", "request", "conversation", "run", null, Map.of());
        assertThat(checkpoints.readExecutionCheckpoint(kernelScope, "harness:call:" + com.chatchat.agents.protocol.ModelProtocolJson.sha256Hex("intent")))
            .get().asString().contains("STARTED").doesNotContain("RESULT_RECORDED");
        var completed = access.call(intent, sources, "coordinate");
        assertThat(completed).containsEntry("status", "SUCCESS");
        var other = new com.chatchat.agents.orchestration.analysis.graph.HarnessToolAccess(registry, List.of("gateway"),
            (name,arguments) -> { throw new AssertionError("Committed outer receipt must not dispatch"); }, evidence, Map.of(),
            new LinkedHashMap<>(), scope, com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore.disabled(), 0)
            .withExecutionCheckpoints(checkpoints).withBatchRecovery(true)
            .withRecoveryAdmission((name,arguments) -> runtime.checkRecoveryAdmission(request(2)));
        assertThat(other.call(intent, new LinkedHashMap<>(), "coordinate")).isEqualTo(completed);
        assertThat(remoteCalls).hasValue(2);
    }
    private static class SimulatedCrash extends Error { }
    private static class MemoryCheckpoints implements RuntimeExecutionCheckpointPort {
        private final Map<String,String> values = new HashMap<>();
        private final AtomicBoolean failResult = new AtomicBoolean();
        private int claims;
        int crashClaim;
        boolean commitBeforeFailure;
        private String key(KernelDataScope scope, String key) { return scope.tenantId() + "/" + scope.userId() + "/" + scope.runId() + "/" + key; }
        public synchronized Optional<String> readExecutionCheckpoint(KernelDataScope scope, String key) { return Optional.ofNullable(values.get(key(scope,key))); }
        public synchronized boolean compareAndSetExecutionCheckpoint(KernelDataScope scope, String key, String expected, String next) {
            String identity = key(scope,key);
            if (!Objects.equals(values.get(identity), expected)) return false;
            if (expected == null && ++claims == crashClaim) {
                if (commitBeforeFailure) values.put(identity,next);
                throw new SimulatedCrash();
            }
            if (next.contains("RESULT_RECORDED") && failResult.compareAndSet(true,false)) {
                if (commitBeforeFailure) values.put(identity,next);
                throw new IllegalStateException("result commit failure");
            }
            values.put(identity,next); return true;
        }
    }
}
