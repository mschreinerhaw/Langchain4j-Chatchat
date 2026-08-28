package com.chatchat.chat.task.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentExecutionStateTest {

    @Test
    void mapsLegacyTaskAndRuntimeStatusesToCanonicalLifecycle() {
        assertThat(AgentExecutionState.fromWire("PENDING")).isEqualTo(AgentExecutionState.SUBMITTED);
        assertThat(AgentExecutionState.fromWire("WAIT_MODEL")).isEqualTo(AgentExecutionState.PLANNING);
        assertThat(AgentExecutionState.fromWire("WAIT_CONFIRMATION")).isEqualTo(AgentExecutionState.WAITING_APPROVAL);
        assertThat(AgentExecutionState.fromWire("COMPLETED_WITH_PARTIAL_EVIDENCE")).isEqualTo(AgentExecutionState.PARTIAL);
        assertThat(AgentExecutionState.fromWire("SUCCESS").terminal()).isTrue();
    }

    @Test
    void rejectsUnknownStates() {
        assertThatThrownBy(() -> AgentExecutionState.fromWire("invented"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
