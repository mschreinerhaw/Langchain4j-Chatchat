package com.chatchat.chat.interaction.service;

import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.common.runtime.capability.CapabilityWorkflowPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Request-owned synchronous scope. Only settled children permit the parent to complete. */
public final class InteractionExecution {
    private final InteractionRequest request;
    private final List<Map<String, String>> phases = new ArrayList<>();
    private int activeChildren;
    private boolean failed;
    private boolean completed;

    public InteractionExecution(InteractionRequest request) { this.request = request; }

    public <T> T call(String phase, Supplier<T> child) {
        if (completed || failed) throw new IllegalStateException("Interaction execution is already settled");
        checkCancellation(request);
        Map<String, String> event = new LinkedHashMap<>();
        event.put("phase", phase);
        event.put("state", "RUNNING");
        phases.add(event);
        activeChildren++;
        try {
            T result = child.get();
            checkCancellation(request);
            event.put("state", "COMPLETED");
            return result;
        } catch (RuntimeException | Error failure) {
            failed = true;
            try {
                propagateCancellation(request, failure);
            } catch (CancellationException cancelled) {
                event.put("state", "CANCELLED");
                throw cancelled;
            }
            event.put("state", "FAILED");
            throw failure;
        } finally {
            activeChildren--;
        }
    }

    public InteractionResponse complete(InteractionResponse response, WorkflowEntryPlan entry) {
        if (activeChildren != 0 || completed || failed)
            throw new IllegalStateException("Cannot complete an interaction with unsettled or failed children");
        checkCancellation(request);
        call("EVALUATE", () -> { CapabilityWorkflowRuntime.normalizeOutcome(response); return response; });
        Map<String, Object> metadata = new LinkedHashMap<>(response.getMetadata());
        metadata.put("workflowEntryPlan", entry);
        metadata.put("runtimeLifecycleDefinition", CapabilityWorkflowPlan.LIFECYCLE);
        metadata.put("runtimeLifecycle", java.util.stream.Stream.concat(
            phases.stream().map(event -> event.get("phase")), java.util.stream.Stream.of("COMPLETE")).toList());
        metadata.put("runtimeExecution", Map.of("state", "COMPLETED", "phases", phases.stream().map(Map::copyOf).toList()));
        response.setMetadata(metadata);
        completed = true;
        return response;
    }

    public static void checkCancellation(InteractionRequest request) {
        Object signal = request.getToolInput() == null ? null : request.getToolInput().get("__agentCancellation");
        if (Thread.currentThread().isInterrupted() || signal instanceof BooleanSupplier token && token.getAsBoolean())
            throw new CancellationException("Interaction cancelled");
    }

    public static void propagateCancellation(InteractionRequest request, Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
            if (cause instanceof CancellationException cancelled) throw cancelled;
        }
        checkCancellation(request);
    }
}
