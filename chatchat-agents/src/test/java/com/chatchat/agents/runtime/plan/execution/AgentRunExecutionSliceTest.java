package com.chatchat.agents.runtime.plan.execution;

import com.chatchat.agents.runtime.AgentRunResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRunExecutionSliceTest {

    @Test
    void completedSliceRequiresOnlyCompletedResult() {
        AgentRunResult result = AgentRunResult.builder().answer("done").build();

        AgentRunExecutionSlice slice = AgentRunExecutionSlice.completed(result);

        assertEquals(AgentRunExecutionSlice.COMPLETED, slice.status());
        assertEquals(result, slice.completedResult());
        assertThrows(IllegalArgumentException.class,
            () -> new AgentRunExecutionSlice(AgentRunExecutionSlice.COMPLETED, null, null));
    }
}
