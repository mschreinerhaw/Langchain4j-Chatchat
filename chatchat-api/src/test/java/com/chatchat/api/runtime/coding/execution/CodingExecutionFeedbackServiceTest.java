package com.chatchat.api.runtime.coding.execution;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort.OperationView;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Policy unit tests; genuine cross-process/Sandbox acceptance is reported separately. */
class CodingExecutionFeedbackServiceTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final DurableCodingTaskService tasks=mock(DurableCodingTaskService.class);
    final CodingPythonExecutionBridge python=mock(CodingPythonExecutionBridge.class);
    final Map<String,JsonNode> state=new HashMap<>();
    final KernelDataScope scope=new KernelDataScope("tenant","owner",null,null,"trace",null,Map.of());
    final CodingJournal.Lease lease=new CodingJournal.Lease("worker",Instant.now().plusSeconds(60),1);
    CodingExecutionFeedbackService service;
    CodingTaskContract contract;
    @BeforeEach void setup(){
        service=new CodingExecutionFeedbackService(tasks,python,json,2);
        contract=new CodingTaskContract(CodingProtocol.VERSION,"task",scope,
            new CodingTaskContract.Objective("Analyze fixture",List.of("rows.jsonl"),List.of("plan")),"input",List.of(),"workspace",
            new CodingTaskContract.Authorization("decision",List.of("python_execute"),List.of("input"),List.of(),List.of(),
                CodingTaskContract.NetworkPolicy.NONE,List.of(),List.of(CodingTaskContract.SideEffect.CODE_EXECUTION)),
            new CodingTaskContract.Budget("budget",Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),10,10000,10,5,"10","USD",1000,1024,10000),
            "environment","plan",new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.JSON_LINES,"schema","checks",
                List.of("rows.jsonl"),List.of("application/x-ndjson"),10,10000,List.of(CodingTaskContract.RequiredEvidence.values())));
        var outcome=mock(CodingTaskOutcome.class);when(outcome.status()).thenReturn(CodingTaskOutcome.Status.RUNNING);
        when(outcome.executionControl()).thenReturn(new CodingTaskOutcome.ExecutionControl(null,null,false,false));
        var zero=new CodingTaskOutcome.Counters(0,0,0,0,0,0,0,"0");
        when(outcome.usage()).thenReturn(new CodingTaskOutcome.Usage("budget","USD",zero,zero,true));
        when(tasks.get(scope,"task")).thenReturn(outcome);
        when(tasks.startedCodeOperations(scope,"task")).thenReturn(List.of("operation"));
        when(tasks.privateState(eq(scope),eq("task"),anyString())).thenAnswer(c->state.get(c.getArgument(2)));
        when(tasks.recordExecutionFeedback(eq(scope),eq("task"),eq(lease),any())).thenAnswer(c->{
            ExecutionFeedback feedback=c.getArgument(3);state.put("feedback:"+feedback.executionId(),json.valueToTree(feedback));return true;
        });
        when(python.executionReceipt(scope,"task","operation"))
            .thenReturn("{\"startedAt\":\"2026-10-22T00:00:00Z\",\"finishedAt\":\"2026-10-22T00:00:01Z\"}");
    }
    OperationView operation(String status){return new OperationView("operation",status,"container",1,false,true,
        "SUCCEEDED".equals(status)?0:1,"a".repeat(64),"sha256:"+"b".repeat(64),"c".repeat(64),null);}
    @Test void actualProjectErrorFramesSurviveTheExistingRepairContract() throws Exception {
        var project=new PythonCodeProject.Project("pkg/main.py",List.of(new PythonCodeProject.File("pkg/main.py","raise KeyError('private')\n")));
        var context=Map.<String,Object>of("schemaVersion","runtime_os.coding.context.v1","consistency","TASK_SNAPSHOT","codeProject",PythonCodeProject.context(project,p->{try{return json.writeValueAsString(p);}catch(Exception e){throw new RuntimeException(e);}}));
        contract=new CodingTaskContract(contract.schemaVersion(),contract.taskId(),contract.scope(),contract.objective(),contract.inputSnapshotRef(),contract.metadataRefs(),contract.workspaceRef(),contract.authorization(),contract.budget(),contract.environmentRef(),contract.validationPlanRef(),contract.outputContract(),context);
        String source=PythonCodeProject.template().replace(PythonCodeProject.TOKEN,Base64.getEncoder().encodeToString(json.writeValueAsBytes(project)));
        var artifacts=mock(com.chatchat.api.runtime.coding.cline.CodingCodeArtifactStore.class);service.setCodeArtifacts(artifacts);
        when(artifacts.find(scope,"task","operation")).thenReturn(Optional.of(Map.of("content",source)));
        when(python.diagnosticRecord(scope,"task","operation")).thenReturn("{\"exceptionType\":\"KeyError\",\"line\":1,\"stack\":[{\"file\":\"project/pkg/main.py\",\"line\":1},{\"file\":\"project/other.py\",\"line\":9}],\"message\":\"private\"}");
        var feedback=service.finish(contract,lease,operation("FAILED"),Map.of());
        assertThat(feedback.error().stack()).containsExactly(new ExecutionFeedback.Frame("project/pkg/main.py",1,null));
        assertThat(feedback.repair().allowed()).isTrue();assertThat(json.writeValueAsString(feedback)).doesNotContain("private","other.py");
    }
    @Test void safeFailureSurvivesIntoFeedbackAndRepeatedPollingDoesNotDownloadAgain(){
        when(python.diagnosticRecord(scope,"task","operation")).thenReturn("""
            {"exceptionType":"TypeError","diagnosticCode":"VALUE_NOT_SUBSCRIPTABLE","line":430,"cpuTimeMs":75,"maxRssBytes":1048576,
             "message":"secret customer value","stack":[{"file":"main.py","line":430},{"file":"private-name","line":9}]}
            """);
        var feedback=service.finish(contract,lease,operation("FAILED"),Map.of());
        assertThat(feedback.error().category()).isEqualTo("PROGRAM");
        assertThat(feedback.error().stack()).containsExactly(new ExecutionFeedback.Frame("main.py",430,null));
        assertThat(feedback.durationMs()).isEqualTo(1000);
        assertThat(feedback.cpuTimeMs()).isEqualTo(75);
        assertThat(feedback.maxRssBytes()).isEqualTo(1048576);
        assertThat(feedback.repair().allowed()).isTrue();
        assertThat(service.response(feedback)).containsKeys("diagnostics","stdoutBytes","executionFeedback")
            .containsEntry("diagnosticPolicy","METADATA_ONLY");
        assertThat(json.valueToTree(feedback).toString()).doesNotContain("secret customer", "private-name");
        assertThat(service.finish(contract,lease,operation("FAILED"),Map.of())).isEqualTo(feedback);
        verify(python,times(1)).diagnosticRecord(scope,"task","operation");
        verify(tasks,times(1)).recordExecutionFeedback(eq(scope),eq("task"),eq(lease),any());
    }
    @Test void knownTechnicalErrorsAreClassifiedWithoutInstallingOrWideningPermissions(){
        for(var entry:Map.of("SyntaxError","SYNTAX", "KeyError","SCHEMA_ACCESS", "ModuleNotFoundError","DEPENDENCY_UNAVAILABLE",
                "PermissionError","AUTHORIZATION", "MemoryError","RESOURCE").entrySet()){
            state.clear();when(python.diagnosticRecord(scope,"task","operation"))
                .thenReturn("{\"exceptionType\":\""+entry.getKey()+"\",\"line\":4}");
            var feedback=service.finish(contract,lease,operation("FAILED"),Map.of());
            assertThat(feedback.error().category()).isEqualTo(entry.getValue());
            assertThat(feedback.repair().allowed()).isEqualTo(!Set.of("PermissionError","MemoryError").contains(entry.getKey()));
            if(entry.getKey().equals("ModuleNotFoundError"))assertThat(feedback.repair().guidance()).contains("prohibited");
        }
    }
    @Test void zeroExitWithoutValidatedArtifactIsAnOutputFailureAndRepairLimitStopsAnotherAttempt(){
        var prior=json.valueToTree(service.finish(contract,lease,operation("SUCCEEDED"),Map.of("outputStatus","INVALID_RESULT_SCHEMA","resultAvailable",false)));
        ((com.fasterxml.jackson.databind.node.ObjectNode)prior).put("executionId","prior-2");
        ((com.fasterxml.jackson.databind.node.ObjectNode)prior.path("repair")).put("attemptsUsed",1);
        state.clear();state.put("feedback:prior-2",prior);
        when(tasks.startedCodeOperations(scope,"task")).thenReturn(List.of("prior-1","prior-2","operation"));
        var feedback=service.finish(contract,lease,operation("SUCCEEDED"),Map.of("outputStatus","INVALID_RESULT_SCHEMA","resultAvailable",false));
        assertThat(feedback.error().category()).isEqualTo("OUTPUT_CONTRACT");assertThat(feedback.exitCode()).isZero();
        assertThat(feedback.repair().allowed()).isFalse();assertThat(feedback.repair().reason()).isEqualTo("REPAIR_LIMIT_REACHED");
        assertThatThrownBy(()->service.admit(contract,lease,"new","d".repeat(64))).hasMessageContaining("AUTO_REPAIR_DENIED");
        service.admit(contract,lease,"operation","a".repeat(64)); // Same operation must use existing idempotency.
    }
    @Test void unknownOrTimedOutExecutionNeverAuthorizesAutomaticReplay(){
        for(String status:List.of("FAILED","TIMED_OUT","CANCELLED")){
            state.clear();var feedback=service.finish(contract,lease,operation(status),Map.of());
            assertThat(feedback.repair().allowed()).isFalse();assertThat(feedback.repair().action()).isEqualTo("STOP");
        }
    }
    @Test void successfulExecutionRequiresIndependentResultAdmission(){
        var feedback=service.finish(contract,lease,operation("SUCCEEDED"),Map.of("resultAvailable",true,
            "validationStatus","INCONCLUSIVE","resultDatasetRef","result","validationRecordRef","validation","evidenceBundleRef","bundle"));
        assertThat(feedback.stage()).isEqualTo("VALIDATED");assertThat(feedback.error()).isNull();
        assertThat(feedback.businessValidationPassed()).isFalse();assertThat(feedback.repair().action()).isEqualTo("COMPLETE");
    }
    @Test void invalidVisualReportTriggersBoundedRepairInTheSameTaskWithoutChangingBusinessChecks(){
        var feedback=service.finish(contract,lease,operation("SUCCEEDED"),Map.of("outputStatus","VISUAL_REPORT_INVALID","resultAvailable",false));
        assertThat(feedback.error().category()).isEqualTo("OUTPUT_CONTRACT");
        assertThat(feedback.error().diagnosticCode()).isEqualTo("VISUAL_REPORT_INVALID");
        assertThat(feedback.repair().action()).isEqualTo("REPAIR_IN_CURRENT_CONTEXT");
        assertThat(feedback.repair().guidance()).contains("artifact_read","UNRESOLVED","never relabel");
        assertThat(feedback.exitCode()).isZero();assertThat(service.response(feedback)).containsEntry("outputStatus","VISUAL_REPORT_INVALID");
    }
    @Test void successfulRecalculationDoesNotConsumeTheFailureRepairAllowance(){
        service.finish(contract,lease,operation("SUCCEEDED"),Map.of("resultAvailable",true,"validationStatus","INCONCLUSIVE"));
        when(tasks.startedCodeOperations(scope,"task")).thenReturn(List.of("operation","recalculation"));
        var next=new OperationView("recalculation","SUCCEEDED","container-2",1,false,true,0,"b".repeat(64),"sha256:"+"b".repeat(64),"c".repeat(64),null);
        var feedback=service.finish(contract,lease,next,Map.of("resultAvailable",true,"validationStatus","INCONCLUSIVE"));
        assertThat(feedback.attemptId()).endsWith("execution-2");assertThat(feedback.repair().attemptsUsed()).isZero();
    }
}
