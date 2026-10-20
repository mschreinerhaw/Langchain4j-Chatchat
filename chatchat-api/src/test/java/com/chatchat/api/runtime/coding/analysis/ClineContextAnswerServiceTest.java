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
    final CodingAnalysisService coding = mock(CodingAnalysisService.class);
    final AgentChatModelResolver models = mock(AgentChatModelResolver.class);
    final ChatModel model = mock(ChatModel.class);
    final SkillDefinition skill = mock(SkillDefinition.class);
    final com.chatchat.agents.runtime.store.AgentRunStore runs = mock(com.chatchat.agents.runtime.store.AgentRunStore.class);
    final ClineContextAnswerService service = new ClineContextAnswerService(coding, models, new ObjectMapper(), runs);
    final InteractionContext context = InteractionContext.builder().requestId("req").conversationId("session").build();
    InteractionRequest request() { return InteractionRequest.builder().query("Design a generic table")
        .tenantId("tenant").userId("user").toolInput(Map.of("__agentRunId", "run")).build(); }

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
}
