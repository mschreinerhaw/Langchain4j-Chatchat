package com.chatchat.api.runtime.coding.nativecline;

import com.chatchat.api.assets.SiloCodingWorkspaceService;
import com.chatchat.api.assets.UserAssetService;
import com.chatchat.api.runtime.coding.knowledge.KnowledgeDisclosureGuard;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeBasicReportTest {
    private final ObjectMapper json=new ObjectMapper();
    private final UserAssetService assets=mock(UserAssetService.class);
    private final NativeSiloTaskService capture=mock(NativeSiloTaskService.class);
    private final SiloCodingWorkspaceService workspace=mock(SiloCodingWorkspaceService.class);
    private final KnowledgeDisclosureGuard guard=mock(KnowledgeDisclosureGuard.class);
    private final NativeBasicReport reports=new NativeBasicReport(assets,capture,workspace,guard,json);
    private final KernelDataScope scope=new KernelDataScope("tenant","owner","request",null,"run",null,Map.of());
    private final UserAssetService.Owner owner=UserAssetService.Owner.of(scope);
    private final CodingTaskContract task=mock(CodingTaskContract.class);
    private final CodingTaskOutcome outcome=mock(CodingTaskOutcome.class);
    private final Map<String,UserAssetService.File> files=new HashMap<>();
    private final Map<String,byte[]> contents=new HashMap<>();

    @BeforeEach void setup() throws Exception {
        when(task.scope()).thenReturn(scope);
        when(task.taskId()).thenReturn("task");
        when(task.runtimeContext()).thenReturn(Map.of(CodingRuntimeContext.NATIVE_TASK_KIND,CodingRuntimeContext.DATA_ANALYSIS,
            CodingRuntimeContext.DELIVERY_RULES,NativeDeliveryPolicy.freeze(List.of("REPORT","RESULT"),Map.of())));
        var authorization=mock(CodingTaskContract.Authorization.class);
        when(authorization.networkPolicy()).thenReturn(CodingTaskContract.NetworkPolicy.NONE);
        when(authorization.allowedDataRefs()).thenReturn(List.of("frozen"));
        when(task.authorization()).thenReturn(authorization);
        var output=mock(CodingTaskContract.OutputContract.class);
        when(output.maxResultBytes()).thenReturn(100000L);
        when(task.outputContract()).thenReturn(output);
        when(outcome.reasonCode()).thenReturn(CodingErrorCode.BUDGET_EXCEEDED);
        when(outcome.status()).thenReturn(CodingTaskOutcome.Status.BUDGET_EXCEEDED);
        when(outcome.endedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(outcome.executionControl()).thenReturn(new CodingTaskOutcome.ExecutionControl(null,null,true,false));
        byte[] data="{}\n".getBytes(StandardCharsets.UTF_8);
        String dataHash=UserAssetService.hash(data);
        String dataPath="input/data/0/source.jsonl";
        var inputs=List.of(new SiloCodingWorkspaceService.Input("frozen",dataPath,data.length,dataHash,"version"));
        when(workspace.get(scope,"task")).thenReturn(new SiloCodingWorkspaceService.State("task","run","tenant",
            "owner","epoch","snapshot","FAILED",inputs,null,null,List.of()));
        var facts=Map.of("schemaVersion","runtime_basic_report_facts.v1","objective","Check a bounded snapshot",
            "datasets",List.of(Map.of("path",dataPath,"datasetReference","dataset-reference","rowCount",1,"bytes",data.length,
                "sha256",dataHash,"inputStatus","DELIVERED_SNAPSHOT","fieldInventoryComplete",true,
                "basicFacts",Map.of("template","published-template","snapshotPersistedAt","2026-01-01T00:00:00Z",
                    "fields",Map.of("measure",Map.of("observedTypes",List.of("number"),"nonNull",1,"nullOrMissing",0,
                        "numeric",Map.of("sum","7","min","7","max","7")))))));
        var storedFacts=add("facts","DATASET","facts.json","application/json","runtime-basic-facts:",json.writeValueAsBytes(facts),true);
        var inventory=Map.of("schemaVersion","native-data-supply-inventory.v1","resultSchema",Map.of("type","object"),
            "basicReport",Map.of("version",1,"assetId","facts","sha256",storedFacts.sha256()));
        var snapshot=add("inventory","DATASET","inventory.json","application/json","native-supply-inventory:",json.writeValueAsBytes(inventory),true);
        when(capture.supplyInventory(task)).thenReturn(Optional.of(new NativeSiloTaskService.SupplyInventory("inventory",snapshot.sha256())));
        when(assets.list(owner,UserAssetService.Kind.LOG,"task",100)).thenAnswer(i->files.values().stream()
            .filter(f->"LOG".equals(f.kind())).toList());
        when(assets.get(eq(owner),anyString())).thenAnswer(i->files.get(i.getArgument(1)));
        when(assets.readTask(eq(scope),eq("task"),anyString())).thenAnswer(i->contents.get(i.getArgument(2)));
        when(assets.store(eq(owner),any(),eq("task"),eq("run"),anyString(),anyString(),anyString(),any(byte[].class)))
            .thenAnswer(i->add(i.getArgument(4),((UserAssetService.Kind)i.getArgument(1)).name(),i.getArgument(5),
                i.getArgument(6),i.getArgument(4),i.getArgument(7),false));
    }

    @Test void verifiedFailureProducesSealedArtifactsAndIndependentReceipt() {
        var report=reports.publish(task,outcome,()->true).orElseThrow();
        assertEquals("PARTIAL",report.row().get("analysisStatus"));
        assertTrue(report.row().get("reportMarkdown").toString().contains(" | 7 | 7 | 7 |"));
        assertEquals("snapshot",report.proof().get("inputSnapshotVersion"));
        assertEquals("BUDGET_EXCEEDED",report.proof().get("reasonCode"));
        assertEquals(report.files(),reports.read(task,outcome).orElseThrow().files());
        assertEquals(report.files(),reports.publish(task,outcome,()->true).orElseThrow().files());
        assertEquals(5,files.size());
    }

    @Test void cancellationAndUnknownExecutionNeverPublish() {
        assertThrows(java.util.concurrent.CancellationException.class,()->reports.publish(task,outcome,()->false));
        when(outcome.executionControl()).thenReturn(new CodingTaskOutcome.ExecutionControl(null,null,false,true));
        assertTrue(reports.publish(task,outcome,()->true).isEmpty());
        when(outcome.status()).thenReturn(CodingTaskOutcome.Status.CANCELLED);
        assertTrue(reports.publish(task,outcome,()->true).isEmpty());
        assertEquals(2,files.size());
    }

    @Test void sourceAbsentFromPinnedSnapshotIsRejected() {
        when(workspace.get(scope,"task")).thenReturn(new SiloCodingWorkspaceService.State("task","run","tenant","owner",
            "epoch","snapshot","FAILED",List.of(),null,null,List.of()));
        assertThrows(SecurityException.class,()->reports.publish(task,outcome,()->true));
        assertEquals(2,files.size());
    }

    private UserAssetService.File add(String id,String kind,String name,String type,String source,byte[] data,boolean bindHash) {
        String sha=UserAssetService.hash(data);
        var file=new UserAssetService.File(id,kind,name,type,sha,data.length,"AVAILABLE","task","run",
            bindHash?source+sha:source,0);
        files.put(id,file);
        contents.put(id,data);
        return file;
    }
}
