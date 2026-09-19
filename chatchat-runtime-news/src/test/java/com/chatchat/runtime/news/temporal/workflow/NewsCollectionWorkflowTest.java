package com.chatchat.runtime.news.temporal.workflow;

import com.chatchat.runtime.news.temporal.activity.NewsCollectionActivity;
import com.chatchat.runtime.news.temporal.contract.NewsCollectionWorkflowCommand;
import com.chatchat.runtime.news.temporal.contract.NewsCollectionWorkflowResult;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class NewsCollectionWorkflowTest {
    @Test
    void executesCollectionAsATemporalActivity() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker("news-test");
            worker.registerWorkflowImplementationTypes(NewsCollectionWorkflowImpl.class);
            var expected = new NewsCollectionWorkflowResult(
                "temporal:test:run", 7L, "COMPLETED", 5, 4, 1, 0, 0, null);
            AtomicBoolean invoked = new AtomicBoolean();
            NewsCollectionActivity activity = sourceId -> {
                invoked.set(true);
                assertThat(sourceId).isEqualTo(7L);
                return expected;
            };
            worker.registerActivitiesImplementations(activity);
            environment.start();

            NewsCollectionWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                NewsCollectionWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId("news-workflow-test")
                    .setTaskQueue("news-test").build());

            assertThat(workflow.collect(new NewsCollectionWorkflowCommand(7L, 60, 1)))
                .isEqualTo(expected);
            assertThat(invoked).isTrue();
        }
    }
}
