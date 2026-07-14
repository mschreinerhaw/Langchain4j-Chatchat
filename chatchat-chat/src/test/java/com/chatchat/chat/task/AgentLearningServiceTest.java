package com.chatchat.chat.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentLearningServiceTest {

    @Test
    void resolvesOnlySampledExperienceAsStructuredPlannerPrior() {
        AgentExperienceRepository experienceRepository = mock(AgentExperienceRepository.class);
        AgentExperienceIndexRepository indexRepository = mock(AgentExperienceIndexRepository.class);
        AgentEventStore eventStore = mock(AgentEventStore.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        AgentLearningProperties properties = new AgentLearningProperties();
        properties.setMinimumRuntimeSamples(2);
        AgentExperienceIndexEntity sampled = experienceIndex("exp-2", 2, 5, 1);
        when(indexRepository.findRuntimeCandidates(
            anyString(), nullable(String.class), nullable(String.class), nullable(String.class), any(Pageable.class)))
            .thenReturn(List.of(sampled));
        AgentLearningService service = new AgentLearningService(
            experienceRepository, indexRepository, eventStore, new ObjectMapper(), chatModelProvider, properties);

        AgentLearningService.RuntimeExperienceContext context = service.resolveRuntimeExperience(
            "tenant-1", "agent-1", "数据库诊断绑定失败", List.of("sql_query_execute"));

        assertThat(context.matchedExperienceIds()).containsExactly("exp-2");
        assertThat(context.plannerPrior())
            .containsEntry("workflowMutationAllowed", false)
            .containsEntry("bindingFailureObserved", true)
            .containsEntry("sampleCount", 2L);
        assertThat(context.prompt()).contains("Never add, replace, remove or reorder");
    }

    @Test
    void doesNotPromoteOnePositiveSampleIntoRuntimePrior() {
        AgentExperienceIndexRepository indexRepository = mock(AgentExperienceIndexRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        AgentLearningProperties properties = new AgentLearningProperties();
        properties.setMinimumRuntimeSamples(2);
        AgentExperienceIndexEntity single = experienceIndex("exp-1", 1, 3, 0);
        when(indexRepository.findRuntimeCandidates(
            anyString(), nullable(String.class), nullable(String.class), nullable(String.class), any(Pageable.class)))
            .thenReturn(List.of(single));
        AgentLearningService service = new AgentLearningService(
            mock(AgentExperienceRepository.class), indexRepository, mock(AgentEventStore.class),
            new ObjectMapper(), chatModelProvider, properties);

        AgentLearningService.RuntimeExperienceContext context = service.resolveRuntimeExperience(
            "tenant-1", "agent-1", "数据库诊断绑定失败", List.of("sql_query_execute"));

        assertThat(context.prompt()).isBlank();
        assertThat(context.plannerPrior()).isEmpty();
    }

    @Test
    void feedbackScoreUsesOnlyOutcomeSignals() {
        AgentExperienceRepository experienceRepository = mock(AgentExperienceRepository.class);
        AgentExperienceIndexRepository indexRepository = mock(AgentExperienceIndexRepository.class);
        AgentEventStore eventStore = mock(AgentEventStore.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        AgentLearningProperties properties = new AgentLearningProperties();
        properties.setModelAttributionEnabled(false);
        AgentTaskLatestEntity task = new AgentTaskLatestEntity();
        task.setTenantId("tenant-1");
        task.setTaskId("task-1");
        task.setSessionId("session-1");
        task.setQuestion("数据库诊断");
        task.setFeedbackUseful(true);
        task.setFeedbackAdopted(false);
        task.setFeedbackResolved(false);
        task.setFeedbackReasonCategory("TOOL_CALL_ERROR");
        task.setFeedbackComment("详细负面说明不应增加评价分");
        when(experienceRepository.findByTenantIdAndTaskId("tenant-1", "task-1")).thenReturn(Optional.empty());
        when(experienceRepository.save(any(AgentExperienceEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(experienceRepository.findByTenantId("tenant-1")).thenReturn(List.of());
        AgentLearningService service = new AgentLearningService(
            experienceRepository, indexRepository, eventStore, new ObjectMapper(), chatModelProvider, properties);

        AgentExperienceSummary.ExperienceItem item = service.recordExperience(task, new AgentTaskFeedbackRequest());

        assertThat(item.feedbackScore()).isEqualTo(33);
    }

    private AgentExperienceIndexEntity experienceIndex(String id, long samples, long successes, long failures) {
        AgentExperienceIndexEntity index = new AgentExperienceIndexEntity();
        index.setId(id);
        index.setTenantId("tenant-1");
        index.setAgentId("agent-1");
        index.setScenario("database_diagnosis");
        index.setIntentType("general");
        index.setKeywords("数据库,诊断,绑定失败");
        index.setToolName("sql_query_execute");
        index.setToolChain("asset_search>sql_query_execute");
        index.setErrorCode("BINDING_FAILED");
        index.setSampleCount(samples);
        index.setUsefulCount(Math.min(successes, samples));
        index.setAdoptedCount(Math.min(Math.max(0, successes - samples), samples));
        index.setResolvedCount(Math.min(Math.max(0, successes - samples * 2), samples));
        index.setFailedCount(failures);
        index.setSuccessRate(samples == 0 ? 0D : successes * 100D / (samples * 3D));
        index.setBestPractice("保留已验证参数绑定");
        index.setAvoidPattern("避免缺少上游字段契约");
        return index;
    }
}
