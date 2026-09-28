package com.chatchat.runtime.skill.application;

import com.chatchat.runtime.skill.api.execution.SkillDataResult;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.api.resolution.SkillResolution;
import com.chatchat.runtime.skill.port.outbound.SkillDataWorkflow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/** Bounded acquisition before analysis. Failures stay local to the requested dataset. */
public final class SkillDataAcquisition {
    public static final String INPUTS = "skillDataInputs";
    public static final String RESULTS = "skillDataResults";
    private final Supplier<List<SkillDataWorkflow>> workflows;

    public SkillDataAcquisition(Supplier<List<SkillDataWorkflow>> workflows) { this.workflows = workflows; }

    public List<SkillDataResult> acquire(SkillResolution skill, SkillRoleContext identity, Map<String, Object> inputs) {
        List<SkillDataResult> results = new ArrayList<>();
        for (var requirement : skill.skill().requirements().data()) {
            if (!skill.resolved()) {
                results.add(SkillDataResult.unavailable(requirement, SkillDataResult.Status.DENIED, "Skill is not authorized"));
                continue;
            }
            Map<String, Object> parameters = new LinkedHashMap<>();
            requirement.parameters().forEach((name, input) -> {
                if (inputs.get(input) != null) parameters.put(name, inputs.get(input));
            });
            if (parameters.size() != requirement.parameters().size()) {
                results.add(SkillDataResult.unavailable(requirement, SkillDataResult.Status.MISSING_INPUT,
                    "A declared request input is missing"));
                continue;
            }
            try {
                var candidates = workflows.get().stream().filter(item -> item.supports(requirement, skill, identity)).toList();
                if (candidates.size() != 1) {
                    results.add(SkillDataResult.unavailable(requirement, candidates.isEmpty()
                        ? SkillDataResult.Status.NO_BINDING : SkillDataResult.Status.AMBIGUOUS_BINDING,
                        "Exactly one published data workflow must match"));
                    continue;
                }
                var result = candidates.get(0).acquire(requirement, skill, identity, Map.copyOf(parameters));
                if (result == null || !requirement.equals(result.requirement()))
                    throw new IllegalStateException("Data workflow returned a mismatched requirement");
                results.add(result);
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (RuntimeException failure) {
                if (Thread.currentThread().isInterrupted()) throw failure;
                results.add(SkillDataResult.unavailable(requirement, SkillDataResult.Status.FAILED,
                    "Data workflow execution failed"));
            }
        }
        return List.copyOf(results);
    }
}
