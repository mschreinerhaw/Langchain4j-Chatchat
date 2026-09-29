package com.chatchat.knowledgebase.runtime.workflow;

import com.chatchat.common.knowledge.model.KnowledgeIR;
import com.chatchat.common.knowledge.model.KnowledgeScope;
import com.chatchat.common.knowledge.model.KnowledgeSourceReference;
import com.chatchat.common.knowledge.runtime.KnowledgeRequest;
import com.chatchat.common.runtime.analysis.evidence.AnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.DocumentAnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.recovery.EvidenceGap;
import com.chatchat.common.runtime.analysis.recovery.EvidenceGapReason;
import com.chatchat.common.runtime.analysis.recovery.EvidenceRecoveryResult;
import com.chatchat.common.runtime.analysis.recovery.RecoveryStatus;
import com.chatchat.common.runtime.analysis.recovery.RecoveryStrategy;
import com.chatchat.common.runtime.analysis.recovery.RecoveryLevel;
import com.chatchat.common.runtime.analysis.spi.EvidenceRecoveryWorkflow;
import com.chatchat.knowledgebase.search.document.api.evidence.DocumentEvidenceChunk;
import com.chatchat.knowledgebase.search.document.api.evidence.DocumentExpandedEvidenceChunk;
import com.chatchat.knowledgebase.search.document.application.DocumentSearchEvidenceService;
import com.chatchat.knowledgebase.search.document.api.evidence.DocumentSearchExpandRequest;
import com.chatchat.knowledgebase.search.document.api.evidence.DocumentSearchExpandResult;
import com.chatchat.knowledgebase.search.document.api.search.DocumentSearchFilters;
import com.chatchat.knowledgebase.search.document.api.search.DocumentSearchRequest;
import com.chatchat.knowledgebase.search.document.api.search.DocumentSearchResult;
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
public class KnowledgeEvidenceExpansionWorkflow implements EvidenceRecoveryWorkflow {

    private static final int RESERVED_BUNDLE_TOKENS = 1_000;
    private static final int MAX_CHARS_PER_DOCUMENT = 6_000;
    private static final int MAX_CHUNKS_PER_DOCUMENT = 8;

    private final DocumentSearchEvidenceService documents;

    @Override
    public boolean supports(AnalysisContext context, EvidenceGap gap) {
        if (context == null || gap == null) return false;
        return gap.documentId() != null
            || !context.documentIds().isEmpty()
            || !context.documentTags().isEmpty()
            || (context.intent() != null
                && context.intent().requiredCapabilities().contains(AnalysisCapability.DOCUMENT_SEARCH));
    }

    @Override
    public int priority() { return 100; }

    @Override
    public EvidenceRecoveryResult recover(AnalysisContext context,
                                          EvidenceBundle currentEvidence,
                                          EvidenceGap gap,
                                          int recoveryRound) {
        RecoveryStrategy strategy = strategy(gap.reason());
        List<DocumentAnalysisEvidence> currentDocuments = currentEvidence.evidence().stream()
            .filter(DocumentAnalysisEvidence.class::isInstance)
            .map(DocumentAnalysisEvidence.class::cast)
            .toList();
        String documentId = firstNonBlank(gap.documentId(), currentDocuments.stream()
            .map(DocumentAnalysisEvidence::documentId).filter(this::hasText).findFirst().orElse(null));
        String section = firstNonBlank(gap.sectionId(), currentDocuments.stream()
            .map(DocumentAnalysisEvidence::section).filter(this::hasText).findFirst().orElse(null));
        RecoveryLevel level = level(strategy, recoveryRound, gap.sequenceSensitive());
        List<DocumentAnalysisEvidence> recovered = recoverAtLevel(
            context, gap, level, documentId, section);
        if (recovered.isEmpty()) {
            return new EvidenceRecoveryResult(RecoveryStatus.EXHAUSTED, currentEvidence, List.of(gap),
                recoveryRound, strategy, level,
                Map.of("recoveryComplete", false, "strategy", strategy.name()));
        }

        Map<String, AnalysisEvidence> merged = new LinkedHashMap<>();
        currentEvidence.evidence().forEach(item -> merged.put(evidenceKey(item), item));
        recovered.forEach(item -> merged.put(evidenceKey(item), item));
        Map<String, Object> metadata = new LinkedHashMap<>(currentEvidence.metadata());
        int recoveredCharacters = recovered.stream().mapToInt(item -> item.content().length()).sum();
        boolean recoveryComplete = recovered.size() < chunkBudget(level)
            && recoveredCharacters < characterBudget(level)
            && (!gap.sequenceSensitive()
                || level.ordinal() >= RecoveryLevel.L3_ADJACENT_SECTION.ordinal());
        metadata.put("recoveryComplete", recoveryComplete);
        metadata.put("sourceTruncated", !recoveryComplete);
        metadata.put("evidenceCoverage", 1.0D);
        metadata.put("recoveryStrategy", strategy.name());
        metadata.put("recoveryLevel", level.name());
        metadata.put("recoveryRound", recoveryRound);
        if (gap.sequenceSensitive()) metadata.put("sequenceComplete", recoveryComplete);
        EvidenceBundle bundle = new EvidenceBundle(EvidenceBundle.SCHEMA_VERSION,
            List.copyOf(merged.values()), currentEvidence.limitations(), metadata);
        return new EvidenceRecoveryResult(RecoveryStatus.RETRY_REQUIRED, bundle, List.of(),
            recoveryRound, strategy, level, Map.of("recoveryComplete", recoveryComplete,
                "strategy", strategy.name(), "level", level.name(), "evidenceCoverage", 1.0D));
    }

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
        int successfulDocuments = 0;
        List<String> failures = new ArrayList<>();

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
            DocumentSearchExpandResult result;
            try {
                result = documents.expand(expansionRequest(
                    request, documentId, sections, charactersPerDocument));
            } catch (RuntimeException ex) {
                if (!isSourceUnavailable(ex)) throw ex;
                String reason = expansionFailureReason(ex);
                failures.add(documentId + ":" + reason);
                log.warn("knowledgeEvidenceExpansionSourceUnavailable documentId={} reason={} error={}",
                    documentId, reason, ex.getMessage());
                continue;
            }
            if (result.evidenceChunks().isEmpty()) {
                failures.add(documentId + ":NO_EXPANDED_CHUNKS");
                log.warn("knowledgeEvidenceExpansionSourceUnavailable documentId={} reason=NO_EXPANDED_CHUNKS",
                    documentId);
                continue;
            }
            result.evidenceChunks().stream()
                .sorted(Comparator.comparing(chunk -> chunk.chunkIndex() == null
                    ? Integer.MAX_VALUE : chunk.chunkIndex()))
                .map(chunk -> toKnowledgeIr(request, template, chunk))
                .forEach(expanded::add);
            successfulDocuments++;
        }
        boolean complete = failures.isEmpty();
        String status = complete ? "COMPLETE" : successfulDocuments > 0 ? "PARTIAL" : "UNAVAILABLE";
        log.info("knowledgeEvidenceExpansionWorkflowCompleted status={} attemptedDocuments={} "
                + "successfulDocuments={} failedDocuments={} initialUnits={} expandedUnits={} "
                + "charactersPerDocument={} failures={}",
            status, byDocument.size(), successfulDocuments, failures.size(), safeUnits.size(), expanded.size(),
            charactersPerDocument, failures);
        return new ExpansionResult(true, complete, List.copyOf(expanded), status,
            successfulDocuments, List.copyOf(failures));
    }

    private List<DocumentAnalysisEvidence> recoverAtLevel(AnalysisContext context,
                                                          EvidenceGap gap,
                                                          RecoveryLevel level,
                                                          String documentId,
                                                          String section) {
        if (!hasText(documentId) || level == RecoveryLevel.L6_CROSS_DOCUMENT_SEARCH
            || level == RecoveryLevel.L7_EXTERNAL_SOURCE) {
            return searchAnalysisEvidence(context, gap, null);
        }
        if (level == RecoveryLevel.L4_SAME_DOCUMENT_SEARCH) {
            return searchAnalysisEvidence(context, gap, documentId);
        }
        return expandAnalysisEvidence(context, documentId, section, level);
    }

    private List<DocumentAnalysisEvidence> expandAnalysisEvidence(AnalysisContext context,
                                                                  String documentId,
                                                                  String section,
                                                                  RecoveryLevel level) {
        List<String> sections = hasText(section) && level != RecoveryLevel.L5_ORIGINAL_DOCUMENT
            ? List.of(section) : List.of();
        DocumentSearchExpandResult result = documents.expand(new DocumentSearchExpandRequest(
            context.query(), documentId,
            context.documentIds(), context.documentIds(), !context.documentIds().isEmpty(),
            sections, chunkBudget(level), sectionBudget(level, sections.size()), chunkBudget(level),
            characterBudget(level), context.kernelScope().tenantId(), context.kernelScope().userId(),
            context.roles(), false));
        return result.evidenceChunks().stream().map(this::toAnalysisEvidence).toList();
    }

    private List<DocumentAnalysisEvidence> searchAnalysisEvidence(AnalysisContext context,
                                                                  EvidenceGap gap,
                                                                  String forcedDocumentId) {
        String query = gap.missingEvidence().isEmpty() ? context.query()
            : context.query() + " " + String.join(" ", gap.missingEvidence());
        List<String> documentIds = hasText(forcedDocumentId)
            ? List.of(forcedDocumentId) : context.documentIds();
        DocumentSearchResult result = documents.search(new DocumentSearchRequest(
            query, 8, documentIds, documentIds, documentIds,
            !documentIds.isEmpty(),
            new DocumentSearchFilters(null, null, null, null, null, context.documentTags()),
            context.kernelScope().tenantId(), context.kernelScope().userId(), context.roles(), false));
        return result.results().stream().map(this::toAnalysisEvidence).toList();
    }

    private DocumentAnalysisEvidence toAnalysisEvidence(DocumentExpandedEvidenceChunk chunk) {
        String citation = chunk.citation() == null ? null
            : chunk.citation().source() + (chunk.citation().locator() == null
                ? "" : "#" + chunk.citation().locator());
        return new DocumentAnalysisEvidence(chunk.refId(), chunk.fileId(), chunk.chunkId(), chunk.fileName(),
            chunk.section(), citation, chunk.text(), normalizedExpandedScore(chunk.score()), Map.of(
                "chunkIndex", chunk.chunkIndex() == null ? -1 : chunk.chunkIndex(),
                "chunkType", chunk.chunkType() == null ? "expanded_evidence" : chunk.chunkType(),
                "recovered", true));
    }

    private DocumentAnalysisEvidence toAnalysisEvidence(DocumentEvidenceChunk chunk) {
        String citation = chunk.citation() == null ? null
            : chunk.citation().source() + (chunk.citation().locator() == null
                ? "" : "#" + chunk.citation().locator());
        return new DocumentAnalysisEvidence(chunk.refId(), chunk.fileId(), chunk.chunkId(), chunk.fileName(),
            chunk.section(), citation, chunk.content(), normalizedExpandedScore(chunk.score()), Map.of(
                "chunkIndex", chunk.chunkIndex() == null ? -1 : chunk.chunkIndex(),
                "chunkType", chunk.chunkType() == null ? "recovered_evidence" : chunk.chunkType(),
                "recovered", true));
    }

    private RecoveryStrategy strategy(EvidenceGapReason reason) {
        return switch (reason) {
            case SOURCE_TRUNCATED, MISSING_CONTEXT -> RecoveryStrategy.CONTEXT_EXPANSION;
            case SECTION_INCOMPLETE -> RecoveryStrategy.SECTION_EXPANSION;
            case SEQUENCE_INCOMPLETE -> RecoveryStrategy.ADJACENT_SECTION_SEARCH;
            case CLAIM_UNSUPPORTED, LOW_COVERAGE -> RecoveryStrategy.CLAIM_TARGETED_SEARCH;
            case CONFLICTING_EVIDENCE, SOURCE_NOT_AUTHORITATIVE -> RecoveryStrategy.CROSS_SOURCE_VERIFY;
            case MISSING_PRIMARY_SOURCE -> RecoveryStrategy.ORIGINAL_DOCUMENT_FETCH;
            case RETRIEVAL_EMPTY, RETRIEVAL_WEAK, DATA_INCOMPLETE ->
                RecoveryStrategy.QUERY_REWRITE_HYBRID_RETRIEVAL;
        };
    }

    private RecoveryLevel level(RecoveryStrategy strategy, int round, boolean sequenceSensitive) {
        RecoveryLevel base = switch (strategy) {
            case CONTEXT_EXPANSION -> RecoveryLevel.L1_CHUNK_EXPANSION;
            case SECTION_EXPANSION -> RecoveryLevel.L2_PARENT_SECTION;
            case ADJACENT_SECTION_SEARCH -> RecoveryLevel.L3_ADJACENT_SECTION;
            case CLAIM_TARGETED_SEARCH -> RecoveryLevel.L4_SAME_DOCUMENT_SEARCH;
            case ORIGINAL_DOCUMENT_FETCH -> RecoveryLevel.L5_ORIGINAL_DOCUMENT;
            case CROSS_SOURCE_VERIFY, QUERY_REWRITE_HYBRID_RETRIEVAL -> RecoveryLevel.L6_CROSS_DOCUMENT_SEARCH;
        };
        if (base.ordinal() >= RecoveryLevel.L4_SAME_DOCUMENT_SEARCH.ordinal()) return base;
        int maximum = sequenceSensitive
            ? RecoveryLevel.L5_ORIGINAL_DOCUMENT.ordinal()
            : RecoveryLevel.L4_SAME_DOCUMENT_SEARCH.ordinal();
        return RecoveryLevel.values()[Math.min(maximum, base.ordinal() + Math.max(0, round - 1))];
    }

    private int sectionBudget(RecoveryLevel level, int requestedSections) {
        return switch (level) {
            case L1_CHUNK_EXPANSION -> Math.max(1, requestedSections);
            case L2_PARENT_SECTION -> 3;
            case L3_ADJACENT_SECTION, L4_SAME_DOCUMENT_SEARCH -> 5;
            case L5_ORIGINAL_DOCUMENT, L6_CROSS_DOCUMENT_SEARCH, L7_EXTERNAL_SOURCE -> 10;
        };
    }

    private int chunkBudget(RecoveryLevel level) {
        return switch (level) {
            case L1_CHUNK_EXPANSION -> 4;
            case L2_PARENT_SECTION -> 8;
            case L3_ADJACENT_SECTION, L4_SAME_DOCUMENT_SEARCH -> 12;
            case L5_ORIGINAL_DOCUMENT, L6_CROSS_DOCUMENT_SEARCH, L7_EXTERNAL_SOURCE -> 20;
        };
    }

    private int characterBudget(RecoveryLevel level) {
        return switch (level) {
            case L1_CHUNK_EXPANSION -> 4_000;
            case L2_PARENT_SECTION -> 6_000;
            case L3_ADJACENT_SECTION, L4_SAME_DOCUMENT_SEARCH -> 12_000;
            case L5_ORIGINAL_DOCUMENT, L6_CROSS_DOCUMENT_SEARCH, L7_EXTERNAL_SOURCE -> 20_000;
        };
    }

    private String evidenceKey(AnalysisEvidence evidence) {
        if (evidence instanceof DocumentAnalysisEvidence document && hasText(document.chunkId())) {
            return "DOCUMENT|" + document.documentId() + "|" + document.chunkId();
        }
        return evidence.capability() + "|" + evidence.evidenceId();
    }

    private double normalizedExpandedScore(Double score) {
        if (score == null || score.isNaN()) return 0.5D;
        return score > 1D ? Math.max(0D, Math.min(1D, score / 100D))
            : Math.max(0D, score);
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }

    private String firstNonBlank(String first, String second) {
        return hasText(first) ? first.trim() : hasText(second) ? second.trim() : null;
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

    private String expansionFailureReason(RuntimeException exception) {
        String message = exception.getMessage();
        if (exception instanceof IllegalArgumentException && message != null
            && message.startsWith("document not found:")) return "DOCUMENT_NOT_FOUND";
        if (exception instanceof IllegalArgumentException && message != null
            && message.startsWith("document is not visible")) return "DOCUMENT_NOT_VISIBLE";
        return "SOURCE_EXPANSION_ERROR";
    }

    private boolean isSourceUnavailable(RuntimeException exception) {
        if (!(exception instanceof IllegalArgumentException) || exception.getMessage() == null) return false;
        return exception.getMessage().startsWith("document not found:")
            || exception.getMessage().startsWith("document is not visible");
    }

    public record ExpansionResult(boolean applicable,
                                  boolean complete,
                                  List<KnowledgeIR> units,
                                  String status,
                                  int successfulSources,
                                  List<String> failures) {
        public ExpansionResult {
            units = units == null ? List.of() : List.copyOf(units);
            status = status == null ? "UNKNOWN" : status;
            successfulSources = Math.max(0, successfulSources);
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        public ExpansionResult(boolean applicable, boolean complete, List<KnowledgeIR> units, String status) {
            this(applicable, complete, units, status, complete ? 1 : 0, List.of());
        }

        static ExpansionResult notApplicable(String status) {
            return new ExpansionResult(false, false, List.of(), status, 0, List.of());
        }

        static ExpansionResult failed(String status) {
            return new ExpansionResult(true, false, List.of(), status, 0, List.of(status));
        }
    }
}
