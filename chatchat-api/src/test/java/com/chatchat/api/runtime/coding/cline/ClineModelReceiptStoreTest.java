package com.chatchat.api.runtime.coding.cline;

import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.CodingProtocol;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.chatchat.common.runtime.evidence.EvidenceLineage;
import com.chatchat.common.runtime.evidence.EvidenceRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.*;

class ClineModelReceiptStoreTest {
    @Test void localClosureReconcilesTheSameTaskWithoutReleasingUnknownUsageOrReplayingAnOperation() {
        var data=new DriverManagerDataSource("jdbc:h2:mem:local-stop-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(data);
        var json=new ObjectMapper().findAndRegisterModules();
        var evidence=new JdbcRuntimeEvidenceStore(new JdbcTemplate(data),json,new DataSourceTransactionManager(data));
        var scope=new KernelDataScope("tenant","owner",null,null,"run",null,Map.of());
        var payload=Map.<String,Object>of("fixture","authorized input");
        evidence.register(new EvidenceRecord(null,"input",scope,"CODING_INPUT_SNAPSHOT","test",
            evidence.contentHash(payload),payload,null,System.currentTimeMillis(),Map.of()),
            new EvidenceLineage(null,"input",scope,List.of(),"task","test",null,Map.of()));
        var value=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(contract(scope));
        ((com.fasterxml.jackson.databind.node.ObjectNode)value.path("budget")).put("maxModelCalls",4).put("maxModelTokens",10000);
        var contract=json.convertValue(value,CodingTaskContract.class);
        var receipts=new ClineModelReceiptStore(evidence);
        var access=new com.chatchat.api.runtime.coding.CodingInvocationContext();
        var timer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        var time=new java.util.concurrent.atomic.AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        var clock=org.mockito.Mockito.mock(java.time.Clock.class);
        org.mockito.Mockito.when(clock.instant()).thenAnswer(call->time.get());
        org.mockito.Mockito.when(clock.millis()).thenAnswer(call->time.get().toEpochMilli());
        var tasks=new com.chatchat.agents.runtime.coding.DurableCodingTaskService(evidence,json,clock,access,
            (owner,ref)->true,receipts,Runnable::run,new org.springframework.scheduling.concurrent.ConcurrentTaskScheduler(timer));
        try { access.as(scope,java.util.EnumSet.allOf(com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.class),()->{
            var submitted=tasks.submit(contract);
            var lease=tasks.claim(scope,"task","worker",java.time.Duration.ofMinutes(5),submitted.revision());
            var checkpoint=json.valueToTree(Map.of("sdkSessionId","session","checkpointOrdinal",1,"modelOrdinal",0,"eventOrdinal",0,
                "inputSnapshotRef",contract.inputSnapshotRef(),"workspaceRef",contract.workspaceRef(),"contractHash",submitted.contractSha256(),
                "codingProgress",Map.of("schemaVersion",1,"state",Map.of("modelCall",0,"callsSinceProgress",0)),
                "messages",List.of(Map.of("role","user","content",List.of(Map.of("type","text","text","Authorized objective"))))));
            tasks.saveSdkCheckpoint(scope,"task",lease,checkpoint);
            tasks.reserve(scope,"task",lease,tasks.get(scope,"task").revision(),"model-1","a".repeat(64),
                com.chatchat.agents.runtime.coding.CodingJournal.OperationKind.MODEL,
                new com.chatchat.common.runtime.coding.CodingTaskOutcome.Counters(1,2048,0,0,0,0,0,"0"));
            tasks.startIntent(scope,"task",lease,tasks.get(scope,"task").revision(),"model-1","runtime-model:model-1");
            assertThat(tasks.sdkCheckpointAfterLocalModelStops(scope,"task",java.util.Set.of("model-1"))).isNull();
            String proof=receipts.commitLocalTermination(contract,"model-1",300000,"MODEL_REQUEST_TIMEOUT");
            assertThat(receipts.response(scope,"task","model-1")).isEmpty();
            assertThat(receipts.telemetry(contract,"model-1",1)).isEmpty();
            assertThat(receipts.confirmsStopped(scope,"other-task","model-1","runtime-model:model-1",proof)).isFalse();
            tasks.reconcileStopped(scope,"task",lease,tasks.get(scope,"task").revision(),"model-1",proof);
            assertThat(tasks.sdkCheckpoint(scope,"task")).isNull();
            assertThat(tasks.sdkCheckpointAfterLocalModelStops(scope,"task",java.util.Set.of())).isNull();
            var restored=tasks.sdkCheckpointAfterLocalModelStops(scope,"task",java.util.Set.of("model-1"));
            assertThat(restored.path("modelOrdinal").asInt()).isEqualTo(1);
            assertThat(restored.path("codingProgress")).isEqualTo(checkpoint.path("codingProgress"));
            assertThat(restored.path("messages").toString()).contains("Authorized objective","unknown provider usage remains reserved");
            var view=tasks.budgetSnapshot(scope,"task",500,new com.chatchat.agents.runtime.coding.CodingCompletionBudget(.15,.35));
            assertThat(view).containsEntry("remainingTokens",7952L).containsEntry("remainingModelCalls",3L);
            assertThat((java.util.List<?>)view.get("pendingOperations")).isEmpty();
            assertThat((java.util.List<?>)view.get("unknownUsageOperations")).hasSize(1);
            var next=tasks.recoveryFence(scope,"task",lease,tasks.get(scope,"task").revision());
            assertThat(next.fencingToken()).isEqualTo(lease.fencingToken()+1);
            assertThatThrownBy(()->tasks.recoveryFence(scope,"task",lease,tasks.get(scope,"task").revision())).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->tasks.startIntent(scope,"task",next,tasks.get(scope,"task").revision(),"model-1","runtime-model:model-1"))
                .isInstanceOf(RuntimeException.class);
            time.set(contract.budget().deadlineAt().plusSeconds(1));
            assertThatThrownBy(()->tasks.recoveryFence(scope,"task",next,tasks.get(scope,"task").revision())).isInstanceOf(RuntimeException.class);
            return null;
        }); } finally {timer.shutdownNow();}
    }

    @Test void durableReceiptProvesOnlyTheBoundModelOperation() {
        var data = new DriverManagerDataSource("jdbc:h2:mem:model-receipt-" + UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(data);
        var evidence = new JdbcRuntimeEvidenceStore(new JdbcTemplate(data), new ObjectMapper(),
            new DataSourceTransactionManager(data));
        var scope = new KernelDataScope("tenant", "owner", null, null, "run", null, Map.of());
        var payload = Map.<String, Object>of("fixture", "authorized input");
        evidence.register(new EvidenceRecord(null, "input", scope, "CODING_INPUT_SNAPSHOT", "test",
            evidence.contentHash(payload), payload, null, System.currentTimeMillis(), Map.of()),
            new EvidenceLineage(null, "input", scope, List.of(), "task", "test", null, Map.of()));
        var contract = contract(scope);
        var receipts = new ClineModelReceiptStore(evidence);
        Map<String, Object> response = Map.of("text", "private response sentinel", "toolCalls", List.of(),
            "usage", Map.of("inputTokens", 4, "outputTokens", 2), "requestId", "provider-request",
            "modelInfo",Map.of("selectedModel","configured-alias","providerModel","actual-fallback-model","protocol","openai",
                "startedAt","2026-10-19T01:00:00Z","finishedAt","2026-10-19T01:00:01Z","durationMs",1000,"apiKey","private key sentinel"));
        String ref = receipts.commit(contract, "model-1", "runtime-model:model-1", response);
        assertThat(receipts.response(scope, "task", "model-1")).contains(response);
        var telemetry=new ClineModelReceiptStore(new JdbcRuntimeEvidenceStore(new JdbcTemplate(data),new ObjectMapper(),
            new DataSourceTransactionManager(data))).telemetry(contract,"model-1",3).orElseThrow();
        assertThat(telemetry.providerModel()).isEqualTo("actual-fallback-model");
        assertThat(telemetry.attemptId()).isEqualTo("task:fence-3");
        assertThat(telemetry.usage()).containsEntry("inputTokens",4L).containsEntry("outputTokens",2L);
        assertThat(telemetry.durationMs()).isEqualTo(1000);
        assertThat(new ObjectMapper().valueToTree(telemetry).toString()).doesNotContain("private response sentinel","private key sentinel","toolCalls","requestId","apiKey");
        assertThat(receipts.telemetry(contract,"model-2",3)).isEmpty();
        receipts.commit(contract,"model-1000001","runtime-model:model-1000001",Map.of(
            "providerDispatched",false,"error","Local preflight rejected the request",
            "usage",Map.of("inputTokens",0,"outputTokens",0)));
        var rejected=receipts.telemetry(contract,"model-1000001",3).orElseThrow();
        assertThat(rejected.modelCall()).isEqualTo(1000001L);
        assertThat(rejected.state()).isEqualTo("REJECTED_LOCALLY");
        assertThat(rejected.providerDispatched()).isFalse();
        assertThat(rejected.error()).contains("Local preflight");
        assertThat(receipts.telemetry(contract(new KernelDataScope("other","owner",null,null,"run",null,Map.of())),"model-1",3)).isEmpty();
        assertThat(receipts.confirmsStopped(scope, "task", "model-1", "runtime-model:model-1", ref)).isTrue();
        assertThat(new ClineModelReceiptStore(new JdbcRuntimeEvidenceStore(new JdbcTemplate(data),
            new ObjectMapper(), new DataSourceTransactionManager(data)))
            .confirmsStopped(scope, "task", "model-1", "runtime-model:model-1", ref)).isTrue();
        assertThat(receipts.confirmsStopped(scope, "task", "model-1", "runtime-model:other", ref)).isFalse();
        assertThat(receipts.confirmsStopped(new KernelDataScope("other", "owner", null, null, "run", null, Map.of()),
            "task", "model-1", "runtime-model:model-1", ref)).isFalse();
    }

    private static CodingTaskContract contract(KernelDataScope scope) {
        return new CodingTaskContract(CodingProtocol.VERSION, "task", scope,
            new CodingTaskContract.Objective("calculate", List.of("report"), List.of("validation:1")),
            "runtime-evidence:input", List.of(), "workspace:1",
            new CodingTaskContract.Authorization("decision:1", List.of(), List.of(), List.of(), List.of(),
                CodingTaskContract.NetworkPolicy.NONE, List.of(), List.of()),
            new CodingTaskContract.Budget("budget:1", Instant.now().plusSeconds(180).truncatedTo(ChronoUnit.MILLIS),
                1, 2048, 0, 0, "1", "USD", 0, 0, 4096), "env:1", "validation:1",
            new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.JSON_LINES,
                "schema:1", "schema:2", List.of("rows.jsonl"), List.of("application/x-ndjson"),
                1, 4096, List.of(CodingTaskContract.RequiredEvidence.values())));
    }
}
