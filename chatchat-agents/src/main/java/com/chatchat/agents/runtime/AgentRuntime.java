package com.chatchat.agents.runtime;

import com.chatchat.agents.runtime.event.AgentRunEvent;
import com.chatchat.agents.runtime.observation.AgentObservation;
import com.chatchat.agents.runtime.run.AgentRun;
import com.chatchat.agents.runtime.run.AgentRunQuery;
import com.chatchat.agents.runtime.run.AgentRunStep;

import com.chatchat.common.kernel.KernelComponentDescriptor;
import com.chatchat.common.kernel.KernelDataBoundary;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.kernel.KernelProtocol;
import com.chatchat.common.kernel.KernelProtocolCatalog;
import com.chatchat.common.kernel.KernelViolationException;
import com.chatchat.common.kernel.RuntimeOsKernel;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface AgentRuntime extends RuntimeOsKernel<AgentRunRequest, AgentRunResult> {

    AgentRunResult run(AgentRunRequest request);

    AgentRunHandle submit(AgentRunRequest request);

    AgentRun cancel(String runId);

    Optional<AgentRun> find(String runId);

    List<AgentRun> list(AgentRunQuery query);

    List<AgentRunEvent> events(String runId);

    List<AgentRunEvent> events(String runId, long afterCreatedAt, int limit);

    List<AgentRunStep> steps(String runId);

    List<AgentRunStep> steps(String runId, int afterStep, int limit);

    List<AgentObservation> observations(String runId);

    List<AgentObservation> observations(String runId, int offset, int limit);

    Optional<Object> evidence(String documentId);

    AgentRuntimeSnapshot snapshot();

    @Override
    default KernelComponentDescriptor kernelDescriptor() {
        return new KernelComponentDescriptor("agent-runtime", "runtime", "1", Set.of(
            "run", "submit", "cancel", "events", "observations", "evidence"));
    }

    @Override
    default KernelProtocol kernelProtocol() {
        return KernelProtocolCatalog.RUNTIME_EXECUTION;
    }

    @Override
    default KernelDataBoundary kernelDataBoundary() {
        return KernelProtocolCatalog.RUNTIME_BOUNDARY;
    }

    @Override
    default AgentRunResult executeKernel(AgentRunRequest request, KernelDataScope scope) {
        if (request == null) {
            throw new KernelViolationException("KERNEL_PAYLOAD_REQUIRED", "Agent run request is required");
        }
        if (request.getTenantId() != null && !request.getTenantId().isBlank()
            && !request.getTenantId().equals(scope.tenantId())) {
            throw new KernelViolationException("KERNEL_TENANT_MISMATCH",
                "Agent request tenant does not match Kernel scope");
        }
        return run(request);
    }
}
