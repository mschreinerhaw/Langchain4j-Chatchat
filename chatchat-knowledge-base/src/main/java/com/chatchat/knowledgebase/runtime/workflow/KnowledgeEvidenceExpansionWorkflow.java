package com.chatchat.knowledgebase.runtime.workflow;

import com.chatchat.common.knowledge.model.KnowledgeIR;
import com.chatchat.common.knowledge.model.KnowledgeScope;
import com.chatchat.common.knowledge.model.KnowledgeSourceReference;
import com.chatchat.common.knowledge.runtime.KnowledgeRequest;
import com.chatchat.knowledgebase.search.document.DocumentExpandedEvidenceChunk;
import com.chatchat.knowledgebase.search.document.DocumentSearchEvidenceService;
import com.chatchat.knowledgebase.search.document.DocumentSearchExpandRequest;
import com.chatchat.knowledgebase.search.document.DocumentSearchExpandResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Runtime-owned continuation for a truncated document evidence bundle.
 *
 * <p>The workflow is deliberately deterministic: the initial retrieval decides the authorized
 * documents and sections, this workflow expands exactly those sources, and the Agent Planner is
 * never asked to invent a retrieval tool or continuation plan.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KnowledgeEvidenceExpansionWorkflow {

    private static final int RESERVED_BUNDLE_TOKENS = 1_000;
    private static final int MAX_CHARS_PER_DOCUMENT = 6_000;
    private static final int MAX_CHUNKS_PER_DOCUMENT = 8;

    private final DocumentSearchEvidenceService documents;

    public ExpansionResult expand(KnowledgeRequest request, List<KnowledgeIR> initialUnits) {
        List<KnowledgeIR> safeUnits = initialUnits == null
            ? List.of() : initialUnits.stream().filter(java.util.Objects::nonNull).toList();
        Map<String, List<KnowledgeIR>> byDocument = sourceUnitsByDocument(safeUnits);
        if (byDocument.isEmpty()) {
            return ExpansionResult.notApplicable("NO_DOCUMENT_SOURCES");
        }

        int totalCharacterBudget = Math.max(1_000,
            KnowledgeRequest.HARD_MAX_TOKENS - RESERVED_BUNDLE_TOKENS);
        int charactersPerDocument = Math.max(1,
            Math.min(MAX_CHARS_PER_DOCUMENT, totalCharacterBudget / byDocument.size()));
        List<KnowledgeIR> expanded = new ArrayList<>();
        safeUnits.stream().filter(unit -> documentId(unit) == null).forEach(expanded::add);

        for (Map.Entry<String, List<KnowledgeIR>> entry : byDocument.entrySet()) {
            String documentId = entry.getKey();
            List<KnowledgeIR> sourceUnits = entry.getValue();
            KnowledgeIR template = sourceUnits.stream()
                .max(Comparator.comparingDouble(KnowledgeIR::relevance))
                .orElseThrow();
            List<String> sections = sourceUnits.stream()
                .map(KnowledgeIR::source)
                .filter(java.util.Objects::nonNull)
                .map(KnowledgeSourceReference::section)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
            DocumentSearchExpandResult result = documents.expand(expansionRequest(
                request, documentId, sections, charactersPerDocument));
            if (result.evidenceChunks().isEmpty()) {
                log.warn("knowledgeEvidenceExpansionFailed documentId={} reason=NO_EXPANDED_CHUNKS", documentId);
                return ExpansionResult.failed("NO_EXPANDED_CHUNKS:" + documentId);
            }
            result.evidenceChunks().stream()
                .sorted(Comparator.comparing(chunk -> chunk.chunkIndex() == null
                    ? Integer.MAX_VALUE : chunk.chunkIndex()))
                .map(chunk -> toKnowledgeIr(request, template, chunk))
                .forEach(expanded::add);
        }
        log.info("knowledgeEvidenceExpansionWorkflowCompleted documents={} initialUnits={} expandedUnits={} "
                + "charactersPerDocument={}",
            byDocument.size(), safeUnits.size(), expanded.size(), charactersPerDocument);
        return new ExpansionResult(true, true, List.copyOf(expanded), "COMPLETE");
    }

    private Map<String, List<KnowledgeIR>> sourceUnitsByDocument(List<KnowledgeIR> units) {
        Map<String, List<KnowledgeIR>> grouped = new LinkedHashMap<>();
        units.stream()
            .sorted(Comparator.comparingDouble(KnowledgeIR::relevance).reversed())
            .forEach(unit -> {
                String documentId = documentId(unit);
                if (documentId != null) grouped.computeIfAbsent(documentId, ignored -> new ArrayList<>()).add(unit);
            });
        return grouped;
    }

    private DocumentSearchExpandRequest expansionRequest(KnowledgeRequest request,
                                                          String documentId,
                                                          List<String> sections,
                                                          int maxTotalChars) {
        KnowledgeScope scope = request.scope();
        boolean strictDocumentScope = !scope.documentIds().isEmpty();
        return new DocumentSearchExpandRequest(
            request.query(), documentId,
            scope.documentIds(), scope.documentIds(), strictDocumentScope,
            sections, MAX_CHUNKS_PER_DOCUMENT,
            Math.max(1, new LinkedHashSet<>(sections).size()),
            MAX_CHUNKS_PER_DOCUMENT, maxTotalChars,
            scope.tenantId(), scope.userId(), scope.roles(), false);
    }

    private KnowledgeIR toKnowledgeIr(KnowledgeRequest request,
                                      KnowledgeIR template,
                                      DocumentExpandedEvidenceChunk chunk) {
        String citation = chunk.citation() == null ? null
            : chunk.citation().source() + (chunk.citation().locator() == null
                ? "" : "#" + chunk.citation().locator());
        KnowledgeSourceReference source = new KnowledgeSourceReference(
            chunk.refId(), chunk.fileId(), chunk.chunkId(), chunk.fileName(), chunk.section(), null, citation);
        String id = chunk.chunkId() == null || chunk.chunkId().isBlank()
            ? template.knowledgeId() + "-expanded-" + (chunk.chunkIndex() == null ? 0 : chunk.chunkIndex())
            : chunk.chunkId();
        String title = chunk.section() == null || chunk.section().isBlank()
            ? template.title() : chunk.section();
        double score = chunk.score() == null ? template.relevance()
            : Math.max(0D, Math.min(1D, chunk.score() / 100D));
        return new KnowledgeIR(id, template.domain(), template.type(), title, chunk.text(),
            template.rules(), template.constraints(), List.of(request.taskType()),
            template.requiredInputs(), chunk.text(), source, score);
    }

    private String documentId(KnowledgeIR unit) {
        if (unit == null || unit.source() == null || unit.source().documentId() == null
            || unit.source().documentId().isBlank()) return null;
        return unit.source().documentId().trim();
    }

    public record ExpansionResult(boolean applicable,
                                  boolean complete,
                                  List<KnowledgeIR> units,
                                  String status) {
        public ExpansionResult {
            units = units == null ? List.of() : List.copyOf(units);
            status = status == null ? "UNKNOWN" : status;
        }

        static ExpansionResult notApplicable(String status) {
            return new ExpansionResult(false, false, List.of(), status);
        }

        static ExpansionResult failed(String status) {
            return new ExpansionResult(true, false, List.of(), status);
        }
    }
}
