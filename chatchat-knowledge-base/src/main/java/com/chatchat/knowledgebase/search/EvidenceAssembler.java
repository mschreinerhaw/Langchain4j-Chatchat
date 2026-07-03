package com.chatchat.knowledgebase.search;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EvidenceAssembler {

    private final EvidenceContextFormatter contextFormatter;

    public DocumentSearchResult evidenceOnly(String query, String intent, List<DocumentEvidenceChunk> chunks) {
        return contextFormatter.toSearchResult(query, intent, chunks == null ? List.of() : chunks);
    }

    public DocumentSearchResult assemble(String query,
                                         String intent,
                                         List<DocumentEvidenceChunk> chunks,
                                         List<DocumentSearchHit> documents,
                                         List<DocumentOutlineItem> outline) {
        List<DocumentEvidenceChunk> safeChunks = chunks == null ? List.of() : chunks;
        List<DocumentSearchHit> safeDocuments = documents == null ? List.of() : documents;
        List<DocumentOutlineItem> safeOutline = outline == null ? List.of() : outline;
        DocumentSearchMatchType matchType = matchType(safeChunks, safeDocuments);
        DocumentSearchResult base = contextFormatter.toSearchResult(query, intent, safeChunks);
        return new DocumentSearchResult(
            base.contractVersion(),
            base.query(),
            base.intent(),
            safeChunks.isEmpty() ? safeDocuments.size() : base.total() + safeDocuments.size(),
            base.results(),
            base.context(),
            base.citations(),
            base.retrievalState(),
            base.evidenceQuality(),
            base.retrievalEvents(),
            matchType,
            semantics(matchType),
            safeDocuments,
            safeOutline,
            outlineSource(safeOutline),
            expansionPolicy(matchType).withQueryContext(query, intent),
            governancePolicy(matchType)
        );
    }

    private DocumentSearchMatchType matchType(List<DocumentEvidenceChunk> chunks, List<DocumentSearchHit> documents) {
        boolean hasChunks = chunks != null && !chunks.isEmpty();
        boolean hasDocuments = documents != null && !documents.isEmpty();
        if (hasChunks && hasDocuments) {
            return DocumentSearchMatchType.MIXED_HIT;
        }
        if (hasChunks) {
            return DocumentSearchMatchType.CONTENT_HIT;
        }
        if (hasDocuments) {
            return DocumentSearchMatchType.TITLE_ONLY_HIT;
        }
        return DocumentSearchMatchType.NO_HIT;
    }

    private DocumentRetrievalSemantics semantics(DocumentSearchMatchType matchType) {
        return switch (matchType) {
            case CONTENT_HIT -> DocumentRetrievalSemantics.evidenceBody();
            case MIXED_HIT -> DocumentRetrievalSemantics.partialEvidence();
            case TITLE_ONLY_HIT -> DocumentRetrievalSemantics.titleOnly();
            case NO_HIT -> DocumentRetrievalSemantics.noHit();
        };
    }

    private DocumentExpansionPolicy expansionPolicy(DocumentSearchMatchType matchType) {
        return switch (matchType) {
            case TITLE_ONLY_HIT -> DocumentExpansionPolicy.titleOnly();
            case MIXED_HIT -> DocumentExpansionPolicy.mixed();
            case CONTENT_HIT, NO_HIT -> DocumentExpansionPolicy.none();
        };
    }

    private EvidenceGovernancePolicy governancePolicy(DocumentSearchMatchType matchType) {
        return switch (matchType) {
            case CONTENT_HIT, MIXED_HIT -> EvidenceGovernancePolicy.contentReady();
            case TITLE_ONLY_HIT -> EvidenceGovernancePolicy.needsExpansion(
                DocumentExpansionPolicy.titleOnly().maxSections(),
                DocumentExpansionPolicy.titleOnly().maxChunks()
            );
            case NO_HIT -> EvidenceGovernancePolicy.noEvidence();
        };
    }

    private DocumentOutlineSource outlineSource(List<DocumentOutlineItem> outline) {
        if (outline == null || outline.isEmpty()) {
            return null;
        }
        return outline.get(0).source();
    }
}
