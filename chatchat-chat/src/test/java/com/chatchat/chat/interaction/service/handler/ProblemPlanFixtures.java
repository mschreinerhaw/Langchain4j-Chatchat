package com.chatchat.chat.interaction.service.handler;

import com.chatchat.chat.interaction.model.InteractionContext;
import com.chatchat.common.runtime.capability.ProblemAnalysisPlan;
import com.chatchat.common.runtime.analysis.model.RuntimeWorkflowFamily;
import java.util.List;

final class ProblemPlanFixtures {
    static InteractionContext.InteractionContextBuilder plannedContext() {
        return InteractionContext.builder().problemAnalysisPlan(plan(RuntimeWorkflowFamily.DATA_ANALYSIS));
    }
    static ProblemAnalysisPlan plan(RuntimeWorkflowFamily family) {
        var intent = switch (family) {
            case DOCUMENT -> ProblemAnalysisPlan.Intent.DOCUMENT_UNDERSTANDING;
            case DATA_ANALYSIS -> ProblemAnalysisPlan.Intent.DATA_ANALYSIS;
            case ASSET_GUIDANCE -> ProblemAnalysisPlan.Intent.ASSET_USAGE_GUIDANCE;
            case ACTION -> ProblemAnalysisPlan.Intent.ACTION_EXECUTION;
        };
        return new ProblemAnalysisPlan(ProblemAnalysisPlan.Status.READY, "测试目标", "测试对象", "测试领域", "测试分析计划",
            List.of(new ProblemAnalysisPlan.Task("测试任务", intent, List.of("证据"), "测试产物")), "");
    }
}
