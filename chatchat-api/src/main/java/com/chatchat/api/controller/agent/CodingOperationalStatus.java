package com.chatchat.api.controller.agent;

import com.chatchat.common.runtime.coding.CodingErrorCode;
import com.chatchat.common.runtime.coding.CodingTaskOutcome;
import java.time.Instant;
import java.util.List;

/** Source-free monitoring facts. Never add prompts, summaries, source locators or arbitrary payloads. */
public record CodingOperationalStatus(String runId, boolean detailOwner, List<Task> tasks) {
    public CodingOperationalStatus { tasks = List.copyOf(tasks); }
    public record Task(String taskId, CodingTaskOutcome.Status status,
            CodingTaskOutcome.ValidationStatus validationStatus, CodingErrorCode reasonCode,
            Instant createdAt, Instant updatedAt, Instant startedAt, Instant endedAt,
            boolean cancellationRequested, long lastEventSequence,
            CodingTaskOutcome.Counters consumed, CodingTaskOutcome.ExecutionControl executionControl) {
        public static Task from(CodingTaskOutcome outcome) {
            return new Task(outcome.taskId(), outcome.status(), outcome.validationStatus(), outcome.reasonCode(),
                outcome.createdAt(), outcome.updatedAt(), outcome.startedAt(), outcome.endedAt(),
                outcome.cancellationRequested(), outcome.lastEventSequence(),
                outcome.usage() == null ? null : outcome.usage().consumed(), outcome.executionControl());
        }
    }
}
