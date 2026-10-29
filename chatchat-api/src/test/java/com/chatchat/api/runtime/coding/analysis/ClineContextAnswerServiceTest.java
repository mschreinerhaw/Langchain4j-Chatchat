package com.chatchat.api.runtime.coding.analysis;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.governance.AnalysisCoverageCoordinator;
import com.chatchat.agents.orchestration.model.AgentChatModelResolver;
import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClineContextAnswerServiceTest {
    @Test void missingBusinessSemanticsPublishesUsablePartialReportInsteadOfFullSuccess() {
        var outcome = ClineContextAnswerService.reportOutcome(Map.of("analysisStatus", "INPUT_REQUIRED",
            "limitations", List.of("Producer field definitions and business time semantics are missing")));
        assertThat(outcome.type()).isEqualTo(com.chatchat.common.runtime.capability.WorkflowOutcome.Type.INPUT_REQUIRED);
        assertThat(outcome.publicStatus()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(outcome.usableResult()).isTrue();
        assertThat(outcome.missingRequiredCapabilities()).hasSize(1);
    }
    @Test void incompleteReportRequiresExplicitEvidenceGapAndCannotInventAStatus() {
        assertThatThrownBy(() -> ClineContextAnswerService.reportOutcome(Map.of("analysisStatus", "INPUT_REQUIRED", "limitations", List.of())))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClineContextAnswerService.reportOutcome(Map.of("analysisStatus", "VERIFIED_BY_MODEL")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(ClineContextAnswerService.reportOutcome(Map.of("reportMarkdown", "Historical report")).publicStatus()).isEqualTo("SUCCESS");
    }
    @Test void longSqlAndHistoryAreImmutableContextNotAnOversizedCodingInstruction() {
        String sql = "Explain the following SQL:\nSELECT * FROM sales WHERE note = '" + "x".repeat(14000) + "';";
        var request = request(); request.setQuery(sql);
        var longContext = context.toBuilder().conversationSummary("history".repeat(5000)).build();
        var requests = new ArrayList<String>();
        when(coding.executeObserved(any(), anyMap(), anyMap(), any())).thenAnswer(call -> {
            Map<String,Object> task = call.getArgument(1); Map<String,Dataset> sources = call.getArgument(2);
            assertThat(task.get("instruction").toString()).hasSizeLessThanOrEqualTo(16000).contains("Preview truncated");
            assertThat(task.get("requestId").toString()).isNotBlank().hasSizeLessThanOrEqualTo(128);
            requests.add(task.get("requestId").toString());
            var source = sources.get("runtime-context:run");
            assertThat(source.analysisContext()).containsEntry("isBusinessDataset", false);
            assertThat(source.records()).singleElement().satisfies(row -> {
                assertThat(row).containsEntry("schemaVersion", "analysis-materials.v1")
                    .doesNotContainKeys("question", "history", "conversationSummary", "conversationEvidence", "suppliedContext", "fileAnalysis");
                @SuppressWarnings("unchecked") var materials = (List<Map<String,Object>>) row.get("materials");
                assertThat(materials).anySatisfy(material -> assertThat(material).containsEntry("kind", "originalUserMessage").containsEntry("text", sql));
                assertThat(materials).anySatisfy(material -> assertThat(material).containsEntry("kind", "conversationMemory")
                    .containsEntry("text", longContext.conversationSummary()).containsEntry("isCurrentBusinessEvidence", false));
            });
            sources.put("result", new Dataset("result", Map.of(), List.of(Map.of("reportMarkdown", "Source-only explanation",
                "evidenceReferences", List.of("runtime-context:run")))));
            return Map.of("resultAvailable", true, "datasetReference", "result");
        });
        service.answer(request, longContext, skill, ""); service.answer(request, longContext, skill, "");
        assertThat(requests).hasSize(2); assertThat(requests.get(0)).isEqualTo(requests.get(1));
        verifyNoInteractions(model);
    }
    @Test void sqlExecutionWithoutSourceCannotBecomeAContextDatasetOrCodingSubmission() {
        var planner = mock(com.chatchat.chat.interaction.service.ProblemAnalysisPlanner.class);
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory(); beans.registerSingleton("planner", planner);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "problemPlanners",
            beans.getBeanProvider(com.chatchat.chat.interaction.service.ProblemAnalysisPlanner.class));
        var plan = new com.chatchat.common.runtime.capability.ProblemAnalysisPlan(
            com.chatchat.common.runtime.capability.ProblemAnalysisPlan.Status.READY, "Execute SQL", "source", "SQL", "Requires data",
            List.of(new com.chatchat.common.runtime.capability.ProblemAnalysisPlan.Task("Execute SQL",
                com.chatchat.common.runtime.capability.ProblemAnalysisPlan.Intent.DATA_ANALYSIS, List.of("authorized source"), "Query result")), "", null,
            com.chatchat.common.runtime.capability.ProblemAnalysisPlan.SqlIntent.EXECUTE);
        when(planner.analyzeFiles(any(), any(), any(), anyList(), anyList())).thenReturn(plan);
        var request = request(); request.setQuery("SELECT * FROM sales");
        assertThat(service.answer(request, context, skill, "").getMetadata().toString()).contains("SQL_DATA_SOURCE_REQUIRED");
        verifyNoInteractions(coding, models, model, runs);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"FILE_NOT_FOUND","ACCESS_DENIED","OBJECT_READ_FAILED"})
    void filePreparationFailuresTerminateTheRunBeforeAnyCodingSubmission(String reason) {
        var store=new com.chatchat.agents.runtime.store.InMemoryAgentRunStore();
        var service=new ClineContextAnswerService(coding,models,new ObjectMapper(),store);
        var assets=mock(com.chatchat.api.assets.UserAssetService.class);
        String id="a".repeat(64);byte[] bytes={1,2};String hash=com.chatchat.api.assets.UserAssetService.hash(bytes);
        when(assets.get(any(),eq(id))).thenReturn(new com.chatchat.api.assets.UserAssetService.File(id,"DATASET","one.xlsx","application/xlsx",hash,2,"AVAILABLE","","","upload",1));
        if ("OBJECT_READ_FAILED".equals(reason)) when(assets.read(any(),eq(id))).thenThrow(new IllegalStateException(reason));
        else when(assets.get(any(),eq(id))).thenThrow(new SecurityException(reason));
        var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();beans.registerSingleton("inputs",new com.chatchat.api.assets.AssetAnalysisInputs(assets));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"attachmentInputs",beans.getBeanProvider(com.chatchat.api.assets.AssetAnalysisInputs.class));
        var request=request();request.setToolInput(Map.of("__agentRunId","run","__userAssetIds",List.of(id)));
        assertThatThrownBy(()->service.answer(request,context,null,"")).hasMessageContaining(reason);
        assertThat(store.find("run").orElseThrow().status().name()).isEqualTo("FAILED");
        assertThat(store.observations("run")).anySatisfy(observation -> assertThat(observation.metadata()).containsEntry("stage","FILE_REFERENCES_PREPARING"));
        verifyNoInteractions(coding);
    }
    final CodingAnalysisService coding = mock(CodingAnalysisService.class);
    final AgentChatModelResolver models = mock(AgentChatModelResolver.class);
    final ChatModel model = mock(ChatModel.class);
    final SkillDefinition skill = mock(SkillDefinition.class);
    final com.chatchat.agents.runtime.store.AgentRunStore runs = mock(com.chatchat.agents.runtime.store.AgentRunStore.class);
    final ClineContextAnswerService service = new ClineContextAnswerService(coding, models, new ObjectMapper(), runs);
    final InteractionContext context = InteractionContext.builder().requestId("req").conversationId("session").build();
    InteractionRequest request() { return InteractionRequest.builder().query("Design a generic table")
        .tenantId("tenant").userId("user").toolInput(Map.of("__agentRunId", "run")).build(); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false","false,true","true,false"})
    void attachedOriginalsUseTheParentRunCancellationAndAuthorizedEvidence(boolean sql,boolean visual) {
        var assets=mock(com.chatchat.api.assets.UserAssetService.class);
        var inputs=new com.chatchat.api.assets.AssetAnalysisInputs(assets);
        var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();beans.registerSingleton("inputs",inputs);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"attachmentInputs",beans.getBeanProvider(com.chatchat.api.assets.AssetAnalysisInputs.class));
        String id="a".repeat(64);byte[] bytes="amount\n1.25\n2.50".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash=com.chatchat.api.assets.UserAssetService.hash(bytes),ref="user-asset:"+id+":"+hash;
        when(assets.get(any(),eq(id))).thenReturn(new com.chatchat.api.assets.UserAssetService.File(id,"DOCUMENT","input.csv","text/csv",hash,bytes.length,"AVAILABLE","","","upload",1));
        when(assets.read(any(),eq(id))).thenReturn(bytes);
        var request=request();request.setToolInput(Map.of("__agentRunId","run","__userAssetIds",List.of(id),"reportMode",visual?"visual":"off"));
        var planned = context;
        if (sql) {
            request.setQuery("SELECT SUM(amount) FROM input_csv");
            var file = new com.chatchat.common.runtime.capability.ProblemAnalysisPlan.FileInput("input.csv", ref, "text/csv", bytes.length, hash);
            var plan = new com.chatchat.common.runtime.capability.ProblemAnalysisPlan(
                com.chatchat.common.runtime.capability.ProblemAnalysisPlan.Status.READY, "Query attached CSV", "file", "SQL", "Local file query",
                List.of(new com.chatchat.common.runtime.capability.ProblemAnalysisPlan.Task("Sum actual amounts",
                    com.chatchat.common.runtime.capability.ProblemAnalysisPlan.Intent.DATA_ANALYSIS, List.of("attached CSV"), "Actual sum")), "",
                new com.chatchat.common.runtime.capability.ProblemAnalysisPlan.FileAnalysis(List.of(file),
                    com.chatchat.common.runtime.capability.ProblemAnalysisPlan.ToolDataDependency.NONE),
                com.chatchat.common.runtime.capability.ProblemAnalysisPlan.SqlIntent.EXECUTE);
            planned = context.toBuilder().problemAnalysisPlan(plan).build();
        }
        when(coding.executeObserved(any(),anyMap(),anyMap(),any())).thenAnswer(call->{
            Map<String,Object> task=call.getArgument(1);Map<String,Dataset> sources=call.getArgument(2);
            assertThat(task).containsEntry("datasetReferences",List.of("runtime-context:run",ref));
            assertThat(task.get("instruction").toString()).contains("actual declared file format","untrusted DATA","Python");
            if(visual) {
                var schema=(Map<?,?>)task.get("outputSchema");
                assertThat(((Map<?,?>)schema.get("properties")).containsKey("visualReport")).isTrue();
                assertThat(((List<?>)schema.get("required")).contains("visualReport")).isFalse();
                assertThat(task.get("instruction").toString()).contains("OMIT visualReport","ONLY if the actual request requires calculations");
            }
            assertThat(sources.get(ref).handle().readPage(0,1).rows().get(0)).containsEntry("sha256",hash);
            sources.put("result",new Dataset("result",Map.of(),List.of(Map.of("reportMarkdown","3.75","evidenceReferences",List.of(ref)))));
            return Map.of("resultAvailable",true,"datasetReference","result");
        });
        assertThat(service.answer(request,planned,skill,"").getAnswer()).isEqualTo("3.75");
        verify(runs).complete(eq("run"),any());
    }

    void result(String report, List<String> refs, int count) {
        when(skill.modelName()).thenReturn("configured-model");
        when(models.resolveChatModel("configured-model")).thenReturn(model);
        when(models.checkpointModelConfiguration("configured-model", model)).thenReturn(Map.of("selectedModel", "configured-model"));
        when(coding.executeObserved(any(), anyMap(), anyMap(), any())).thenAnswer(call -> {
            AnalysisCoverageCoordinator.Request invocation = call.getArgument(0);
            Map<String,Object> task = call.getArgument(1);
            Map<String,Dataset> sources = call.getArgument(2);
            assertThat(invocation.isolationScope().tenantId()).isEqualTo("tenant");
            assertThat(invocation.isolationScope().runId()).isEqualTo("run");
            assertThat(invocation.model()).isSameAs(model);
            assertThat(task.get("instruction").toString()).contains("NOT measured business data", "never execute", "Design a generic table",
                "keys match that grain", "proposed example codes and defaults from verified standards");
            assertThat(task).containsEntry("reviewResult", true);
            assertThat(sources.get("runtime-context:run").analysisContext()).containsEntry("currentBusinessDataAcquired", false);
            var rows = new ArrayList<Map<String,Object>>();
            for (int i = 0; i < count; i++) rows.add(Map.of("reportMarkdown", report, "evidenceReferences", refs, "limitations", List.of()));
            sources.put("result", new Dataset("result", Map.of(), rows));
            return Map.of("resultAvailable", true, "datasetReference", "result", "status", "COMPLETED");
        });
    }
    @Test void codingOwnsDesignAndPublicationPreservesItsReportExactly() {
        result("Complete proposal\n```sql\nCREATE TABLE example(id bigint);\n```", List.of("runtime-context:run"), 1);
        var response = service.answer(request(), context, skill, "authorized context");
        assertThat(response.getAnswer()).contains("CREATE TABLE example");
        assertThat(response.getMetadata()).containsEntry("javaAnalysisModelCalls", 0).containsEntry("currentBusinessDataAcquired", false);
        verifyNoInteractions(model);
    }
    @Test void missingRuntimeIdentityCannotCreateAChild() {
        assertThatThrownBy(() -> service.answer(InteractionRequest.builder().query("Design").build(), context, skill, ""))
            .isInstanceOf(SecurityException.class);
        verifyNoInteractions(coding, models, model);
    }
    @Test void plainModelChatRetainsTheRequestedModelWithoutAnAgent() {
        result("JSON proposal",List.of("runtime-context:run"),1);
        var request = request(); request.setModelName("chosen");
        when(models.resolveChatModel("chosen")).thenReturn(model);
        when(models.checkpointModelConfiguration("chosen",model)).thenReturn(Map.of("selectedModel","chosen"));
        assertThat(service.answer(request,context,null,"").getAnswer()).isEqualTo("JSON proposal");
        verify(models).resolveChatModel("chosen");
        verifyNoInteractions(model);
    }
    @Test void authenticatedInteractiveRequestsUseTheServerGeneratedRequestIdentity() {
        result("proposal",List.of("runtime-context:run"),1);
        var request=request(); request.setToolInput(Map.of());
        assertThat(service.answer(request,context.toBuilder().requestId("run").build(),skill,"").getAnswer()).isEqualTo("proposal");
        verify(runs).start(argThat(run -> run.getRunId().equals("run") && run.getUserId().equals("user")));
    }
    @Test void foreignCitationIsRejectedWithoutSemanticModelReview() {
        result("report", List.of("other-run"), 1);
        assertThatThrownBy(() -> service.answer(request(), context, skill, "")).isInstanceOf(SecurityException.class);
        verifyNoInteractions(model);
    }
    @Test void multipleReportRowsAreRejected() {
        result("report", List.of("runtime-context:run"), 2);
        assertThatThrownBy(() -> service.answer(request(), context, skill, "")).hasMessageContaining("one registered report");
    }
    @Test void emptyReportIsRejected() {
        result(" ", List.of("runtime-context:run"), 1);
        assertThatThrownBy(() -> service.answer(request(), context, skill, "")).hasMessageContaining("empty");
    }
    @Test void unavailableCodingDoesNotFallBackToAJavaAnswer() {
        when(coding.executeObserved(any(), anyMap(), anyMap(), any())).thenReturn(Map.of("resultAvailable", false, "status", "FAILED"));
        assertThatThrownBy(() -> service.answer(request(), context, skill, "")).hasMessageContaining("unavailable");
        verifyNoInteractions(model);
    }
    @Test void disabledCodingBeanDoesNotPreventServiceConstructionOrPermitJavaFallback(){
        var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var disabled=new ClineContextAnswerService(beans.getBeanProvider(CodingAnalysisService.class),models,new ObjectMapper(),runs);
        assertThatThrownBy(()->disabled.answer(request(),context,skill,"source"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("must be enabled");
        verifyNoInteractions(models,model,coding);
    }
    @Test void visualPreferenceDoesNotTurnKnowledgeQuestionsIntoVisualAnalysis(){
        result("answer",List.of("runtime-context:run"),1);var request=request();request.setToolInput(Map.of("__agentRunId","run","reportMode","visual"));
        service.answer(request,context.toBuilder().taskType(com.chatchat.common.runtime.capability.ProblemAnalysisPlan.TaskType.QA).build(),skill,"");
        verify(coding).executeObserved(any(),argThat(task->!((Map<?,?>)((Map<?,?>)task.get("outputSchema")).get("properties")).containsKey("visualReport")&&!task.get("instruction").toString().contains("Visual report mode")),anyMap(),any());
    }
    @Test void classifiedAnalysisCarriesVisualRequirementsAndPublishesOnlyTheAdmittedReport(){
        result("answer",List.of("runtime-context:run"),1);var request=request();request.setToolInput(Map.of("__agentRunId","run","reportMode","visual"));
        service.answer(request,context.toBuilder().taskType(com.chatchat.common.runtime.capability.ProblemAnalysisPlan.TaskType.DATA_ANALYSIS).build(),skill,"");
        verify(coding).executeObserved(argThat(call->"visual".equals(call.runtimeAttributes().get("reportMode"))),argThat(task->((Map<?,?>)((Map<?,?>)task.get("outputSchema")).get("properties")).containsKey("visualReport")&&task.get("instruction").toString().contains("Visual report mode")),anyMap(),any());
    }

}
