package com.chatchat.agents.runtime.plan.transformation;

import com.chatchat.agents.runtime.plan.InterpretationPlan;

import java.util.ArrayList;
import java.util.List;

/**
 * Pipeline-local mutable builder. It never exposes or mutates the immutable source plan.
 * A workspace snapshot is used to roll back failed optional optimization passes.
 */
public final class PlanTransformationWorkspace {

    private final InterpretationPlan sourcePlan;
    private List<InterpretationPlan.Step> steps;
    private List<InterpretationPlan.EdgeContract> edgeContracts;
    private List<InterpretationPlan.DependencyContract> dependencyContracts;
    private List<InterpretationPlan.Binding> bindings;
    private InterpretationPlan.ExecutionPolicy executionPolicy;
    private final List<String> appliedPasses;
    private final List<PlanPassFailure> passFailures;

    public PlanTransformationWorkspace(InterpretationPlan sourcePlan) {
        this.sourcePlan = sourcePlan;
        this.steps = new ArrayList<>(sourcePlan.steps());
        this.edgeContracts = copy(sourcePlan.plan().edgeContracts());
        this.dependencyContracts = copy(sourcePlan.plan().dependencyContracts());
        this.bindings = copy(sourcePlan.plan().bindings());
        this.executionPolicy = sourcePlan.executionPolicy();
        this.appliedPasses = new ArrayList<>();
        this.passFailures = new ArrayList<>();
    }

    private PlanTransformationWorkspace(PlanTransformationWorkspace source) {
        this.sourcePlan = source.sourcePlan;
        this.steps = new ArrayList<>(source.steps);
        this.edgeContracts = new ArrayList<>(source.edgeContracts);
        this.dependencyContracts = new ArrayList<>(source.dependencyContracts);
        this.bindings = new ArrayList<>(source.bindings);
        this.executionPolicy = source.executionPolicy;
        this.appliedPasses = new ArrayList<>(source.appliedPasses);
        this.passFailures = new ArrayList<>(source.passFailures);
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    public PlanTransformationWorkspace snapshot() {
        return new PlanTransformationWorkspace(this);
    }

    public void restore(PlanTransformationWorkspace snapshot) {
        if (snapshot == null || snapshot.sourcePlan != sourcePlan) {
            throw new IllegalArgumentException("Workspace snapshot does not belong to this source plan");
        }
        steps = new ArrayList<>(snapshot.steps);
        edgeContracts = new ArrayList<>(snapshot.edgeContracts);
        dependencyContracts = new ArrayList<>(snapshot.dependencyContracts);
        bindings = new ArrayList<>(snapshot.bindings);
        executionPolicy = snapshot.executionPolicy;
        appliedPasses.clear();
        appliedPasses.addAll(snapshot.appliedPasses);
        passFailures.clear();
        passFailures.addAll(snapshot.passFailures);
    }

    public InterpretationPlan sourcePlan() {
        return sourcePlan;
    }

    public List<InterpretationPlan.Step> steps() {
        return steps;
    }

    public void steps(List<InterpretationPlan.Step> values) {
        steps = copy(values);
    }

    public List<InterpretationPlan.EdgeContract> edgeContracts() {
        return edgeContracts;
    }

    public void edgeContracts(List<InterpretationPlan.EdgeContract> values) {
        edgeContracts = copy(values);
    }

    public List<InterpretationPlan.DependencyContract> dependencyContracts() {
        return dependencyContracts;
    }

    public void dependencyContracts(List<InterpretationPlan.DependencyContract> values) {
        dependencyContracts = copy(values);
    }

    public List<InterpretationPlan.Binding> bindings() {
        return bindings;
    }

    public void bindings(List<InterpretationPlan.Binding> values) {
        bindings = copy(values);
    }

    public InterpretationPlan.ExecutionPolicy executionPolicy() {
        return executionPolicy;
    }

    public void executionPolicy(InterpretationPlan.ExecutionPolicy value) {
        executionPolicy = value;
    }

    public void markApplied(String passId) {
        if (passId != null && !passId.isBlank() && !appliedPasses.contains(passId)) {
            appliedPasses.add(passId);
        }
    }

    public List<String> appliedPasses() {
        return List.copyOf(appliedPasses);
    }

    public void recordFailure(PlanPassFailure failure) {
        if (failure != null) {
            passFailures.add(failure);
        }
    }

    public List<PlanPassFailure> passFailures() {
        return List.copyOf(passFailures);
    }
}
