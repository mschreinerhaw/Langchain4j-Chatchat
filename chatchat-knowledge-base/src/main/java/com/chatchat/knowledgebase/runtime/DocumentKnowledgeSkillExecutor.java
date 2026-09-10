package com.chatchat.knowledgebase.runtime;

import com.chatchat.common.knowledge.KnowledgeIR;
import com.chatchat.common.knowledge.KnowledgeSkillExecutionContext;
import com.chatchat.common.knowledge.KnowledgeSkillExecutorPort;
import com.chatchat.common.knowledge.KnowledgeSkillInstance;
import com.chatchat.common.knowledge.KnowledgeSkillResult;
import com.chatchat.common.knowledge.KnowledgeSkillType;
import com.chatchat.common.knowledge.KnowledgeSourceReference;
import com.chatchat.knowledgebase.search.document.DocumentEvidenceChunk;
import com.chatchat.knowledgebase.search.document.DocumentSearchEvidenceService;
import com.chatchat.knowledgebase.search.document.DocumentSearchFilters;
import com.chatchat.knowledgebase.search.document.DocumentSearchRequest;
import com.chatchat.knowledgebase.search.document.DocumentSearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.Map;

/** Current document-index adapter. It can later be replaced by a native Knowledge IR index. */
@Component
@Order(100)
@RequiredArgsConstructor
public class DocumentKnowledgeSkillExecutor implements KnowledgeSkillExecutorPort {

    private final DocumentSearchEvidenceService documentSearchService;

    @Override
    public boolean supports(KnowledgeSkillType skillType) {
        return skillType != null;
    }

    @Override
    public KnowledgeSkillResult execute(KnowledgeSkillExecutionContext context) {
        KnowledgeSkillInstance skill = context.skill();
        String query = skill.goal() + (skill.queryHints().isEmpty()
            ? "" : "；检索提示：" + String.join("、", skill.queryHints()));
        List<String> documentIds = context.request().scope().documentIds();
        List<String> tags = context.request().scope().tags();
        DocumentSearchResult result = documentSearchService.search(new DocumentSearchRequest(
            query, 3, documentIds, documentIds, documentIds, true,
            new DocumentSearchFilters(null, null, tags.isEmpty() ? null : tags.get(0), null, null),
            context.request().scope().tenantId(), context.request().scope().userId(), List.of(), false));
        List<KnowledgeIR> units = toUnits(context, result);
        return new KnowledgeSkillResult(skill.instanceId(), skill.skillType(), units,
            units.isEmpty() ? "empty" : "used", Map.of("adapter", "document-index"));
    }

    private List<KnowledgeIR> toUnits(KnowledgeSkillExecutionContext context, DocumentSearchResult result) {
        if (result == null) return List.of();
        if (!result.results().isEmpty()) {
            return result.results().stream().map(chunk -> toIr(context.skill(), chunk)).toList();
        }
        if (result.context() == null || result.context().isBlank()) return List.of();
        KnowledgeSourceReference source = result.citations().isEmpty() ? null
            : new KnowledgeSourceReference(
                result.citations().get(0).refId(), result.citations().get(0).fileId(),
                result.citations().get(0).chunkId(), result.citations().get(0).fileName(),
                result.citations().get(0).section(), null, result.citations().get(0).citation());
        KnowledgeSkillInstance skill = context.skill();
        return List.of(new KnowledgeIR(
            skill.instanceId() + "-context", skill.domain(), skill.skillType().knowledgeType(),
            skill.goal(), result.context(), List.of(), List.of(),
            List.of(context.request().taskType()), List.of(), result.context(), source, 0.5D));
    }

    private KnowledgeIR toIr(KnowledgeSkillInstance skill, DocumentEvidenceChunk chunk) {
        KnowledgeSourceReference source = new KnowledgeSourceReference(
            chunk.refId(), chunk.fileId(), chunk.chunkId(), chunk.fileName(), chunk.section(), null,
            chunk.citation() == null ? null
                : chunk.citation().source() + (chunk.citation().locator() == null
                    ? "" : "#" + chunk.citation().locator()));
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
