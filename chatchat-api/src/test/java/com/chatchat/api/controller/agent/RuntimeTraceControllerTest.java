package com.chatchat.api.controller.agent;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.spi.AnalysisProgressPort;
import com.chatchat.common.runtime.evidence.ExecutionTraceRetrievalPort;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeTraceControllerTest {
    ExecutionTraceRetrievalPort traces = mock(ExecutionTraceRetrievalPort.class);
    AnalysisProgressPort progress = mock(AnalysisProgressPort.class);
    RuntimeTraceController controller = new RuntimeTraceController(traces, progress);

    @Test void callerIdentityComesOnlyFromAuthenticationAndMissingRunsStayOpaque() {
        var request = authenticated();
        var expected = new KernelDataScope("tenant", "owner", null, null, "source-run", null, Map.of());
        when(traces.search(expected, "result", 10)).thenReturn(List.of());
        controller.search(new RuntimeTraceController.Search("source-run", "result", 10), request);
        verify(traces).search(expected, "result", 10);
        when(progress.state(expected)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.state("source-run", request))
            .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode().value()).isEqualTo(404));
        when(traces.get(expected, "evidence")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.get("evidence", "source-run", request))
            .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode().value()).isEqualTo(404));
    }

    @Test void unauthenticatedAndUnboundedRequestsCannotReachStore() {
        assertThatThrownBy(() -> controller.state("run", new MockHttpServletRequest()))
            .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode().value()).isEqualTo(401));
        assertThatThrownBy(() -> controller.search(new RuntimeTraceController.Search("run", "x".repeat(257), 10), authenticated()))
            .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(traces, progress);
    }
    @Test void httpRoutesBindNamedParametersWithoutCompilerParameterMetadata() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        when(progress.state(any())).thenReturn(Optional.empty());
        when(traces.get(any(), anyString())).thenReturn(Optional.empty());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/agent/analysis/runtime/runs/source")
            .requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant")
            .requestAttr(ApiAuthenticationFilter.CURRENT_USER_ID, "owner"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/agent/analysis/runtime/traces/evidence")
            .param("runId", "source").requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant")
            .requestAttr(ApiAuthenticationFilter.CURRENT_USER_ID, "owner"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        var expected = new KernelDataScope("tenant", "owner", null, null, "source", null, Map.of());
        verify(progress).state(expected);
        verify(traces).get(expected, "evidence");
    }
    private MockHttpServletRequest authenticated() {
        var request = new MockHttpServletRequest();
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID, "owner");
        return request;
    }
}
