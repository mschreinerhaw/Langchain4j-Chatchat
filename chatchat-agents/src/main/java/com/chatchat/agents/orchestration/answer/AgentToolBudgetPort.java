package com.chatchat.agents.orchestration.answer;

import com.chatchat.common.interaction.InteractionToolTrace;

import java.util.List;
import java.util.Map;

/** Tool-budget capability required by orchestration and workflow recovery. */
public interface AgentToolBudgetPort {

    boolean markToolBudgetExceeded(String requestedToolName,
                                   int maxToolCalls,
                                   List<InteractionToolTrace> traces,
                                   Map<String, Object> metadata,
                                   List<String> observations);
}
