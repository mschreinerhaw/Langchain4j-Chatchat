package com.chatchat.knowledgebase.runtime.workflow;

import com.chatchat.common.runtime.analysis.evidence.AnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.DocumentAnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.execution.VerificationResult;
import com.chatchat.common.runtime.analysis.execution.WorkflowExecutionResult;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisIntent;
import com.chatchat.common.runtime.analysis.model.AnalysisScope;
import com.chatchat.common.runtime.analysis.model.AnalysisWorkflowType;
import com.chatchat.common.runtime.analysis.plan.EvidenceRequirement;
import com.chatchat.common.runtime.analysis.plan.PlanStep;
import com.chatchat.common.runtime.analysis.plan.StandardWorkflowPlan;
import com.chatchat.common.runtime.analysis.plan.WorkflowPlan;
import com.chatchat.common.runtime.analysis.workflow.AbstractAnalysisWorkflow;

import com.chatchat.knowledgebase.search.document.DocumentEvidenceChunk;
import com.chatchat.knowledgebase.search.document.DocumentSearchEvidenceService;
import com.chatchat.knowledgebase.search.document.DocumentSearchFilters;
import com.chatchat.knowledgebase.search.document.DocumentSearchRequest;
import com.chatchat.knowledgebase.search.document.DocumentSearchResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Document-class implementation of the parent problem-analysis workflow. */
@Component
public class DocumentProblemAnalysisWorkflow extends AbstractAnalysisWorkflow {
    private final DocumentSearchEvidenceService documents;
    private final DocumentSkillEnrichmentWorkflow skillEnrichment;

    @Autowired
    public DocumentProblemAnalysisWorkflow(DocumentSearchEvidenceService documents,
                                           DocumentSkillEnrichmentWorkflow skillEnrichment) {
        this.documents = documents;
        this.skillEnrichment = skillEnrichment;
    }

    /** Compatibility constructor for isolated callers and tests. */
    public DocumentProblemAnalysisWorkflow(DocumentSearchEvidenceService documents) {
        this(documents, new DocumentSkillEnrichmentWorkflow(
            (com.chatchat.common.skills.DomainSkillRuntimePort) null));
    }

    @Override public AnalysisWorkflowType type() { return AnalysisWorkflowType.DOCUMENT; }
    @Override public String workflowId() { return "problem-analysis.document"; }
    @Override public int priority() { return 100; }

    @Override
    public boolean supports(AnalysisContext context, AnalysisIntent intent) {
        return intent.requiredCapabilities().size() == 1
            && intent.requiredCapabilities().contains(AnalysisCapability.DOCUMENT_SEARCH);
    }

    @Override
    protected AnalysisScope resolveScope(AnalysisContext context) {
        return new AnalysisScope(context.kernelScope().tenantId(), context.kernelScope().userId(),
            context.roles(), context.documentIds(), Map.of(
                "skillId", context.skillId(), "documentTags", context.documentTags()));
    }

    @Override
    protected WorkflowPlan plan(AnalysisContext context, AnalysisScope scope) {
        return new StandardWorkflowPlan(UUID.randomUUID().toString(), type(), List.of(
            step("1", "QUERY_ANALYZE"), step("2", "SKILL_ROLE_CONTEXT"),
            step("3", "POSTGRES_DOCUMENT_ROUTE"), step("4", "OPENSEARCH_HYBRID_RETRIEVE"),
            step("5", "RRF_FUSION"), step("6", "BGE_RERANK"),
            step("7", "POSTGRES_PARENT_SECTION"), step("8", "ROCKSDB_SOURCE_VERIFY"),
            step("9", "CONTENT_SKILL_CANDIDATE_EXTRACT"),
            step("10", "AUTHORIZED_SKILL_MATCH"),
            step("11", "SKILL_ENRICHED_DOCUMENT_ANALYSIS"),
            step("12", "EVIDENCE_BUNDLE")),
            List.of(new EvidenceRequirement("DOCUMENT", true, 1,
                "current ACL, version, and source passage must match")));
    }

    @Override
    protected WorkflowExecutionResult executePlan(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan) {
        int topK = integerAttribute(context, "topK", 5);
        DocumentSearchResult result = documents.search(new DocumentSearchRequest(
            context.query(), topK, context.documentIds(), context.documentIds(), context.documentIds(),
            !context.documentIds().isEmpty(),
            new DocumentSearchFilters(null, null, null, null, null, context.documentTags()),
            scope.tenantId(), scope.userId(), scope.roles(), booleanAttribute(context, "debug")));
        DocumentSkillEnrichmentWorkflow.Result skillResult = Boolean.FALSE.equals(
            context.attributes().get("autoDocumentSkills"))
            ? DocumentSkillEnrichmentWorkflow.Result.disabled()
            : skillEnrichment.execute(new DocumentSkillEnrichmentWorkflow.Request(
                context.query(), scope.roles(), result.results(),
                integerAttribute(context, "maxDocumentSkills", 3)), context.kernelScope());
        List<AnalysisEvidence> evidence = result.results().stream()
            .map(chunk -> (AnalysisEvidence) evidence(chunk, skillResult)).toList();
        return new WorkflowExecutionResult(evidence, Map.of(
            "documentSearchResult", result,
            "documentSkillEnrichment", skillResult),
            evidence.isEmpty() ? List.of("Document workflow returned no verified evidence") : List.of());
    }

    @Override
    protected VerificationResult verify(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan,
                                        WorkflowExecutionResult execution) {
        boolean accepted = !execution.evidence().isEmpty();
        return new VerificationResult(accepted, accepted ? execution.evidence() : List.of(),
            accepted ? List.of() : execution.observations());
    }

    @Override
    protected AnalysisExecutionOutcome synthesize(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan,
                                        WorkflowExecutionResult execution, VerificationResult verification,
                                        EvidenceBundle bundle) {
        DocumentSearchResult result = (DocumentSearchResult) execution.outputs().get("documentSearchResult");
        DocumentSkillEnrichmentWorkflow.Result skillResult = (DocumentSkillEnrichmentWorkflow.Result)
            execution.outputs().get("documentSkillEnrichment");
        Map<String, Object> bundleMetadata = new LinkedHashMap<>(bundle.metadata());
        Map<String, Object> outcomeMetadata = new LinkedHashMap<>();
        outcomeMetadata.put("workflowId", workflowId());
        if (skillResult != null) {
            Map<String, Object> skillProjection = skillProjection(skillResult);
            bundleMetadata.put("documentSkillEnrichment", skillProjection);
            outcomeMetadata.put("documentSkillEnrichment", skillProjection);
            if (!skillResult.compiledContext().isBlank()) {
                outcomeMetadata.put("skillAnalysisContext", skillResult.compiledContext());
            }
        }
        EvidenceBundle enrichedBundle = new EvidenceBundle(bundle.schemaVersion(), bundle.evidence(),
            bundle.limitations(), bundleMetadata);
        return new AnalysisExecutionOutcome(AnalysisExecutionOutcome.SCHEMA_VERSION, type(), plan, verification,
            enrichedBundle, result == null ? "" : result.context(), outcomeMetadata);
    }

    private PlanStep step(String id, String operation) {
        return new PlanStep(id, operation, AnalysisCapability.DOCUMENT_SEARCH, true, Map.of());
    }

    private DocumentAnalysisEvidence evidence(DocumentEvidenceChunk chunk,
                                              DocumentSkillEnrichmentWorkflow.Result skillResult) {
        String citation = chunk.citation() == null ? null
            : chunk.citation().source() + (chunk.citation().locator() == null ? "" : "#" + chunk.citation().locator());
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("chunkIndex", chunk.chunkIndex() == null ? -1 : chunk.chunkIndex());
        attributes.put("chunkType", chunk.chunkType() == null ? "" : chunk.chunkType());
        if (skillResult != null) {
            attributes.put("activatedSkillIds", skillResult.activatedSkillIds());
            attributes.put("skillEnrichmentStatus", skillResult.status());
            if (!skillResult.planningKnowledge().isEmpty()) {
                attributes.put("skillPlanningKnowledge", skillResult.planningKnowledge());
            }
        }
        return new DocumentAnalysisEvidence(chunk.refId(), chunk.fileId(), chunk.chunkId(), chunk.fileName(),
            chunk.section(), citation, chunk.content(), chunk.score() == null ? 0D : chunk.score(),
            attributes);
    }

    private Map<String, Object> skillProjection(DocumentSkillEnrichmentWorkflow.Result result) {
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("schemaVersion", "document_skill_enrichment.v1");
        projection.put("status", result.status());
        projection.put("candidates", result.candidates());
        projection.put("activatedSkills", result.activatedSkills());
        projection.put("activatedSkillIds", result.activatedSkillIds());
        projection.put("planningKnowledge", result.planningKnowledge());
        projection.put("trace", result.trace());
        if (result.error() != null && !result.error().isBlank()) projection.put("error", result.error());
        return Map.copyOf(projection);
    }

    private int integerAttribute(AnalysisContext context, String key, int fallback) {
        Object value = context.attributes().get(key);
        return value instanceof Number number ? Math.max(1, number.intValue()) : fallback;
    }

    private boolean booleanAttribute(AnalysisContext context, String key) {
        return Boolean.TRUE.equals(context.attributes().get(key));
    }
}
