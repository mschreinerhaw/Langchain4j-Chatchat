package com.chatchat.knowledgebase.runtime.workflow;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.workflow.AbstractStagedExecutionWorkflow;
import com.chatchat.common.skills.DomainSkillRuntimePort;
import com.chatchat.knowledgebase.search.document.DocumentEvidenceChunk;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Selects authorized, published domain skills from verified document previews. */
@Component
public final class DocumentSkillEnrichmentWorkflow extends AbstractStagedExecutionWorkflow<
    DocumentSkillEnrichmentWorkflow.Request, DocumentSkillEnrichmentWorkflow.Analysis,
    DocumentSkillEnrichmentWorkflow.Plan, DomainSkillRuntimePort.EvidenceSkillActivation,
    DocumentSkillEnrichmentWorkflow.Result> {

    public static final String WORKFLOW_ID = "enrich.document-analysis-skills.v1";
    private static final int MAX_PREVIEWS = 6;
    private static final int MAX_PREVIEW_CHARS = 2_500;

    private final ObjectProvider<DomainSkillRuntimePort> skillProvider;
    private final DomainSkillRuntimePort fixedSkills;

    @Autowired
    public DocumentSkillEnrichmentWorkflow(ObjectProvider<DomainSkillRuntimePort> skills) {
        this.skillProvider = skills;
        this.fixedSkills = null;
    }

    public DocumentSkillEnrichmentWorkflow(DomainSkillRuntimePort skills) {
        this.skillProvider = null;
        this.fixedSkills = skills;
    }

    @Override public String workflowId() { return WORKFLOW_ID; }

    @Override
    protected void validateInput(Request input, KernelDataScope scope) {
        if (input == null) throw new IllegalArgumentException("document skill enrichment request is required");
        if (input.query() == null || input.query().isBlank()) {
            throw new IllegalArgumentException("document skill enrichment query is required");
        }
    }

    @Override
    protected Analysis analyze(Request input, KernelDataScope scope) {
        List<DomainSkillRuntimePort.EvidencePreview> previews = input.chunks().stream()
            .filter(java.util.Objects::nonNull)
            .filter(chunk -> chunk.content() != null && !chunk.content().isBlank())
            .limit(MAX_PREVIEWS)
            .map(chunk -> new DomainSkillRuntimePort.EvidencePreview(
                chunk.refId(), chunk.fileId(), chunk.fileName(), chunk.section(),
                bounded(chunk.content(), MAX_PREVIEW_CHARS)))
            .toList();
        String mode = previews.isEmpty() ? "NO_CONTENT" : "CONTENT_DRIVEN";
        return new Analysis(previews, mode);
    }

    @Override
    protected Plan plan(Request input, Analysis analysis, KernelDataScope scope) {
        return new Plan(List.of(
            "PREVIEW_VERIFIED_DOCUMENTS",
            "EXTRACT_CONTENT_SIGNALS",
            "MATCH_PUBLISHED_SKILLS",
            "ENFORCE_ROLE_AND_RESOURCE_POLICY",
            "SELECT_MINIMUM_SKILL_SET",
            "COMPILE_ANALYSIS_GUIDANCE"
        ), input.maxActivatedSkills());
    }

    @Override
    protected DomainSkillRuntimePort.EvidenceSkillActivation executePlan(
        Request input, Analysis analysis, Plan plan, KernelDataScope scope) {
        if (!"CONTENT_DRIVEN".equals(analysis.mode())) {
            return DomainSkillRuntimePort.EvidenceSkillActivation.empty(analysis.mode());
        }
        DomainSkillRuntimePort skills = fixedSkills != null ? fixedSkills
            : skillProvider == null ? null : skillProvider.getIfAvailable();
        if (skills == null) return DomainSkillRuntimePort.EvidenceSkillActivation.empty("DISABLED");
        return skills.activateForEvidence(scope.tenantId(), scope.userId(), input.roles(),
            input.query(), analysis.previews(), plan.maxActivatedSkills());
    }

    @Override
    protected void verify(Request input, Analysis analysis, Plan plan,
                          DomainSkillRuntimePort.EvidenceSkillActivation activation,
                          KernelDataScope scope) {
        if (activation == null) throw new IllegalStateException("document skill activation returned null");
        Set<String> candidates = activation.candidates().stream()
            .map(DomainSkillRuntimePort.DomainSkillContent::id)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<String> activated = activation.activatedSkillIds();
        if (!candidates.containsAll(activated)) {
            throw new IllegalStateException("document skill activation contains an unauthorized candidate");
        }
        if (activated.size() > plan.maxActivatedSkills()) {
            throw new IllegalStateException("document skill activation exceeds the workflow limit");
        }
    }

    @Override
    protected Result assemble(Request input, Analysis analysis, Plan plan,
                              DomainSkillRuntimePort.EvidenceSkillActivation activation,
                              KernelDataScope scope) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("workflowId", WORKFLOW_ID);
        trace.put("mode", analysis.mode());
        trace.put("steps", plan.steps());
        trace.put("previewCount", analysis.previews().size());
        trace.put("candidateCount", activation.candidates().size());
        trace.put("activatedCount", activation.activated().size());
        return new Result(activation.activatedSkillIds(), cards(activation.candidates()),
            cards(activation.activated()), activation.planningKnowledge(), activation.compiledContext(),
            activation.status(), activation.error(), Map.copyOf(trace));
    }

    private List<Map<String, Object>> cards(List<DomainSkillRuntimePort.DomainSkillContent> values) {
        return values.stream().map(skill -> Map.<String, Object>of(
            "id", safe(skill.id()), "name", safe(skill.name()), "category", safe(skill.category())))
            .toList();
    }

    private String bounded(String value, int limit) {
        String safe = value == null ? "" : value;
        return safe.length() <= limit ? safe : safe.substring(0, limit);
    }

    private String safe(String value) { return value == null ? "" : value; }

    public record Request(String query, List<String> roles, List<DocumentEvidenceChunk> chunks,
                          int maxActivatedSkills) {
        public Request {
            roles = roles == null ? List.of() : List.copyOf(roles);
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
            maxActivatedSkills = Math.max(1, Math.min(5, maxActivatedSkills));
        }
    }

    public record Analysis(List<DomainSkillRuntimePort.EvidencePreview> previews, String mode) {
        public Analysis { previews = List.copyOf(previews); }
    }

    public record Plan(List<String> steps, int maxActivatedSkills) {
        public Plan { steps = List.copyOf(steps); }
    }

    public record Result(List<String> activatedSkillIds,
                         List<Map<String, Object>> candidates,
                         List<Map<String, Object>> activatedSkills,
                         Map<String, Object> planningKnowledge,
                         String compiledContext,
                         String status,
                         String error,
                         Map<String, Object> trace) {
        public Result {
            activatedSkillIds = List.copyOf(activatedSkillIds);
            candidates = List.copyOf(candidates);
            activatedSkills = List.copyOf(activatedSkills);
            planningKnowledge = Map.copyOf(planningKnowledge);
            compiledContext = compiledContext == null ? "" : compiledContext;
            status = status == null ? "" : status;
            trace = Map.copyOf(trace);
        }

        public static Result disabled() {
            return new Result(List.of(), List.of(), List.of(), Map.of(), "",
                "DISABLED", null, Map.of("workflowId", WORKFLOW_ID, "mode", "DISABLED"));
        }
    }
}
