package com.chatchat.chat.interaction.service;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.agents.runtime.event.*;
import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.runtime.capability.ProblemAnalysisPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProblemAnalysisLifecycleTest {
    private static final String READY = """
        {"status":"READY","objective":"分析","subject":"数据","domain":"","explanation":"分析已提供的数据",
        "tasks":[{"objective":"分析数据","intent":"DATA_ANALYSIS","dataRequirements":[],"expectedResult":"分析结果"}],
        "clarificationQuestion":""}
        """;

    @Test @Timeout(45)
    void fiveParentTasksRemainRunningBeyondThirtySecondsUntilTheirModelsReturn() throws Exception {
        var entered = new CountDownLatch(5);
        var release = new CountDownLatch(1);
        var model = mock(ChatModel.class);
        var taskContext = new ThreadLocal<String>();
        when(model.chat(anyString())).thenAnswer(invocation -> {
            // The model runs in its owning task, retaining thread-scoped context.
            assertThat(taskContext.get()).isEqualTo("parent-context");
            entered.countDown();
            assertThat(release.await(40, TimeUnit.SECONDS)).isTrue();
            return READY;
        });
        var events = new CopyOnWriteArrayList<AgentRunEvent>();
        var planner = planner(model, events);
        var executor = Executors.newFixedThreadPool(5);
        var parents = new ArrayList<Future<ProblemAnalysisPlan>>();
        try {
            for (int index = 0; index < 5; index++) {
                String runId = "parent-" + index;
                parents.add(executor.submit(() -> {
                    taskContext.set("parent-context");
                    try { return planner.analyze(request(runId, () -> false), InteractionContext.builder().build(), mock(SkillDefinition.class)); }
                    finally { taskContext.remove(); }
                }));
            }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            // Regression: the removed 30-second Future.get used to finish these parents as failed.
            assertThatThrownBy(() -> parents.get(0).get(31, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(parents).allMatch(parent -> !parent.isDone());
            assertThat(events).hasSize(5).allMatch(event -> state(event).equals("RUNNING"));
            release.countDown();
            for (var parent : parents) assertThat(parent.get(5, TimeUnit.SECONDS).status()).isEqualTo(ProblemAnalysisPlan.Status.READY);
            for (int index = 0; index < 5; index++) {
                String runId = "parent-" + index;
                assertThat(events.stream().filter(event -> event.runId().equals(runId)).map(ProblemAnalysisLifecycleTest::state))
                    .containsExactly("RUNNING", "COMPLETED");
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test @Timeout(10)
    void cancellationDuringInferenceCannotBePromotedToReadyOrPlanningFailure() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return READY;
        });
        var events = new CopyOnWriteArrayList<AgentRunEvent>();
        var planner = planner(model, events);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var parent = executor.submit(() -> planner.analyze(request("cancelled", cancelled::get),
                InteractionContext.builder().build(), mock(SkillDefinition.class)));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
            release.countDown();
            assertThatThrownBy(() -> parent.get(3, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(CancellationException.class);
            assertThat(events.stream().map(ProblemAnalysisLifecycleTest::state)).containsExactly("RUNNING", "CANCELLED");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test void modelInterruptionPropagatesCancellationAndRetainsInterruptFlag() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenThrow(new IllegalStateException(new InterruptedException("cancelled")));
        var events = new CopyOnWriteArrayList<AgentRunEvent>();
        try {
            assertThatThrownBy(() -> planner(model, events).analyze(request("interrupt", () -> false),
                InteractionContext.builder().build(), mock(SkillDefinition.class))).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(events.stream().map(ProblemAnalysisLifecycleTest::state)).containsExactly("RUNNING", "CANCELLED");
        } finally { Thread.interrupted(); }
    }

    private InteractionRequest request(String runId, BooleanSupplier cancellation) {
        return InteractionRequest.builder().query("分析数据")
            .toolInput(Map.of("__agentRunId", runId, "__agentCancellation", cancellation)).build();
    }

    @SuppressWarnings("unchecked")
    private ProblemAnalysisPlanner planner(ChatModel model, List<AgentRunEvent> events) {
        var publisher = mock(AgentRunEventPublisher.class);
        doAnswer(invocation -> { events.add(invocation.getArgument(0)); return null; }).when(publisher).publish(any());
        ObjectProvider<AgentRunEventPublisher> publishers = mock(ObjectProvider.class);
        when(publishers.orderedStream()).thenAnswer(invocation -> java.util.stream.Stream.of(publisher));
        return new ProblemAnalysisPlanner(model, mock(ConfigurableChatModelFactory.class), new ObjectMapper(), publishers);
    }

    private static String state(AgentRunEvent event) {
        return String.valueOf(((Map<?, ?>) event.payload().get("metadata")).get("eventState"));
    }
}
