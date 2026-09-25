package com.chatchat.api.runtime;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.evidence.ToolAnalysisEvidence;
import com.chatchat.common.runtime.analysis.execution.WorkflowExecutionResult;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreauthorizedStructuredDataOperatorTest {
    private final RegisteredToolAnalysisOperator tools = mock(RegisteredToolAnalysisOperator.class);
    private final PreauthorizedStructuredDataOperator operator =
        new PreauthorizedStructuredDataOperator(tools, new ObjectMapper());

    @Test void turnsVerifiedTemplateResultIntoBoundedStructuredEvidence() {
        when(tools.execute(any(), any(), any())).thenReturn(new WorkflowExecutionResult(List.of(
            new ToolAnalysisEvidence("e-1", PreauthorizedStructuredDataOperator.TOOL_NAME, "call-1",
                "{\"success\":true,\"data\":{\"complete\":true,\"rowCount\":1,\"rows\":[{\"total\":42}]}}", Map.of())),
            Map.of(), List.of()));

        var result = operator.execute(context(), scope(), null);

        assertThat(result.evidence()).hasSize(1);
        assertThat(result.evidence().get(0).capability().name()).isEqualTo("STRUCTURED_DATA");
        assertThat(result.evidence().get(0).attributes()).containsKey("remoteProjection");
        verify(tools).execute(any(), any(), any());
    }

    @Test void refusesAnUnverifiableOrFailedToolResult() {
        when(tools.execute(any(), any(), any())).thenReturn(new WorkflowExecutionResult(List.of(
            new ToolAnalysisEvidence("e-1", PreauthorizedStructuredDataOperator.TOOL_NAME, "call-1",
                "{\"success\":false,\"data\":{\"rowCount\":1,\"rows\":[{}]}}", Map.of())),
            Map.of(), List.of()));

        assertThat(operator.execute(context(), scope(), null).evidence()).isEmpty();
    }

    private AnalysisContext context() {
        return new AnalysisContext("analyze sales", new KernelDataScope("tenant", "user", "request",
            null, "run", null, Map.of()), "sales-skill", List.of(), List.of(), List.of(), null,
            Map.of(PreauthorizedStructuredDataOperator.TEMPLATE_ID, "SALES_TOTAL",
                PreauthorizedStructuredDataOperator.ASSET_NAME, "sales",
                PreauthorizedStructuredDataOperator.ENVIRONMENT, "PROD",
                PreauthorizedStructuredDataOperator.PARAMETERS, Map.of("year", 2025)));
    }

    private AnalysisScope scope() {
        return new AnalysisScope("tenant", "user", List.of(), List.of(), Map.of());
    }
}
