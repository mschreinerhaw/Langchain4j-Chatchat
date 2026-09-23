package com.chatchat.knowledgebase.runtime;

import com.chatchat.common.knowledge.KnowledgeIR;
import com.chatchat.common.knowledge.KnowledgeSkillExecutionContext;
import com.chatchat.common.knowledge.KnowledgeSkillExecutorPort;
import com.chatchat.common.knowledge.KnowledgeSkillInstance;
import com.chatchat.common.knowledge.KnowledgeSkillResult;
import com.chatchat.common.knowledge.KnowledgeSkillType;
import com.chatchat.common.knowledge.KnowledgeSourceReference;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisIntent;
import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.spi.AnalysisRuntimePort;
import com.chatchat.common.runtime.analysis.evidence.DocumentAnalysisEvidence;
import com.chatchat.knowledgebase.search.document.DocumentSearchEvidenceService;
import com.chatchat.knowledgebase.runtime.workflow.DocumentProblemAnalysisWorkflow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Current document-index adapter. It can later be replaced by a native Knowledge IR index. */
@Component
@Order(100)
public class DocumentKnowledgeSkillExecutor implements KnowledgeSkillExecutorPort {

    private final DocumentProblemAnalysisWorkflow workflow;
    @Autowired(required = false)
    private AnalysisRuntimePort analysisRuntime;

    @Autowired
    public DocumentKnowledgeSkillExecutor(DocumentProblemAnalysisWorkflow workflow) {
        this.workflow = workflow;
    }

    /** Compatibility constructor for isolated callers. */
    public DocumentKnowledgeSkillExecutor(DocumentSearchEvidenceService documentSearchService) {
        this(new DocumentProblemAnalysisWorkflow(documentSearchService));
    }

    @Override
    public boolean supports(KnowledgeSkillType skillType) {
        return skillType != null;
    }

    @Override
    public KnowledgeSkillResult execute(KnowledgeSkillExecutionContext context) {
        KnowledgeSkillInstance skill = context.skill();
        String query = skill.goal() + (skill.queryHints().isEmpty()
            ? "" : "；检索提示：" + String.join("、", skill.queryHints()));
        int topK = Math.max(1, Math.min(8, (skill.tokenBudget() + 299) / 300));
        com.chatchat.common.knowledge.KnowledgeScope knowledgeScope = context.request().scope();
        Map<String, Object> kernelAttributes = knowledgeScope.agentId() == null
            ? Map.of() : Map.of("agentId", knowledgeScope.agentId());
        KernelDataScope kernelScope = new KernelDataScope(knowledgeScope.tenantId(), knowledgeScope.userId(),
            UUID.randomUUID().toString(), null, null, null, kernelAttributes);
        AnalysisIntent intent = new AnalysisIntent("DOCUMENT_KNOWLEDGE", List.of(),
            Set.of(AnalysisCapability.DOCUMENT_SEARCH), "UNSPECIFIED", true);
        AnalysisContext analysisContext = new AnalysisContext(query, kernelScope, skill.instanceId(),
            knowledgeScope.documentIds(), knowledgeScope.tags(), knowledgeScope.roles(), intent,
            Map.of("topK", topK));
        AnalysisExecutionOutcome result = analysisRuntime == null
            ? workflow.execute(analysisContext, kernelScope) : analysisRuntime.analyze(analysisContext);
        List<KnowledgeIR> units = toUnits(context, result);
        return new KnowledgeSkillResult(skill.instanceId(), skill.skillType(), units,
            units.isEmpty() ? "empty" : "used",
            Map.of("adapter", "document-index", "topK", topK, "tokenBudget", skill.tokenBudget()));
    }

    private List<KnowledgeIR> toUnits(KnowledgeSkillExecutionContext context, AnalysisExecutionOutcome result) {
        if (result == null) return List.of();
        List<DocumentAnalysisEvidence> evidence = result.evidenceBundle().evidence().stream()
            .filter(DocumentAnalysisEvidence.class::isInstance).map(DocumentAnalysisEvidence.class::cast).toList();
        if (!evidence.isEmpty()) {
            return evidence.stream().map(chunk -> toIr(context.skill(), chunk)).toList();
        }
        if (result.synthesis() == null || result.synthesis().isBlank()) return List.of();
        KnowledgeSkillInstance skill = context.skill();
        return List.of(new KnowledgeIR(
            skill.instanceId() + "-context", skill.domain(), skill.skillType().knowledgeType(),
            skill.goal(), result.synthesis(), List.of(), List.of(),
            List.of(context.request().taskType()), List.of(), result.synthesis(), null, 0.5D));
    }

    private KnowledgeIR toIr(KnowledgeSkillInstance skill, DocumentAnalysisEvidence chunk) {
        KnowledgeSourceReference source = new KnowledgeSourceReference(
            chunk.evidenceId(), chunk.documentId(), chunk.chunkId(), chunk.documentName(), chunk.section(), null,
            chunk.citation());
        return new KnowledgeIR(
            chunk.chunkId() == null || chunk.chunkId().isBlank() ? skill.instanceId() + "-unit" : chunk.chunkId(),
            skill.domain(), skill.skillType().knowledgeType(),
            chunk.section() == null || chunk.section().isBlank() ? skill.goal() : chunk.section(),
            chunk.content(), List.of(), List.of(), List.of(), List.of(), chunk.content(), source,
            normalizedScore(chunk.score()));
    }

    private double normalizedScore(Double score) {
        if (score == null || score.isNaN()) return 0.5D;
        return Math.max(0D, Math.min(score, 1D));
    }
}
