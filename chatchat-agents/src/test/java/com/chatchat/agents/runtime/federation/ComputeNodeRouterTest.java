package com.chatchat.agents.runtime.federation;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.capability.ComputeNodeType;
import com.chatchat.common.runtime.capability.ExecutionUnit;
import com.chatchat.common.runtime.capability.ModelPromptRequest;
import com.chatchat.agents.orchestration.model.AgentChatModelResolver;
import com.chatchat.agents.runtime.analysis.workflow.AnalysisWorkflowExecutionUnit;
import com.chatchat.agents.runtime.analysis.workflow.StructuredDataAnalysisWorkflow;
import com.chatchat.agents.runtime.analysis.workflow.StructuredDataExecutionUnit;
import com.chatchat.common.runtime.agent.AgentComputeRuntimePort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ComputeNodeRouterTest {
    private final KernelDataScope scope = new KernelDataScope("tenant", "user", "request", "conversation",
        "run", "test", Map.of());

    @Test void routesTypedUnitAndRejectsMissingOrMismatchedNode() {
        ComputeNodeRouter router = new ComputeNodeRouter(List.of(unit()));
        assertThat(router.available(ComputeNodeType.AGENT)).isTrue();
        assertThat(router.available(ComputeNodeType.DATA)).isFalse();
        assertThat(router.execute(ComputeNodeType.AGENT, "task", Integer.class, scope)).isEqualTo(4);
        assertThatThrownBy(() -> router.execute(ComputeNodeType.DATA, "task", Integer.class, scope))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> router.execute(ComputeNodeType.AGENT, 1, Integer.class, scope))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsDuplicateNodeBindings() {
        assertThatThrownBy(() -> new ComputeNodeRouter(List.of(unit(), unit())))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test void bindsAllFiveEstablishedComputeEntrypoints() {
        @SuppressWarnings("unchecked") ObjectProvider<com.chatchat.common.runtime.analysis.spi.AnalysisRuntimePort> workflows =
            mock(ObjectProvider.class);
        ComputeNodeRouter router = new ComputeNodeRouter(List.of(
            new ModelExecutionUnit(mock(AgentChatModelResolver.class)),
            new AgentExecutionUnit(mock(AgentComputeRuntimePort.class)),
            new KnowledgeSkillExecutionUnit(mock(ObjectProvider.class)),
            new StructuredDataExecutionUnit(mock(StructuredDataAnalysisWorkflow.class)),
            new AnalysisWorkflowExecutionUnit(workflows)));
        for (ComputeNodeType type : ComputeNodeType.values()) assertThat(router.available(type)).isTrue();
        KernelDataScope another = new KernelDataScope("other", "user", "request", "conversation", "run", "test", Map.of());
        assertThatThrownBy(() -> router.execute(ComputeNodeType.MODEL,
            new ModelPromptRequest("", "hello", scope), String.class, another))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private ExecutionUnit<String, Integer> unit() {
        return new ExecutionUnit<>() {
            public ComputeNodeType nodeType() { return ComputeNodeType.AGENT; }
            public Class<String> inputType() { return String.class; }
            public Class<Integer> outputType() { return Integer.class; }
            public Integer execute(String input, KernelDataScope actual) {
                assertThat(actual).isEqualTo(scope);
                return input.length();
            }
        };
    }
}
