package com.chatchat.knowledgebase.search.workflow;

import com.chatchat.knowledgebase.runtime.index.KnowledgeIREntity;
import com.chatchat.knowledgebase.runtime.index.KnowledgeIRRepository;
import com.chatchat.knowledgebase.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Loads parent/section navigation metadata for the reranked document set. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ParentSectionExpansionStage implements DocumentRetrievalStage {
    private final KnowledgeIRRepository repository;
    private final SearchProperties properties;

    @Override public String id() { return "parent-section-expansion"; }
    @Override public int order() { return 550; }

    @Override
    public void execute(DocumentRetrievalWorkflowContext context) {
        List<String> documentIds = context.candidates().stream()
            .map(candidate -> candidate.result().docId()).distinct().toList();
        if (documentIds.isEmpty()) return;
        int limit = properties.getProblemAnalysis() == null ? 20
            : Math.max(1, properties.getProblemAnalysis().getParentSectionLimitPerDocument());
        Map<String, List<KnowledgeIREntity>> byDocument;
        try {
            byDocument = repository.findByDocumentIdInAndActiveTrue(documentIds)
                .stream().collect(Collectors.groupingBy(KnowledgeIREntity::getDocumentId));
        } catch (RuntimeException ex) {
            log.warn("parent_section_expansion_failed documents={} error={}", documentIds.size(), ex.getMessage());
            return;
        }
        for (String documentId : documentIds) {
            List<DocumentParentSection> sections = byDocument.getOrDefault(documentId, List.of()).stream()
                .limit(limit).map(this::reference).toList();
            context.parentSections(documentId, sections);
        }
    }

    private DocumentParentSection reference(KnowledgeIREntity entity) {
        return new DocumentParentSection(entity.getDocumentId(), entity.getKnowledgeId(),
            entity.getSourceChunkId(), entity.getSourceSection(), entity.getTitle(), entity.getSourceCitation());
    }
}
