package com.chatchat.agents.runtime.federation;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.agent.AgentComputeRuntimePort;
import com.chatchat.common.runtime.agent.AgentExecutionOutcome;
import com.chatchat.common.runtime.agent.AgentExecutionRequest;
import com.chatchat.common.runtime.capability.ComputeNodeType;
import com.chatchat.common.runtime.capability.ExecutionUnit;
import org.springframework.stereotype.Component;

/** Bridges Agent Compute into the common five-node execution contract. */
@Component
public class AgentExecutionUnit implements ExecutionUnit<AgentExecutionRequest, AgentExecutionOutcome> {
    private final AgentComputeRuntimePort runtime;

    public AgentExecutionUnit(AgentComputeRuntimePort runtime) { this.runtime = runtime; }

    @Override public ComputeNodeType nodeType() { return ComputeNodeType.AGENT; }
    @Override public Class<AgentExecutionRequest> inputType() { return AgentExecutionRequest.class; }
    @Override public Class<AgentExecutionOutcome> outputType() { return AgentExecutionOutcome.class; }

    @Override public AgentExecutionOutcome execute(AgentExecutionRequest input, KernelDataScope scope) {
        if (input == null || scope == null || !scope.equals(input.scope()))
            throw new IllegalArgumentException("Agent execution scope mismatch");
        return runtime.execute(input);
    }
}
