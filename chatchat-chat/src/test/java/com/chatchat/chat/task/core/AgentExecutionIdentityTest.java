package com.chatchat.chat.task.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutionIdentityTest {

    @Test
    void retryPreservesExecutionAndBuildsAttemptLineage() {
        AgentExecutionIdentity first = AgentExecutionIdentity.initial("task-1");
        AgentExecutionIdentity retry = first.retry();

        assertThat(retry.executionId()).isEqualTo(first.executionId());
        assertThat(retry.rootExecutionId()).isEqualTo(first.rootExecutionId());
        assertThat(retry.parentAttemptId()).isEqualTo(first.attemptId());
        assertThat(retry.attemptId()).isNotEqualTo(first.attemptId());
        assertThat(retry.attemptNumber()).isEqualTo(2);
    }

    @Test
    void legacyTaskCanBeProjectedWithoutMissingIdentity() {
        AgentTaskLatestEntity task = new AgentTaskLatestEntity();
        task.setTaskId("legacy-task");

        AgentExecutionIdentity identity = AgentExecutionIdentity.from(task);

        assertThat(identity.executionId()).isEqualTo("legacy-task");
        assertThat(identity.attemptNumber()).isEqualTo(1);
        assertThat(identity.attemptId()).startsWith("att-1-");
    }
}
