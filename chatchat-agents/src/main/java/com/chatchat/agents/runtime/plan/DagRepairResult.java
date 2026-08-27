package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.runtime.plan.transformation.PlanPassFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Auditable, copy-on-write derivation from a model plan to a runtime plan.
 * The source plan is never changed; only {@link #executablePlan()} may be executed,
 * and callers must validate it after repair.
 */
public record DagRepairResult(
    String schemaVersion,
    InterpretationPlan sourcePlan,
    InterpretationPlan executablePlan,
    String sourceFingerprint,
    String executableFingerprint,
    Status status,
    List<String> appliedPasses,
    List<PlanPassFailure> passFailures,
    List<Operation> operations,
    Map<Integer, Integer> stepIdMappings
) {

    public static final String SCHEMA_VERSION = "dag_repair_result.v1";
    private static final ObjectMapper FINGERPRINT_MAPPER = new ObjectMapper()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public DagRepairResult {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            schemaVersion = SCHEMA_VERSION;
        }
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported DAG repair result version: " + schemaVersion);
        }
        status = status == null ? Status.UNCHANGED : status;
        appliedPasses = appliedPasses == null ? List.of() : List.copyOf(appliedPasses);
        passFailures = passFailures == null ? List.of() : List.copyOf(passFailures);
        operations = operations == null ? List.of() : List.copyOf(operations);
        stepIdMappings = stepIdMappings == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(stepIdMappings));
    }

    public static DagRepairResult derive(InterpretationPlan source,
                                         InterpretationPlan executable,
                                         List<String> appliedPasses) {
        return derive(source, executable, appliedPasses, null, List.of());
    }

    public static DagRepairResult derive(InterpretationPlan source,
                                         InterpretationPlan executable,
                                         List<String> appliedPasses,
                                         Map<Integer, Integer> authoritativeStepIdMappings) {
        return derive(source, executable, appliedPasses, authoritativeStepIdMappings, List.of());
    }

    public static DagRepairResult derive(InterpretationPlan source,
                                         InterpretationPlan executable,
                                         List<String> appliedPasses,
                                         Map<Integer, Integer> authoritativeStepIdMappings,
                                         List<PlanPassFailure> passFailures) {
        List<String> passes = appliedPasses == null ? List.of() : List.copyOf(appliedPasses);
        Map<Integer, Integer> mappings = authoritativeStepIdMappings == null
            ? mapStepIds(source, executable)
            : Collections.unmodifiableMap(new LinkedHashMap<>(authoritativeStepIdMappings));
        List<Operation> operations = operations(source, executable, mappings, passes);
        Status status = operations.isEmpty()
            ? Status.UNCHANGED
            : passes.isEmpty() ? Status.NORMALIZED : Status.REPAIRED;
        return new DagRepairResult(
            SCHEMA_VERSION,
            source,
            executable,
            fingerprint(source),
            fingerprint(executable),
            status,
            passes,
            passFailures,
            operations,
            mappings
        );
    }

    public boolean changed() {
        return status != Status.UNCHANGED;
    }

    public Map<String, Object> auditMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", schemaVersion);
        metadata.put("status", status.name());
        metadata.put("sourceFingerprint", sourceFingerprint);
        metadata.put("executableFingerprint", executableFingerprint);
        metadata.put("appliedPasses", appliedPasses);
        metadata.put("passFailures", passFailures);
        metadata.put("stepIdMappings", stepIdMappings);
        metadata.put("operations", operations);
        return Collections.unmodifiableMap(metadata);
    }

    private static Map<Integer, Integer> mapStepIds(InterpretationPlan source,
                                                     InterpretationPlan executable) {
        List<InterpretationPlan.Step> sourceSteps = steps(source);
        List<InterpretationPlan.Step> executableSteps = steps(executable);
        Set<Integer> matchedIndexes = new LinkedHashSet<>();
        Map<Integer, Integer> mappings = new LinkedHashMap<>();
        for (InterpretationPlan.Step sourceStep : sourceSteps) {
            if (sourceStep == null || sourceStep.id() == null) {
                continue;
            }
            int matched = findMatch(sourceStep, executableSteps, matchedIndexes, true);
            if (matched < 0) {
                matched = findMatch(sourceStep, executableSteps, matchedIndexes, false);
            }
            if (matched >= 0) {
                matchedIndexes.add(matched);
                Integer targetId = executableSteps.get(matched).id();
                if (targetId != null) {
                    mappings.put(sourceStep.id(), targetId);
                }
            }
        }
        return mappings;
    }

    private static int findMatch(InterpretationPlan.Step source,
                                 List<InterpretationPlan.Step> candidates,
                                 Set<Integer> matchedIndexes,
                                 boolean requireSameInput) {
        for (int index = 0; index < candidates.size(); index++) {
            if (matchedIndexes.contains(index)) {
                continue;
            }
            InterpretationPlan.Step candidate = candidates.get(index);
            if (candidate == null
                || !Objects.equals(source.actionType(), candidate.actionType())
                || !Objects.equals(source.toolName(), candidate.toolName())) {
                continue;
            }
            if (!requireSameInput || Objects.equals(source.input(), candidate.input())) {
                return index;
            }
        }
        return -1;
    }

    private static List<Operation> operations(InterpretationPlan source,
                                              InterpretationPlan executable,
                                              Map<Integer, Integer> mappings,
                                              List<String> passes) {
        List<Operation> operations = new ArrayList<>();
        Map<Integer, InterpretationPlan.Step> executableById = byId(executable);
        Set<Integer> mappedExecutableIds = new LinkedHashSet<>(mappings.values());
        String provenance = passes.isEmpty() ? "CopyOnWriteDagDerivation" : String.join(",", passes);

        for (InterpretationPlan.Step sourceStep : steps(source)) {
            if (sourceStep == null || sourceStep.id() == null) {
                continue;
            }
            Integer executableId = mappings.get(sourceStep.id());
            if (executableId == null) {
                operations.add(operation(Type.STEP_REMOVED, provenance, sourceStep.id(), null,
                    "plan.steps", sourceStep, null, "Step removed by DAG derivation."));
                continue;
            }
            InterpretationPlan.Step executableStep = executableById.get(executableId);
            if (!Objects.equals(sourceStep.id(), executableId)) {
                operations.add(operation(Type.STEP_RENUMBERED, provenance, sourceStep.id(), executableId,
                    "plan.steps[].id", sourceStep.id(), executableId,
                    "Stable source-to-executable step mapping after graph ordering."));
            }
            List<Integer> mappedDependencies = mapDependencies(sourceStep.dependsOn(), mappings);
            List<Integer> executableDependencies = executableStep == null ? null : executableStep.dependsOn();
            if (!Objects.equals(emptyIfNull(mappedDependencies), emptyIfNull(executableDependencies))) {
                operations.add(operation(Type.DEPENDENCIES_REPLACED, provenance, sourceStep.id(), executableId,
                    "plan.steps[].depends_on", sourceStep.dependsOn(), executableDependencies,
                    "Dependencies repaired against the executable DAG."));
            }
            if (executableStep != null && !Objects.equals(sourceStep.input(), executableStep.input())) {
                operations.add(operation(Type.INPUT_REPAIRED, provenance, sourceStep.id(), executableId,
                    "plan.steps[].input", sourceStep.input(), executableStep.input(),
                    "Model-owned or unsafe input fields were normalized."));
            }
        }
        for (InterpretationPlan.Step executableStep : steps(executable)) {
            if (executableStep != null && executableStep.id() != null
                && !mappedExecutableIds.contains(executableStep.id())) {
                operations.add(operation(Type.STEP_ADDED, provenance, null, executableStep.id(),
                    "plan.steps", null, executableStep, "Step added by DAG derivation."));
            }
        }
        compare(operations, Type.GRAPH_CONTRACTS_REPAIRED, provenance, "plan.edge_contracts",
            planValue(source, PlanValue.EDGES), planValue(executable, PlanValue.EDGES));
        compare(operations, Type.GRAPH_CONTRACTS_REPAIRED, provenance, "plan.dependency_contracts",
            planValue(source, PlanValue.DEPENDENCIES), planValue(executable, PlanValue.DEPENDENCIES));
        compare(operations, Type.GRAPH_CONTRACTS_REPAIRED, provenance, "plan.bindings",
            planValue(source, PlanValue.BINDINGS), planValue(executable, PlanValue.BINDINGS));
        compare(operations, Type.EXECUTION_POLICY_REPAIRED, provenance, "execution_policy",
            source == null ? null : source.executionPolicy(), executable == null ? null : executable.executionPolicy());
        return List.copyOf(operations);
    }

    private static void compare(List<Operation> target,
                                Type type,
                                String provenance,
                                String path,
                                Object before,
                                Object after) {
        if (!Objects.equals(before, after)) {
            target.add(operation(type, provenance, null, null, path, before, after,
                "Derived graph metadata differs from the source plan."));
        }
    }

    private static Operation operation(Type type,
                                       String pass,
                                       Integer sourceStepId,
                                       Integer executableStepId,
                                       String path,
                                       Object before,
                                       Object after,
                                       String reason) {
        return new Operation(type, pass, sourceStepId, executableStepId, path,
            immutableValue(before), immutableValue(after), reason);
    }

    private static List<Integer> mapDependencies(List<Integer> dependencies,
                                                 Map<Integer, Integer> mappings) {
        if (dependencies == null) {
            return List.of();
        }
        return dependencies.stream().map(id -> mappings.getOrDefault(id, id)).toList();
    }

    private static List<?> emptyIfNull(List<?> values) {
        return values == null ? List.of() : values;
    }

    private static List<InterpretationPlan.Step> steps(InterpretationPlan plan) {
        return plan == null ? List.of() : plan.steps();
    }

    private static Map<Integer, InterpretationPlan.Step> byId(InterpretationPlan plan) {
        Map<Integer, InterpretationPlan.Step> values = new LinkedHashMap<>();
        for (InterpretationPlan.Step step : steps(plan)) {
            if (step != null && step.id() != null) {
                values.put(step.id(), step);
            }
        }
        return values;
    }

    private enum PlanValue { EDGES, DEPENDENCIES, BINDINGS }

    private static Object planValue(InterpretationPlan plan, PlanValue value) {
        if (plan == null || plan.plan() == null) {
            return null;
        }
        return switch (value) {
            case EDGES -> plan.plan().edgeContracts();
            case DEPENDENCIES -> plan.plan().dependencyContracts();
            case BINDINGS -> plan.plan().bindings();
        };
    }

    private static String fingerprint(InterpretationPlan plan) {
        if (plan == null) {
            return null;
        }
        try {
            byte[] canonical = FINGERPRINT_MAPPER.writeValueAsBytes(plan);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to fingerprint InterpretationPlan", ex);
        }
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(key, immutableValue(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(nested -> copy.add(immutableValue(nested)));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Set<?> set) {
            Set<Object> copy = new LinkedHashSet<>();
            set.forEach(nested -> copy.add(immutableValue(nested)));
            return Collections.unmodifiableSet(copy);
        }
        return value;
    }

    public enum Status {
        UNCHANGED,
        NORMALIZED,
        REPAIRED
    }

    public enum Type {
        STEP_ADDED,
        STEP_REMOVED,
        STEP_RENUMBERED,
        DEPENDENCIES_REPLACED,
        INPUT_REPAIRED,
        GRAPH_CONTRACTS_REPAIRED,
        EXECUTION_POLICY_REPAIRED
    }

    public record Operation(
        Type type,
        String repairPass,
        Integer sourceStepId,
        Integer executableStepId,
        String path,
        Object beforeValue,
        Object afterValue,
        String reason
    ) {
    }
}
