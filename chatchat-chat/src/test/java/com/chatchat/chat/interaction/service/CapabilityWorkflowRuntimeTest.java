package com.chatchat.chat.interaction.service;

import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.common.runtime.capability.*;
import com.chatchat.common.runtime.analysis.model.RuntimeWorkflowFamily;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class CapabilityWorkflowRuntimeTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = RuntimeWorkflowFamily.class,
        names = {"DOCUMENT", "DATA_ANALYSIS", "ASSET_GUIDANCE", "ACTION"})
    @org.junit.jupiter.api.Timeout(10)
    void parentCannotEvaluateOrReturnBeforeChildCompletes(RuntimeWorkflowFamily family) throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var plan = CapabilityWorkflowPlan.forFamily(family);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var provider = new CapabilityWorkflowRuntime.Provider("child", CapabilityWorkflowRuntime.ProviderKind.NATIVE_RUNTIME,
            plan.requiredCapabilities(), () -> {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException();
                }
                return InteractionResponse.builder().answer("child result")
                    .metadata(Map.of(WorkflowOutcome.METADATA_KEY, new WorkflowOutcome(WorkflowOutcome.Type.READY_TO_ANSWER,
                        "CHILD_VERIFIED", List.of(), List.of(), true))).build();
            });
        try {
            var parent = executor.submit(() -> new CapabilityWorkflowRuntime().execute(plan, List.of(provider)));
            assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> parent.get(150, java.util.concurrent.TimeUnit.MILLISECONDS))
                .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            assertThat(parent.get(3, java.util.concurrent.TimeUnit.SECONDS).getAnswer()).isEqualTo("child result");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
    @Test void nativeProviderCanMeetRequiredCapabilitiesWithoutSkillsAndRunsOnce() {
        var plan = CapabilityWorkflowPlan.forFamily(RuntimeWorkflowFamily.DATA_ANALYSIS);
        var count = new AtomicInteger();
        var provider = new CapabilityWorkflowRuntime.Provider("native", CapabilityWorkflowRuntime.ProviderKind.NATIVE_RUNTIME,
            plan.requiredCapabilities(), () -> { count.incrementAndGet(); return InteractionResponse.builder().answer("verified")
                .metadata(Map.of("agent", Map.of("publicStatus", "SUCCESS"))).build(); });
        var response = new CapabilityWorkflowRuntime().execute(plan, List.of(provider));
        assertThat(count.get()).isEqualTo(1);
        assertThat(response.getMetadata()).containsEntry("capabilityProvider", "native");
        assertThat(((WorkflowOutcome) response.getMetadata().get(WorkflowOutcome.METADATA_KEY)).publicStatus()).isEqualTo("SUCCESS");
    }
    @Test void partialProviderIsNotExecutedWhenAnyRequiredCapabilityIsMissing() {
        var plan = CapabilityWorkflowPlan.forFamily(RuntimeWorkflowFamily.ASSET_GUIDANCE);
        var provider = new CapabilityWorkflowRuntime.Provider("incomplete", CapabilityWorkflowRuntime.ProviderKind.MCP_WORKFLOW,
            Set.of("asset_resolve"), () -> { throw new AssertionError("must not execute"); });
        var response = new CapabilityWorkflowRuntime().execute(plan, List.of(provider));
        var outcome = (WorkflowOutcome) response.getMetadata().get(WorkflowOutcome.METADATA_KEY);
        assertThat(outcome.type()).isEqualTo(WorkflowOutcome.Type.NO_EXECUTABLE_PLAN);
        assertThat(outcome.missingRequiredCapabilities()).containsExactly("asset_metadata_read");
    }
    @Test void actionCannotSucceedWithOnlyTextAndNoExecutionRecord() {
        var plan = CapabilityWorkflowPlan.forFamily(RuntimeWorkflowFamily.ACTION);
        var provider = new CapabilityWorkflowRuntime.Provider("action", CapabilityWorkflowRuntime.ProviderKind.NATIVE_RUNTIME,
            plan.requiredCapabilities(), () -> InteractionResponse.builder().answer("claimed done")
                .metadata(Map.of("agent", Map.of("publicStatus", "SUCCESS"))).build());
        var response = new CapabilityWorkflowRuntime().execute(plan, List.of(provider));
        assertThat(((WorkflowOutcome) response.getMetadata().get(WorkflowOutcome.METADATA_KEY)).publicStatus()).isEqualTo("NO_PRESENTABLE_RESULT");
    }
    @Test void noPlanIsNotRetriedThroughAnotherProviderOrPromotedByExplanationText() {
        var plan = CapabilityWorkflowPlan.forFamily(RuntimeWorkflowFamily.DATA_ANALYSIS);
        var provider = new CapabilityWorkflowRuntime.Provider("skill", CapabilityWorkflowRuntime.ProviderKind.SKILL,
            plan.requiredCapabilities(), () -> InteractionResponse.builder().answer("没有分析技能")
                .metadata(Map.of(WorkflowOutcome.METADATA_KEY, new WorkflowOutcome(WorkflowOutcome.Type.NO_EXECUTABLE_PLAN,
                    "NO_EXECUTABLE_PLAN", List.of("analysis"), List.of(), false))).build());
        var fallback = new CapabilityWorkflowRuntime.Provider("native", CapabilityWorkflowRuntime.ProviderKind.NATIVE_RUNTIME,
            plan.requiredCapabilities(), () -> { throw new AssertionError("no post-execution retry"); });
        var response = new CapabilityWorkflowRuntime().execute(plan, List.of(provider, fallback));
        assertThat(((Map<?, ?>) response.getMetadata().get("agent")).get("publicStatus")).isEqualTo("NO_PRESENTABLE_RESULT");
    }
}
