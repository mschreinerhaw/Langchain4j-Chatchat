package com.chatchat.knowledgebase.search;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentSearchEvidenceService {

    private static final int DEFAULT_TOP_K = 8;
    private static final int MAX_TOP_K = 30;

    private final SearchService searchService;
    private final SearchTokenizer tokenizer;
    private final QueryIntentClassifier intentClassifier;
    private final EvidenceContextFormatter contextFormatter;
    private final SearchProperties properties;
    private final RetrievalQueryValidator queryValidator;
    private final RetrievalEvidenceQualityScorer qualityScorer;
    private final TextChunker chunker;
    private final EvidenceReasoningEngine reasoningEngine = new EvidenceReasoningEngine();
    private final EvidenceDecisionEngine decisionEngine = new EvidenceDecisionEngine();

    public DocumentSearchResult search(DocumentSearchRequest request) {
        long startedAt = System.nanoTime();
        String traceId = UUID.randomUUID().toString();
        RetrievalExecutionState state = RetrievalExecutionState.started(traceId);
        List<RetrievalEvent> events = new ArrayList<>();
        String query = requireQuery(request == null ? null : request.query());
        int topK = normalizeTopK(request == null ? null : request.topK());
        DocumentSearchFilters filters = request == null ? null : request.filters();
        List<String> scopedFileIds = request == null ? List.of() : safeList(request.fileIds()).stream()
            .filter(this::hasText)
            .map(String::trim)
            .distinct()
            .toList();
        String fileIds = joinValues(scopedFileIds);
        boolean debug = request != null && Boolean.TRUE.equals(request.debug());
        SearchPermissionContext permissionContext = request == null
            ? SearchPermissionContext.system()
            : SearchPermissionContext.of(request.tenantId(), request.userId(), request.roles());
        String intent = intentClassifier.classifyName(query);
        RetrievalValidationResult validation = queryValidator.validate(query, !scopedFileIds.isEmpty(), filters);
        events.add(event(
            traceId,
            RetrievalControlStep.VALIDATOR,
            validation.action(),
            query,
            0,
            state.budgetUsed(),
            state.budgetUsed(),
            elapsedMs(startedAt),
            validation.reason()
        ));
        if (validation.action() == RetrievalControlAction.REJECT || validation.action() == RetrievalControlAction.REWRITE) {
            state = state.withAction(RetrievalControlAction.STOP);
            events.add(event(
                traceId,
                RetrievalControlStep.GATE,
                RetrievalControlAction.STOP,
                query,
                0,
                state.budgetUsed(),
                state.budgetUsed(),
                elapsedMs(startedAt),
                validation.reason()
            ));
            return controlledResult(query, intent, List.of(), state, events);
        }
        events.add(event(
            traceId,
            RetrievalControlStep.GATE,
            RetrievalControlAction.ALLOW,
            query,
            0,
            state.budgetUsed(),
            state.budgetUsed(),
            elapsedMs(startedAt),
            validation.reason()
        ));
        if (!canReserveSearch(state)) {
            state = state.withAction(RetrievalControlAction.STOP);
            events.add(event(
                traceId,
                RetrievalControlStep.BUDGET,
                RetrievalControlAction.STOP,
                query,
                0,
                state.budgetUsed(),
                state.budgetUsed(),
                elapsedMs(startedAt),
                "budget_exhausted"
            ));
            return controlledResult(query, intent, List.of(), state, events, elapsedMs(startedAt));
        }
        RetrievalBudgetUsage beforeBudget = state.budgetUsed();
        RetrievalBudgetReservation reservation = budgetReservation();
        state = state.withBudgetUsed(beforeBudget.plus(reservation.toUsage())).withAction(RetrievalControlAction.SEARCH);
        events.add(event(
            traceId,
            RetrievalControlStep.BUDGET,
            RetrievalControlAction.ALLOW,
            query,
            0,
            beforeBudget,
            state.budgetUsed(),
            elapsedMs(startedAt),
            "budget_reserved"
        ));

        if (!scopedFileIds.isEmpty()) {
            DocumentSearchResult result = searchScopedDocuments(query, topK, scopedFileIds, filters, debug, permissionContext);
            return controlledResult(result, state, events, elapsedMs(startedAt));
        }

        SearchPage page = searchService.search(
            query,
            filters == null ? null : filters.tag(),
            filters == null ? null : filters.company(),
            filters == null ? null : filters.industry(),
            fileIds,
            1,
            Math.max(topK, DEFAULT_TOP_K),
            permissionContext
        );

        List<DocumentEvidenceChunk> chunks = new ArrayList<>();
        List<DocumentSearchHit> documents = new ArrayList<>();
        List<DocumentOutlineItem> outline = new ArrayList<>();
        for (SearchResult result : page.results()) {
            if (!matchesFileType(result, filters == null ? null : filters.fileType())) {
                continue;
            }
            boolean titleOnlyHit = isTitleOnlyHit(result);
            List<SearchMatchedChunk> matchedChunks = result.matchedChunks();
            if (matchedChunks == null || matchedChunks.isEmpty()) {
                if (titleOnlyHit) {
                    addTitleOnlyDocument(documents, outline, result, permissionContext);
                } else {
                    addSummaryEvidence(chunks, result, query, intent, debug, filters);
                }
            } else {
                if (titleOnlyHit) {
                    addTitleOnlyDocument(documents, outline, result, permissionContext);
                    continue;
                }
                for (SearchMatchedChunk chunk : matchedChunks) {
                    if (!matchesChunkType(chunk, filters == null ? null : filters.chunkType())) {
                        continue;
                    }
                    chunks.add(toEvidence(result, chunk, query, intent, debug));
                    if (chunks.size() >= topK) {
                        return controlledResult(toV2SearchResult(query, intent, chunks, documents, outline), state, events, elapsedMs(startedAt));
                    }
                }
            }
            if (chunks.size() >= topK) {
                break;
            }
        }
        return controlledResult(toV2SearchResult(query, intent, chunks, documents, outline), state, events, elapsedMs(startedAt));
    }

    public DocumentSearchExpandResult expand(DocumentSearchExpandRequest request) {
        String query = requireQuery(request == null ? null : request.query());
        String intent = intentClassifier.classifyName(query);
        String docId = request == null ? null : request.docId();
        if (!hasText(docId)) {
            throw new IllegalArgumentException("docId is required");
        }
        SearchPermissionContext permissionContext = SearchPermissionContext.of(
            request.tenantId(),
            request.userId(),
            request.roles()
        );
        SearchDocument document = searchService.get(docId.trim(), permissionContext)
            .orElseThrow(() -> new IllegalArgumentException("document not found: " + docId.trim()));
        int maxChunks = normalizeExpansionMax(request.maxChunks(), request.topK(), 6, 20);
        int maxSections = normalizeExpansionMax(request.maxSections(), null, 3, 10);
        int maxTotalChars = normalizeExpansionMax(request.maxTotalChars(), null, 6000, 20000);
        List<String> queryTokens = tokenizer.searchTokens(query);
        List<DocumentEvidenceChunk> chunks = expandedEvidence(
            document,
            query,
            queryTokens,
            safeList(request.sections()),
            maxSections,
            maxChunks,
            maxTotalChars,
            Boolean.TRUE.equals(request.debug())
        );
        DocumentSearchResult formatted = contextFormatter.toSearchResult(query, intent, chunks);
        EvidenceReasoningResult reasoning = reasoningEngine.reason(formatted, qualityScorer.score(formatted.results()));
        EvidenceDecisionResult decision = decisionEngine.decide(formatted, reasoning);
        return new DocumentSearchExpandResult(
            EvidenceContextFormatter.CONTRACT_VERSION,
            query,
            docId.trim(),
            formatted.results().stream().map(DocumentExpandedEvidenceChunk::from).toList(),
            formatted.context(),
            formatted.citations(),
            formatted.results().isEmpty() ? DocumentRetrievalSemantics.noHit() : DocumentRetrievalSemantics.evidenceBody(),
            new DocumentExpansionPolicy(false, maxSections, maxChunks, maxTotalChars, "DOCUMENT_EXPAND")
                .withQueryContext(query, intent),
            formatted.results().isEmpty()
                ? EvidenceGovernancePolicy.noEvidence()
                : EvidenceGovernancePolicy.needsExpansion(maxSections, maxChunks),
            reasoning,
            decision
        );
    }

    private DocumentSearchResult searchScopedDocuments(String query,
                                                       int topK,
                                                       List<String> fileIds,
                                                       DocumentSearchFilters filters,
                                                       boolean debug,
                                                       SearchPermissionContext permissionContext) {
        String intent = intentClassifier.classifyName(query);
        List<String> queryTokens = tokenizer.searchTokens(query);
        List<DocumentEvidenceChunk> chunks = new ArrayList<>();
        for (String fileId : fileIds) {
            searchService.get(fileId, permissionContext)
                .filter(document -> matchesDocumentFilters(document, filters))
                .ifPresent(document -> chunks.addAll(toScopedEvidence(document, query, queryTokens, intent, debug, topK)));
            if (chunks.size() >= topK) {
                break;
            }
        }
        List<DocumentEvidenceChunk> ranked = chunks.stream()
            .sorted(Comparator
                .comparing(DocumentEvidenceChunk::score, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(DocumentEvidenceChunk::fileName, Comparator.nullsLast(String::compareTo)))
            .limit(topK)
            .toList();
        return contextFormatter.toSearchResult(query, intent, ranked);
    }

    private DocumentSearchResult controlledResult(String query,
                                                  String intent,
                                                  List<DocumentEvidenceChunk> chunks,
                                                  RetrievalExecutionState state,
                                                  List<RetrievalEvent> events) {
        return controlledResult(contextFormatter.toSearchResult(query, intent, chunks), state, events, 0L);
    }

    private DocumentSearchResult controlledResult(String query,
                                                  String intent,
                                                  List<DocumentEvidenceChunk> chunks,
                                                  RetrievalExecutionState state,
                                                  List<RetrievalEvent> events,
                                                  long latencyMs) {
        return controlledResult(contextFormatter.toSearchResult(query, intent, chunks), state, events, latencyMs);
    }

    private DocumentSearchResult controlledResult(DocumentSearchResult result,
                                                  RetrievalExecutionState state,
                                                  List<RetrievalEvent> events,
                                                  long latencyMs) {
        DocumentSearchResult safeResult = result == null
            ? contextFormatter.toSearchResult("", "GENERAL", List.of())
            : result;
        RetrievalExecutionState currentState = state == null
            ? RetrievalExecutionState.started(UUID.randomUUID().toString())
            : state;
        boolean hasEvidence = !safeResult.results().isEmpty();
        boolean hasDocumentHits = !safeResult.documents().isEmpty();
        if (!hasEvidence && !hasDocumentHits) {
            currentState = currentState.withEmptyResult().withAction(RetrievalControlAction.STOP);
            if (currentState.budgetUsed().searchCalls() > 0) {
                events.add(event(
                    currentState.traceId(),
                    RetrievalControlStep.SEARCH,
                    RetrievalControlAction.STOP,
                    safeResult.query(),
                    0,
                    currentState.budgetUsed(),
                    currentState.budgetUsed(),
                    latencyMs,
                    "empty_result"
                ));
            }
        } else {
            currentState = currentState.withAction(RetrievalControlAction.SCORE);
            events.add(event(
                currentState.traceId(),
                RetrievalControlStep.SEARCH,
                RetrievalControlAction.SEARCH,
                safeResult.query(),
                hasEvidence ? safeResult.results().size() : safeResult.documents().size(),
                currentState.budgetUsed(),
                currentState.budgetUsed(),
                latencyMs,
                "search_completed"
            ));
        }
        RetrievalEvidenceQuality quality = qualityScorer.score(safeResult.results());
        currentState = currentState.withQuality(quality);
        events.add(event(
            currentState.traceId(),
            RetrievalControlStep.SCORER,
            RetrievalControlAction.SCORE,
            safeResult.query(),
            safeResult.results().size(),
            currentState.budgetUsed(),
            currentState.budgetUsed(),
            latencyMs,
            quality.reason()
        ));
        EvidenceReasoningResult reasoning = reasoningEngine.reason(safeResult, quality);
        EvidenceDecisionResult decision = decisionEngine.decide(safeResult, reasoning);
        return new DocumentSearchResult(
            safeResult.contractVersion(),
            safeResult.query(),
            safeResult.intent(),
            safeResult.total(),
            safeResult.results(),
            safeResult.context(),
            safeResult.citations(),
            currentState,
            quality,
            List.copyOf(events),
            safeResult.matchType(),
            safeResult.retrievalSemantics(),
            safeResult.documents(),
            safeResult.outline(),
            safeResult.outlineSource(),
            safeResult.expansionPolicy(),
            safeResult.evidenceGovernancePolicy(),
            reasoning,
            decision
        );
    }

    private DocumentSearchResult toV2SearchResult(String query,
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

    private boolean isTitleOnlyHit(SearchResult result) {
        if (result == null || result.scoreBreakdown() == null) {
            return false;
        }
        SearchScoreBreakdown breakdown = result.scoreBreakdown();
        Map<String, Integer> fieldScores = breakdown.fieldScores() == null ? Map.of() : breakdown.fieldScores();
        int titleScore = Math.max(breakdown.titleScore(), fieldScores.getOrDefault("title", 0));
        int fileNameScore = fieldScores.getOrDefault("fileName", 0);
        int contentScore = Math.max(breakdown.contentScore(), fieldScores.getOrDefault("content", 0));
        return (titleScore > 0 || fileNameScore > 0) && contentScore <= 0;
    }

    private void addTitleOnlyDocument(List<DocumentSearchHit> documents,
                                      List<DocumentOutlineItem> outline,
                                      SearchResult result,
                                      SearchPermissionContext permissionContext) {
        if (result == null || documents.stream().anyMatch(document -> same(document.docId(), result.docId()))) {
            return;
        }
        documents.add(toSearchHit(result));
        outline.addAll(outlineFor(result, permissionContext));
    }

    private DocumentSearchHit toSearchHit(SearchResult result) {
        return new DocumentSearchHit(
            result.docId(),
            result.title(),
            result.fileName(),
            result.documentType(),
            (double) result.score(),
            safeList(result.tags())
        );
    }

    private List<DocumentOutlineItem> outlineFor(SearchResult result, SearchPermissionContext permissionContext) {
        if (result == null || !hasText(result.docId())) {
            return List.of();
        }
        SearchDocument document = searchService.get(result.docId(), permissionContext).orElse(null);
        if (document != null && hasText(document.getContent())) {
            return outlineForDocument(document, 20);
        }
        String summary = truncate(firstNonBlank(result.summary(), result.title(), result.fileName()), 260);
        if (!hasText(summary)) {
            return List.of();
        }
        return List.of(new DocumentOutlineItem(
            result.docId(),
            firstNonBlank(result.title(), result.fileName(), "Document"),
            summary,
            List.of(),
            List.of(),
            DocumentOutlineSource.SYNTHETIC,
            sectionKeywords(firstNonBlank(result.title(), result.fileName(), summary)),
            sectionEmbeddingRef(result.docId(), firstNonBlank(result.title(), result.fileName(), "Document"))
        ));
    }

    private List<DocumentOutlineItem> outlineForDocument(SearchDocument document, int maxItems) {
        List<TextChunker.TextChunk> chunks = chunker.splitChunks(
            document.getContent(),
            properties.getChunkSize(),
            properties.getChunkOverlap()
        );
        if (chunks.isEmpty()) {
            return List.of();
        }
        boolean hasStructuredSections = chunks.stream().anyMatch(chunk -> hasText(chunk.section()));
        DocumentOutlineSource source = hasStructuredSections ? DocumentOutlineSource.DOC_STRUCTURE : DocumentOutlineSource.CLUSTERED;
        List<DocumentOutlineItem> items = new ArrayList<>();
        String currentSection = null;
        String currentSummary = "";
        List<String> chunkIds = new ArrayList<>();
        List<Integer> chunkIndexes = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            TextChunker.TextChunk chunk = chunks.get(i);
            String section = sectionLabel(chunk, i);
            if (currentSection != null && !currentSection.equals(section)) {
                items.add(outlineItem(document.getDocId(), currentSection, currentSummary, chunkIds, chunkIndexes, source));
                chunkIds = new ArrayList<>();
                chunkIndexes = new ArrayList<>();
                currentSummary = "";
                if (items.size() >= Math.max(1, maxItems)) {
                    break;
                }
            }
            currentSection = section;
            if (!hasText(currentSummary)) {
                currentSummary = outlineSummary(chunk.content(), section);
            }
            chunkIds.add(chunkId(document.getDocId(), i));
            chunkIndexes.add(i);
        }
        if (currentSection != null && items.size() < Math.max(1, maxItems)) {
            items.add(outlineItem(document.getDocId(), currentSection, currentSummary, chunkIds, chunkIndexes, source));
        }
        return items;
    }

    private DocumentOutlineItem outlineItem(String docId,
                                            String section,
                                            String summary,
                                            List<String> chunkIds,
                                            List<Integer> chunkIndexes,
                                            DocumentOutlineSource source) {
        return new DocumentOutlineItem(
            docId,
            section,
            summary,
            List.copyOf(chunkIds),
            List.copyOf(chunkIndexes),
            source,
            sectionKeywords(section + " " + summary),
            sectionEmbeddingRef(docId, section)
        );
    }

    private List<DocumentEvidenceChunk> expandedEvidence(SearchDocument document,
                                                         String query,
                                                         List<String> queryTokens,
                                                         List<String> requestedSections,
                                                         int maxSections,
                                                         int maxChunks,
                                                         int maxTotalChars,
                                                         boolean debug) {
        List<TextChunker.TextChunk> chunks = chunker.splitChunks(
            document.getContent(),
            properties.getChunkSize(),
            properties.getChunkOverlap()
        );
        if (chunks.isEmpty()) {
            return List.of();
        }
        List<String> normalizedRequestedSections = requestedSections.stream()
            .filter(this::hasText)
            .map(this::normalizeComparable)
            .distinct()
            .toList();
        List<ScoredTextChunk> scored = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            TextChunker.TextChunk chunk = chunks.get(i);
            String section = sectionLabel(chunk, i);
            if (!normalizedRequestedSections.isEmpty() && !sectionRequested(section, normalizedRequestedSections)) {
                continue;
            }
            double score = expansionScore(chunk, section, queryTokens, normalizedRequestedSections, i);
            scored.add(new ScoredTextChunk(i, section, chunk.content(), score));
        }
        if (scored.isEmpty()) {
            for (int i = 0; i < Math.min(chunks.size(), maxChunks); i++) {
                TextChunker.TextChunk chunk = chunks.get(i);
                scored.add(new ScoredTextChunk(i, sectionLabel(chunk, i), chunk.content(), Math.max(35, 70 - i)));
            }
        }
        Set<String> allowedSections = scored.stream()
            .sorted(Comparator.comparingDouble(ScoredTextChunk::score).reversed().thenComparingInt(ScoredTextChunk::index))
            .map(chunk -> normalizeComparable(chunk.section()))
            .filter(this::hasText)
            .distinct()
            .limit(Math.max(1, maxSections))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        int usedChars = 0;
        List<DocumentEvidenceChunk> evidence = new ArrayList<>();
        for (ScoredTextChunk chunk : scored.stream()
            .filter(chunk -> allowedSections.isEmpty() || allowedSections.contains(normalizeComparable(chunk.section())))
            .sorted(Comparator.comparingDouble(ScoredTextChunk::score).reversed().thenComparingInt(ScoredTextChunk::index))
            .toList()) {
            if (evidence.size() >= maxChunks || usedChars >= maxTotalChars) {
                break;
            }
            int remaining = maxTotalChars - usedChars;
            String content = truncate(chunk.content(), remaining);
            if (!hasText(content)) {
                continue;
            }
            evidence.add(toExpandedEvidence(document, query, queryTokens, chunk, content, debug));
            usedChars += content.length();
        }
        return evidence;
    }

    private DocumentEvidenceChunk toExpandedEvidence(SearchDocument document,
                                                     String query,
                                                     List<String> queryTokens,
                                                     ScoredTextChunk chunk,
                                                     String content,
                                                     boolean debug) {
        String fileId = firstNonBlank(document.getDocId(), "unknown");
        String fileName = firstNonBlank(document.getFileName(), document.getTitle(), fileId);
        return new DocumentEvidenceChunk(
            refId(fileId, chunk.index()),
            chunkId(fileId, chunk.index()),
            fileId,
            fileName,
            chunk.section(),
            chunk.index(),
            "expanded_evidence",
            Math.round(Math.min(100.0D, Math.max(0.0D, chunk.score())) * 10.0D) / 10.0D,
            content,
            highlights(query, queryTokens, content),
            citation(fileName, chunk.section(), chunk.index()),
            debug ? new SearchTrace(
                highlights(query, queryTokens, content),
                intentClassifier.classifyName(query),
                List.of(),
                "document-scoped expansion evidence"
            ) : null,
            document.getTenantId(),
            document.getUserId(),
            document.getVisibility(),
            safeList(document.getPermissionRoles())
        );
    }


    private List<DocumentEvidenceChunk> toScopedEvidence(SearchDocument document,
                                                         String query,
                                                         List<String> queryTokens,
                                                         String intent,
                                                         boolean debug,
                                                         int topK) {
        String content = firstNonBlank(document.getContent(), "");
        if (!hasText(content)) {
            return List.of();
        }
        List<ScopedExcerpt> excerpts = scopedExcerpts(content, queryTokens, Math.min(Math.max(1, topK), 3));
        List<DocumentEvidenceChunk> chunks = new ArrayList<>();
        for (int i = 0; i < excerpts.size(); i++) {
            ScopedExcerpt excerpt = excerpts.get(i);
            String fileId = firstNonBlank(document.getDocId(), "unknown");
            String fileName = firstNonBlank(document.getFileName(), document.getTitle(), fileId);
            Integer chunkIndex = excerpt.index();
            chunks.add(new DocumentEvidenceChunk(
                refId(fileId, chunkIndex),
                fileId + "_" + chunkIndex,
                fileId,
                fileName,
                "",
                chunkIndex,
                "document",
                excerpt.score(),
                excerpt.text(),
                highlights(query, List.of(), excerpt.text()),
                citation(fileName, "", chunkIndex),
                debug ? new SearchTrace(
                    highlights(query, List.of(), excerpt.text()),
                    intent,
                    List.of(),
                    "scoped document evidence matched"
                ) : null,
                document.getTenantId(),
                document.getUserId(),
                document.getVisibility(),
                safeList(document.getPermissionRoles())
            ));
        }
        return chunks;
    }

    private List<ScopedExcerpt> scopedExcerpts(String content, List<String> queryTokens, int maxChunks) {
        String normalizedContent = content.toLowerCase(Locale.ROOT);
        List<String> tokens = safeList(queryTokens).stream()
            .filter(this::hasText)
            .map(token -> token.toLowerCase(Locale.ROOT))
            .distinct()
            .toList();
        int window = 1200;
        int overlap = 180;
        List<ScopedExcerpt> excerpts = new ArrayList<>();
        int index = 0;
        for (int start = 0; start < content.length(); start += Math.max(1, window - overlap)) {
            int end = Math.min(content.length(), start + window);
            String text = content.substring(start, end).trim();
            double score = scopedScore(normalizedContent.substring(start, end), tokens, index);
            if (score > 0 || excerpts.isEmpty()) {
                excerpts.add(new ScopedExcerpt(index, text, score));
            }
            index++;
            if (end == content.length()) {
                break;
            }
        }
        return excerpts.stream()
            .sorted(Comparator
                .comparingDouble(ScopedExcerpt::score)
                .reversed()
                .thenComparingInt(ScopedExcerpt::index))
            .limit(maxChunks)
            .toList();
    }

    private double scopedScore(String normalizedText, List<String> tokens, int index) {
        double score = Math.max(0, 10 - index);
        for (String token : tokens) {
            if (hasText(token) && normalizedText.contains(token)) {
                score += token.length() > 2 ? 18 : 8;
            }
        }
        return Math.round(Math.min(100.0D, score) * 10.0D) / 10.0D;
    }

    private DocumentEvidenceChunk toEvidence(SearchResult result,
                                             SearchMatchedChunk chunk,
                                             String query,
                                             String intent,
                                             boolean debug) {
        String fileName = firstNonBlank(chunk.fileName(), result.fileName(), result.title());
        String section = nullToEmpty(chunk.section());
        String content = firstNonBlank(chunk.content(), chunk.text(), result.summary());
        Integer chunkIndex = chunk.chunkIndex();
        String fileId = firstNonBlank(chunk.fileId(), result.docId());
        String refId = refId(fileId, chunkIndex);
        return new DocumentEvidenceChunk(
            refId,
            firstNonBlank(chunk.chunkId(), result.docId() + "_" + chunkIndex),
            fileId,
            fileName,
            section,
            chunkIndex,
            nullToEmpty(chunk.chunkType()),
            normalizeScore(chunk.score(), result.score()),
            content,
            highlights(query, result.matchedKeywords(), content),
            citation(fileName, section, chunkIndex),
            debug ? trace(result, chunk, query, intent) : null,
            firstNonBlank(chunk.tenantId(), result.tenantId()),
            firstNonBlank(chunk.userId(), result.userId()),
            firstNonBlank(chunk.visibility(), result.visibility()),
            safeList(chunk.permissionRoles()).isEmpty() ? safeList(result.permissionRoles()) : safeList(chunk.permissionRoles())
        );
    }

    private void addSummaryEvidence(List<DocumentEvidenceChunk> chunks,
                                    SearchResult result,
                                    String query,
                                    String intent,
                                    boolean debug,
                                    DocumentSearchFilters filters) {
        if (filters != null && hasText(filters.chunkType())) {
            return;
        }
        String fileName = firstNonBlank(result.fileName(), result.title());
        String content = firstNonBlank(result.summary(), "");
        if (!hasText(content)) {
            return;
        }
        String refId = refId(result.docId(), null);
        chunks.add(new DocumentEvidenceChunk(
            refId,
            result.docId() + "_summary",
            result.docId(),
            fileName,
            "",
            null,
            "",
            normalizeScore(result.score(), result.score()),
            content,
            highlights(query, result.matchedKeywords(), content),
            citation(fileName, "", null),
            debug ? new SearchTrace(
                safeList(result.matchedKeywords()),
                intent,
                List.of(),
                "document summary matched"
            ) : null,
            result.tenantId(),
            result.userId(),
            result.visibility(),
            safeList(result.permissionRoles())
        ));
    }

    private SearchTrace trace(SearchResult result, SearchMatchedChunk chunk, String query, String intent) {
        List<String> matched = highlights(query, result.matchedKeywords(), firstNonBlank(chunk.content(), chunk.text()));
        List<String> reasons = new ArrayList<>();
        if (containsAny(chunk.section(), matched)) {
            reasons.add("section matched");
        }
        if (containsAny(chunk.chunkType(), matched)) {
            reasons.add("chunkType matched");
        }
        if (containsAny(firstNonBlank(chunk.content(), chunk.text()), matched)) {
            reasons.add("content matched");
        }
        if (chunk.score() > 0) {
            reasons.add("chunk score retained");
        }
        return new SearchTrace(
            matched,
            intent,
            List.of(),
            reasons.isEmpty() ? "retrieval evidence matched" : String.join(" + ", reasons)
        );
    }

    private Citation citation(String fileName, String section, Integer chunkIndex) {
        List<String> locators = new ArrayList<>();
        if (hasText(section)) {
            locators.add("section: " + section);
        }
        if (chunkIndex != null) {
            locators.add("chunk: " + chunkIndex);
        }
        return new Citation(firstNonBlank(fileName, "document"), String.join("; ", locators));
    }

    private String refId(String fileId, Integer chunkIndex) {
        return "doc://" + firstNonBlank(fileId, "unknown") + "#chunk=" + (chunkIndex == null ? "summary" : chunkIndex);
    }

    private List<String> highlights(String query, List<String> matchedKeywords, String content) {
        Set<String> values = new LinkedHashSet<>();
        for (String keyword : safeList(matchedKeywords)) {
            if (hasText(keyword) && appears(keyword, content)) {
                values.add(keyword.trim());
            }
        }
        for (String token : tokenizer.searchTokens(query)) {
            if (hasText(token) && appears(token, content)) {
                values.add(token.trim());
            }
        }
        return values.stream().limit(8).toList();
    }

    private boolean matchesFileType(SearchResult result, String fileType) {
        if (!hasText(fileType)) {
            return true;
        }
        String expected = fileType.trim().toLowerCase(Locale.ROOT);
        String documentType = nullToEmpty(result.documentType()).toLowerCase(Locale.ROOT);
        String fileName = nullToEmpty(result.fileName()).toLowerCase(Locale.ROOT);
        return expected.equals(documentType) || fileName.endsWith("." + expected);
    }

    private boolean matchesDocumentFilters(SearchDocument document, DocumentSearchFilters filters) {
        if (filters == null) {
            return true;
        }
        if (hasText(filters.fileType())) {
            String expected = filters.fileType().trim().toLowerCase(Locale.ROOT);
            String documentType = nullToEmpty(document.getDocumentType()).toLowerCase(Locale.ROOT);
            String fileName = nullToEmpty(document.getFileName()).toLowerCase(Locale.ROOT);
            if (!expected.equals(documentType) && !fileName.endsWith("." + expected)) {
                return false;
            }
        }
        if (hasText(filters.tag()) && !containsIgnoreCase(document.getTags(), filters.tag())) {
            return false;
        }
        if (hasText(filters.company()) && !containsIgnoreCase(document.getCompanies(), filters.company())) {
            return false;
        }
        return !hasText(filters.industry()) || containsIgnoreCase(document.getIndustries(), filters.industry());
    }

    private boolean containsIgnoreCase(List<String> values, String expected) {
        String needle = expected == null ? "" : expected.trim().toLowerCase(Locale.ROOT);
        return safeList(values).stream()
            .filter(this::hasText)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(value -> value.contains(needle) || needle.contains(value));
    }

    private boolean matchesChunkType(SearchMatchedChunk chunk, String chunkType) {
        if (!hasText(chunkType)) {
            return true;
        }
        return chunkType.trim().equalsIgnoreCase(nullToEmpty(chunk.chunkType()).trim());
    }

    private Double normalizeScore(float chunkScore, int resultScore) {
        double raw = chunkScore > 0 ? chunkScore * 10.0D : resultScore;
        return Math.round(Math.max(0.0D, Math.min(100.0D, raw)) * 10.0D) / 10.0D;
    }

    private RetrievalBudgetReservation budgetReservation() {
        SearchProperties.RetrievalControl control = properties.getRetrievalControl();
        SearchProperties.QueryBudget queryBudget = properties.getQueryBudget();
        int searchCalls = control == null ? 1 : Math.max(0, control.getMaxSearchCalls());
        int candidateDocs = queryBudget == null ? 0 : Math.max(0, queryBudget.getMaxDocScan());
        int rocksdbIter = queryBudget == null ? 0 : Math.max(0, queryBudget.getMaxRocksdbIter());
        long latencyMs = control == null ? 0L : Math.max(0L, control.getLatencyMs());
        return new RetrievalBudgetReservation(
            Math.min(1, searchCalls),
            candidateDocs,
            rocksdbIter,
            latencyMs
        );
    }

    private boolean canReserveSearch(RetrievalExecutionState state) {
        SearchProperties.RetrievalControl control = properties.getRetrievalControl();
        if (control == null || !control.isEnabled()) {
            return true;
        }
        int maxSearchCalls = Math.max(0, control.getMaxSearchCalls());
        int usedSearchCalls = state == null || state.budgetUsed() == null ? 0 : state.budgetUsed().searchCalls();
        return usedSearchCalls < maxSearchCalls;
    }

    private RetrievalEvent event(String traceId,
                                 RetrievalControlStep step,
                                 RetrievalControlAction action,
                                 String query,
                                 int resultSize,
                                 RetrievalBudgetUsage before,
                                 RetrievalBudgetUsage after,
                                 long latencyMs,
                                 String reason) {
        return new RetrievalEvent(
            traceId,
            step,
            action,
            query,
            Math.max(0, resultSize),
            before == null ? RetrievalBudgetUsage.zero() : before,
            after == null ? RetrievalBudgetUsage.zero() : after,
            Math.max(0L, latencyMs),
            reason
        );
    }

    private long elapsedMs(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private int normalizeTopK(Integer value) {
        if (value == null) {
            return DEFAULT_TOP_K;
        }
        return Math.max(1, Math.min(MAX_TOP_K, value));
    }

    private int normalizeExpansionMax(Integer primary, Integer fallback, int defaultValue, int maxValue) {
        Integer value = primary == null ? fallback : primary;
        if (value == null) {
            return defaultValue;
        }
        return Math.max(1, Math.min(maxValue, value));
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

    private String sectionLabel(TextChunker.TextChunk chunk, int index) {
        String section = chunk == null ? "" : chunk.section();
        return hasText(section) ? section.trim() : "Section " + (index + 1);
    }

    private String outlineSummary(String content, String section) {
        String summary = nullToEmpty(content).replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (hasText(section) && summary.startsWith(section)) {
            summary = summary.substring(section.length()).trim();
        }
        return truncate(summary, 260);
    }

    private boolean sectionRequested(String section, List<String> normalizedRequestedSections) {
        String normalizedSection = normalizeComparable(section);
        return normalizedRequestedSections.stream()
            .anyMatch(requested -> normalizedSection.contains(requested) || requested.contains(normalizedSection));
    }

    private double expansionScore(TextChunker.TextChunk chunk,
                                  String section,
                                  List<String> queryTokens,
                                  List<String> requestedSections,
                                  int index) {
        String content = chunk == null ? "" : chunk.content();
        String normalizedContent = normalizeComparable(content);
        String normalizedSection = normalizeComparable(section);
        double score = Math.max(10, 50 - index);
        if (!requestedSections.isEmpty() && sectionRequested(section, requestedSections)) {
            score += 35;
        }
        for (String token : safeList(queryTokens)) {
            String normalizedToken = normalizeComparable(token);
            if (!hasText(normalizedToken)) {
                continue;
            }
            if (normalizedSection.contains(normalizedToken)) {
                score += normalizedToken.length() > 2 ? 24 : 12;
            }
            if (normalizedContent.contains(normalizedToken)) {
                score += normalizedToken.length() > 2 ? 18 : 8;
            }
        }
        return score;
    }

    private String chunkId(String fileId, int chunkIndex) {
        return firstNonBlank(fileId, "unknown") + "_" + chunkIndex;
    }

    private List<String> sectionKeywords(String text) {
        return tokenizer.searchTokens(text).stream()
            .filter(this::hasText)
            .map(String::trim)
            .distinct()
            .limit(12)
            .toList();
    }

    private String sectionEmbeddingRef(String docId, String section) {
        String normalizedDocId = firstNonBlank(docId, "unknown");
        String normalizedSection = normalizeComparable(section);
        if (!hasText(normalizedSection)) {
            normalizedSection = "section";
        }
        return "section://" + normalizedDocId + "#" + normalizedSection;
    }

    private boolean same(String left, String right) {
        return nullToEmpty(left).equals(nullToEmpty(right));
    }

    private String normalizeComparable(String value) {
        return nullToEmpty(value)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\s\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF]+", "");
    }

    private String truncate(String value, int maxChars) {
        if (!hasText(value)) {
            return "";
        }
        int safeMax = Math.max(0, maxChars);
        if (value.length() <= safeMax) {
            return value;
        }
        return value.substring(0, safeMax).trim();
    }

    private String requireQuery(String query) {
        if (!hasText(query)) {
            throw new IllegalArgumentException("query is required");
        }
        return query.trim();
    }

    private String joinValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream()
            .filter(this::hasText)
            .map(String::trim)
            .reduce((left, right) -> left + "," + right)
            .orElse(null);
    }

    private boolean containsAny(String text, List<String> values) {
        if (!hasText(text) || values == null || values.isEmpty()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return values.stream()
            .filter(this::hasText)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(normalized::contains);
    }

    private boolean appears(String keyword, String content) {
        if (!hasText(keyword)) {
            return false;
        }
        if (!hasText(content)) {
            return true;
        }
        return content.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private record ScopedExcerpt(int index, String text, Double score) {
    }

    private record ScoredTextChunk(int index, String section, String content, double score) {
    }
}
