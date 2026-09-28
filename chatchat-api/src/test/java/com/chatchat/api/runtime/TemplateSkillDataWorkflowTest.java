package com.chatchat.api.runtime;

import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.chatchat.common.runtime.analysis.evidence.StructuredDataEvidence;
import com.chatchat.common.runtime.analysis.execution.WorkflowExecutionResult;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.runtime.skill.api.execution.SkillDataResult;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.api.resolution.*;
import com.chatchat.runtime.skill.api.skill.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TemplateSkillDataWorkflowTest {
    private final SkillDataRequirement requirement = new SkillDataRequirement("returns", "customer.returns.v1",
        List.of("describe"), false, Map.of("customerId", "customer"));
    private final SkillRoleContext identity = new SkillRoleContext("tenant", "user", List.of(), List.of(), Map.of());
    private final PreauthorizedStructuredDataOperator operator = mock(PreauthorizedStructuredDataOperator.class);
    private final SkillExecutionScopePort scopes = mock(SkillExecutionScopePort.class);

    @Test void usesOnlyPublishedTemplateAndNormalizesFieldsWithProvenance() {
        when(scopes.resolve(eq("tenant"), eq("user"), eq("execution-skill"), anyList(), anyList()))
            .thenReturn(new SkillExecutionScopePort.EffectiveScope(List.of(), List.of(), List.of("reader"), true, true));
        when(operator.execute(any(), any(), isNull())).thenReturn(new WorkflowExecutionResult(List.of(
            new StructuredDataEvidence("e1", "asset", "template", 1, "today",
                "{\"data\":{\"rows\":[{\"daily_return\":0.05,\"secret\":\"omit\"}]}}", Map.of())), Map.of(), List.of()));
        var result = workflow(List.of(binding())).acquire(requirement, resolution(true), identity,
            Map.of("customerId", "c1", "templateId", "forged", "assetName", "forged"));
        var captured = org.mockito.ArgumentCaptor.forClass(AnalysisContext.class);
        verify(operator).execute(captured.capture(), any(), isNull());
        assertThat(captured.getValue().skillId()).isEqualTo("execution-skill");
        assertThat(captured.getValue().attributes()).containsEntry(PreauthorizedStructuredDataOperator.TEMPLATE_ID, "template")
            .containsEntry(PreauthorizedStructuredDataOperator.PARAMETERS, Map.of("customer_id", "c1"));
        assertThat(result.status()).isEqualTo(SkillDataResult.Status.AVAILABLE);
        assertThat(result.rows()).containsExactly(Map.of("return", new java.math.BigDecimal("0.05")));
        assertThat(result.provenance()).containsEntry("evidenceId", "e1").containsEntry("workflowVersion", "1");
    }

    @Test void workflowAndExecutionSkillRequireSeparateGrants() {
        assertThat(workflow(List.of(binding())).acquire(requirement, resolution(false), identity, Map.of()).status())
            .isEqualTo(SkillDataResult.Status.DENIED);
        verifyNoInteractions(scopes, operator);
        when(scopes.resolve(anyString(), anyString(), anyString(), anyList(), anyList()))
            .thenReturn(SkillExecutionScopePort.EffectiveScope.denied(List.of()));
        assertThat(workflow(List.of(binding())).acquire(requirement, resolution(true), identity, Map.of()).status())
            .isEqualTo(SkillDataResult.Status.DENIED);
        verifyNoInteractions(operator);
    }

    @Test void missingCanonicalFieldIsReportedWithoutInventingData() {
        when(scopes.resolve(anyString(), anyString(), anyString(), anyList(), anyList()))
            .thenReturn(new SkillExecutionScopePort.EffectiveScope(List.of(), List.of(), List.of(), true, true));
        when(operator.execute(any(), any(), isNull())).thenReturn(new WorkflowExecutionResult(List.of(
            new StructuredDataEvidence("e1", "asset", "template", 1, "today",
                "{\"data\":{\"rows\":[{\"other\":1}]}}", Map.of())), Map.of(), List.of()));
        var result = workflow(List.of(binding())).acquire(requirement, resolution(true), identity, Map.of("customerId", "c1"));
        assertThat(result.status()).isEqualTo(SkillDataResult.Status.INVALID_DATA);
        assertThat(result.rows()).isEmpty();
    }

    @Test void bindingsAreTenantScopedAndAmbiguityDoesNotExecute() {
        assertThat(workflow(List.of(binding())).supports(requirement, resolution(true),
            new SkillRoleContext("other", "user", List.of(), List.of(), Map.of()))).isFalse();
        assertThat(workflow(List.of(binding(), binding())).acquire(requirement, resolution(true), identity, Map.of()).status())
            .isEqualTo(SkillDataResult.Status.AMBIGUOUS_BINDING);
        verifyNoInteractions(scopes, operator);
    }

    @Test void requestLocalReuseReauthorizesAndNeverCrossesUsers() {
        when(scopes.resolve(anyString(), anyString(), anyString(), anyList(), anyList()))
            .thenReturn(new SkillExecutionScopePort.EffectiveScope(List.of(), List.of(), List.of(), true, true));
        when(operator.execute(any(), any(), isNull())).thenReturn(new WorkflowExecutionResult(List.of(
            new StructuredDataEvidence("e1", "asset", "template", 1, "today",
                "{\"data\":{\"rows\":[{\"daily_return\":0.05}]}}", Map.of())), Map.of(), List.of()));
        var workflow = workflow(List.of(binding()));
        var session = new com.chatchat.runtime.skill.api.execution.SkillDataSession();
        workflow.acquire(requirement, resolution(true), identity, Map.of("customerId", "c1"), session);
        workflow.acquire(requirement, resolution(true), identity, Map.of("customerId", "c1"), session);
        verify(operator, times(1)).execute(any(), any(), isNull());
        workflow.acquire(requirement, resolution(true),
            new SkillRoleContext("tenant", "other", List.of(), List.of(), Map.of()), Map.of("customerId", "c1"), session);
        verify(operator, times(2)).execute(any(), any(), isNull());
        when(scopes.resolve(anyString(), anyString(), anyString(), anyList(), anyList()))
            .thenReturn(SkillExecutionScopePort.EffectiveScope.denied(List.of()));
        assertThat(workflow.acquire(requirement, resolution(true), identity, Map.of("customerId", "c1"), session).status())
            .isEqualTo(SkillDataResult.Status.DENIED);
        verify(operator, times(2)).execute(any(), any(), isNull());
    }

    private TemplateSkillDataWorkflow workflow(List<SkillDataWorkflowProperties.Binding> bindings) {
        return new TemplateSkillDataWorkflow(new SkillDataWorkflowProperties(bindings), scopes, operator, new ObjectMapper());
    }
    private SkillDataWorkflowProperties.Binding binding() {
        return new SkillDataWorkflowProperties.Binding(true, "tenant", "domain", "customer.returns.v1", "workflow", "1",
            "execution-skill", "template", "asset", "prod", Map.of("customer_id", "customerId"),
            Map.of("return", "daily_return"), Map.of("unit", "ratio"));
    }
    private SkillResolution resolution(boolean authorizedWorkflow) {
        var descriptor = new SkillDescriptor("domain", "1", "Domain", "", "", "DATABASE", "source", "", "", 1, Map.of());
        return new SkillResolution(new ResolvedSkill(descriptor, "instructions", List.of(), null, Map.of()),
            new AuthorizedSkillScope(true, List.of(), List.of(), List.of(), List.of(),
                authorizedWorkflow ? List.of("workflow") : List.of(), List.of()), "db", "RESOLVED", Map.of());
    }
}
