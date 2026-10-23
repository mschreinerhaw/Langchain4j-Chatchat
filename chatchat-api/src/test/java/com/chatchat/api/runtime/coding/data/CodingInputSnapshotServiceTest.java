package com.chatchat.api.runtime.coding.data;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.EnumSet;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodingInputSnapshotServiceTest {
    @Test void sortedAuthorizationDoesNotChangeManifestOrderAndStillRejectsMissingOrExtraFiles() {
        var db=new DriverManagerDataSource("jdbc:h2:mem:sorted-input-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(db);
        var json=new ObjectMapper().findAndRegisterModules();
        var evidence=new JdbcRuntimeEvidenceStore(new JdbcTemplate(db),json,new DataSourceTransactionManager(db));
        var access=new CodingInvocationContext();
        var inputs=new CodingInputSnapshotService(evidence,mock(McpPythonControlPlanePort.class),access,json);
        var scope=new KernelDataScope("tenant","owner",null,null,"run",null,Map.of());
        String root="c"+"f".repeat(63), first="c"+"a".repeat(63), second="c"+"5".repeat(63), hash="a".repeat(64);
        var chunks=List.of(new CodingInputSnapshotService.Chunk(first,"input-00000.jsonl",hash,1,10),
            new CodingInputSnapshotService.Chunk(second,"input-00001.jsonl",hash,1,10));
        var payload=new java.util.LinkedHashMap<String,Object>();
        payload.put("taskId","task");payload.put("decisionRef","decision:1");payload.put("workspaceRef","workspace:1");
        payload.put("fileId",root);payload.put("fileName","manifest.json");payload.put("sha256",hash);
        payload.put("sourceDatasetRef","source");payload.put("rowCount",2);payload.put("size",20);
        payload.put("chunks",json.convertValue(chunks,List.class));
        String id="coding:input:"+root.substring(1);
        evidence.register(new com.chatchat.common.runtime.evidence.EvidenceRecord(null,id,scope,"CODING_INPUT_SNAPSHOT","test",
            evidence.contentHash(payload),payload,null,System.currentTimeMillis(),Map.of()),
            new com.chatchat.common.runtime.evidence.EvidenceLineage(null,id,scope,List.of(),"task","test",null,Map.of()));
        var input=new CodingInputSnapshotService.Snapshot("runtime-evidence:"+id,root,"manifest.json",hash,2,20,"source",chunks);
        var base=contract(scope,input);
        for(var refs:List.of(List.of(root,first,second),List.of(first,second,root),List.of(second,root,first),
                List.of(root,second,first),List.of(first,root,second),List.of(second,first,root))) {
            var c=withRefs(base,refs);
            assertThat(access.as(scope,EnumSet.of(CodingTaskAccess.Action.READ),()->inputs.requireAuthorized(c))).isEqualTo(input);
        }
        for(var refs:List.of(List.of(root,first),List.of(first,second),List.of(root,first,second,"unapproved-file"))) {
            var c=withRefs(base,refs);
            assertThatThrownBy(()->access.as(scope,EnumSet.of(CodingTaskAccess.Action.READ),()->inputs.requireAuthorized(c)))
                .isInstanceOf(SecurityException.class);
        }
    }

    private static CodingTaskContract withRefs(CodingTaskContract c,List<String> refs) {
        return new CodingTaskContract(c.schemaVersion(),c.taskId(),c.scope(),c.objective(),c.inputSnapshotRef(),c.metadataRefs(),c.workspaceRef(),
            new CodingTaskContract.Authorization(c.authorization().decisionRef(),c.authorization().allowedTools(),refs,List.of(),List.of(),
                CodingTaskContract.NetworkPolicy.NONE,List.of(),c.authorization().allowedSideEffects()),
            c.budget(),c.environmentRef(),c.validationPlanRef(),c.outputContract());
    }
    @Test void verifiedZeroRowsHaveAnUploadableEmptyJsonlSnapshotWithoutFabricatedRecords() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:empty-snapshot-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(source);
        var json=new ObjectMapper();
        var evidence=new JdbcRuntimeEvidenceStore(new JdbcTemplate(source),json,new DataSourceTransactionManager(source));
        var python=mock(McpPythonControlPlanePort.class);
        when(python.uploadDataFile(anyString(),anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(call->{
            byte[] bytes=call.getArgument(5);assertThat(bytes).containsExactly((byte)'\n');
            return new McpPythonControlPlanePort.DataFileResult(call.getArgument(2),"stored","mounted",bytes.length,call.getArgument(4),"AVAILABLE");
        });
        var access=new CodingInvocationContext();
        var snapshots=new CodingInputSnapshotService(evidence,python,access,json);
        var scope=new KernelDataScope("tenant","owner",null,null,"run",null,Map.of());
        var dataset=new Dataset("empty",Map.of("sourceVerifiedEmpty",true),List.of());
        var snapshot=access.as(scope,EnumSet.of(CodingTaskAccess.Action.SUBMIT),()->snapshots.capture(scope,"empty-task",
            List.of(dataset),"empty","decision","workspace",10,4096));
        assertThat(snapshot.rowCount()).isZero();assertThat(snapshot.size()).isEqualTo(1);
    }
    @Test void capturesOnlyDeliveredDatasetAndBindsContractToArchivedInput() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:coding-input-" + UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(source);
        var json = new ObjectMapper().findAndRegisterModules();
        var evidence = new JdbcRuntimeEvidenceStore(new JdbcTemplate(source), json,
            new DataSourceTransactionManager(source));
        var python = mock(McpPythonControlPlanePort.class);
        when(python.uploadDataFile(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
            .thenAnswer(call -> new McpPythonControlPlanePort.DataFileResult(call.getArgument(2),
                "stored", "mounted", ((byte[]) call.getArgument(5)).length,
                call.getArgument(4), "AVAILABLE"));
        var access = new CodingInvocationContext();
        var snapshots = new CodingInputSnapshotService(evidence, python, access, json);
        var scope = new KernelDataScope("tenant", "owner", null, null, "run", null, Map.of());
        var delivered = new Dataset("template#call", Map.of("inputVersions",List.of(Map.of(
                "datasetReference","template#call","contentSha256","a".repeat(64),"rowCount",2L,
                "fieldSchema",Map.of("tradeDate",List.of("STRING")),
                "fieldDefinitions",Map.of("tradeDate",Map.of("type","string","description","Observation date","default","PRIVATE_DEFAULT","rawData","PRIVATE_ROW")),
                "sourceDefinition",Map.of("displayName","Completed observations","rawBody","PRIVATE_SOURCE"),
                "secretRecords","RAW_SHOULD_NOT_ENTER_DESCRIPTOR"))),
            List.of(Map.of("tradeDate", "2026-10-14", "amount", "1.25"),
                Map.of("tradeDate", "2026-10-15")));
        var snapshot = access.as(scope, EnumSet.of(CodingTaskAccess.Action.SUBMIT), () ->
            snapshots.capture(scope, "task", List.of(delivered), "template#call", "decision:1",
                "workspace:1", 10, 4096));
        assertThat(snapshot.rowCount()).isEqualTo(2);
        assertThat(snapshot.reference()).startsWith("runtime-evidence:coding:input:");
        var descriptor=access.as(scope,EnumSet.of(CodingTaskAccess.Action.READ),()->snapshots.describe(scope,"task",snapshot));
        assertThat(descriptor)
            .containsEntry("format","JSON_LINES").containsEntry("encoding","UTF-8").containsEntry("rowCount",2L)
            .doesNotContainKeys("records","rows");
        @SuppressWarnings("unchecked") var quality=(Map<String,Object>)descriptor.get("qualitySummary");
        assertThat(quality).containsEntry("coverage","FULL_SNAPSHOT_STRUCTURAL_ONLY");
        @SuppressWarnings("unchecked") var amount=(Map<String,Object>)((Map<?,?>)quality.get("fields")).get("amount");
        assertThat(((Number)amount.get("missingCount")).longValue()).isEqualTo(1L);
        assertThat(((Number)amount.get("nullCount")).longValue()).isZero();
        assertThat(descriptor.toString()).doesNotContain("2026-10-14","1.25");
        assertThat(descriptor.toString()).contains("template#call","fieldSchema").doesNotContain("RAW_SHOULD_NOT_ENTER_DESCRIPTOR");
        assertThat(descriptor.toString()).contains("fieldDefinitions","Observation date")
            .contains("sourceDefinition","Completed observations")
            .doesNotContain("PRIVATE_DEFAULT","PRIVATE_ROW","PRIVATE_SOURCE");
        var contract = contract(scope, snapshot);
        assertThat(access.as(scope, EnumSet.of(CodingTaskAccess.Action.READ), () ->
            snapshots.requireAuthorized(contract))).isEqualTo(snapshot);
        assertThat(evidence.find(scope, snapshot.reference().substring("runtime-evidence:".length()))
            .orElseThrow().payload()).containsEntry("sha256", snapshot.sha256())
            .containsEntry("rowCount", 2);
        assertThatThrownBy(() -> access.as(scope, EnumSet.of(CodingTaskAccess.Action.SUBMIT), () ->
            snapshots.capture(scope, "task", List.of(delivered), "unapproved", "decision:1",
                "workspace:1", 10, 4096))).isInstanceOf(SecurityException.class);
        KernelDataScope other = new KernelDataScope("other", "owner", null, null, "run", null, Map.of());
        assertThatThrownBy(() -> access.as(other, EnumSet.of(CodingTaskAccess.Action.READ), () ->
            snapshots.requireAuthorized(contract))).isInstanceOf(RuntimeException.class);
        verify(python, times(1)).uploadDataFile(anyString(), anyString(), anyString(), anyString(),
            eq(snapshot.sha256()), any());
    }

    private static CodingTaskContract contract(KernelDataScope scope, CodingInputSnapshotService.Snapshot input) {
        return new CodingTaskContract(CodingProtocol.VERSION, "task", scope,
            new CodingTaskContract.Objective("calculate", List.of("result"), List.of("rule:1")),
            input.reference(), List.of(), "workspace:1",
            new CodingTaskContract.Authorization("decision:1", List.of("python_execute"),
                List.of(input.fileId()), List.of(), List.of(), CodingTaskContract.NetworkPolicy.NONE,
                List.of(), List.of(CodingTaskContract.SideEffect.CODE_EXECUTION)),
            new CodingTaskContract.Budget("budget:1", Instant.now().plusSeconds(180)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                1, 2048, 1, 1, "1", "USD", 1000, 256 * 1024 * 1024, 10000),
            "env:1", "rule:1",
            new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.JSON_LINES,
                "schema:1", "schema:2", List.of("rows.jsonl"), List.of("application/x-ndjson"),
                100, 10000, List.of(CodingTaskContract.RequiredEvidence.values())));
    }
}
