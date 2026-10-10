package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator;
import com.chatchat.agents.runtime.analysis.*;
import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiFunction;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HarnessToolRecoveryTest {
    @TempDir Path directory;
    private final List<RocksDbAnalysisEvidenceSpillStore> stores = new ArrayList<>();
    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final AnalysisEvidenceCoordinator evidence = mock(AnalysisEvidenceCoordinator.class);
    private final GovernanceIsolationScope scope = GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation");
    private final AtomicInteger executions = new AtomicInteger();
    private final AtomicInteger admissions = new AtomicInteger();
    private final String payload = "full-result-" + "\u6d4b\u8bd5".repeat(10_000) + "-tail";
    @BeforeEach void initialize() {
        when(registry.getToolMetadata("read_tool")).thenReturn(ToolMetadata.builder().id("read_tool").agentCompatible(true).operationType("read").build());
        when(evidence.project(any(), anyMap())).thenAnswer(call -> {
            InterpretationPlanRuntime.ExecutionResult result = call.getArgument(0);
            Object data = ((ToolOutput)result.steps().get(0).output()).getData();
            return new AnalysisEvidenceCoordinator.Projection(List.of(new AnalysisEvidenceCoordinator.Dataset("source", Map.of(),
                List.of(Map.of("payload", ((Map<?,?>)data).get("payload"))))), List.of());
        });
    }
    @AfterEach void close() { stores.forEach(RocksDbAnalysisEvidenceSpillStore::close); }
    private RocksDbAnalysisEvidenceSpillStore store() {
        var properties = new AgentRuntimeProperties();
        properties.setRocksDbPath(directory.resolve("run-db").toString());
        properties.setAnalysisSpillRocksDbPath(directory.resolve("spill-db").toString());
        var store = new RocksDbAnalysisEvidenceSpillStore(properties, new ObjectMapper()); stores.add(store); store.open(); return store;
    }
    private ToolRuntimeExecution execute(String name, Map<String,Object> arguments) {
        executions.incrementAndGet();
        return new ToolRuntimeExecution(ToolOutput.success(Map.of("payload", payload)), null,
            InteractionToolTrace.builder().toolName(name).success(true).input(arguments).build(), "success", Map.of());
    }
    private HarnessToolAccess access(AnalysisEvidenceSpillStore store, Map<String,Object> metadata,
            BiFunction<String,Map<String,Object>,ToolRuntimeExecution> operation) {
        return new HarnessToolAccess(registry, List.of("read_tool"), operation, evidence, Map.of(), metadata, scope, store, 0)
            .withRecoveryAdmission((name,args) -> { admissions.incrementAndGet(); return null; });
    }
    private Map<String,Object> request(String id, Map<String,Object> arguments) {
        return Map.of("requestId", id, "toolName", "read_tool", "arguments", arguments);
    }
    @Test void committedResultSurvivesStoreReopenAndRechecksAuthorizationWithoutReexecution() {
        var store = store(); var request = request("intent-1", Map.of("sample", 1));
        var firstSources = new LinkedHashMap<String,AnalysisEvidenceCoordinator.Dataset>();
        var firstMetadata = new LinkedHashMap<String,Object>();
        var first = access(store, firstMetadata, this::execute).call(request, firstSources, "coordinate");
        assertThat(((Map<?,?>)firstMetadata.get("harnessLastToolReceipt")).get("origin")).isEqualTo("NEW_EXECUTION");
        store.close(); var reopened = store(); var metadata = new LinkedHashMap<String,Object>();
        var sources = new LinkedHashMap<String,AnalysisEvidenceCoordinator.Dataset>();
        when(registry.getToolRevision("read_tool")).thenReturn(42L); // Process-local registration order is not a contract version.
        var restored = access(reopened, metadata, this::execute).call(request, sources, "coordinate");
        assertThat(restored).isEqualTo(first); assertThat(executions).hasValue(1); assertThat(admissions).hasValue(1);
        assertThat(sources.values().iterator().next().handle().readPage(0, 1).rows().get(0)).containsEntry("payload", payload);
        assertThat((List<?>) metadata.get("harnessToolTraces")).hasSize(1);
        assertThat(metadata).containsEntry("harnessToolCalls", 1);
        assertThat(((Map<?,?>) metadata.get("harnessLastRecovery")).get("state")).isEqualTo("RESTORED");
        assertThat(((Map<?,?>) metadata.get("harnessLastToolReceipt")).get("origin")).isEqualTo("COMMITTED_RESULT");
    }
    @Test void projectionFailureRetainsCommittedResultAndRetriesProjectionWithoutToolInvocation() {
        var access = access(store(), new LinkedHashMap<>(), this::execute);
        var request = request("projection-retry", Map.of());
        doThrow(new IllegalStateException("projection unavailable"))
            .doReturn(new AnalysisEvidenceCoordinator.Projection(List.of(new AnalysisEvidenceCoordinator.Dataset("source", Map.of(),
                List.of(Map.of("payload", payload)))), List.of())).when(evidence).project(any(), anyMap());
        var sources = new LinkedHashMap<String,AnalysisEvidenceCoordinator.Dataset>();
        assertThat(access.call(request, sources, "coordinate")).containsEntry("status", "RESULT_AVAILABLE_PROJECTION_FAILED");
        assertThat(sources).isEmpty();
        assertThat(access.call(request, sources, "coordinate")).containsEntry("status", "SUCCESS");
        assertThat(executions).hasValue(1); assertThat(sources).hasSize(1);
    }
    @Test void identitiesDistinguishNewExplorationAndRejectArgumentSubstitution() {
        var access = access(store(), new LinkedHashMap<>(), this::execute); var sources = new LinkedHashMap<String,AnalysisEvidenceCoordinator.Dataset>();
        access.call(request("first", Map.of("sample", 1)), sources, "coordinate");
        access.call(request("second", Map.of("sample", 2)), sources, "coordinate");
        access.call(request("third", Map.of("sample", 2)), sources, "coordinate");
        assertThatThrownBy(() -> access.call(request("first", Map.of("sample", 9)), sources, "coordinate"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity conflicts");
        assertThat(executions).hasValue(3); assertThat(sources).hasSize(3);
    }
    @Test void argumentKeyOrderingDoesNotChangeRequestIdentity() {
        var access = access(store(), new LinkedHashMap<>(), this::execute); var sources = new LinkedHashMap<String,AnalysisEvidenceCoordinator.Dataset>();
        var first = new LinkedHashMap<String,Object>(); first.put("a", 1); first.put("b", Map.of("x", 2, "y", 3));
        var second = new LinkedHashMap<String,Object>(); second.put("b", Map.of("y", 3, "x", 2)); second.put("a", 1);
        assertThat(access.call(request("same", first), sources, "coordinate"))
            .isEqualTo(access.call(request("same", second), sources, "coordinate"));
        assertThat(executions).hasValue(1);
    }
    @Test void revokedAuthorizationAndChangedPublishedContractNeverReleaseCachedData() {
        var store = store(); var request = request("first", Map.of());
        access(store, new LinkedHashMap<>(), this::execute).call(request, new LinkedHashMap<>(), "coordinate");
        var denied = access(store, new LinkedHashMap<>(), this::execute).withRecoveryAdmission((name,args) ->
            new ToolRuntimeExecution(ToolOutput.failure("denied"), null, null, "permission_denied", Map.of()));
        var sources = new LinkedHashMap<String,AnalysisEvidenceCoordinator.Dataset>();
        assertThat(denied.call(request, sources, "coordinate")).containsEntry("status", "RECOVERY_ADMISSION_REJECTED");
        assertThat(sources).isEmpty();
        when(registry.getToolMetadata("read_tool")).thenReturn(ToolMetadata.builder().id("read_tool").agentCompatible(true)
            .operationType("read").metadata(Map.of("inputSchema", Map.of("type", "object", "required", List.of("new-field")))).build());
        assertThat(access(store, new LinkedHashMap<>(), this::execute).call(request, sources, "coordinate"))
            .containsEntry("status", "RECOVERY_ADMISSION_REJECTED");
        assertThat(executions).hasValue(1);
    }
    @Test void recoveryReauthorizesTheActualRecordedInvocationRatherThanRebindingTheOriginalModelArguments() {
        var store = store(); var request = request("actual-invocation", Map.of("query", "requested"));
        access(store, new LinkedHashMap<>(), (name,args) -> new ToolRuntimeExecution(ToolOutput.success(Map.of("payload", payload)), null,
            InteractionToolTrace.builder().toolName(name).success(true).input(Map.of("query", "requested", "resourceId", "original-resource")).build(),
            "success", Map.of())).call(request, new LinkedHashMap<>(), "coordinate");
        var recovered = access(store, new LinkedHashMap<>(), this::execute).withRecoveryAdmission((name,args) -> {
            assertThat(args).containsEntry("resourceId", "original-resource"); return null;
        });
        assertThat(recovered.call(request, new LinkedHashMap<>(), "coordinate")).containsEntry("status", "SUCCESS");
        assertThat(executions).hasValue(0);
    }

    @Test void savedResultFailureCanBeRepairedInProcessWithoutAnotherInvocation() {
        var store = failingResults(store(), false); var access = access(store, new LinkedHashMap<>(), this::execute);
        var request = request("first", Map.of()); var sources = new LinkedHashMap<String,AnalysisEvidenceCoordinator.Dataset>();
        assertThat(access.call(request, sources, "coordinate")).containsEntry("status", "RESULT_PERSISTENCE_PENDING")
            .containsEntry("remoteExecutionState", "SUCCEEDED");
        assertThat(sources).isEmpty();
        assertThat(access.call(request, sources, "coordinate")).containsEntry("status", "SUCCESS");
        assertThat(executions).hasValue(1);
    }
    @Test void restartAfterSuccessWithNoSavedResultReportsUncertaintyAndNeverReexecutes() {
        var durable = store(); var request = request("first", Map.of());
        assertThat(access(failingResults(durable, false), new LinkedHashMap<>(), this::execute).call(request, new LinkedHashMap<>(), "coordinate"))
            .containsEntry("status", "RESULT_PERSISTENCE_PENDING");
        durable.close(); var reopened = store();
        assertThat(access(reopened, new LinkedHashMap<>(), this::execute).call(request, new LinkedHashMap<>(), "coordinate"))
            .containsEntry("status", "EXECUTION_OUTCOME_UNKNOWN");
        assertThat(executions).hasValue(1);
    }
    @Test void lostCommitAcknowledgementRestoresCommittedResultWithoutReexecuting() {
        var durable = store(); var request = request("first", Map.of());
        assertThat(access(failingResults(durable, true), new LinkedHashMap<>(), this::execute).call(request, new LinkedHashMap<>(), "coordinate"))
            .containsEntry("status", "RESULT_PERSISTENCE_PENDING");
        durable.close(); var reopened = store();
        assertThat(access(reopened, new LinkedHashMap<>(), this::execute).call(request, new LinkedHashMap<>(), "coordinate"))
            .containsEntry("status", "SUCCESS");
        assertThat(executions).hasValue(1);
    }
    @Test void restartDoesNotResetToolBudgetAndRestoringExistingResultsIsFree() {
        var store = store(); var access = access(store, new LinkedHashMap<>(), this::execute);
        for (int index = 0; index < 4; index++) access.call(request("intent-"+index, Map.of()), new LinkedHashMap<>(), "coordinate");
        store.close(); var reopened = access(store(), new LinkedHashMap<>(), this::execute);
        assertThat(reopened.call(request("intent-0", Map.of()), new LinkedHashMap<>(), "coordinate")).containsEntry("status", "SUCCESS");
        assertThatThrownBy(() -> reopened.call(request("intent-4", Map.of()), new LinkedHashMap<>(), "coordinate"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("budget exhausted");
        assertThat(executions).hasValue(4);
    }
    @Test void concurrentRecoveryDoesNotDispatchAnAlreadyClaimedRequest() throws Exception {
        var store = store(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var owner = access(store, new LinkedHashMap<>(), (name,args) -> {
            entered.countDown(); try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new CancellationException(); }
            return execute(name,args);
        });
        var request = request("concurrent", Map.of()); var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> owner.call(request, new LinkedHashMap<>(), "coordinate"));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(access(store, new LinkedHashMap<>(), this::execute).call(request, new LinkedHashMap<>(), "coordinate"))
                .containsEntry("status", "EXECUTION_OUTCOME_UNKNOWN");
            release.countDown(); assertThat(future.get(10, TimeUnit.SECONDS)).containsEntry("status", "SUCCESS");
            assertThat(executions).hasValue(1);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void fullHarnessReplaysSavedModelTurnAndRestoresToolEvidenceAfterCancellation() {
        var store = store(); var model = mock(dev.langchain4j.model.chat.ChatModel.class);
        String continued = com.chatchat.agents.protocol.ModelProtocolJson.compact(Map.of("schemaVersion", "model_native_analysis.v2",
            "decision", Map.of("action", "CONTINUE"), "reportMarkdown", "Working draft", "evidenceRequests", List.of(
                Map.of("operation", "CALL_TOOL", "toolName", "read_tool", "arguments", Map.of("sample", 1)))));
        when(model.chat(anyString())).thenReturn(continued).thenThrow(new CancellationException("process interrupted"));
        var seed = List.of(new AnalysisEvidenceCoordinator.Dataset("seed", Map.of(), List.of(Map.of("seed", 1))));
        var metadata = new LinkedHashMap<String,Object>();
        assertThatThrownBy(() -> new ModelNativeAnalysisHarness(3).withToolAccess(access(store, metadata, this::execute))
            .execute("Explore", seed, model, scope, store, metadata, () -> {}, event -> {})).isInstanceOf(CancellationException.class);
        assertThat(executions).hasValue(1);
        store.close(); var reopened = store(); var recoveredModel = mock(dev.langchain4j.model.chat.ChatModel.class);
        when(recoveredModel.chat(anyString())).thenReturn(com.chatchat.agents.protocol.ModelProtocolJson.compact(Map.of(
            "schemaVersion", "model_native_analysis.v2", "decision", Map.of("action", "PUBLISH"), "reportMarkdown", "Recovered report")));
        var recoveredMetadata = new LinkedHashMap<String,Object>();
        var result = new ModelNativeAnalysisHarness(3).withToolAccess(access(reopened, recoveredMetadata, this::execute))
            .execute("Explore", seed, recoveredModel, scope, reopened, recoveredMetadata, () -> {}, event -> {});
        assertThat(result.markdown()).isEqualTo("Recovered report");
        assertThat(result.datasetReferences()).hasSize(2); assertThat(result.modelCalls()).isEqualTo(1);
        assertThat(executions).hasValue(1); assertThat(admissions).hasValue(1);
        assertThat(recoveredMetadata).containsEntry("publicationState", "REQUESTED").containsEntry("harnessToolCalls", 1);
    }

    private AnalysisEvidenceSpillStore failingResults(AnalysisEvidenceSpillStore delegate, boolean writeBeforeFailure) {
        return new AnalysisEvidenceSpillStore() {
            boolean fail = true;
            public boolean isEnabled() { return true; }
            public boolean supportsAtomicCheckpoints() { return true; }
            public SpillReference spill(GovernanceIsolationScope s, String id, String hash, byte[] value) { return delegate.spill(s,id,hash,value); }
            public byte[] read(GovernanceIsolationScope s, SpillReference ref) { return delegate.read(s,ref); }
            public Optional<String> readCheckpoint(GovernanceIsolationScope s,String key,String hash) { return delegate.readCheckpoint(s,key,hash); }
            public void checkpoint(GovernanceIsolationScope s,String key,String hash,String value) { delegate.checkpoint(s,key,hash,value); }
            public boolean compareAndSetCheckpoint(GovernanceIsolationScope s,String key,String hash,String expected,String next) {
                if (fail && next.contains("RESULT_RECORDED")) {
                    fail = false; if (writeBeforeFailure) delegate.compareAndSetCheckpoint(s,key,hash,expected,next);
                    throw new IllegalStateException("Injected result persistence failure");
                }
                return delegate.compareAndSetCheckpoint(s,key,hash,expected,next);
            }
        };
    }
}
