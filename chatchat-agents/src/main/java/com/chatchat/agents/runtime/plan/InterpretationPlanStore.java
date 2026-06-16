package com.chatchat.agents.runtime.plan;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface InterpretationPlanStore {

    InterpretationPlanRecord savePlan(String tenantId, String taskId, String planId, InterpretationPlan plan, String status);

    default InterpretationPlanRecord savePlan(String tenantId,
                                              String taskId,
                                              String planId,
                                              InterpretationPlan plan,
                                              String status,
                                              Map<String, Object> dag) {
        return savePlan(tenantId, taskId, planId, plan, status);
    }

    void saveSnapshot(InterpretationPlanRecord record);

    void saveVersion(InterpretationPlanRecord record);

    Optional<InterpretationPlanRecord> getSnapshot(String tenantId, String taskId);

    Optional<String> getDagJson(String tenantId, String taskId);

    List<InterpretationPlanRecord> listVersions(String tenantId, String taskId);
}
