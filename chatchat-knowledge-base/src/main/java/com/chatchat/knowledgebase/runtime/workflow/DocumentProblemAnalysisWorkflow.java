package com.chatchat.knowledgebase.runtime.workflow;

import com.chatchat.common.runtime.analysis.workflow.*;
import com.chatchat.knowledgebase.search.document.DocumentEvidenceChunk;
import com.chatchat.knowledgebase.search.document.DocumentSearchEvidenceService;
import com.chatchat.knowledgebase.search.document.DocumentSearchFilters;
import com.chatchat.knowledgebase.search.document.DocumentSearchRequest;
import com.chatchat.knowledgebase.search.document.DocumentSearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Document-class implementation of the parent problem-analysis workflow. */
@Component
@RequiredArgsConstructor
public class DocumentProblemAnalysisWorkflow extends AbstractAnalysisWorkflow {
    private final DocumentSearchEvidenceService documents;

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
            step("9", "EVIDENCE_BUNDLE")),
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
        List<AnalysisEvidence> evidence = result.results().stream()
            .map(chunk -> (AnalysisEvidence) evidence(chunk)).toList();
        return new WorkflowExecutionResult(evidence, Map.of("documentSearchResult", result),
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
    protected AnalysisResult synthesize(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan,
                                        WorkflowExecutionResult execution, VerificationResult verification,
                                        EvidenceBundle bundle) {
        DocumentSearchResult result = (DocumentSearchResult) execution.outputs().get("documentSearchResult");
        return new AnalysisResult(AnalysisResult.SCHEMA_VERSION, type(), plan, verification, bundle,
            result == null ? "" : result.context(), Map.of("workflowId", workflowId()));
    }

    private PlanStep step(String id, String operation) {
        return new PlanStep(id, operation, AnalysisCapability.DOCUMENT_SEARCH, true, Map.of());
    }

    private DocumentAnalysisEvidence evidence(DocumentEvidenceChunk chunk) {
        String citation = chunk.citation() == null ? null
            : chunk.citation().source() + (chunk.citation().locator() == null ? "" : "#" + chunk.citation().locator());
        return new DocumentAnalysisEvidence(chunk.refId(), chunk.fileId(), chunk.chunkId(), chunk.fileName(),
            chunk.section(), citation, chunk.content(), chunk.score() == null ? 0D : chunk.score(),
            Map.of("chunkIndex", chunk.chunkIndex() == null ? -1 : chunk.chunkIndex(),
                "chunkType", chunk.chunkType() == null ? "" : chunk.chunkType()));
    }

    private int integerAttribute(AnalysisContext context, String key, int fallback) {
        Object value = context.attributes().get(key);
        return value instanceof Number number ? Math.max(1, number.intValue()) : fallback;
    }

    private boolean booleanAttribute(AnalysisContext context, String key) {
        return Boolean.TRUE.equals(context.attributes().get(key));
    }
}
