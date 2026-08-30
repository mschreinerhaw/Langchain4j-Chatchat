package com.chatchat.runtime.temporal.core;

import com.chatchat.agents.runtime.workflow.WorkflowDefinition;
import com.chatchat.agents.runtime.workflow.WorkflowRegistration;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class TemporalWorkflowDefinitionRegistry {

    private final ConcurrentMap<String, WorkflowRegistration<?, ?>> registrations =
        new ConcurrentHashMap<>();

    public <I, O> void register(String workflowType, Class<I> inputType, Class<O> outputType,
                         WorkflowDefinition<I, O> definition) {
        WorkflowRegistration<I, O> registration =
            new WorkflowRegistration<>(workflowType, inputType, outputType, definition);
        WorkflowRegistration<?, ?> existing = registrations.putIfAbsent(
            registration.workflowType(), registration);
        if (existing != null && (!existing.inputType().equals(inputType)
            || !existing.outputType().equals(outputType))) {
            throw new IllegalStateException("Workflow type is already registered with another contract: "
                + workflowType);
        }
    }

    public WorkflowRegistration<?, ?> required(String workflowType) {
        WorkflowRegistration<?, ?> registration = registrations.get(workflowType);
        if (registration == null) {
            throw new IllegalStateException("Workflow type is not registered: " + workflowType);
        }
        return registration;
    }
}
