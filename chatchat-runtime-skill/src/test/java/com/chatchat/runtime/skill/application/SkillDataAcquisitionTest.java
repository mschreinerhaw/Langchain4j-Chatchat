package com.chatchat.runtime.skill.application;

import com.chatchat.runtime.skill.api.execution.SkillDataResult;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.api.resolution.*;
import com.chatchat.runtime.skill.api.skill.*;
import com.chatchat.runtime.skill.port.outbound.SkillDataWorkflow;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class SkillDataAcquisitionTest {
    private final SkillRoleContext identity = new SkillRoleContext("tenant", "user", List.of(), List.of(), Map.of());
    private final SkillDataRequirement required = new SkillDataRequirement("returns", "customer.returns.v1",
        List.of("describe"), false, Map.of("customerId", "customer"));
    private final SkillDataRequirement optional = new SkillDataRequirement("benchmark", "market.benchmark.v1",
        List.of("compare"), true, Map.of());

    @Test void missingInputsNeverInvokeAcquisition() {
        var result = new SkillDataAcquisition(() -> { throw new AssertionError("must not resolve providers"); })
            .acquire(resolution(List.of(required)), identity, Map.of());
        assertThat(result).extracting(SkillDataResult::status).containsExactly(SkillDataResult.Status.MISSING_INPUT);
    }

    @Test void datasetFailureDoesNotDiscardSuccessfulSiblingAndOnlyDeclaredInputsArePassed() {
        var provider = provider((requirement, parameters) -> {
            if (requirement.optional()) throw new IllegalStateException("timeout");
            assertThat(parameters).containsExactlyEntriesOf(Map.of("customerId", "c1"));
            return new SkillDataResult(requirement, SkillDataResult.Status.AVAILABLE,
                List.of(Map.of("return", 0.05)), Map.of("workflowVersion", "1"), List.of());
        });
        var result = new SkillDataAcquisition(() -> List.of(provider)).acquire(resolution(List.of(required, optional)),
            identity, Map.of("customer", "c1", "toolName", "forged", "tenantId", "other"));
        assertThat(result).extracting(SkillDataResult::status)
            .containsExactly(SkillDataResult.Status.AVAILABLE, SkillDataResult.Status.FAILED);
        assertThat(result.get(0).rows()).hasSize(1);
    }

    @Test void missingOrAmbiguousProvidersDoNotRun() {
        AtomicInteger calls = new AtomicInteger();
        var provider = provider((requirement, parameters) -> { calls.incrementAndGet(); return null; });
        var missing = new SkillDataAcquisition(List::of).acquire(resolution(List.of(optional)), identity, Map.of());
        var ambiguous = new SkillDataAcquisition(() -> List.of(provider, provider))
            .acquire(resolution(List.of(optional)), identity, Map.of());
        assertThat(missing.get(0).status()).isEqualTo(SkillDataResult.Status.NO_BINDING);
        assertThat(ambiguous.get(0).status()).isEqualTo(SkillDataResult.Status.AMBIGUOUS_BINDING);
        assertThat(calls).hasValue(0);
    }

    @Test void cancellationPropagates() {
        var provider = provider((requirement, parameters) -> { throw new CancellationException(); });
        assertThatThrownBy(() -> new SkillDataAcquisition(() -> List.of(provider))
            .acquire(resolution(List.of(optional)), identity, Map.of())).isInstanceOf(CancellationException.class);
    }

    @Test void unversionedContractsAndDuplicateIdsAreRejected() {
        assertThatThrownBy(() -> new SkillDataRequirement("x", "returns", List.of(), false, Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillRequirements(List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(required, required))).isInstanceOf(IllegalArgumentException.class);
    }

    private SkillResolution resolution(List<SkillDataRequirement> data) {
        var descriptor = new SkillDescriptor("skill", "1", "Skill", "", "", "DATABASE", "", "", "", 1, Map.of());
        return new SkillResolution(new ResolvedSkill(descriptor, "analyze", List.of(),
            new SkillRequirements(List.of(), List.of(), List.of(), List.of(), List.of("workflow"), data), Map.of()),
            new AuthorizedSkillScope(true, List.of(), List.of(), List.of(), List.of(), List.of("workflow"), List.of()),
            "db", "RESOLVED", Map.of());
    }

    private SkillDataWorkflow provider(java.util.function.BiFunction<SkillDataRequirement, Map<String, Object>, SkillDataResult> action) {
        return new SkillDataWorkflow() {
            public boolean supports(SkillDataRequirement requirement, SkillResolution skill, SkillRoleContext role) { return true; }
            public SkillDataResult acquire(SkillDataRequirement requirement, SkillResolution skill, SkillRoleContext role,
                                           Map<String, Object> parameters) { return action.apply(requirement, parameters); }
        };
    }
}
