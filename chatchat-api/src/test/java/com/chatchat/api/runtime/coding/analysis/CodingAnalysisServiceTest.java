package com.chatchat.api.runtime.coding.analysis;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.governance.AnalysisCoverageCoordinator;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.CodingParentModels;
import com.chatchat.api.runtime.coding.data.CodingInputSnapshotService;
import com.chatchat.api.runtime.coding.evidence.CodingEvidenceCollector;
import com.chatchat.api.runtime.coding.evidence.CodingValidationPlanService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.evidence.RuntimeExecutionCheckpointPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodingAnalysisServiceTest {
    @Test void largeOriginalMessagesAreDeliveredAsSnapshotsInsteadOfAnOversizedCodingInstruction(){
        var service=spy(service());doReturn(Map.of("status","COMPLETED")).when(service).executeObserved(any(),anyMap(),anyMap(),any());
        var original=context("tenant");String message="Analyze these supplied observations\n"+"observation 42\n".repeat(3000);
        var parent=new AnalysisCoverageCoordinator.Request(original.model(),message,null,original.runtimeAttributes(),original.metadata(),
            ()->false,()->{},original.isolationScope(),null);
        var delivered=new LinkedHashMap<String,Dataset>(sources());service.executePrimaryAnalysis(parent,delivered,event->{});
        String ref="runtime-user-message:"+parent.isolationScope().runId();
        assertThat(delivered.get(ref).records()).singleElement().satisfies(row->assertThat(row).containsEntry("text",message));
        verify(service).executeObserved(same(parent),argThat(request->request.get("instruction").toString().length()<16000
            &&!request.get("instruction").toString().contains(message)&&((List<?>)request.get("datasetReferences")).contains(ref)),same(delivered),any());
    }
    @Test void layoutPreferencesReachTheSameCodingContractWithoutAnAdditionalModelCall(){
        prepare(outcome(CodingTaskOutcome.Status.FAILED,CodingTaskOutcome.ValidationStatus.NOT_RUN));
        when(inputs.captureConfigured(any(),anyString(),anyList(),anyString(),anyString(),anyString(),anyLong(),anyLong(),any()))
            .thenReturn(new CodingInputSnapshotService.Snapshot("runtime-evidence:input","c"+"a".repeat(63),"input.jsonl","a".repeat(64),1,30,"source"));
        var original=context("tenant");var prefs=Map.of("strategy","concise","density","compact","autoCharts",false,"autoImages",false);
        var parent=new AnalysisCoverageCoordinator.Request(original.model(),original.query(),null,
            Map.of("reportMode","visual","taskType","DATA_ANALYSIS","reportPresentation",prefs),original.metadata(),
            ()->false,()->{},original.isolationScope(),null);
        service().executePrimaryAnalysis(parent,sources(),event->{});
        var capture=org.mockito.ArgumentCaptor.forClass(CodingTaskContract.class);verify(agent,times(1)).submit(capture.capture());
        assertThat(capture.getValue().objective().instruction()).contains("report_layout.v1","concise","Do not rerun data acquisition");
        assertThat(((Map<?,?>)capture.getValue().runtimeContext().get("reportPresentation")).get("autoCharts")).isEqualTo(false);
        verify(original.model(),never()).chat(anyString());
    }
    @Test void T13_passesEffectiveDefaultScopeToTheExistingCodingTurnWithoutAnotherReviewer() {
        prepare(outcome(CodingTaskOutcome.Status.FAILED,CodingTaskOutcome.ValidationStatus.NOT_RUN));
        var audit=Map.<String,Object>of("effectiveParameters",Map.of("subject","catalog-B"),
            "parameterSources",Map.of("subject","template_default"),"scopeStatus","REQUIRES_SCOPE_VERIFICATION",
            "droppedOverrideFields",List.of("subject"));
        var source=new Dataset("source",Map.of("templateParameterBinding",audit),List.of(Map.of("amount",7)));
        var parent=context("tenant");service().execute(parent,request(),Map.of("source",source));
        verify(agent).submit(argThat(contract->contract.objective().instruction().contains("catalog-B")
            &&contract.objective().instruction().contains("REQUIRES_SCOPE_VERIFICATION")
            &&contract.objective().instruction().contains("template_default")));
        verify(parent.model(),never()).chat(anyString());
    }
    @Test void contextOnlyTasksCannotAcquireBusinessDataTools() {
        prepare(outcome(CodingTaskOutcome.Status.FAILED, CodingTaskOutcome.ValidationStatus.NOT_RUN));
        var supply = mock(com.chatchat.api.runtime.coding.data.CodingDataSupplyService.class);
        var service = service(); service.setDataSupply(supply);
        var original = context("tenant");
        var context = new AnalysisCoverageCoordinator.Request(original.model(), original.query(), null,
            Map.of("contextOnlyAnswer", true), original.metadata(), () -> false, () -> {}, original.isolationScope(), null);
        service.execute(context, request(), sources());
        verify(agent).submit(argThat(contract -> !contract.authorization().allowedTools().contains("data_request")));
        verify(supply, never()).describe(any());
    }
    @Test void newAnalysisTasksReceiveSnapshotsWithoutBusinessAcquisitionCapabilities() {
        prepare(outcome(CodingTaskOutcome.Status.FAILED, CodingTaskOutcome.ValidationStatus.NOT_RUN));
        var supply = mock(com.chatchat.api.runtime.coding.data.CodingDataSupplyService.class);
        var service = service(); service.setDataSupply(supply);
        service.execute(context("tenant"), request(), sources());
        verify(agent).submit(argThat(contract -> !contract.authorization().allowedTools().contains("data_request")
            && contract.metadataRefs().stream().noneMatch(ref -> ref.startsWith("coding-data-policy:"))
            && !contract.objective().instruction().contains("from runtime_input")
            && contract.objective().instruction().contains("No application-specific Python module is required")));
        verify(supply, never()).describe(any());
        verify(supply, never()).policyRef(any());
    }
    @Test void invalidCodingFieldsNameTheActualContractFailureWithoutRemovingBounds() {
        for (var field : List.of("requestId", "instruction", "datasetReference")) {
            var request = new LinkedHashMap<String,Object>(request()); request.remove(field);
            assertThatThrownBy(() -> service().execute(context("tenant"), request, sources()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("field=" + field)
                .hasMessageContaining("reason=MISSING");
        }
        var request = new LinkedHashMap<String,Object>(request()); request.put("instruction", "s".repeat(16001));
        assertThatThrownBy(() -> service().execute(context("tenant"), request, sources()))
            .hasMessageContaining("field=instruction").hasMessageContaining("actualCharacters=16001")
            .hasMessageContaining("reason=TOO_LONG");
        verifyNoInteractions(agent, python, inputs, plans, collector);
    }
    @Test void supplyStatusIsDeliveredAsTechnicalEvidenceWithoutInventingBusinessData() {
        var service=spy(service());
        doReturn(Map.of("status","COMPLETED")).when(service).executeObserved(any(),anyMap(),anyMap(),any());
        var parent=context("tenant");parent.metadata().put("dataSupplyStatus",Map.of("businessDataAvailable",false));
        var delivered=Map.of("supply-status:run",new Dataset("supply-status:run",
            Map.of("recordType","DATA_SUPPLY_STATUS","businessData",false),
            List.of(Map.of("selectionStatus","MODEL_UNAVAILABLE"))));
        service.executePrimaryAnalysis(parent,delivered,event -> {});
        verify(service).executeObserved(same(parent),argThat(task ->
            task.get("instruction").toString().contains("DATA_SUPPLY_STATUS")
            &&task.get("instruction").toString().contains("not business measurements")
            &&!task.get("instruction").toString().contains("reselect templates")),same(delivered),any());
        verifyNoInteractions(agent,python,inputs,plans,collector);
    }

    @Test void verifiedEmptySourceKeepsItsIdentityAndDeliveryStatusInTheCodingManifest() {
        prepare(outcome(CodingTaskOutcome.Status.FAILED,CodingTaskOutcome.ValidationStatus.NOT_RUN));
        when(inputs.captureConfigured(any(),anyString(),anyList(),startsWith("coding-input-set:"),anyString(),anyString(),anyLong(),anyLong(),any()))
            .thenAnswer(call->{
                List<Dataset> captured=call.getArgument(2);
                var versions=(List<?>)captured.get(0).analysisContext().get("inputVersions");
                assertThat(versions).hasSize(1);
                assertThat((Map<String,Object>)versions.get(0)).containsEntry("deliveryStatus","EMPTY_RESULT")
                    .containsEntry("sourceVerifiedEmpty",true).containsEntry("sourceTemplateId","published-template");
                assertThat(captured.get(0).recordCount()).isZero();
                return new CodingInputSnapshotService.Snapshot("runtime-evidence:input","c"+"a".repeat(63),"input.jsonl","a".repeat(64),0,0,"combined");
            });
        var delivered=Map.of("empty-source",new Dataset("empty-source",
            Map.of("sourceVerifiedEmpty",true,"sourceTemplateId","published-template"),List.of()));
        service().executePrimaryAnalysis(context("tenant"),delivered,event->{});
        verify(agent).submit(any());
    }

    @Test void sameCodingRequestReusesItsImmutableSnapshotAndOriginalBudget() {
        prepare(outcome(CodingTaskOutcome.Status.FAILED,CodingTaskOutcome.ValidationStatus.NOT_RUN));
        var service=service();var parent=context("tenant");
        var first=service.execute(parent,request(),sources());
        var repeated=service.execute(parent,request(),sources());
        assertThat(first).containsEntry("inputSnapshotCaptureCount",1).containsEntry("inputSnapshotReused",false);
        assertThat(repeated).containsEntry("inputSnapshotCaptureCount",0).containsEntry("inputSnapshotReused",true);
        assertThat(repeated.get("taskId")).isEqualTo(first.get("taskId"));
        assertThat(repeated.get("budgetId")).isEqualTo(first.get("budgetId"));
        verify(inputs,times(1)).captureConfigured(any(),anyString(),anyList(),anyString(),anyString(),anyString(),anyLong(),anyLong(),any());
    }

    @Test void combinedInputsKeepProducerFieldMeaningsWithoutGuessingFromOpaqueNames() {
        prepare(outcome(CodingTaskOutcome.Status.FAILED,CodingTaskOutcome.ValidationStatus.NOT_RUN));
        when(inputs.captureConfigured(any(),anyString(),anyList(),startsWith("coding-input-set:"),anyString(),anyString(),anyLong(),anyLong(),any()))
            .thenAnswer(call->{
                List<Dataset> captured=call.getArgument(2);
                var context=captured.get(0).analysisContext();
                assertThat(context.toString()).contains("fieldDefinitions","Previous observation amount","Elapsed duration")
                    .doesNotContain("PRIVATE_RAW","PRIVATE_DEFAULT");
                return new CodingInputSnapshotService.Snapshot("runtime-evidence:input","c"+"a".repeat(63),"input.jsonl","a".repeat(64),1,30,"combined");
            });
        var source=new Dataset("opaque#source",Map.of("schema",Map.of("definition",Map.of("properties",Map.of(
            "a",Map.of("type","string","description","Previous observation amount","default","PRIVATE_DEFAULT"),
            "b",Map.of("type","integer","description","Elapsed duration","rawData","PRIVATE_RAW"))))),
            List.of(Map.of("a","12.50","b",3)));
        var request=new LinkedHashMap<>(request());request.remove("datasetReference");request.put("datasetReferences",List.of(source.reference()));
        service().execute(context("tenant"),request,Map.of(source.reference(),source));
        verify(agent).submit(any());
    }
    @Test void workshopDefaultAndActualImageCapabilitiesReachTheCodingContract() {
        prepare(outcome(CodingTaskOutcome.Status.FAILED,CodingTaskOutcome.ValidationStatus.NOT_RUN));
        var defaults = mock(com.chatchat.api.datascience.python.PythonDefaultEnvironmentService.class);
        var environment = new McpPythonControlPlanePort.EnvironmentView("published-default", "Default", "", "python:new",
            "3.11", "1", "512m", "1g", "64m", "10001:10001", "NONE", null, "[]", 300, false, 5, "PUBLISHED");
        when(defaults.resolveForTask(anyString(), anyString(), any())).thenReturn(
            new com.chatchat.api.datascience.python.PythonDefaultEnvironmentService.Selection("asset", environment, "WORKSHOP_DEFAULT"));
        when(python.environmentRuntime("published-default")).thenReturn(new McpPythonControlPlanePort.EnvironmentRuntime(
            "published-default", 5, "python:new", "sha256:" + "a".repeat(64), "3.11.14", Map.of("pandas", "2.3.3")));
        var service = service(); service.setDefaultEnvironments(defaults);
        service.execute(context("tenant"), request(), sources());
        var captured = org.mockito.ArgumentCaptor.forClass(CodingTaskContract.class);
        verify(agent).submit(captured.capture());
        assertThat(captured.getValue().environmentRef()).isEqualTo("published-default");
        assertThat(captured.getValue().objective().instruction()).contains("3.11.14", "2.3.3", "WORKSHOP_DEFAULT");
        assertThat(((Map<?,?>)captured.getValue().runtimeContext().get("pythonEnvironment")).get("imageDigest"))
            .isEqualTo("sha256:" + "a".repeat(64));
        verify(python, never()).environment("environment");
    }
    @Test void largeEnvironmentMetadataFitsWithoutTruncatingTheObjectiveOrPackageSnapshot() {
        prepare(outcome(CodingTaskOutcome.Status.FAILED,CodingTaskOutcome.ValidationStatus.NOT_RUN));
        var packages=new LinkedHashMap<String,String>();
        for(int i=0;i<50;i++)packages.put("package-"+i,"version-".repeat(45));
        when(python.environmentRuntime("environment")).thenReturn(new McpPythonControlPlanePort.EnvironmentRuntime(
            "environment",1,"image","sha256:"+"a".repeat(64),"3",packages));
        var request=new LinkedHashMap<>(request());
        String objective="核对客户资产 🧮\n"+"x".repeat(14000);request.put("instruction",objective);
        service().execute(context("tenant"),request,sources());
        var captured=org.mockito.ArgumentCaptor.forClass(CodingTaskContract.class);verify(agent).submit(captured.capture());
        var accepted=captured.getValue();
        assertThat(accepted.objective().instruction()).startsWith(objective).contains("runtimeContext.pythonEnvironment");
        assertThat(accepted.objective().instruction().codePointCount(0,accepted.objective().instruction().length())).isLessThanOrEqualTo(32768);
        assertThat(((Map<?,?>)accepted.runtimeContext().get("pythonEnvironment")).get("packages")).isEqualTo(packages);
    }
    @Test void largeInputDescriptorDefersOnlyItsDuplicateWhileKeepingScopeAndSourceSemanticsInline() {
        prepare(outcome(CodingTaskOutcome.Status.FAILED,CodingTaskOutcome.ValidationStatus.NOT_RUN));
        when(inputs.describe(any(),anyString(),any())).thenReturn(Map.of("rowCount",1,
            "inputVersions",List.of(Map.of("fieldDefinitions",Map.of("amount",Map.of("description","declared-meaning-".repeat(2400)))))));
        service().execute(context("tenant"),request(),sources());
        var captured=org.mockito.ArgumentCaptor.forClass(CodingTaskContract.class);verify(agent).submit(captured.capture());
        String goal=captured.getValue().objective().instruction();
        assertThat(goal).contains("This replaces only the duplicate descriptor",
            "Producer-declared source representation:","Runtime template parameter provenance:","Scope applicability:");
        assertThat(goal.codePointCount(0,goal.length())).isLessThanOrEqualTo(32768);
    }
    @Test void nineSourceFileContractRetainsTheAuthorizedParentModelAcrossComplexitySelection() {
        prepare(outcome(CodingTaskOutcome.Status.FAILED,CodingTaskOutcome.ValidationStatus.NOT_RUN));
        policy.setComplexModelName("must-not-replace-parent");
        when(inputs.captureConfigured(any(),anyString(),anyList(),startsWith("coding-input-set:"),
            anyString(),anyString(),anyLong(),anyLong(),any())).thenReturn(
                new CodingInputSnapshotService.Snapshot("runtime-evidence:input","c"+"a".repeat(63),
                    "manifest.json","a".repeat(64),9,90,"combined",List.of(
                        new CodingInputSnapshotService.Chunk("c"+"b".repeat(63),"input.jsonl","b".repeat(64),9,90))));
        var delivered = new LinkedHashMap<String,Dataset>();
        for (int i = 0; i < 9; i++) delivered.put("source-"+i,new Dataset("source-"+i,Map.of(),List.of(Map.of("value",i))));
        var parent=context("tenant"); parent.metadata().put("modelName","request-model");
        service().executePrimaryAnalysis(parent,delivered,event -> {});
        var captured=org.mockito.ArgumentCaptor.forClass(CodingTaskContract.class);
        verify(agent).submit(captured.capture());
        var contract=captured.getValue();
        assertThat(contract.schemaVersion()).isEqualTo(CodingProtocol.ANALYSIS_VERSION);
        @SuppressWarnings("unchecked") var profile=(Map<String,Object>)contract.runtimeContext().get("executionProfile");
        assertThat(profile)
            .containsKey("sourceCount").doesNotContainKey("selectedModel");
        assertThat(((Map<?,?>)contract.runtimeContext().get("executionProfile")).get("sourceCount")).isEqualTo(9);
        assertThat(((Map<?,?>)contract.runtimeContext().get("model")).get("selectedModel")).isEqualTo("request-model");
        verify(parent.model(),never()).chat(anyString());
    }

    @Test void visualOutputExtendsTheExistingPrimaryTaskWithoutChangingDataAcquisition(){
        var service=spy(service());doReturn(Map.of("status","COMPLETED")).when(service).executeObserved(any(),anyMap(),anyMap(),any());
        var original=context("tenant");var request=new AnalysisCoverageCoordinator.Request(original.model(),original.query(),null,Map.of("taskType","DATA_ANALYSIS","reportMode","visual"),original.metadata(),()->false,()->{},original.isolationScope(),null);
        var sources=new LinkedHashMap<String,Dataset>();sources.put("dataset",new Dataset("dataset",Map.of(),List.of(Map.of("value",1))));
        service.executePrimaryAnalysis(request,sources,event->{});
        verify(service).executeObserved(same(request),argThat(task->"primary-data-analysis-visual-v1".equals(task.get("requestId"))&&task.get("instruction").toString().contains("SAME computed Python objects")&&((Map<?,?>)((Map<?,?>)task.get("outputSchema")).get("properties")).containsKey("visualReport")),same(sources),any());
        verifyNoInteractions(agent,python,inputs,plans,collector);
    }
    @Test void primaryAnalysisBatchesNineAuthorizedDatasetsIntoOneGenericCodingAssignment() {
        var service = spy(service());
        doReturn(Map.of("status", "COMPLETED")).when(service).executeObserved(
            any(AnalysisCoverageCoordinator.Request.class), anyMap(), anyMap(), any());
        var delivered = new LinkedHashMap<String,Dataset>();
        for (int i = 1; i <= 9; i++)
            delivered.put("dataset-" + i, new Dataset("dataset-" + i, Map.of(), List.of(Map.of("value", i))));

        service.executePrimaryAnalysis(context("tenant"), delivered, event -> {});

        @SuppressWarnings("rawtypes") var request = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(service).executeObserved(any(AnalysisCoverageCoordinator.Request.class), request.capture(),
            same(delivered), any());
        @SuppressWarnings("unchecked") Map<String,Object> value = request.getValue();
        assertThat((List<?>) value.get("datasetReferences")).hasSize(9);
        assertThat(value.get("requestId")).isEqualTo("primary-data-analysis-v1");
        assertThat(value.get("reviewResult")).isEqualTo(true);
        assertThat(value.get("instruction").toString()).contains("Choose your own analysis methods",
            "same declared metric, scope, period and unit", "every table header aligns with its values",
            "User objective", "Question").doesNotContain("customer", "security", "profit formula");
        assertThat((Map<String,Object>) value.get("outputSchema")).containsEntry("type", "object");
        verifyNoInteractions(agent, python, inputs, plans, collector);
    }

    @Test void runtimeLifecycleSummaryIsNotSubmittedAsAnAnalyticalClaim() {
        var completed=outcome(CodingTaskOutcome.Status.COMPLETED,CodingTaskOutcome.ValidationStatus.INCONCLUSIVE);
        when(completed.summary()).thenReturn("runtime lifecycle marker: validation INCONCLUSIVE");
        prepare(completed);
        var parent=context("tenant");
        var result=service().execute(parent,request(),sources());
        verify(parent.model(),never()).chat(anyString());
        assertThat(result).containsEntry("resultAvailable",true)
            .containsEntry("validationStatus","INCONCLUSIVE").containsEntry("businessValidation","NOT_EVALUATED");
    }

    @Test void reviewedAnalysisKeepsSemanticReviewInsideCodingAndAllowsReadingOnlyItsResultArtifact() {
        prepare(outcome(CodingTaskOutcome.Status.COMPLETED, CodingTaskOutcome.ValidationStatus.INCONCLUSIVE));
        var reviewed = new LinkedHashMap<String,Object>(request());
        reviewed.put("reviewResult", true);

        service().execute(context("tenant"), reviewed, sources());

        var contract = org.mockito.ArgumentCaptor.forClass(CodingTaskContract.class);
        verify(agent).submit(contract.capture());
        assertThat(contract.getValue().authorization().allowedTools()).contains("python_execute", "artifact_read");
        assertThat(contract.getValue().metadataRefs()).contains("runtime-coding:analysis-reviewed-path:v1")
            .doesNotContain("runtime-coding:analysis-fast-path:v1");
        assertThat(contract.getValue().objective().instruction()).contains("automatically invokes", "reviewedArtifact",
            "confirm completion with artifact_read", "without a second read or Python execution", "Runtime will perform no semantic review",
            "preserve the exact field meaning declared by fieldDefinitions", "a plausible value under the wrong label must be repaired",
            "field-provenance section", "exact source column", "generic code/name/id/date/amount substring",
            "a customer/counterparty name is not an instrument name", "request the missing dictionary",
            "actual column-selection code", "report unknown semantics rather than passing a business-role check");
    }
    @Test void combinedSourcesKeepTheirOwnDeclaredRepresentationInTheAcceptedObjective() {
        prepare(outcome(CodingTaskOutcome.Status.FAILED,CodingTaskOutcome.ValidationStatus.NOT_RUN));
        when(inputs.captureConfigured(any(),anyString(),anyList(),startsWith("coding-input-set:"),
            anyString(),anyString(),anyLong(),anyLong(),any())).thenReturn(
                new CodingInputSnapshotService.Snapshot("runtime-evidence:input","c"+"a".repeat(63),
                    "input.jsonl","a".repeat(64),2,60,"combined"));
        var datasets=new LinkedHashMap<String,Dataset>();
        datasets.put("source#payload",new Dataset("source#payload",Map.of("contentType","application/json",
            "recordMeaning","CONTENT_CHUNK_NOT_BUSINESS_ROW","privateContext","private-value"),
            List.of(Map.of("chunkIndex",0,"content","private-source"))));
        datasets.put("other#stdout",new Dataset("other#stdout",Map.of("contentType","text/plain","stream","stdout"),
            List.of(Map.of("chunkIndex",0,"content","private-other"))));
        var request=new LinkedHashMap<>(request());request.remove("datasetReference");
        request.put("datasetReferences",List.of("source#payload","other#stdout"));
        service().execute(context("tenant"),request,datasets);
        var captured=org.mockito.ArgumentCaptor.forClass(CodingTaskContract.class);
        verify(agent).submit(captured.capture());
        assertThat(captured.getValue().objective().instruction()).contains("source#payload","other#stdout",
            "application/json","text/plain","Group logical rows by their exact datasetReference first",
            "Decode the reconstructed text as JSON first").doesNotContain("private-source","private-value","private-other");
    }
    @Test void producerRepresentationReachesTheAcceptedCodingObjective() {
        prepare(outcome(CodingTaskOutcome.Status.FAILED,CodingTaskOutcome.ValidationStatus.NOT_RUN));
        var datasets=new LinkedHashMap<String,Dataset>();
        datasets.put("source",new Dataset("source",Map.of("contentType","application/json",
            "recordMeaning","CONTENT_CHUNK_NOT_BUSINESS_ROW","privateContext","private-value"),
            List.of(Map.of("chunkIndex",1,"content","private-source"))));
        service().execute(context("tenant"),request(),datasets);
        var captured=org.mockito.ArgumentCaptor.forClass(CodingTaskContract.class);
        verify(agent).submit(captured.capture());
        assertThat(captured.getValue().objective().instruction()).contains("application/json",
            "Decode the reconstructed text as JSON first").doesNotContain("private-source","private-value");
    }
    @Test void expiredLegacySharedBudgetDoesNotBlockNewIndependentToolInvocation() throws Exception {
        prepare(outcome(CodingTaskOutcome.Status.FAILED,CodingTaskOutcome.ValidationStatus.NOT_RUN));
        var expired=new CodingTaskContract.Budget("analysis-coding-budget:run",java.time.Instant.EPOCH,
            12,65536,24,12,"10","USD",300000,512L*1024*1024,4L*1024*1024);
        saved.put("tenant:user:run:coding:analysis-budget.v1",new ObjectMapper().findAndRegisterModules().writeValueAsString(expired));
        var result=service().execute(context("tenant"),request(),sources());
        assertThat(result).containsEntry("status","FAILED").doesNotContainKey("budgetExhausted");
        verify(agent).submit(any());
    }
    final CodingAnalysisProperties policy=new CodingAnalysisProperties();
    final CodingInvocationContext identity=new CodingInvocationContext();
    final CodingInputSnapshotService inputs=mock(CodingInputSnapshotService.class);
    final CodingValidationPlanService plans=mock(CodingValidationPlanService.class);
    final CodingAgentSPI agent=mock(CodingAgentSPI.class);
    final CodingEvidenceCollector collector=mock(CodingEvidenceCollector.class);
    final McpPythonControlPlanePort python=mock(McpPythonControlPlanePort.class);
    final CodingParentModels models=new CodingParentModels();
    final Map<String,String> saved=new ConcurrentHashMap<>();
    final RuntimeExecutionCheckpointPort checkpoints=new RuntimeExecutionCheckpointPort(){
        String key(KernelDataScope scope,String key){return scope.tenantId()+":"+scope.userId()+":"+scope.runId()+":"+key;}
        public Optional<String> readExecutionCheckpoint(KernelDataScope scope,String key){return Optional.ofNullable(saved.get(key(scope,key)));}
        public String mutateExecutionCheckpoint(KernelDataScope scope,String checkpoint,java.util.function.UnaryOperator<String> mutation){
            synchronized(saved){String name=key(scope,checkpoint);String next=mutation.apply(saved.get(name));saved.put(name,next);return next;}
        }
        public boolean compareAndSetExecutionCheckpoint(KernelDataScope scope,String key,String old,String next){
            String name=key(scope,key);return old==null?saved.putIfAbsent(name,next)==null:saved.replace(name,old,next);
        }
    };
    CodingAnalysisService service(){
        when(inputs.withInputContext(anyMap(),any(),anyString(),any())).thenAnswer(call->call.getArgument(0));
        when(collector.codeForReview(any(),anyString(),anyString())).thenReturn("print('computed result')");
        when(inputs.requireAuthorized(any())).thenReturn(new CodingInputSnapshotService.Snapshot("runtime-evidence:input","c"+"a".repeat(63),"input.jsonl","a".repeat(64),1,30,"source"));
        return new CodingAnalysisService(policy,identity,inputs,plans,agent,
        mock(DurableCodingTaskService.class),collector,checkpoints,models,python,new ObjectMapper().findAndRegisterModules());}
    Map<String,Object> request(){return Map.of("operation","RUN_CODING","requestId","calculation-1",
        "datasetReference","source","instruction","Transform the authorized data",
        "outputSchema",Map.of("type","object","properties",Map.of("value",Map.of("type","string")))) ;}
    Map<String,Dataset> sources(){var result=new LinkedHashMap<String,Dataset>();result.put("source",new Dataset("source",Map.of(),List.of(Map.of("text","example"))));return result;}
    AnalysisCoverageCoordinator.Request context(String tenant){
        var model=mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("{\"status\":\"SUPPORTED\",\"reason\":\"Test review\",\"unsupportedFields\":[]}");
        return new AnalysisCoverageCoordinator.Request(model,"Question",null,Map.of(),
        new LinkedHashMap<>(),()->false,()->{},GovernanceIsolationScope.runtime(tenant,"user","run","request","conversation"),null);}

    @Test void reusesPersistedContractAndBudgetAndAdmitsOnlyCommittedScopedResults() {
        policy.setEnvironmentId("environment");
        when(python.environment("environment")).thenReturn(new McpPythonControlPlanePort.EnvironmentView("environment","Python",null,"image","3", "1","512m","8m","8m","1000","NONE",null,"[]",300,false,1,"PUBLISHED"));
        when(inputs.captureConfigured(any(),anyString(),anyList(),eq("source"),anyString(),anyString(),anyLong(),anyLong(),any()))
            .thenAnswer(call->new CodingInputSnapshotService.Snapshot("runtime-evidence:input","c"+"a".repeat(63),"input.jsonl","a".repeat(64),1,30,"source"));
        when(inputs.describe(any(),anyString(),any())).thenReturn(Map.of("rowCount",1,"schemaJson","{}"));
        when(plans.register(any(),anyString(),any(),any())).thenReturn("runtime-evidence:plan");
        var completed=mock(CodingTaskOutcome.class);
        when(completed.status()).thenReturn(CodingTaskOutcome.Status.COMPLETED);
        when(completed.validationStatus()).thenReturn(CodingTaskOutcome.ValidationStatus.INCONCLUSIVE);
        when(completed.executionControl()).thenReturn(new CodingTaskOutcome.ExecutionControl(null,null,true,false));
        when(completed.usage()).thenReturn(mock(CodingTaskOutcome.Usage.class));
        when(completed.inputSnapshotRef()).thenReturn("runtime-evidence:input");when(completed.codeArtifactRef()).thenReturn("runtime-evidence:code");
        when(completed.executionReceiptRef()).thenReturn("runtime-evidence:receipt");when(completed.resultDatasetRef()).thenReturn("runtime-evidence:result");
        when(completed.validationRecordRef()).thenReturn("runtime-evidence:validation");when(completed.evidenceBundleRef()).thenReturn("runtime-evidence:bundle");
        when(agent.submit(any())).thenReturn(completed);
        when(collector.registeredResult(any(),anyString(),eq("runtime-evidence:result")))
            .thenReturn(new Dataset("runtime-evidence:result",Map.of(),List.of(Map.of("value","computed"))));
        var source=sources();var parent=context("tenant");
        var first=service().execute(parent,request(),source);
        assertThat(first).containsEntry("status","COMPLETED").containsEntry("businessValidation","NOT_EVALUATED")
            .containsEntry("validationStatus","INCONCLUSIVE").containsEntry("resultAvailable",true);
        assertThat(source).containsKey("runtime-evidence:result");assertThat((List<?>)first.get("evidenceRefs")).hasSize(6);
        var provenance=(Map<?,?>)source.get("runtime-evidence:result").analysisContext().get("coding");
        assertThat(provenance.get("inputDatasetReference")).isEqualTo("source");
        assertThat(provenance.get("validationStatus")).isEqualTo("INCONCLUSIVE");
        assertThat(provenance.get("businessValidation")).isEqualTo("NOT_EVALUATED");
        assertThat((List<?>)provenance.get("evidenceRefs")).hasSize(6);
        var second=service().execute(context("tenant"),new TreeMap<>(request()),sources());
        assertThat(second.get("taskId")).isEqualTo(first.get("taskId"));
        var contracts=org.mockito.ArgumentCaptor.forClass(CodingTaskContract.class);
        verify(agent,times(2)).submit(contracts.capture());
        assertThat(contracts.getAllValues().get(0)).isEqualTo(contracts.getAllValues().get(1));
        assertThat(models.find(contracts.getValue().scope(),contracts.getValue().taskId())).isNull();
        try(var active=models.bind(contracts.getValue().scope(),contracts.getValue().taskId(),parent.model())) {
            assertThatThrownBy(()->service().execute(parent,request(),sources())).isInstanceOf(IllegalStateException.class);
            verify(agent,never()).cancel(any(),anyString(),anyString());
        } catch(Exception failure) { throw new AssertionError(failure); }
        verify(inputs,times(1)).captureConfigured(any(),anyString(),anyList(),anyString(),anyString(),anyString(),anyLong(),anyLong(),any());
        var conflicting=new LinkedHashMap<>(request());conflicting.put("instruction","Changed target under the same identity");
        assertThatThrownBy(()->service().execute(parent,conflicting,sources())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity conflict");
        verify(agent,times(2)).submit(any());
    }
    @Test void unknownSourceAndAnonymousIdentityCannotAcquireExecutionAuthority() {
        var request=new LinkedHashMap<>(request());request.put("datasetReference","another-run");
        assertThatThrownBy(()->service().execute(context("tenant"),request,sources())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service().execute(context("default"),request(),sources())).isInstanceOf(SecurityException.class);
        verifyNoInteractions(agent,python,inputs,plans);
    }

    @Test void parentCancellationIsCheckedBeforeCapturingOrDispatchingData() {
        var parent=context("tenant");
        var cancelled=new AnalysisCoverageCoordinator.Request(parent.model(),parent.query(),null,Map.of(),parent.metadata(),
            ()->true,()->{},parent.isolationScope(),null);
        assertThatThrownBy(()->service().execute(cancelled,request(),sources())).isInstanceOf(java.util.concurrent.CancellationException.class);
        verifyNoInteractions(agent,python,inputs,plans,collector);
    }

    @Test void expiredParentDeadlineIsCheckedBeforeCapturingData() {
        var parent=context("tenant");
        var expired=new AnalysisCoverageCoordinator.Request(parent.model(),parent.query(),null,
            Map.of("__agentDeadlineAt",System.currentTimeMillis()-1),parent.metadata(),()->false,()->{},parent.isolationScope(),null);
        assertThatThrownBy(()->service().execute(expired,request(),sources()))
            .isInstanceOf(com.chatchat.agents.orchestration.model.AgentDeadlineExceededException.class);
        verifyNoInteractions(agent,python,inputs,plans,collector);
    }

    @Test void cancellationDuringCodingStopsTheOwnedTaskAndReleasesTheModelBinding() {
        var running=outcome(CodingTaskOutcome.Status.RUNNING,CodingTaskOutcome.ValidationStatus.NOT_RUN);
        prepare(running);
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();var parent=context("tenant");
        var context=new AnalysisCoverageCoordinator.Request(parent.model(),parent.query(),null,Map.of(),parent.metadata(),
            cancelled::get,()->{},parent.isolationScope(),null);
        when(agent.submit(any())).thenAnswer(call->{cancelled.set(true);return running;});
        assertThatThrownBy(()->service().execute(context,request(),sources())).isInstanceOf(java.util.concurrent.CancellationException.class);
        var contract=org.mockito.ArgumentCaptor.forClass(CodingTaskContract.class);
        verify(agent).submit(contract.capture());
        verify(agent).cancel(eq(contract.getValue().scope()),eq(contract.getValue().taskId()),anyString());
        assertThat(models.find(contract.getValue().scope(),contract.getValue().taskId())).isNull();
        verifyNoInteractions(collector);
    }

    @Test void failedOrValidationFailedTasksReturnReasonsWithoutAdmittingADataset() {
        for(var state:List.of(CodingTaskOutcome.Status.FAILED,CodingTaskOutcome.Status.COMPLETED)){
            var result=outcome(state,CodingTaskOutcome.ValidationStatus.FAILED);
            when(result.reasonCode()).thenReturn(state==CodingTaskOutcome.Status.FAILED?CodingErrorCode.EXECUTION_FAILED:null);
            when(result.summary()).thenReturn("Worker reported execution/validation failure");
            when(result.limitations()).thenReturn(List.of("No trusted analysis result is available"));
            prepare(result);
            var sources=sources();
            var receipt=service().execute(context("tenant"),request(),sources);
            assertThat(receipt).containsEntry("resultAvailable",false).containsEntry("summary","Worker reported execution/validation failure")
                .containsEntry("limitations",List.of("No trusted analysis result is available"));
            if(state==CodingTaskOutcome.Status.FAILED)assertThat(receipt).containsEntry("reasonCode","EXECUTION_FAILED");
            assertThat(receipt).doesNotContainKeys("datasetReference","evidenceRefs");
            assertThat(sources).containsOnlyKeys("source");
        }
        verifyNoInteractions(collector);
        verify(agent,never()).cancel(any(),anyString(),anyString());
    }

    @Test void outputContractFailureReturnsToParentForReplanningWithoutReadingRejectedRows() {
        var result=outcome(CodingTaskOutcome.Status.FAILED,CodingTaskOutcome.ValidationStatus.FAILED);
        when(result.reasonCode()).thenReturn(CodingErrorCode.OUTPUT_CONTRACT_VIOLATION);
        when(result.summary()).thenReturn("Source copy rejected");
        prepare(result);
        var sources=sources();

        var receipt=service().execute(context("tenant"),request(),sources);

        assertThat(receipt).containsEntry("resultAvailable",false).containsEntry("replanningRequired",true)
            .containsEntry("retryable",false).containsEntry("reasonCode","OUTPUT_CONTRACT_VIOLATION")
            .doesNotContainKeys("datasetReference","evidenceRefs","budgetExhausted");
        assertThat(receipt.get("diagnostic").toString()).contains("new requestId","independent tool budget","Same-shape transformations");
        assertThat(sources).containsOnlyKeys("source");
        verifyNoInteractions(collector);
        verify(agent,never()).cancel(any(),anyString(),anyString());
    }

    @Test void rejectionBudgetSurvivesServiceReplacementAndCountsEachTaskOnce() {
        var failed=outcome(CodingTaskOutcome.Status.FAILED,CodingTaskOutcome.ValidationStatus.FAILED);
        when(failed.reasonCode()).thenReturn(CodingErrorCode.OUTPUT_CONTRACT_VIOLATION);
        prepare(failed);
        var first=service().execute(context("tenant"),request(),sources());
        assertThat(first).containsEntry("replanningRequired",true).doesNotContainKey("analysisBlocked");
        policy.setMaxConsecutiveRejectedTasks(10);
        var replay=service().execute(context("tenant"),request(),sources());
        assertThat(replay.get("rejectionBudget")).isEqualTo(first.get("rejectionBudget"));
        var secondRequest=new LinkedHashMap<String,Object>(request());secondRequest.put("requestId","calculation-2");
        var second=service().execute(context("tenant"),secondRequest,sources());
        assertThat(second).containsEntry("analysisBlocked",true).containsEntry("replanningRequired",false)
            .containsEntry("retryable",false).containsEntry("resultAvailable",false);
        assertThat(second.get("rejectionBudget")).isEqualTo(Map.of("limit",2,"consecutiveRejectedTasks",2,"scope","RUN"));
        var otherTenant=service().execute(context("other-tenant"),secondRequest,sources());
        assertThat(otherTenant).doesNotContainKey("analysisBlocked");
        verifyNoInteractions(collector);
    }

    @Test void onlyANewAdmittedResultResetsConsecutiveRejections() {
        var failed=outcome(CodingTaskOutcome.Status.FAILED,CodingTaskOutcome.ValidationStatus.FAILED);
        when(failed.reasonCode()).thenReturn(CodingErrorCode.OUTPUT_CONTRACT_VIOLATION);
        var completed=outcome(CodingTaskOutcome.Status.COMPLETED,CodingTaskOutcome.ValidationStatus.INCONCLUSIVE);
        var good=new LinkedHashMap<String,Object>(request());good.put("requestId","good-result");
        var next=new LinkedHashMap<String,Object>(request());next.put("requestId","next-rejection");
        var last=new LinkedHashMap<String,Object>(request());last.put("requestId","last-rejection");
        prepare(failed);service().execute(context("tenant"),request(),sources());
        prepare(completed);
        assertThat(service().execute(context("tenant"),good,sources())).containsEntry("resultAvailable",true);
        prepare(failed);
        assertThat(service().execute(context("tenant"),next,sources())).doesNotContainKey("analysisBlocked");
        prepare(completed);service().execute(context("tenant"),good,sources());
        prepare(failed);
        assertThat(service().execute(context("tenant"),last,sources())).containsEntry("analysisBlocked",true);
    }

    @Test void cancellationWhileCapturingInputPreventsWorkerDispatch() {
        prepare(outcome(CodingTaskOutcome.Status.RUNNING,CodingTaskOutcome.ValidationStatus.NOT_RUN));
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();var parent=context("tenant");
        when(inputs.describe(any(),anyString(),any())).thenAnswer(call->{cancelled.set(true);return Map.of("rowCount",1);});
        var context=new AnalysisCoverageCoordinator.Request(parent.model(),parent.query(),null,Map.of(),parent.metadata(),
            cancelled::get,()->{},parent.isolationScope(),null);
        assertThatThrownBy(()->service().execute(context,request(),sources())).isInstanceOf(java.util.concurrent.CancellationException.class);
        verifyNoInteractions(agent,collector);
    }

    @Test void expiredPersistedBudgetIsReconciledBeforeSendingCancellation() throws Exception {
        var completed=outcome(CodingTaskOutcome.Status.COMPLETED,CodingTaskOutcome.ValidationStatus.INCONCLUSIVE);
        prepare(completed);var service=service();var parent=context("tenant");
        service.execute(parent,request(),sources());
        var json=new ObjectMapper().findAndRegisterModules();
        var entry=saved.entrySet().stream().filter(e->e.getKey().contains("coding:analysis-request:")).findFirst().orElseThrow();
        var checkpoint=json.readTree(entry.getValue());
        ((com.fasterxml.jackson.databind.node.ObjectNode)checkpoint.path("contract").path("budget"))
            .put("deadlineAt",java.time.Instant.ofEpochMilli(System.currentTimeMillis()-1).toString());
        saved.put(entry.getKey(),json.writeValueAsString(checkpoint));
        var running=outcome(CodingTaskOutcome.Status.RUNNING,CodingTaskOutcome.ValidationStatus.NOT_RUN);
        var stopped=outcome(CodingTaskOutcome.Status.BUDGET_EXCEEDED,CodingTaskOutcome.ValidationStatus.NOT_RUN);
        when(stopped.reasonCode()).thenReturn(CodingErrorCode.DEADLINE_EXCEEDED);
        when(stopped.executionControl()).thenReturn(new CodingTaskOutcome.ExecutionControl(CodingErrorCode.DEADLINE_EXCEEDED,
            java.time.Instant.ofEpochMilli(System.currentTimeMillis()),true,false));
        when(agent.submit(any())).thenReturn(running);when(agent.resume(any(),anyString())).thenReturn(stopped);
        var receipt=service.execute(parent,request(),sources());
        assertThat(receipt).containsEntry("status","BUDGET_EXCEEDED").containsEntry("stopReason","DEADLINE_EXCEEDED")
            .containsEntry("resultAvailable",false);
        verify(agent).resume(any(),anyString());verify(agent,never()).cancel(any(),anyString(),anyString());
        verify(collector,times(1)).registeredResult(any(),anyString(),anyString());
    }

    @Test void unknownExecutionRemainsUnknownAndCannotSupplyAnAnalysisResult() {
        policy.setTimeoutSeconds(1);
        var unknown=outcome(CodingTaskOutcome.Status.RUNNING,CodingTaskOutcome.ValidationStatus.NOT_RUN);
        when(unknown.executionControl()).thenReturn(new CodingTaskOutcome.ExecutionControl(null,null,false,true));
        when(unknown.reasonCode()).thenReturn(CodingErrorCode.EXECUTION_OUTCOME_UNKNOWN);
        when(unknown.limitations()).thenReturn(List.of("Provider execution requires reconciliation"));
        prepare(unknown);when(agent.resume(any(),anyString())).thenReturn(unknown);
        when(agent.get(any(),anyString())).thenReturn(unknown);
        var events=new ArrayList<Map<String,Object>>();
        var sources=sources();var receipt=service().executeObserved(context("tenant"),request(),sources,events::add);
        assertThat(events.stream().filter(event->"CODING_RECONCILIATION_REQUIRED".equals(event.get("eventKind"))).count()).isEqualTo(1);
        assertThat(receipt).containsEntry("status","RUNNING").containsEntry("reconciliationRequired",true)
            .containsEntry("resultAvailable",false).containsEntry("reasonCode","EXECUTION_OUTCOME_UNKNOWN")
            .doesNotContainKeys("datasetReference","evidenceRefs");
        assertThat(sources).containsOnlyKeys("source");
        verifyNoInteractions(collector);
        verify(agent).submit(any());verify(agent,atLeastOnce()).resume(any(),anyString());
        verify(agent).cancel(any(),anyString(),anyString());
    }

    @Test void reconciledWorkerLossRegistersStructuralResultUnderTheOriginalTaskBudget() {
        policy.setTimeoutSeconds(30);
        var unknown=outcome(CodingTaskOutcome.Status.RUNNING,CodingTaskOutcome.ValidationStatus.NOT_RUN);
        when(unknown.executionControl()).thenReturn(new CodingTaskOutcome.ExecutionControl(null,null,false,true));
        var completed=outcome(CodingTaskOutcome.Status.COMPLETED,CodingTaskOutcome.ValidationStatus.INCONCLUSIVE);
        prepare(unknown);
        when(agent.get(any(),anyString())).thenReturn(unknown);
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        when(agent.resume(any(),anyString())).thenAnswer(call->{
            assertThat(models.find(call.getArgument(0),call.getArgument(1))).isNotNull();
            return attempts.incrementAndGet()==1?unknown:completed;
        });
        var sources=sources();var receipt=service().execute(context("tenant"),request(),sources);
        assertThat(receipt).containsEntry("status","COMPLETED").containsEntry("resultAvailable",true);
        assertThat(sources).containsKey("runtime-evidence:result");
        verify(collector).registeredResult(any(),anyString(),eq("runtime-evidence:result"));
        verify(agent,times(2)).resume(any(),anyString());
        verify(agent,times(1)).submit(any());verify(agent,never()).cancel(any(),anyString(),anyString());
    }

    @Test void runtimeRegistersCommittedResultsWithoutCallingABusinessReviewer() {
        prepare(outcome(CodingTaskOutcome.Status.COMPLETED,CodingTaskOutcome.ValidationStatus.INCONCLUSIVE));
        var parent=context("tenant");
        when(parent.model().chat(anyString())).thenThrow(new AssertionError("No OS semantic veto"));
        var receipt=service().execute(parent,request(),sources());
        assertThat(receipt).containsEntry("resultAvailable",true).containsEntry("businessValidation","NOT_EVALUATED")
            .containsEntry("inputDatasetCount",1).containsEntry("inputSnapshotCaptureCount",1)
            .containsEntry("supplementalDataSnapshotCount",0)
            .doesNotContainKey("semanticAdmission");
        verify(parent.model(),never()).chat(anyString());
        verify(collector,never()).codeForReview(any(),anyString(),anyString());
    }

    @Test void resultReadFailureDoesNotCancelAnAlreadyCompletedTask() {
        prepare(outcome(CodingTaskOutcome.Status.COMPLETED,CodingTaskOutcome.ValidationStatus.INCONCLUSIVE));
        when(collector.registeredResult(any(),anyString(),anyString())).thenThrow(new IllegalStateException("Archive temporarily unavailable"));
        assertThatThrownBy(()->service().execute(context("tenant"),request(),sources())).isInstanceOf(IllegalStateException.class)
            .hasMessage("Archive temporarily unavailable");
        verify(agent,never()).cancel(any(),anyString(),anyString());
        var contract=org.mockito.ArgumentCaptor.forClass(CodingTaskContract.class);verify(agent).submit(contract.capture());
        assertThat(models.find(contract.getValue().scope(),contract.getValue().taskId())).isNull();
    }

    @Test void differentModelChosenCalculationsHaveIndependentDurableToolBudgets() {
        var completed=outcome(CodingTaskOutcome.Status.COMPLETED,CodingTaskOutcome.ValidationStatus.INCONCLUSIVE);
        prepare(completed);
        var service=service();var parent=context("tenant");
        service.execute(parent,request(),sources());
        var another=new LinkedHashMap<>(request());another.put("requestId","calculation-2");another.put("instruction","Another model-selected transformation");
        service.execute(parent,another,sources());
        var contracts=org.mockito.ArgumentCaptor.forClass(CodingTaskContract.class);verify(agent,times(2)).submit(contracts.capture());
        assertThat(contracts.getAllValues().get(0).taskId()).isNotEqualTo(contracts.getAllValues().get(1).taskId());
        assertThat(contracts.getAllValues().get(0).budget().budgetId()).isNotEqualTo(contracts.getAllValues().get(1).budget().budgetId());
        assertThat(contracts.getAllValues()).allSatisfy(c -> assertThat(c.budget().maxModelTokens()).isEqualTo(policy.getMaxModelTokens()));
    }

    @Test void codingDeadlineIsCappedByParentWithoutChargingParentTokensOrExtendingTime() {
        prepare(outcome(CodingTaskOutcome.Status.FAILED,CodingTaskOutcome.ValidationStatus.NOT_RUN));
        var provider=mock(ChatModel.class);when(provider.chat(anyString())).thenReturn("tool response");
        var usage=new LinkedHashMap<String,Object>();
        var deadline=new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis()+60_000);
        var parentModel=new com.chatchat.agents.orchestration.model.MeteredChatModel(
            new com.chatchat.agents.orchestration.model.DeadlineAwareChatModel(provider,()->Math.max(0,deadline.get()-System.currentTimeMillis())),usage,1,0,0,0,0.8);
        var parent=context("tenant");
        var ctx=new AnalysisCoverageCoordinator.Request(parentModel,parent.query(),null,Map.of("__agentDeadlineAt",deadline),
            parent.metadata(),()->false,()->{},parent.isolationScope(),null);
        when(agent.submit(any())).thenAnswer(call -> {
            CodingTaskContract contract=call.getArgument(0);
            assertThat(contract.budget().deadlineAt().toEpochMilli()).isEqualTo(deadline.get());
            assertThat(models.find(contract.scope(),contract.taskId()).chat("a large code generation prompt")).isEqualTo("tool response");
            return outcome(CodingTaskOutcome.Status.FAILED,CodingTaskOutcome.ValidationStatus.NOT_RUN);
        });
        service().execute(ctx,request(),sources());
        assertThat(usage.get("invocations")).isEqualTo(0L);
        assertThat(deadline.get()-System.currentTimeMillis()).isBetween(55_000L,60_000L);
    }

    @Test void reportContractRequiresCompletionScopeAndChecksFieldProvenanceShape() {
        var schema = CodingAnalysisService.primaryReportSchema();
        var row = new LinkedHashMap<String,Object>(Map.of("reportMarkdown", "Source structure only",
            "evidenceReferences", List.of("source"), "limitations", List.of("Field meanings absent")));
        assertThatThrownBy(() -> com.chatchat.agents.orchestration.analysis.graph.WorkspaceResultSchema.validate(row, schema))
            .isInstanceOf(IllegalArgumentException.class);
        row.put("analysisStatus", "INPUT_REQUIRED");
        row.put("fieldProvenance", List.of(Map.of("datasetReference", "source", "sourceField", "opaque_time",
            "meaning", "Unknown", "declarationSource", "Not supplied", "status", "UNCONFIRMED")));
        com.chatchat.agents.orchestration.analysis.graph.WorkspaceResultSchema.validate(row, schema);
        row.put("analysisStatus", "VERIFIED_BY_MODEL");
        assertThatThrownBy(() -> com.chatchat.agents.orchestration.analysis.graph.WorkspaceResultSchema.validate(row, schema))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private CodingTaskOutcome outcome(CodingTaskOutcome.Status status,CodingTaskOutcome.ValidationStatus validation){
        var result=mock(CodingTaskOutcome.class);
        when(result.status()).thenReturn(status);when(result.validationStatus()).thenReturn(validation);
        when(result.executionControl()).thenReturn(new CodingTaskOutcome.ExecutionControl(null,null,status.terminal(),false));
        when(result.usage()).thenReturn(mock(CodingTaskOutcome.Usage.class));
        when(result.inputSnapshotRef()).thenReturn("runtime-evidence:input");when(result.codeArtifactRef()).thenReturn("runtime-evidence:code");
        when(result.executionReceiptRef()).thenReturn("runtime-evidence:receipt");when(result.resultDatasetRef()).thenReturn("runtime-evidence:result");
        when(result.validationRecordRef()).thenReturn("runtime-evidence:validation");when(result.evidenceBundleRef()).thenReturn("runtime-evidence:bundle");
        return result;
    }
    private void prepare(CodingTaskOutcome result){
        policy.setEnvironmentId("environment");
        when(python.environment("environment")).thenReturn(new McpPythonControlPlanePort.EnvironmentView("environment","Python",null,"image","3", "1","512m","8m","8m","1000","NONE",null,"[]",300,false,1,"PUBLISHED"));
        when(inputs.captureConfigured(any(),anyString(),anyList(),eq("source"),anyString(),anyString(),anyLong(),anyLong(),any()))
            .thenReturn(new CodingInputSnapshotService.Snapshot("runtime-evidence:input","c"+"a".repeat(63),"input.jsonl","a".repeat(64),1,30,"source"));
        when(inputs.describe(any(),anyString(),any())).thenReturn(Map.of("rowCount",1,"schemaJson","{}"));
        when(plans.register(any(),anyString(),any(),any())).thenReturn("runtime-evidence:plan");
        when(agent.submit(any())).thenReturn(result);
        when(collector.registeredResult(any(),anyString(),eq("runtime-evidence:result")))
            .thenReturn(new Dataset("runtime-evidence:result",Map.of("parentEvidenceIds",List.of("input")),List.of(Map.of("value","computed"))));
    }
}
