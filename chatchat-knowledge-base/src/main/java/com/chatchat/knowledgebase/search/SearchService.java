package com.chatchat.knowledgebase.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private static final String ALL_CATEGORY = "all";
    private static final String UNCATEGORIZED = "uncategorized";
    private static final int TITLE_PHRASE_SCORE = 45;
    private static final int FILENAME_PHRASE_SCORE = 40;
    private static final int TITLE_MEMORY_PHRASE_RECALL_SCORE = 90;
    private static final int FILENAME_MEMORY_PHRASE_RECALL_SCORE = 80;
    private static final int TITLE_MEMORY_TERM_RECALL_SCORE = 18;
    private static final int FILENAME_MEMORY_TERM_RECALL_SCORE = 16;
    private static final int TITLE_MEMORY_RECALL_SCORE_CAP = 60;
    private static final int TITLE_MEMORY_RECALL_WEAK_LEXICAL_CAP = 36;
    private static final int TITLE_MEMORY_RECALL_STRONG_LEXICAL_CAP = 16;
    private static final int TITLE_MEMORY_WEAK_LEXICAL_THRESHOLD = 12;
    private static final int TITLE_MEMORY_STRONG_LEXICAL_THRESHOLD = 40;
    private static final int KEYWORD_PHRASE_SCORE = 36;
    private static final int CONTENT_PHRASE_SCORE = 20;
    private static final int TITLE_TERM_SCORE = 12;
    private static final int FILENAME_TERM_SCORE = 11;
    private static final int KEYWORD_TERM_SCORE = 10;
    private static final int TAG_TERM_SCORE = 14;
    private static final int COMPANY_TERM_SCORE = 10;
    private static final int INDUSTRY_TERM_SCORE = 10;
    private static final int CONTENT_TERM_SCORE = 4;
    private static final int SOURCE_TERM_SCORE = 2;
    private static final int COVERAGE_SCORE = 20;
    private static final int FRONTEND_CONTENT_SCAN_CHARS = 120_000;
    private static final int FRONTEND_SNIPPET_RADIUS = 180;
    private static final double FRONTEND_BM25_K1 = 1.2D;
    private static final double FRONTEND_BM25_B = 0.75D;
    private static final double FRONTEND_TITLE_WEIGHT = 4.0D;
    private static final double FRONTEND_KEYWORD_WEIGHT = 0.5D;
    private static final double FRONTEND_TAG_WEIGHT = 3.0D;
    private static final double FRONTEND_COMPANY_WEIGHT = 2.5D;
    private static final double FRONTEND_INDUSTRY_WEIGHT = 2.0D;
    private static final double FRONTEND_FILENAME_WEIGHT = 1.5D;
    private static final double FRONTEND_CONTENT_WEIGHT = 1.0D;
    private static final double FRONTEND_SOURCE_WEIGHT = 0.5D;
    private static final int REINDEX_PROGRESS_INTERVAL = 20;

    private final RocksDbSearchStore store;
    private final DocumentSearchIndex luceneStore;
    private final SearchTokenizer tokenizer;
    private final DocumentTextExtractor textExtractor;
    private final KeywordExtractor keywordExtractor;
    private final QueryExpander queryExpander;
    private final SearchProperties properties;

    /**
     * Performs the rebuild lucene index operation.
     */
    @PostConstruct
    public void rebuildLuceneIndex() {
        if (!properties.isRebuildOnStartup()) {
            log.info("search_rebuild_startup_skipped reason=disabled");
            return;
        }
        if (!luceneStore.isAvailable()) {
            log.info("search_rebuild_lucene_skipped reason=lucene_unavailable");
            return;
        }
        long startedAt = System.nanoTime();
        try {
            List<SearchDocument> documents = loadLatestDocuments();
            log.info("search_rebuild_lucene_start documents={}", documents.size());
            luceneStore.rebuildLatest(documents);
            log.info("search_rebuild_lucene_complete documents={} durationMs={}", documents.size(), elapsedMs(startedAt));
        } catch (Exception ex) {
            log.warn("search_rebuild_lucene_failed durationMs={} error={}", elapsedMs(startedAt), ex.getMessage(), ex);
        }
    }

    /**
     * Creates the or update.
     *
     * @param request the request value
     * @return the created or update
     */
    public SearchDocument createOrUpdate(SearchDocument request) {
        if (request == null) {
            throw new IllegalArgumentException("document payload is required");
        }
        long startedAt = System.nanoTime();
        SearchDocument document = normalizeDocument(request);
        log.info(
            "search_document_save_start docId={} title={} source={} tenantId={} userId={} contentChars={}",
            document.getDocId(),
            safeLogValue(document.getTitle(), 80),
            safeLogValue(document.getSource(), 60),
            document.getTenantId(),
            document.getUserId(),
            lengthOf(document.getContent())
        );
        SearchIndexData oldIndexData = store.get(document.getDocId())
            .map(this::buildIndexData)
            .orElse(null);
        SearchIndexData indexData = buildIndexData(document);
        store.put(document, indexData, oldIndexData);
        syncLuceneIndex(document);
        log.info(
            "search_document_save_complete docId={} title={} keywords={} tags={} durationMs={}",
            document.getDocId(),
            safeLogValue(document.getTitle(), 80),
            cleanList(document.getKeywords()).size(),
            cleanList(document.getTags()).size(),
            elapsedMs(startedAt)
        );
        return document;
    }

    /**
     * Performs the upload operation.
     *
     * @param file the file value
     * @param title the title value
     * @param source the source value
     * @param date the date value
     * @param tags the tags value
     * @param companies the companies value
     * @param industries the industries value
     * @param keywords the keywords value
     * @param documentType the document type value
     * @param fallbackContent the fallback content value
     * @return the operation result
     */
    public SearchDocument upload(MultipartFile file,
                                 String title,
                                 String source,
                                 String date,
                                 String tags,
                                 String companies,
                                 String industries,
                                 String keywords,
                                 String documentType,
                                 String fallbackContent) {
        return upload(file, title, source, date, tags, companies, industries, keywords, documentType, fallbackContent,
            SearchPermissionContext.system(), null, null);
    }

    public SearchDocument upload(MultipartFile file,
                                 String title,
                                 String source,
                                 String date,
                                 String tags,
                                 String companies,
                                 String industries,
                                 String keywords,
                                 String documentType,
                                 String fallbackContent,
                                 SearchPermissionContext permissionContext,
                                 String visibility,
                                 List<String> permissionRoles) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }
        if (file.getSize() > properties.getMaxUploadBytes()) {
            throw new IllegalArgumentException("file size exceeds 5MB limit");
        }
        String originalFileName = safeFileName(file.getOriginalFilename());
        if (!textExtractor.supports(originalFileName)) {
            throw new IllegalArgumentException("unsupported file type: " + originalFileName);
        }
        long startedAt = System.nanoTime();
        SearchPermissionContext context = permissionContext == null ? SearchPermissionContext.system() : permissionContext;
        log.info(
            "search_document_upload_start fileName={} fileSize={} title={} tenantId={} userId={} visibility={} roles={}",
            safeLogValue(originalFileName, 100),
            file.getSize(),
            safeLogValue(title, 80),
            context.tenantId(),
            context.userId(),
            safeLogValue(visibility, 40),
            permissionRoles == null ? context.roles().size() : permissionRoles.size()
        );

        String resolvedTitle = isBlank(title) ? stripExtension(originalFileName) : title.trim();
        normalizeExistingVersionFamily(resolvedTitle);
        Optional<SearchDocument> latestDocument = findLatestByTitle(resolvedTitle);
        String docId = generateDocId();
        String versionGroupId = latestDocument.map(this::versionGroupIdOf).orElse(docId);
        int version = latestDocument
            .map(document -> nextVersion(versionGroupId, resolvedTitle))
            .orElse(1);
        Path savedFile = saveOriginalFile(file, docId, originalFileName);
        String extractedContent = textExtractor.extractText(savedFile, originalFileName);
        String content = !isBlank(extractedContent) ? extractedContent : nullToEmpty(fallbackContent);
        if (isBlank(content)) {
            throw new IllegalArgumentException("document content is empty after extraction");
        }

        SearchDocument document = SearchDocument.builder()
            .docId(docId)
            .title(resolvedTitle)
            .content(content)
            .source(isBlank(source) ? "local_upload" : source.trim())
            .date(isBlank(date) ? LocalDate.now().toString() : date.trim())
            .tags(parseList(tags))
            .companies(parseList(companies))
            .industries(parseList(industries))
            .keywords(keywordExtractor.mergeKeywords(parseList(keywords), content))
            .fileName(originalFileName)
            .filePath(savedFile.toString())
            .documentType(resolveDocumentType(documentType, originalFileName))
            .fileSize(file.getSize())
            .uploadedAt(Instant.now().toEpochMilli())
            .updatedAt(Instant.now().toEpochMilli())
            .versionGroupId(versionGroupId)
            .version(version)
            .latestVersion(true)
            .tenantId(normalizeTenant(context.tenantId()))
            .userId(normalizeUser(context.userId()))
            .visibility(normalizeVisibility(visibility))
            .permissionRoles(cleanList(permissionRoles == null ? context.roles() : permissionRoles))
            .lifecycleStatus(DocumentLifecycleStatus.INDEXED)
            .build();

        SearchDocument saved = createOrUpdate(document);
        markPreviousVersionsNotLatest(saved);
        log.info(
            "search_document_upload_complete docId={} title={} fileName={} version={} contentChars={} keywords={} tags={} durationMs={}",
            saved.getDocId(),
            safeLogValue(saved.getTitle(), 80),
            safeLogValue(saved.getFileName(), 100),
            versionOf(saved),
            lengthOf(saved.getContent()),
            cleanList(saved.getKeywords()).size(),
            cleanList(saved.getTags()).size(),
            elapsedMs(startedAt)
        );
        return saved;
    }

    /**
     * Searches the search.
     *
     * @param keyword the keyword value
     * @param tag the tag value
     * @param company the company value
     * @param industry the industry value
     * @param limit the limit value
     * @return the operation result
     */
    public SearchPage search(String keyword, String tag, String company, String industry, Integer limit) {
        return search(keyword, tag, company, industry, null, null, limit);
    }

    /**
     * Searches the search.
     *
     * @param keyword the keyword value
     * @param tag the tag value
     * @param company the company value
     * @param industry the industry value
     * @param docIds the doc ids value
     * @param limit the limit value
     * @return the operation result
     */
    public SearchPage search(String keyword, String tag, String company, String industry, String docIds, Integer limit) {
        return search(keyword, tag, company, industry, docIds, null, limit);
    }

    /**
     * Searches the search.
     *
     * @param keyword the keyword value
     * @param tag the tag value
     * @param company the company value
     * @param industry the industry value
     * @param docIds the doc ids value
     * @param page the page value
     * @param limit the limit value
     * @return the operation result
     */
    public SearchPage search(String keyword,
                             String tag,
                             String company,
                             String industry,
                             String docIds,
                             Integer page,
                             Integer limit) {
        return search(keyword, tag, company, industry, docIds, page, limit, SearchPermissionContext.system());
    }

    public SearchPage search(String keyword,
                             String tag,
                             String company,
                             String industry,
                             String docIds,
                             Integer page,
                             Integer limit,
                             SearchPermissionContext permissionContext) {
        SearchPermissionContext context = permissionContext == null ? SearchPermissionContext.system() : permissionContext;
        long startedAt = System.nanoTime();
        int pageSize = normalizeLimit(limit);
        int pageNumber = normalizePage(page);
        String focusedKeyword = queryExpander.focusQuery(keyword);
        String normalizedKeyword = queryExpander.normalizeQuery(keyword);
        log.info(
            "search_document_search_start query='{}' tag={} company={} industry={} docIds={} page={} pageSize={} tenantId={} userId={}",
            safeLogQuery(normalizedKeyword),
            safeLogValue(tag, 60),
            safeLogValue(company, 60),
            safeLogValue(industry, 60),
            parseList(docIds).size(),
            pageNumber,
            pageSize,
            context.tenantId(),
            context.userId()
        );
        List<String> queryTokens = tokenizer.searchTokens(normalizedKeyword);
        List<String> focusedQueryTokens = tokenizer.searchTokens(focusedKeyword);
        QueryIntent queryIntent = queryExpander.classifyIntent(normalizedKeyword);
        String queryIntentName = queryExpander.classifyIntentName(normalizedKeyword);
        List<String> expandedQueryTokens = queryExpander.expandTokens(queryTokens, queryIntentName, normalizedKeyword);
        List<String> scopedDocumentIds = parseList(docIds);
        if (hasKeyword(keyword)
            && expandedQueryTokens.isEmpty()
            && noFilters(tag, company, industry)
            && scopedDocumentIds.isEmpty()) {
            log.info(
                "search_document_search_complete query='{}' resultTotal=0 pageResults=0 page={} pageSize={} reason=no_match durationMs={}",
                safeLogQuery(normalizedKeyword),
                pageNumber,
                pageSize,
                elapsedMs(startedAt)
            );
            return emptySearchPage(keyword, pageSize, pageNumber, startedAt, "no_match", context);
        }
        List<String> significantTerms = significantQueryTerms(focusedKeyword, focusedQueryTokens);
        QueryRecallMode recallMode = routeQueryRecall(focusedKeyword, significantTerms);
        Map<String, Integer> titleMemoryScores = titleMemoryCandidateScores(focusedKeyword, significantTerms, context);
        log.info(
            "search_route query='{}' focusedQuery='{}' mode={} queryTokens={} expandedTokens={} significantTerms={} memoryCandidates={}",
            safeLogQuery(normalizedKeyword),
            safeLogQuery(focusedKeyword),
            recallMode,
            queryTokens,
            expandedQueryTokens,
            significantTerms,
            titleMemoryScores.size()
        );
        LuceneSearchOutcome luceneOutcome = searchWithLucene(
            focusedKeyword,
            tag,
            company,
            industry,
            docIds,
            pageNumber,
            pageSize,
            queryTokens,
            expandedQueryTokens,
            significantTerms,
            titleMemoryScores,
            context
        );
        if (luceneOutcome.page() != null) {
            SearchPage pageResult = luceneOutcome.page();
            log.info(
                "search_document_search_complete query='{}' resultTotal={} pageResults={} page={} pageSize={} source=primary_index durationMs={}",
                safeLogQuery(normalizedKeyword),
                pageResult.total(),
                pageResult.results() == null ? 0 : pageResult.results().size(),
                pageNumber,
                pageSize,
                elapsedMs(startedAt)
            );
            return luceneOutcome.page();
        }
        Map<String, Integer> scores = new HashMap<>();
        Map<String, Integer> titleMemoryRawScores = new HashMap<>();
        Map<String, Integer> titleMemoryCalibratedScores = new HashMap<>();
        Map<String, Set<String>> matchedKeywords = new HashMap<>();
        Set<String> candidates = null;
        int candidateLimit = fallbackCandidateLimit(luceneOutcome.fallbackMode());

        if (!expandedQueryTokens.isEmpty() && candidateLimit > 0) {
            candidates = new LinkedHashSet<>();
            for (String token : expandedQueryTokens) {
                int remaining = candidateLimit - candidates.size();
                if (remaining <= 0) {
                    break;
                }
                for (String docId : store.findByKeyword(token, remaining)) {
                    candidates.add(docId);
                    scores.merge(docId, scoreForToken(token), Integer::sum);
                    matchedKeywords.computeIfAbsent(docId, ignored -> new LinkedHashSet<>()).add(token);
                }
            }
        }
        int keywordCandidateCount = candidates == null ? 0 : candidates.size();

        candidates = injectTitleMemoryCandidates(
            candidates,
            scores,
            matchedKeywords,
            titleMemoryScores,
            titleMemoryRawScores,
            titleMemoryCalibratedScores,
            focusedKeyword
        );

        candidates = applyFilter(candidates, tokenizer.splitFilter(tag), term -> store.findByTag(term, candidateLimit));
        candidates = applyFilter(candidates, tokenizer.splitFilter(company), term -> store.findByCompany(term, candidateLimit));
        candidates = applyFilter(candidates, tokenizer.splitFilter(industry), term -> store.findByIndustry(term, candidateLimit));
        candidates = applyDocumentScope(candidates, scopedDocumentIds);

        if (candidates == null) {
            candidates = fallbackCandidateIds(luceneOutcome.fallbackMode());
        }
        if (candidates.isEmpty() && !expandedQueryTokens.isEmpty() && noFilters(tag, company, industry) && scopedDocumentIds.isEmpty()) {
            candidates = fallbackCandidateIds(luceneOutcome.fallbackMode());
        }
        log.info(
            "search_candidate_union query='{}' mode={} fallbackMode={} keywordCandidates={} memoryCandidates={} finalCandidates={}",
            safeLogQuery(normalizedKeyword),
            recallMode,
            luceneOutcome.fallbackMode(),
            keywordCandidateCount,
            titleMemoryScores.size(),
            candidates.size()
        );

        List<SearchResult> allResults = candidates.stream()
            .map(store::get)
            .flatMap(Optional::stream)
            .filter(this::isLatestVersion)
            .filter(document -> canAccess(document, context))
            .map(document -> toResult(
                document,
                focusedKeyword,
                expandedQueryTokens,
                significantTerms,
                scores,
                matchedKeywords,
                null,
                titleMemoryRawScores,
                titleMemoryCalibratedScores
            ))
            .filter(result -> expandedQueryTokens.isEmpty() || isRelevantResult(result, significantTerms))
            .sorted(resultComparator())
            .toList();
        log.info(
            "search_result query='{}' mode={} finalCandidates={} resultTotal={}",
            safeLogQuery(normalizedKeyword),
            recallMode,
            candidates.size(),
            allResults.size()
        );

        List<SearchResult> results = allResults.stream()
            .skip(pageOffset(pageNumber, pageSize))
            .limit(pageSize)
            .toList();

        int documentCount = onlineDocumentCount(context);
        int totalPages = totalPages(allResults.size(), pageSize);
        log.info(
            "search_document_search_complete query='{}' resultTotal={} pageResults={} page={} pageSize={} source=fallback durationMs={}",
            safeLogQuery(normalizedKeyword),
            allResults.size(),
            results.size(),
            pageNumber,
            pageSize,
            elapsedMs(startedAt)
        );
        return new SearchPage(
            keyword,
            queryTokens,
            results,
            allResults.size(),
            pageSize,
            pageNumber,
            pageSize,
            totalPages,
            pageNumber < totalPages,
            (System.nanoTime() - startedAt) / 1_000_000,
            documentCount,
            buildSearchMessage(allResults.size(), documentCount, queryTokens)
        );
    }

    public SearchPage frontendQuickSearch(String keyword,
                                          String tag,
                                          String company,
                                          String industry,
                                          String docIds,
                                          Integer page,
                                          Integer limit,
                                          SearchPermissionContext permissionContext) {
        SearchPermissionContext context = permissionContext == null ? SearchPermissionContext.system() : permissionContext;
        long startedAt = System.nanoTime();
        int pageSize = normalizeLimit(limit);
        int pageNumber = normalizePage(page);
        String focusedKeyword = queryExpander.focusQuery(keyword);
        String normalizedKeyword = queryExpander.normalizeQuery(keyword);
        log.info(
            "search_document_frontend_start query='{}' tag={} company={} industry={} docIds={} page={} pageSize={} tenantId={} userId={}",
            safeLogQuery(normalizedKeyword),
            safeLogValue(tag, 60),
            safeLogValue(company, 60),
            safeLogValue(industry, 60),
            parseList(docIds).size(),
            pageNumber,
            pageSize,
            context.tenantId(),
            context.userId()
        );
        List<String> queryTokens = tokenizer.searchTokens(normalizedKeyword);
        List<String> focusedQueryTokens = tokenizer.searchTokens(focusedKeyword);
        String queryIntentName = queryExpander.classifyIntentName(normalizedKeyword);
        List<String> expandedQueryTokens = queryExpander.expandTokens(queryTokens, queryIntentName, normalizedKeyword);
        List<String> resultTokens = expandedQueryTokens.isEmpty() ? queryTokens : expandedQueryTokens;
        List<String> significantTerms = significantQueryTerms(focusedKeyword, focusedQueryTokens);
        QueryRecallMode recallMode = routeQueryRecall(focusedKeyword, significantTerms);
        Map<String, Integer> titleMemoryScores = shouldRunTitleMemoryRecall(recallMode, focusedKeyword)
            ? titleMemoryCandidateScores(focusedKeyword, significantTerms, context)
            : Map.of();
        log.info(
            "frontend_search_route query='{}' focusedQuery='{}' mode={} queryTokens={} significantTerms={} memoryCandidates={}",
            safeLogQuery(normalizedKeyword),
            safeLogQuery(focusedKeyword),
            recallMode,
            queryTokens,
            significantTerms,
            titleMemoryScores.size()
        );
        List<String> scopedDocumentIds = parseList(docIds);
        List<String> tagTerms = tokenizer.splitFilter(tag);
        List<String> companyTerms = tokenizer.splitFilter(company);
        List<String> industryTerms = tokenizer.splitFilter(industry);
        Set<String> scopedIds = scopedDocumentIds.isEmpty() ? Set.of() : new LinkedHashSet<>(scopedDocumentIds);

        List<SearchDocument> documents = loadLatestDocuments(context);
        List<SearchDocument> candidates = documents.stream()
            .filter(document -> scopedIds.isEmpty() || scopedIds.contains(document.getDocId()))
            .filter(document -> matchesQuickFilter(document.getTags(), tagTerms))
            .filter(document -> matchesQuickFilter(document.getCompanies(), companyTerms))
            .filter(document -> matchesQuickFilter(document.getIndustries(), industryTerms))
            .toList();
        FrontendQuickCorpus corpus = frontendQuickCorpus(candidates);
        List<SearchResult> quickCandidates = candidates.stream()
            .map(document -> toFrontendQuickResult(
                document,
                focusedKeyword,
                significantTerms,
                resultTokens,
                corpus,
                titleMemoryScores
            ))
            .sorted(resultComparator())
            .toList();
        List<SearchResult> allResults = quickCandidates.stream()
            .filter(result -> !hasKeyword(keyword) || isFrontendQuickRelevant(result, significantTerms, recallMode))
            .toList();

        if (hasKeyword(keyword) && allResults.isEmpty()) {
            log.info(
                "frontend_search_fallback query='{}' mode={} filteredCandidates={} memoryCandidates={} reason=quick_zero_results",
                safeLogQuery(normalizedKeyword),
                recallMode,
                candidates.size(),
                titleMemoryScores.size()
            );
            SearchPage fallbackPage = search(
                keyword,
                tag,
                company,
                industry,
                docIds,
                page,
                pageSize,
                context
            );
            log.info(
                "frontend_search_fallback_result query='{}' mode={} fallbackResults={} fallbackTotal={}",
                safeLogQuery(normalizedKeyword),
                recallMode,
                fallbackPage.results() == null ? 0 : fallbackPage.results().size(),
                fallbackPage.total()
            );
            if (fallbackPage.total() > 0) {
                return fallbackPage;
            }
            allResults = quickCandidates.stream()
                .filter(this::isFrontendQuickWeakRelevant)
                .toList();
            if (!allResults.isEmpty()) {
                log.info(
                    "frontend_search_relaxed_result query='{}' mode={} weakResults={} reason=strict_and_fallback_empty",
                    safeLogQuery(normalizedKeyword),
                    recallMode,
                    allResults.size()
                );
            }
        }

        log.info(
            "frontend_search_result query='{}' mode={} filteredCandidates={} memoryCandidates={} quickResults={} quickTotal={}",
            safeLogQuery(normalizedKeyword),
            recallMode,
            candidates.size(),
            titleMemoryScores.size(),
            Math.min(allResults.size(), pageSize),
            allResults.size()
        );

        List<SearchResult> results = allResults.stream()
            .skip(pageOffset(pageNumber, pageSize))
            .limit(pageSize)
            .toList();

        int totalPages = totalPages(allResults.size(), pageSize);
        log.info(
            "search_document_frontend_complete query='{}' resultTotal={} pageResults={} page={} pageSize={} candidates={} durationMs={}",
            safeLogQuery(normalizedKeyword),
            allResults.size(),
            results.size(),
            pageNumber,
            pageSize,
            candidates.size(),
            elapsedMs(startedAt)
        );
        return new SearchPage(
            keyword,
            queryTokens,
            results,
            allResults.size(),
            pageSize,
            pageNumber,
            pageSize,
            totalPages,
            pageNumber < totalPages,
            (System.nanoTime() - startedAt) / 1_000_000,
            documents.size(),
            buildSearchMessage(allResults.size(), documents.size(), queryTokens)
        );
    }

    /**
     * Lists the library.
     *
     * @param category the category value
     * @param title the title value
     * @param limit the limit value
     * @return the library list
     */
    public LibraryPage listLibrary(String category, String title, Integer limit) {
        return listLibrary(category, title, null, limit);
    }

    public LibraryPage listLibrary(String category,
                                   String title,
                                   Integer page,
                                   Integer limit,
                                   SearchPermissionContext permissionContext) {
        return listLibraryInternal(category, title, page, limit, permissionContext);
    }

    /**
     * Lists the library.
     *
     * @param category the category value
     * @param title the title value
     * @param page the page value
     * @param limit the limit value
     * @return the library list
     */
    public LibraryPage listLibrary(String category, String title, Integer page, Integer limit) {
        return listLibraryInternal(category, title, page, limit, SearchPermissionContext.system());
    }

    private LibraryPage listLibraryInternal(String category,
                                            String title,
                                            Integer page,
                                            Integer limit,
                                            SearchPermissionContext permissionContext) {
        int pageSize = normalizeLimit(limit);
        int pageNumber = normalizePage(page);
        String normalizedCategory = normalizeCategory(category);
        String normalizedTitle = normalizeSearchText(title);
        SearchPermissionContext context = permissionContext == null ? SearchPermissionContext.system() : permissionContext;
        List<SearchDocument> allDocuments = loadLatestDocuments(context);
        List<LibraryCategory> categories = buildCategories(allDocuments);

        List<SearchDocument> matchedDocuments = allDocuments.stream()
            .filter(document -> matchesCategory(document, normalizedCategory))
            .filter(document -> matchesLibraryTitle(document, normalizedTitle))
            .sorted(documentComparator())
            .toList();

        List<LibraryDocumentItem> documents = matchedDocuments.stream()
            .skip(pageOffset(pageNumber, pageSize))
            .limit(pageSize)
            .map(this::toLibraryItem)
            .toList();

        TitleExistsResult titleExistsResult = titleExists(title);
        int totalPages = totalPages(matchedDocuments.size(), pageSize);
        return new LibraryPage(
            normalizedCategory,
            title,
            categories,
            documents,
            matchedDocuments.size(),
            pageNumber,
            pageSize,
            totalPages,
            allDocuments.size(),
            titleExistsResult.exists(),
            titleExistsResult.docId(),
            buildLibraryMessage(matchedDocuments.size(), allDocuments.size(), normalizedTitle)
        );
    }

    /**
     * Performs the apply document scope operation.
     *
     * @param candidates the candidates value
     * @param docIds the doc ids value
     * @return the operation result
     */
    private Set<String> applyDocumentScope(Set<String> candidates, List<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return candidates;
        }
        Set<String> scope = new LinkedHashSet<>(docIds);
        if (candidates == null) {
            return scope;
        }
        candidates.retainAll(scope);
        return candidates;
    }

    private Set<String> injectTitleMemoryCandidates(Set<String> candidates,
                                                    Map<String, Integer> scores,
                                                    Map<String, Set<String>> matchedKeywords,
                                                    Map<String, Integer> titleMemoryScores,
                                                    Map<String, Integer> titleMemoryRawScores,
                                                    Map<String, Integer> titleMemoryCalibratedScores,
                                                    String keyword) {
        if (titleMemoryScores == null || titleMemoryScores.isEmpty()) {
            return candidates;
        }
        Set<String> merged = candidates == null ? new LinkedHashSet<>() : candidates;
        String normalizedKeyword = normalizeSearchText(keyword);
        for (Map.Entry<String, Integer> entry : titleMemoryScores.entrySet()) {
            String docId = entry.getKey();
            if (docId == null || docId.isBlank()) {
                continue;
            }
            merged.add(docId);
            int rawMemoryScore = Math.max(1, entry.getValue());
            int lexicalScore = scores == null ? 0 : scores.getOrDefault(docId, 0);
            int calibratedMemoryScore = calibrateTitleMemoryScore(rawMemoryScore, lexicalScore);
            if (titleMemoryRawScores != null) {
                titleMemoryRawScores.merge(docId, rawMemoryScore, Math::max);
            }
            if (titleMemoryCalibratedScores != null) {
                titleMemoryCalibratedScores.merge(docId, calibratedMemoryScore, Math::max);
            }
            if (scores != null) {
                scores.merge(docId, calibratedMemoryScore, Math::max);
            }
            if (matchedKeywords != null && !normalizedKeyword.isBlank()) {
                matchedKeywords.computeIfAbsent(docId, ignored -> new LinkedHashSet<>()).add(normalizedKeyword);
            }
        }
        return merged;
    }

    private int calibrateTitleMemoryScore(int memoryScore, int lexicalScore) {
        int cappedMemoryScore = Math.min(Math.max(1, memoryScore), TITLE_MEMORY_RECALL_SCORE_CAP);
        if (lexicalScore >= TITLE_MEMORY_STRONG_LEXICAL_THRESHOLD) {
            return Math.max(1, Math.min(TITLE_MEMORY_RECALL_STRONG_LEXICAL_CAP,
                Math.round(cappedMemoryScore * 0.25F)));
        }
        if (lexicalScore >= TITLE_MEMORY_WEAK_LEXICAL_THRESHOLD) {
            return Math.max(1, Math.min(TITLE_MEMORY_RECALL_WEAK_LEXICAL_CAP,
                Math.round(cappedMemoryScore * 0.6F)));
        }
        return cappedMemoryScore;
    }

    private Map<String, Integer> titleMemoryCandidateScores(String keyword,
                                                            List<String> significantTerms,
                                                            SearchPermissionContext permissionContext) {
        String phrase = normalizeSearchText(keyword);
        if (phrase.isBlank() || !TitleAwareTerms.containsCjk(phrase)) {
            return Map.of();
        }
        List<String> recallTerms = titleMemoryRecallTerms(phrase, significantTerms);
        if (recallTerms.isEmpty()) {
            return Map.of();
        }
        SearchPermissionContext context = permissionContext == null ? SearchPermissionContext.system() : permissionContext;
        Map<String, Integer> scores = new HashMap<>();
        for (SearchDocument document : loadLatestDocuments(context)) {
            int score = titleMemoryScore(document, phrase, recallTerms);
            if (score > 0) {
                scores.put(document.getDocId(), score);
            }
        }
        return scores;
    }

    private List<String> titleMemoryRecallTerms(String phrase, List<String> significantTerms) {
        Set<String> terms = new LinkedHashSet<>();
        String compact = normalizeSearchText(phrase).replace(" ", "");
        if (!compact.isBlank()) {
            terms.add(compact);
        }
        if (significantTerms != null) {
            significantTerms.stream()
                .filter(term -> term != null && normalizeSearchText(term).length() >= 2)
                .map(this::normalizeSearchText)
                .forEach(terms::add);
        }
        tokenizer.searchTokens(phrase).stream()
            .filter(term -> term != null && term.length() >= 2)
            .forEach(terms::add);
        return new ArrayList<>(terms);
    }

    private int titleMemoryScore(SearchDocument document, String phrase, List<String> recallTerms) {
        if (document == null || document.getDocId() == null || document.getDocId().isBlank()) {
            return 0;
        }
        int score = 0;
        boolean phraseMatched = false;
        if (containsNormalizedCompact(document.getTitle(), phrase)) {
            score += TITLE_MEMORY_PHRASE_RECALL_SCORE;
            phraseMatched = true;
        }
        if (containsNormalizedCompact(document.getFileName(), phrase)) {
            score += FILENAME_MEMORY_PHRASE_RECALL_SCORE;
            phraseMatched = true;
        }
        if (phraseMatched) {
            return score;
        }

        int matchedTerms = 0;
        for (String term : recallTerms == null ? List.<String>of() : recallTerms) {
            String normalizedTerm = normalizeSearchText(term);
            if (normalizedTerm.isBlank() || normalizedTerm.length() < 2 || normalizedTerm.equals(phrase)) {
                continue;
            }
            boolean matched = false;
            if (containsNormalizedCompact(document.getTitle(), normalizedTerm)) {
                score += TITLE_MEMORY_TERM_RECALL_SCORE;
                matched = true;
            }
            if (containsNormalizedCompact(document.getFileName(), normalizedTerm)) {
                score += FILENAME_MEMORY_TERM_RECALL_SCORE;
                matched = true;
            }
            if (matched) {
                matchedTerms++;
            }
        }
        return matchedTerms >= 2 ? score : 0;
    }

    private Set<String> fallbackCandidateIds(FallbackMode mode) {
        int limit = fallbackCandidateLimit(mode);
        if (limit <= 0) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(store.listDocumentIds(limit));
    }

    private int fallbackCandidateLimit(FallbackMode mode) {
        int configuredLimit = switch (mode == null ? FallbackMode.NORMAL : mode) {
            case EMPTY_LUCENE_RESULT -> properties.getFallbackEmptyResultLimit();
            case LUCENE_FAILURE -> properties.getFallbackExceptionLimit();
            case NORMAL -> properties.getFallbackCandidateLimit();
        };
        return boundedLookupLimit(configuredLimit);
    }

    private int boundedLookupLimit(int configuredLimit) {
        int maxDocScan = properties.getQueryBudget() == null ? configuredLimit : properties.getQueryBudget().getMaxDocScan();
        int maxRocksDbIter = properties.getQueryBudget() == null ? configuredLimit : properties.getQueryBudget().getMaxRocksdbIter();
        return positiveMin(configuredLimit, maxDocScan, maxRocksDbIter);
    }

    private int positiveMin(int... values) {
        int min = Integer.MAX_VALUE;
        for (int value : values) {
            if (value > 0) {
                min = Math.min(min, value);
            }
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    /**
     * Searches the with lucene.
     *
     * @param keyword the keyword value
     * @param tag the tag value
     * @param company the company value
     * @param industry the industry value
     * @param docIds the doc ids value
     * @param pageNumber the page number value
     * @param pageSize the page size value
     * @param queryTokens the query tokens value
     * @param significantTerms the significant terms value
     * @return the operation result
     */
    private LuceneSearchOutcome searchWithLucene(String keyword,
                                                 String tag,
                                                 String company,
                                                 String industry,
                                                 String docIds,
                                                 int pageNumber,
                                                 int pageSize,
                                                 List<String> queryTokens,
                                                 List<String> expandedQueryTokens,
                                                 List<String> significantTerms,
                                                 Map<String, Integer> titleMemoryScores,
                                                 SearchPermissionContext permissionContext) {
        if (expandedQueryTokens.isEmpty() || !luceneStore.isAvailable()) {
            if (!expandedQueryTokens.isEmpty() && properties.isLuceneEnabled()) {
                return LuceneSearchOutcome.failure();
            }
            return LuceneSearchOutcome.skipped();
        }
        try {
            SearchPermissionContext context = permissionContext == null ? SearchPermissionContext.system() : permissionContext;
            List<LuceneSearchHit> hits = luceneStore.search(keyword, properties.getLuceneMaxHits(), context);
            Map<String, Integer> safeTitleMemoryScores = titleMemoryScores == null ? Map.of() : titleMemoryScores;
            if (hits.isEmpty() && safeTitleMemoryScores.isEmpty()) {
                log.info(
                    "lucene_candidate_union query='{}' luceneHits=0 luceneDocCandidates=0 memoryCandidates=0 unionCandidates=0",
                    safeLogQuery(keyword)
                );
                return LuceneSearchOutcome.emptyResult();
            }
            Map<String, List<LuceneSearchHit>> hitsByDocId = new HashMap<>();
            Map<String, Integer> scores = new HashMap<>();
            Map<String, Integer> titleMemoryRawScores = new HashMap<>();
            Map<String, Integer> titleMemoryCalibratedScores = new HashMap<>();
            Set<String> candidates = new LinkedHashSet<>();
            for (LuceneSearchHit hit : hits) {
                candidates.add(hit.docId());
                hitsByDocId.computeIfAbsent(hit.docId(), ignored -> new ArrayList<>()).add(hit);
                scores.merge(hit.docId(), Math.max(1, Math.round(hit.score() * 10)), Math::max);
            }
            int luceneDocCandidateCount = candidates.size();
            hitsByDocId.replaceAll((docId, docHits) -> docHits.stream()
                .sorted(Comparator
                    .comparingDouble(LuceneSearchHit::score)
                    .reversed()
                    .thenComparingInt(LuceneSearchHit::chunkIndex))
                .limit(Math.max(1, properties.getLuceneChunksPerDocument()))
                .toList());

            candidates = injectTitleMemoryCandidates(
                candidates,
                scores,
                null,
                safeTitleMemoryScores,
                titleMemoryRawScores,
                titleMemoryCalibratedScores,
                keyword
            );
            int unionCandidateCount = candidates.size();
            log.info(
                "lucene_candidate_union query='{}' luceneHits={} luceneDocCandidates={} memoryCandidates={} unionCandidates={}",
                safeLogQuery(keyword),
                hits.size(),
                luceneDocCandidateCount,
                safeTitleMemoryScores.size(),
                candidates.size()
            );

            int filterLookupLimit = boundedLookupLimit(properties.getLuceneMaxHits());
            candidates = applyFilter(candidates, tokenizer.splitFilter(tag), term -> store.findByTag(term, filterLookupLimit));
            candidates = applyFilter(candidates, tokenizer.splitFilter(company), term -> store.findByCompany(term, filterLookupLimit));
            candidates = applyFilter(candidates, tokenizer.splitFilter(industry), term -> store.findByIndustry(term, filterLookupLimit));
            candidates = applyDocumentScope(candidates, parseList(docIds));
            int postHardFilterCandidateCount = candidates.size();

            Map<String, Set<String>> matchedKeywords = new HashMap<>();
            candidates.forEach(docId -> matchedKeywords.put(docId, new LinkedHashSet<>(expandedQueryTokens)));

            List<SearchResult> rankedCandidates = candidates.stream()
                .map(store::get)
                .flatMap(Optional::stream)
                .filter(this::isLatestVersion)
                .filter(document -> canAccess(document, context))
                .map(document -> toResult(
                    document,
                    keyword,
                    expandedQueryTokens,
                    significantTerms,
                    scores,
                    matchedKeywords,
                    hitsByDocId.get(document.getDocId()),
                    titleMemoryRawScores,
                    titleMemoryCalibratedScores
                ))
                .toList();
            List<SearchResult> allResults = rankedCandidates.stream()
                .filter(result -> isRelevantResult(result, significantTerms))
                .sorted(resultComparator())
                .toList();
            log.info(
                "lucene_search_result query='{}' unionCandidates={} postHardFilterCandidates={} scoredCandidates={} relevanceResults={}",
                safeLogQuery(keyword),
                unionCandidateCount,
                postHardFilterCandidateCount,
                rankedCandidates.size(),
                allResults.size()
            );
            List<SearchResult> results = allResults.stream()
                .skip(pageOffset(pageNumber, pageSize))
                .limit(pageSize)
                .toList();
            int documentCount = onlineDocumentCount(context);
            int totalPages = totalPages(allResults.size(), pageSize);
            return LuceneSearchOutcome.success(new SearchPage(
                keyword,
                queryTokens,
                results,
                allResults.size(),
                pageSize,
                pageNumber,
                pageSize,
                totalPages,
                pageNumber < totalPages,
                0L,
                documentCount,
                buildSearchMessage(allResults.size(), documentCount, queryTokens)
            ));
        } catch (Exception ex) {
            log.warn("Lucene search failed, falling back to RocksDB keyword search: {}", ex.getMessage(), ex);
            return LuceneSearchOutcome.failure();
        }
    }

    /**
     * Creates the category.
     *
     * @param name the name value
     * @return the created category
     */
    public LibraryCategory createCategory(String name) {
        String category = normalizeCategory(name);
        validateMutableCategory(category);
        store.putCategory(category);
        return new LibraryCategory(category, countDocumentsByCategory(category));
    }

    public LibraryCategory renameCategory(String oldName, String newName) {
        String oldCategory = normalizeCategory(oldName);
        String newCategory = normalizeCategory(newName);
        validateMutableCategory(oldCategory);
        validateMutableCategory(newCategory);
        long startedAt = System.nanoTime();
        log.info("search_library_category_rename_start oldCategory={} newCategory={}", oldCategory, newCategory);
        if (oldCategory.equals(newCategory)) {
            log.info(
                "search_library_category_rename_complete oldCategory={} newCategory={} affected=0 reason=same_category durationMs={}",
                oldCategory,
                newCategory,
                elapsedMs(startedAt)
            );
            return new LibraryCategory(newCategory, countDocumentsByCategory(newCategory));
        }

        store.deleteCategory(oldCategory);
        store.putCategory(newCategory);
        List<SearchDocument> affectedDocuments = loadAllDocuments().stream()
            .filter(document -> document.getTags() != null
                && document.getTags().stream().anyMatch(tag -> normalizeCategory(tag).equals(oldCategory)))
            .toList();
        affectedDocuments.forEach(document -> {
                SearchIndexData oldIndexData = buildIndexData(document);
                document.setTags(replaceCategoryTag(document.getTags(), oldCategory, newCategory));
                document.setUpdatedAt(Instant.now().toEpochMilli());
                store.put(document, buildIndexData(document), oldIndexData);
                syncLuceneIndex(document);
            });
        log.info(
            "search_library_category_rename_complete oldCategory={} newCategory={} affected={} durationMs={}",
            oldCategory,
            newCategory,
            affectedDocuments.size(),
            elapsedMs(startedAt)
        );
        return new LibraryCategory(newCategory, countDocumentsByCategory(newCategory));
    }

    public boolean deleteCategory(String name) {
        String category = normalizeCategory(name);
        validateMutableCategory(category);
        long startedAt = System.nanoTime();
        log.info("search_library_category_delete_start category={}", category);
        store.deleteCategory(category);
        List<SearchDocument> affectedDocuments = loadAllDocuments().stream()
            .filter(document -> document.getTags() != null
                && document.getTags().stream().anyMatch(tag -> normalizeCategory(tag).equals(category)))
            .toList();
        affectedDocuments.forEach(document -> {
                SearchIndexData oldIndexData = buildIndexData(document);
                document.setTags(removeCategoryTag(document.getTags(), category));
                document.setUpdatedAt(Instant.now().toEpochMilli());
                store.put(document, buildIndexData(document), oldIndexData);
                syncLuceneIndex(document);
            });
        log.info(
            "search_library_category_delete_complete category={} affected={} durationMs={}",
            category,
            affectedDocuments.size(),
            elapsedMs(startedAt)
        );
        return true;
    }

    public Optional<SearchDocument> updateDocumentCategory(String docId,
                                                           String category,
                                                           SearchPermissionContext permissionContext) {
        String normalizedCategory = normalizeCategory(category);
        validateMutableCategory(normalizedCategory);
        SearchPermissionContext context = permissionContext == null ? SearchPermissionContext.system() : permissionContext;
        long startedAt = System.nanoTime();
        log.info(
            "search_document_category_update_start docId={} category={} tenantId={} userId={}",
            docId,
            normalizedCategory,
            context.tenantId(),
            context.userId()
        );
        Optional<SearchDocument> result = get(docId, context).map(document -> {
            SearchIndexData oldIndexData = buildIndexData(document);
            List<String> previousTags = cleanList(document.getTags());
            document.setTags(assignPrimaryCategory(document.getTags(), normalizedCategory));
            document.setUpdatedAt(Instant.now().toEpochMilli());
            store.put(document, buildIndexData(document), oldIndexData);
            syncLuceneIndex(document);
            store.putCategory(normalizedCategory);
            log.info(
                "search_document_category_update_complete docId={} category={} previousTags={} tags={} durationMs={}",
                document.getDocId(),
                normalizedCategory,
                previousTags,
                cleanList(document.getTags()),
                elapsedMs(startedAt)
            );
            return document;
        });
        if (result.isEmpty()) {
            log.info(
                "search_document_category_update_not_found docId={} category={} durationMs={}",
                docId,
                normalizedCategory,
                elapsedMs(startedAt)
            );
        }
        return result;
    }

    private void validateMutableCategory(String category) {
        if (category.isEmpty() || ALL_CATEGORY.equals(category) || UNCATEGORIZED.equals(category)) {
            throw new IllegalArgumentException("category name is required");
        }
    }

    private List<String> assignPrimaryCategory(List<String> tags, String category) {
        List<String> cleaned = cleanList(tags);
        List<String> updated = new ArrayList<>();
        updated.add(category);
        cleaned.stream()
            .filter(tag -> !normalizeCategory(tag).equals(category))
            .forEach(updated::add);
        return updated;
    }

    private List<String> replaceCategoryTag(List<String> tags, String oldCategory, String newCategory) {
        List<String> updated = new ArrayList<>();
        for (String tag : cleanList(tags)) {
            String normalized = normalizeCategory(tag);
            String value = normalized.equals(oldCategory) ? newCategory : tag;
            if (updated.stream().noneMatch(existing -> normalizeCategory(existing).equals(normalizeCategory(value)))) {
                updated.add(value);
            }
        }
        return updated;
    }

    private List<String> removeCategoryTag(List<String> tags, String category) {
        return cleanList(tags).stream()
            .filter(tag -> !normalizeCategory(tag).equals(category))
            .toList();
    }

    /**
     * Performs the title exists operation.
     *
     * @param title the title value
     * @return the operation result
     */
    public TitleExistsResult titleExists(String title) {
        return titleExists(title, SearchPermissionContext.system());
    }

    public TitleExistsResult titleExists(String title, SearchPermissionContext permissionContext) {
        String normalizedTitle = normalizeText(title);
        if (normalizedTitle.isEmpty()) {
            return new TitleExistsResult(title, false, null);
        }
        SearchPermissionContext context = permissionContext == null ? SearchPermissionContext.system() : permissionContext;
        return loadLatestDocuments(context).stream()
            .filter(document -> normalizeText(document.getTitle()).equals(normalizedTitle))
            .findFirst()
            .map(document -> new TitleExistsResult(title, true, document.getDocId()))
            .orElseGet(() -> new TitleExistsResult(title, false, null));
    }

    /**
     * Returns the get.
     *
     * @param docId the doc id value
     * @return the get
     */
    public Optional<SearchDocument> get(String docId) {
        if (isBlank(docId)) {
            return Optional.empty();
        }
        return store.get(docId.trim());
    }

    public Optional<SearchDocument> get(String docId, SearchPermissionContext permissionContext) {
        SearchPermissionContext context = permissionContext == null ? SearchPermissionContext.system() : permissionContext;
        return get(docId).filter(document -> canAccess(document, context));
    }

    /**
     * Lists the versions.
     *
     * @param docId the doc id value
     * @return the versions list
     */
    public List<SearchDocumentVersionItem> listVersions(String docId) {
        Optional<SearchDocument> document = get(docId);
        if (document.isEmpty()) {
            return List.of();
        }
        return listVersionDocuments(document.get()).stream()
            .map(this::toVersionItem)
            .toList();
    }

    public List<SearchDocumentVersionItem> listVersions(String docId, SearchPermissionContext permissionContext) {
        Optional<SearchDocument> document = get(docId, permissionContext);
        if (document.isEmpty()) {
            return List.of();
        }
        SearchPermissionContext context = permissionContext == null ? SearchPermissionContext.system() : permissionContext;
        return listVersionDocuments(document.get()).stream()
            .filter(candidate -> canAccess(candidate, context))
            .map(this::toVersionItem)
            .toList();
    }

    /**
     * Returns the version.
     *
     * @param docId the doc id value
     * @param version the version value
     * @return the version
     */
    public Optional<SearchDocument> getVersion(String docId, Integer version) {
        if (version == null || version <= 0) {
            return Optional.empty();
        }
        return get(docId).flatMap(document -> listVersionDocuments(document).stream()
            .filter(candidate -> versionOf(candidate) == version)
            .findFirst());
    }

    public Optional<SearchDocument> getVersion(String docId, Integer version, SearchPermissionContext permissionContext) {
        SearchPermissionContext context = permissionContext == null ? SearchPermissionContext.system() : permissionContext;
        return getVersion(docId, version).filter(document -> canAccess(document, context));
    }

    /**
     * Returns the version file resource.
     *
     * @param docId the doc id value
     * @param version the version value
     * @return the version file resource
     */
    public Optional<DocumentFileResource> getVersionFileResource(String docId, Integer version) {
        return getVersion(docId, version).flatMap(this::fileResourceFor);
    }

    public Optional<DocumentFileResource> getVersionFileResource(String docId,
                                                                Integer version,
                                                                SearchPermissionContext permissionContext) {
        return getVersion(docId, version, permissionContext).flatMap(this::fileResourceFor);
    }

    /**
     * Returns the file resource.
     *
     * @param docId the doc id value
     * @return the file resource
     */
    public Optional<DocumentFileResource> getFileResource(String docId) {
        return get(docId).flatMap(this::fileResourceFor);
    }

    public Optional<DocumentFileResource> getFileResource(String docId, SearchPermissionContext permissionContext) {
        return get(docId, permissionContext).flatMap(this::fileResourceFor);
    }

    /**
     * Returns whether delete document.
     *
     * @param docId the doc id value
     * @return whether the condition is satisfied
     */
    public boolean deleteDocument(String docId) {
        return deleteDocument(docId, SearchPermissionContext.system());
    }

    public boolean deleteDocument(String docId, SearchPermissionContext permissionContext) {
        SearchPermissionContext context = permissionContext == null ? SearchPermissionContext.system() : permissionContext;
        long startedAt = System.nanoTime();
        log.info(
            "search_document_delete_start docId={} tenantId={} userId={}",
            docId,
            context.tenantId(),
            context.userId()
        );
        Optional<SearchDocument> document = get(docId, context);
        if (document.isEmpty()) {
            log.info(
                "search_document_delete_not_found docId={} tenantId={} userId={} durationMs={}",
                docId,
                context.tenantId(),
                context.userId(),
                elapsedMs(startedAt)
            );
            return false;
        }
        SearchDocument target = document.get();
        boolean wasLatest = isLatestVersion(target);
        List<SearchDocument> family = listVersionDocuments(target);
        store.delete(target, buildIndexData(target));
        luceneStore.deleteDocument(target.getDocId());
        deleteOriginalFile(target);

        String promotedDocId = null;
        if (wasLatest) {
            Optional<SearchDocument> previousVersion = family.stream()
                .filter(candidate -> !candidate.getDocId().equals(target.getDocId()))
                .max(Comparator
                    .comparingInt(this::versionOf)
                    .thenComparing(SearchDocument::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
            if (previousVersion.isPresent()) {
                SearchDocument previous = previousVersion.get();
                    SearchIndexData oldIndexData = buildIndexData(previous);
                    previous.setLatestVersion(true);
                    previous.setUpdatedAt(Instant.now().toEpochMilli());
                    store.put(previous, buildIndexData(previous), oldIndexData);
                    syncLuceneIndex(previous);
                promotedDocId = previous.getDocId();
            }
        }
        log.info(
            "search_document_delete_complete docId={} title={} fileName={} wasLatest={} promotedDocId={} durationMs={}",
            target.getDocId(),
            safeLogValue(target.getTitle(), 80),
            safeLogValue(target.getFileName(), 100),
            wasLatest,
            promotedDocId,
            elapsedMs(startedAt)
        );
        return true;
    }

    public Optional<SearchDocument> reindexDocument(String docId) {
        return reindexDocument(docId, SearchPermissionContext.system());
    }

    public Optional<SearchDocument> reindexDocument(String docId, SearchPermissionContext permissionContext) {
        SearchPermissionContext context = permissionContext == null ? SearchPermissionContext.system() : permissionContext;
        long startedAt = System.nanoTime();
        log.info(
            "search_reindex_document_start docId={} tenantId={} userId={}",
            docId,
            context.tenantId(),
            context.userId()
        );
        Optional<SearchDocument> document = get(docId, context);
        if (document.isEmpty()) {
            log.info(
                "search_reindex_document_not_found docId={} tenantId={} userId={} durationMs={}",
                docId,
                context.tenantId(),
                context.userId(),
                elapsedMs(startedAt)
            );
            return Optional.empty();
        }
        SearchDocument target = document.get();
        try {
            SearchIndexData oldIndexData = buildIndexData(target);
            refreshDocumentContent(target);
            target.setLifecycleStatus(DocumentLifecycleStatus.INDEXED);
            target.setIndexedAt(Instant.now().toEpochMilli());
            target.setUpdatedAt(Instant.now().toEpochMilli());
            target.setDeletedAt(null);
            target.setErrorMessage(null);
            store.put(target, buildIndexData(target), oldIndexData);
            syncLuceneIndex(target);
            log.info(
                "search_reindex_document_complete docId={} title={} fileName={} contentChars={} keywords={} durationMs={}",
                target.getDocId(),
                safeLogValue(target.getTitle(), 80),
                safeLogValue(target.getFileName(), 80),
                lengthOf(target.getContent()),
                cleanList(target.getKeywords()).size(),
                elapsedMs(startedAt)
            );
            return Optional.of(target);
        } catch (RuntimeException ex) {
            log.warn(
                "search_reindex_document_failed docId={} title={} fileName={} durationMs={} error={}",
                target.getDocId(),
                safeLogValue(target.getTitle(), 80),
                safeLogValue(target.getFileName(), 80),
                elapsedMs(startedAt),
                ex.getMessage(),
                ex
            );
            throw ex;
        }
    }

    public ReindexSummary reindexDocumentsByCategory(String category, SearchPermissionContext permissionContext) {
        SearchPermissionContext context = permissionContext == null ? SearchPermissionContext.system() : permissionContext;
        String normalizedCategory = normalizeCategory(category);
        List<SearchDocument> documents = loadLatestDocuments(context);
        long startedAt = System.nanoTime();
        log.info(
            "search_reindex_category_start category={} tenantId={} userId={} scannedCandidates={}",
            normalizedCategory,
            context.tenantId(),
            context.userId(),
            documents.size()
        );
        List<String> reindexedDocIds = new ArrayList<>();
        List<String> failedDocIds = new ArrayList<>();
        int matchedDocuments = 0;
        for (SearchDocument document : documents) {
            if (!matchesCategory(document, normalizedCategory)) {
                continue;
            }
            matchedDocuments++;
            long documentStartedAt = System.nanoTime();
            log.info(
                "search_reindex_document_start scope=category category={} ordinal={} docId={} title={} fileName={}",
                normalizedCategory,
                matchedDocuments,
                document.getDocId(),
                safeLogValue(document.getTitle(), 80),
                safeLogValue(document.getFileName(), 80)
            );
            try {
                reindexLoadedDocument(document);
                reindexedDocIds.add(document.getDocId());
                log.info(
                    "search_reindex_document_complete scope=category category={} ordinal={} docId={} contentChars={} keywords={} durationMs={}",
                    normalizedCategory,
                    matchedDocuments,
                    document.getDocId(),
                    lengthOf(document.getContent()),
                    cleanList(document.getKeywords()).size(),
                    elapsedMs(documentStartedAt)
                );
            } catch (Exception ex) {
                failedDocIds.add(document.getDocId());
                log.warn(
                    "search_reindex_document_failed scope=category category={} ordinal={} docId={} durationMs={} error={}",
                    normalizedCategory,
                    matchedDocuments,
                    document.getDocId(),
                    elapsedMs(documentStartedAt),
                    ex.getMessage(),
                    ex
                );
            }
            if (matchedDocuments % REINDEX_PROGRESS_INTERVAL == 0) {
                log.info(
                    "search_reindex_category_progress category={} matched={} reindexed={} failed={} elapsedMs={}",
                    normalizedCategory,
                    matchedDocuments,
                    reindexedDocIds.size(),
                    failedDocIds.size(),
                    elapsedMs(startedAt)
                );
            }
        }
        log.info(
            "search_reindex_category_complete category={} scanned={} matched={} reindexed={} failed={} durationMs={}",
            normalizedCategory,
            documents.size(),
            matchedDocuments,
            reindexedDocIds.size(),
            failedDocIds.size(),
            elapsedMs(startedAt)
        );
        return new ReindexSummary(
            documents.size(),
            matchedDocuments,
            reindexedDocIds.size(),
            failedDocIds.size(),
            reindexedDocIds,
            failedDocIds
        );
    }

    public ReindexSummary reindexUploadedSqlDocuments(SearchPermissionContext permissionContext) {
        SearchPermissionContext context = permissionContext == null ? SearchPermissionContext.system() : permissionContext;
        List<SearchDocument> documents = loadLatestDocuments(context);
        long startedAt = System.nanoTime();
        log.info(
            "search_reindex_sql_start tenantId={} userId={} scannedCandidates={}",
            context.tenantId(),
            context.userId(),
            documents.size()
        );
        List<String> reindexedDocIds = new ArrayList<>();
        List<String> failedDocIds = new ArrayList<>();
        int sqlDocuments = 0;
        for (SearchDocument document : documents) {
            if (!isSqlDocument(document)) {
                continue;
            }
            sqlDocuments++;
            long documentStartedAt = System.nanoTime();
            log.info(
                "search_reindex_document_start scope=sql ordinal={} docId={} title={} fileName={}",
                sqlDocuments,
                document.getDocId(),
                safeLogValue(document.getTitle(), 80),
                safeLogValue(document.getFileName(), 80)
            );
            try {
                reindexLoadedDocument(document);
                reindexedDocIds.add(document.getDocId());
                log.info(
                    "search_reindex_document_complete scope=sql ordinal={} docId={} contentChars={} keywords={} durationMs={}",
                    sqlDocuments,
                    document.getDocId(),
                    lengthOf(document.getContent()),
                    cleanList(document.getKeywords()).size(),
                    elapsedMs(documentStartedAt)
                );
            } catch (Exception ex) {
                failedDocIds.add(document.getDocId());
                log.warn(
                    "search_reindex_document_failed scope=sql ordinal={} docId={} durationMs={} error={}",
                    sqlDocuments,
                    document.getDocId(),
                    elapsedMs(documentStartedAt),
                    ex.getMessage(),
                    ex
                );
            }
            if (sqlDocuments % REINDEX_PROGRESS_INTERVAL == 0) {
                log.info(
                    "search_reindex_sql_progress matched={} reindexed={} failed={} elapsedMs={}",
                    sqlDocuments,
                    reindexedDocIds.size(),
                    failedDocIds.size(),
                    elapsedMs(startedAt)
                );
            }
        }
        log.info(
            "search_reindex_sql_complete scanned={} matched={} reindexed={} failed={} durationMs={}",
            documents.size(),
            sqlDocuments,
            reindexedDocIds.size(),
            failedDocIds.size(),
            elapsedMs(startedAt)
        );
        return new ReindexSummary(documents.size(), sqlDocuments, reindexedDocIds.size(), failedDocIds.size(), reindexedDocIds, failedDocIds);
    }

    public record ReindexSummary(
        int scannedDocuments,
        int matchedDocuments,
        int reindexedDocuments,
        int failedDocuments,
        List<String> reindexedDocIds,
        List<String> failedDocIds
    ) {
    }

    private void reindexLoadedDocument(SearchDocument document) {
        SearchIndexData oldIndexData = buildIndexData(document);
        refreshDocumentContent(document);
        document.setLifecycleStatus(DocumentLifecycleStatus.INDEXED);
        document.setIndexedAt(Instant.now().toEpochMilli());
        document.setUpdatedAt(Instant.now().toEpochMilli());
        document.setDeletedAt(null);
        document.setErrorMessage(null);
        store.put(document, buildIndexData(document), oldIndexData);
        syncLuceneIndex(document);
    }

    /**
     * Normalizes the document.
     *
     * @param request the request value
     * @return the operation result
     */
    private SearchDocument normalizeDocument(SearchDocument request) {
        String content = request.getContent() == null ? "" : request.getContent().trim();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("content is required");
        }
        long now = Instant.now().toEpochMilli();
        String docId = isBlank(request.getDocId()) ? generateDocId() : request.getDocId().trim();
        Optional<SearchDocument> existing = store.get(docId);
        String versionGroupId = !isBlank(request.getVersionGroupId())
            ? request.getVersionGroupId().trim()
            : existing.map(this::versionGroupIdOf).orElse(docId);
        int version = request.getVersion() == null || request.getVersion() <= 0
            ? existing.map(this::versionOf).orElse(1)
            : request.getVersion();
        boolean latestVersion = request.getLatestVersion() != null
            ? request.getLatestVersion()
            : existing.map(this::isLatestVersion).orElse(true);
        return SearchDocument.builder()
            .docId(docId)
            .title(isBlank(request.getTitle()) ? "untitled_document" : request.getTitle().trim())
            .content(content)
            .source(isBlank(request.getSource()) ? "manual" : request.getSource().trim())
            .date(isBlank(request.getDate()) ? LocalDate.now().toString() : request.getDate().trim())
            .tags(cleanList(request.getTags()))
            .companies(cleanList(request.getCompanies()))
            .industries(cleanList(request.getIndustries()))
            .keywords(keywordExtractor.mergeKeywords(cleanList(request.getKeywords()), content))
            .fileName(request.getFileName())
            .filePath(request.getFilePath())
            .documentType(resolveDocumentType(request.getDocumentType(), request.getFileName()))
            .fileSize(request.getFileSize())
            .uploadedAt(request.getUploadedAt() == null ? existing.map(SearchDocument::getUploadedAt).orElse(now) : request.getUploadedAt())
            .updatedAt(now)
            .versionGroupId(versionGroupId)
            .version(version)
            .latestVersion(latestVersion)
            .tenantId(normalizeTenant(firstNonBlank(request.getTenantId(), existing.map(SearchDocument::getTenantId).orElse(null))))
            .userId(normalizeUser(firstNonBlank(request.getUserId(), existing.map(SearchDocument::getUserId).orElse(null))))
            .visibility(normalizeVisibility(firstNonBlank(request.getVisibility(), existing.map(SearchDocument::getVisibility).orElse(null))))
            .permissionRoles(cleanList(request.getPermissionRoles() == null || request.getPermissionRoles().isEmpty()
                ? existing.map(SearchDocument::getPermissionRoles).orElse(List.of())
                : request.getPermissionRoles()))
            .lifecycleStatus(normalizeLifecycleStatus(firstNonBlank(
                request.getLifecycleStatus(),
                existing.map(SearchDocument::getLifecycleStatus).orElse(null),
                DocumentLifecycleStatus.INDEXED
            )))
            .indexedAt(request.getIndexedAt() == null ? existing.map(SearchDocument::getIndexedAt).orElse(now) : request.getIndexedAt())
            .deletedAt(request.getDeletedAt())
            .errorMessage(request.getErrorMessage())
            .build();
    }

    /**
     * Finds the latest by title.
     *
     * @param title the title value
     * @return the matching latest by title
     */
    private Optional<SearchDocument> findLatestByTitle(String title) {
        String normalizedTitle = normalizeText(title);
        if (normalizedTitle.isEmpty()) {
            return Optional.empty();
        }
        return loadLatestDocuments().stream()
            .filter(document -> normalizeText(document.getTitle()).equals(normalizedTitle))
            .max(Comparator
                .comparingInt(this::versionOf)
                .thenComparing(SearchDocument::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
    }

    /**
     * Normalizes the existing version family.
     *
     * @param title the title value
     */
    private void normalizeExistingVersionFamily(String title) {
        String normalizedTitle = normalizeText(title);
        if (normalizedTitle.isEmpty()) {
            return;
        }
        List<SearchDocument> documents = loadAllDocuments().stream()
            .filter(document -> normalizeText(document.getTitle()).equals(normalizedTitle))
            .sorted(Comparator
                .comparing(SearchDocument::getUploadedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SearchDocument::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SearchDocument::getDocId, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
        if (documents.isEmpty() || !needsVersionNormalization(documents)) {
            return;
        }
        String versionGroupId = documents.stream()
            .map(SearchDocument::getVersionGroupId)
            .filter(value -> !isBlank(value))
            .findFirst()
            .orElse(documents.get(0).getDocId());

        for (int index = 0; index < documents.size(); index++) {
            SearchDocument document = documents.get(index);
            SearchIndexData oldIndexData = buildIndexData(document);
            document.setVersionGroupId(versionGroupId);
            document.setVersion(index + 1);
            if (document.getLatestVersion() == null) {
                document.setLatestVersion(true);
            }
            store.put(document, buildIndexData(document), oldIndexData);
            syncLuceneIndex(document);
        }
    }

    /**
     * Returns whether needs version normalization.
     *
     * @param documents the documents value
     * @return whether the condition is satisfied
     */
    private boolean needsVersionNormalization(List<SearchDocument> documents) {
        Set<Integer> versions = new LinkedHashSet<>();
        for (SearchDocument document : documents) {
            if (isBlank(document.getVersionGroupId())
                || document.getVersion() == null
                || document.getVersion() <= 0
                || !versions.add(document.getVersion())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Performs the next version operation.
     *
     * @param versionGroupId the version group id value
     * @param title the title value
     * @return the operation result
     */
    private int nextVersion(String versionGroupId, String title) {
        return loadAllDocuments().stream()
            .filter(document -> sameVersionFamily(document, versionGroupId, title))
            .mapToInt(this::versionOf)
            .max()
            .orElse(0) + 1;
    }

    /**
     * Performs the mark previous versions not latest operation.
     *
     * @param latestDocument the latest document value
     */
    private void markPreviousVersionsNotLatest(SearchDocument latestDocument) {
        for (SearchDocument candidate : listVersionDocuments(latestDocument)) {
            if (candidate.getDocId().equals(latestDocument.getDocId()) || !isLatestVersion(candidate)) {
                continue;
            }
            SearchIndexData oldIndexData = buildIndexData(candidate);
            candidate.setLatestVersion(false);
            candidate.setVersionGroupId(versionGroupIdOf(latestDocument));
            store.put(candidate, buildIndexData(candidate), oldIndexData);
            syncLuceneIndex(candidate);
        }
    }

    /**
     * Lists the version documents.
     *
     * @param document the document value
     * @return the version documents list
     */
    private List<SearchDocument> listVersionDocuments(SearchDocument document) {
        String versionGroupId = versionGroupIdOf(document);
        String title = document.getTitle();
        return loadAllDocuments().stream()
            .filter(candidate -> sameVersionFamily(candidate, versionGroupId, title))
            .sorted(Comparator
                .comparingInt(this::versionOf).reversed()
                .thenComparing(SearchDocument::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    /**
     * Converts the value to version item.
     *
     * @param document the document value
     * @return the converted version item
     */
    private SearchDocumentVersionItem toVersionItem(SearchDocument document) {
        return new SearchDocumentVersionItem(
            document.getDocId(),
            versionGroupIdOf(document),
            versionOf(document),
            isLatestVersion(document),
            document.getTitle(),
            document.getSource(),
            document.getDate(),
            document.getFileName(),
            document.getDocumentType(),
            document.getFileSize(),
            document.getUploadedAt(),
            document.getUpdatedAt(),
            "/api/v1/search/documents/" + document.getDocId(),
            "/api/v1/search/documents/" + document.getDocId() + "/file"
        );
    }

    /**
     * Performs the file resource for operation.
     *
     * @param document the document value
     * @return the operation result
     */
    private Optional<DocumentFileResource> fileResourceFor(SearchDocument document) {
        if (isBlank(document.getFilePath())) {
            return Optional.empty();
        }
        Path file = Path.of(document.getFilePath()).toAbsolutePath().normalize();
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(new DocumentFileResource(
            new FileSystemResource(file),
            document.getFileName(),
            document.getDocumentType()
        ));
    }

    /**
     * Deletes the original file.
     *
     * @param document the document value
     */
    private void deleteOriginalFile(SearchDocument document) {
        if (document == null || isBlank(document.getFilePath())) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(document.getFilePath()).toAbsolutePath().normalize());
        } catch (IOException ex) {
            log.warn("Failed to delete uploaded file for document {}: {}",
                document.getDocId(), ex.getMessage(), ex);
        }
    }

    /**
     * Returns whether same version family.
     *
     * @param document the document value
     * @param versionGroupId the version group id value
     * @param title the title value
     * @return whether the condition is satisfied
     */
    private boolean sameVersionFamily(SearchDocument document, String versionGroupId, String title) {
        return versionGroupIdOf(document).equals(versionGroupId)
            || (!isBlank(title) && normalizeText(document.getTitle()).equals(normalizeText(title)));
    }

    /**
     * Performs the version group id of operation.
     *
     * @param document the document value
     * @return the operation result
     */
    private String versionGroupIdOf(SearchDocument document) {
        if (document == null) {
            return "";
        }
        if (!isBlank(document.getVersionGroupId())) {
            return document.getVersionGroupId().trim();
        }
        return document.getDocId();
    }

    /**
     * Performs the version of operation.
     *
     * @param document the document value
     * @return the operation result
     */
    private int versionOf(SearchDocument document) {
        if (document == null || document.getVersion() == null || document.getVersion() <= 0) {
            return 1;
        }
        return document.getVersion();
    }

    /**
     * Returns whether is latest version.
     *
     * @param document the document value
     * @return whether the condition is satisfied
     */
    private boolean isLatestVersion(SearchDocument document) {
        return document == null || !Boolean.FALSE.equals(document.getLatestVersion());
    }

    private boolean isSqlDocument(SearchDocument document) {
        if (document == null) {
            return false;
        }
        return "sql".equals(normalizeText(document.getDocumentType()))
            || "sql".equals(extensionOf(document.getFileName()));
    }

    private void refreshDocumentContent(SearchDocument document) {
        long startedAt = System.nanoTime();
        String extractedContent = "";
        if (!isBlank(document.getFilePath())) {
            Path file = Path.of(document.getFilePath());
            String fileName = !isBlank(document.getFileName()) ? document.getFileName() : file.getFileName().toString();
            boolean fileExists = Files.exists(file);
            boolean supported = textExtractor.supports(fileName);
            log.info(
                "search_reindex_extract_start docId={} title={} fileName={} fileExists={} extractorSupported={}",
                document.getDocId(),
                safeLogValue(document.getTitle(), 80),
                safeLogValue(fileName, 80),
                fileExists,
                supported
            );
            if (fileExists && supported) {
                extractedContent = textExtractor.extractText(file, fileName);
            }
        } else {
            log.info(
                "search_reindex_extract_start docId={} title={} fileName={} fileExists=false extractorSupported=false source=stored_content",
                document.getDocId(),
                safeLogValue(document.getTitle(), 80),
                safeLogValue(document.getFileName(), 80)
            );
        }
        String content = !isBlank(extractedContent) ? extractedContent : nullToEmpty(document.getContent());
        if (isBlank(content)) {
            log.warn(
                "search_reindex_extract_failed docId={} title={} fileName={} durationMs={} error=empty_content",
                document.getDocId(),
                safeLogValue(document.getTitle(), 80),
                safeLogValue(document.getFileName(), 80),
                elapsedMs(startedAt)
            );
            throw new IllegalArgumentException("document content is empty and original file is unavailable");
        }
        document.setContent(content);
        document.setKeywords(keywordExtractor.mergeKeywords(cleanList(document.getKeywords()), content));
        document.setDocumentType(resolveDocumentType(document.getDocumentType(), document.getFileName()));
        log.info(
            "search_reindex_extract_complete docId={} extractedChars={} finalContentChars={} keywords={} documentType={} durationMs={}",
            document.getDocId(),
            lengthOf(extractedContent),
            lengthOf(content),
            cleanList(document.getKeywords()).size(),
            safeLogValue(document.getDocumentType(), 40),
            elapsedMs(startedAt)
        );
    }

    /**
     * Builds the index data.
     *
     * @param document the document value
     * @return the built index data
     */
    private SearchIndexData buildIndexData(SearchDocument document) {
        Set<String> keywords = new LinkedHashSet<>();
        keywords.addAll(TitleAwareTerms.extract(tokenizer, document.getTitle(), document.getFileName()));
        keywords.addAll(tokenizer.tokenize(document.getContent()));
        keywords.addAll(tokenizer.tokenize(document.getSource()));
        keywords.addAll(tokenizer.normalizeTerms(document.getKeywords()));
        keywords.addAll(tokenizer.normalizeTerms(document.getTags()));
        keywords.addAll(tokenizer.normalizeTerms(document.getCompanies()));
        keywords.addAll(tokenizer.normalizeTerms(document.getIndustries()));

        return new SearchIndexData(
            new ArrayList<>(keywords),
            exactTerms(document.getTags()),
            exactTerms(document.getCompanies()),
            exactTerms(document.getIndustries())
        );
    }

    /**
     * Synchronizes the lucene index.
     *
     * @param document the document value
     */
    private void syncLuceneIndex(SearchDocument document) {
        if (!luceneStore.isAvailable() || document == null) {
            return;
        }
        try {
            if (isLatestVersion(document) && !isDeleted(document)) {
                luceneStore.indexLatest(document);
            } else {
                luceneStore.deleteDocument(document.getDocId());
            }
        } catch (Exception ex) {
            log.warn("Failed to synchronize Lucene index for document {}: {}",
                document.getDocId(), ex.getMessage(), ex);
        }
    }

    /**
     * Performs the apply filter operation.
     *
     * @param candidates the candidates value
     * @param filterTerms the filter terms value
     * @param lookup the lookup value
     * @return the operation result
     */
    private Set<String> applyFilter(Set<String> candidates,
                                    List<String> filterTerms,
                                    IndexLookup lookup) {
        if (filterTerms.isEmpty()) {
            return candidates;
        }
        Set<String> filterIds = new LinkedHashSet<>();
        for (String term : filterTerms) {
            filterIds.addAll(lookup.find(term));
        }
        if (candidates == null) {
            return filterIds;
        }
        candidates.retainAll(filterIds);
        return candidates;
    }

    /**
     * Converts the value to result.
     *
     * @param document the document value
     * @param keyword the keyword value
     * @param queryTokens the query tokens value
     * @param significantTerms the significant terms value
     * @param baseScores the base scores value
     * @param matchedKeywords the matched keywords value
     * @param luceneHits the lucene hits value
     * @return the converted result
     */
    private SearchResult toResult(SearchDocument document,
                                  String keyword,
                                  List<String> queryTokens,
                                  List<String> significantTerms,
                                  Map<String, Integer> baseScores,
                                  Map<String, Set<String>> matchedKeywords,
                                  List<LuceneSearchHit> luceneHits,
                                  Map<String, Integer> titleMemoryRawScores,
                                  Map<String, Integer> titleMemoryCalibratedScores) {
        String docId = document.getDocId();
        int baseScore = baseScores.getOrDefault(docId, 0);
        Set<String> matches = new LinkedHashSet<>(matchedKeywords.getOrDefault(docId, Set.of()));
        int titleMemoryRawScore = titleMemoryRawScores == null ? 0 : titleMemoryRawScores.getOrDefault(docId, 0);
        int titleMemoryCalibratedScore = titleMemoryCalibratedScores == null
            ? 0
            : titleMemoryCalibratedScores.getOrDefault(docId, 0);

        ScoredDocument scored = scoreDocument(
            document,
            keyword,
            significantTerms,
            queryTokens,
            baseScore,
            titleMemoryRawScore,
            titleMemoryCalibratedScore
        );
        matches.addAll(scored.matchedTerms());
        LuceneSearchHit bestHit = firstHit(luceneHits);
        List<SearchMatchedChunk> matchedChunks = toMatchedChunks(luceneHits);

        return new SearchResult(
            docId,
            document.getTitle(),
            buildSummary(bestHit == null || bestHit.chunkText() == null || bestHit.chunkText().isBlank()
                ? document.getContent()
                : bestHit.chunkText(), matches),
            document.getSource(),
            document.getDate(),
            document.getFileName(),
            document.getDocumentType(),
            "/api/v1/search/documents/" + docId,
            document.getTags(),
            document.getCompanies(),
            document.getIndustries(),
            scored.totalScore(),
            scored.breakdown(),
            new ArrayList<>(matches),
            matchedChunks,
            versionGroupIdOf(document),
            versionOf(document),
            isLatestVersion(document),
            normalizeTenant(document.getTenantId()),
            normalizeUser(document.getUserId()),
            normalizeVisibility(document.getVisibility()),
            cleanList(document.getPermissionRoles()),
            normalizeLifecycleStatus(document.getLifecycleStatus()),
            document.getIndexedAt(),
            document.getDeletedAt(),
            document.getErrorMessage()
        );
    }

    private SearchResult toFrontendQuickResult(SearchDocument document,
                                               String keyword,
                                               List<String> significantTerms,
                                               List<String> queryTokens) {
        return toFrontendQuickResult(document, keyword, significantTerms, queryTokens, FrontendQuickCorpus.empty());
    }

    private SearchResult toFrontendQuickResult(SearchDocument document,
                                               String keyword,
                                               List<String> significantTerms,
                                               List<String> queryTokens,
                                               FrontendQuickCorpus corpus) {
        return toFrontendQuickResult(document, keyword, significantTerms, queryTokens, corpus, Map.of());
    }

    private SearchResult toFrontendQuickResult(SearchDocument document,
                                               String keyword,
                                               List<String> significantTerms,
                                               List<String> queryTokens,
                                               FrontendQuickCorpus corpus,
                                               Map<String, Integer> titleMemoryScores) {
        FrontendQuickScore scored = scoreFrontendDocument(
            document,
            keyword,
            significantTerms,
            queryTokens,
            corpus,
            titleMemoryScores == null ? 0 : titleMemoryScores.getOrDefault(document.getDocId(), 0)
        );
        String summary = scored.bestSnippet().isBlank()
            ? buildSummary(document.getContent(), scored.matchedTerms())
            : scored.bestSnippet();
        return new SearchResult(
            document.getDocId(),
            document.getTitle(),
            summary,
            document.getSource(),
            document.getDate(),
            document.getFileName(),
            document.getDocumentType(),
            "/api/v1/search/documents/" + document.getDocId(),
            document.getTags(),
            document.getCompanies(),
            document.getIndustries(),
            scored.score(),
            scored.breakdown(),
            new ArrayList<>(scored.matchedTerms()),
            scored.matchedChunk() == null ? List.of() : List.of(scored.matchedChunk()),
            versionGroupIdOf(document),
            versionOf(document),
            isLatestVersion(document),
            normalizeTenant(document.getTenantId()),
            normalizeUser(document.getUserId()),
            normalizeVisibility(document.getVisibility()),
            cleanList(document.getPermissionRoles()),
            normalizeLifecycleStatus(document.getLifecycleStatus()),
            document.getIndexedAt(),
            document.getDeletedAt(),
            document.getErrorMessage()
        );
    }

    private FrontendQuickScore scoreFrontendDocument(SearchDocument document,
                                                    String keyword,
                                                    List<String> significantTerms,
                                                    List<String> queryTokens) {
        return scoreFrontendDocument(document, keyword, significantTerms, queryTokens, FrontendQuickCorpus.empty());
    }

    private FrontendQuickScore scoreFrontendDocument(SearchDocument document,
                                                    String keyword,
                                                    List<String> significantTerms,
                                                    List<String> queryTokens,
                                                    FrontendQuickCorpus corpus) {
        return scoreFrontendDocument(document, keyword, significantTerms, queryTokens, corpus, 0);
    }

    private FrontendQuickScore scoreFrontendDocument(SearchDocument document,
                                                    String keyword,
                                                    List<String> significantTerms,
                                                    List<String> queryTokens,
                                                    FrontendQuickCorpus corpus,
                                                    int titleMemoryRawScore) {
        List<String> scoringTerms = significantTerms == null || significantTerms.isEmpty()
            ? queryTokens
            : significantTerms;
        String phrase = normalizeSearchText(keyword);
        String title = normalizeSearchText(document.getTitle());
        String fileName = normalizeSearchText(document.getFileName());
        Set<String> titleTerms = titleAwareTermSet(document.getTitle());
        Set<String> fileNameTerms = titleAwareTermSet(document.getFileName());
        String source = normalizeSearchText(document.getSource());
        String content = quickContentText(document.getContent());
        Set<String> matchedTerms = new LinkedHashSet<>();
        int titleScore = 0;
        int fileNameScore = 0;
        int keywordScore = 0;
        int tagScore = 0;
        int companyScore = 0;
        int industryScore = 0;
        int contentScore = 0;
        int sourceScore = 0;
        int phraseScore = 0;
        int coveredTerms = 0;
        String bestTerm = "";
        double bm25 = frontendBm25Score(document, scoringTerms, corpus);
        int bm25Score = (int) Math.round(Math.min(220.0D, bm25 * 60.0D));

        if (!phrase.isBlank()) {
            if (containsNormalizedCompact(document.getTitle(), phrase) || containsTitleAwareTerm(titleTerms, phrase)) {
                phraseScore += TITLE_PHRASE_SCORE;
                matchedTerms.add(phrase);
                bestTerm = phrase;
            }
            if (containsNormalizedCompact(document.getFileName(), phrase) || containsTitleAwareTerm(fileNameTerms, phrase)) {
                phraseScore += FILENAME_PHRASE_SCORE;
                matchedTerms.add(phrase);
                bestTerm = phrase;
            }
            if (listContainsNormalized(document.getKeywords(), phrase)) {
                phraseScore += KEYWORD_PHRASE_SCORE;
                matchedTerms.add(phrase);
                bestTerm = phrase;
            }
            if (content.contains(phrase)) {
                phraseScore += CONTENT_PHRASE_SCORE;
                matchedTerms.add(phrase);
                bestTerm = phrase;
            }
        }

        for (String term : scoringTerms) {
            String normalizedTerm = normalizeSearchText(term);
            if (normalizedTerm.isBlank()) {
                continue;
            }
            boolean matched = false;
            if (containsNormalizedCompact(document.getTitle(), normalizedTerm) || containsTitleAwareTerm(titleTerms, normalizedTerm)) {
                titleScore += TITLE_TERM_SCORE;
                matched = true;
            }
            if (containsNormalizedCompact(document.getFileName(), normalizedTerm) || containsTitleAwareTerm(fileNameTerms, normalizedTerm)) {
                fileNameScore += FILENAME_TERM_SCORE;
                matched = true;
            }
            if (listContainsNormalized(document.getKeywords(), normalizedTerm)) {
                keywordScore += KEYWORD_TERM_SCORE;
                matched = true;
            }
            if (listContainsNormalized(document.getTags(), normalizedTerm)) {
                tagScore += TAG_TERM_SCORE;
                matched = true;
            }
            if (listContainsNormalized(document.getCompanies(), normalizedTerm)) {
                companyScore += COMPANY_TERM_SCORE;
                matched = true;
            }
            if (listContainsNormalized(document.getIndustries(), normalizedTerm)) {
                industryScore += INDUSTRY_TERM_SCORE;
                matched = true;
            }
            if (content.contains(normalizedTerm)) {
                contentScore += CONTENT_TERM_SCORE;
                matched = true;
            }
            if (source.contains(normalizedTerm)) {
                sourceScore += SOURCE_TERM_SCORE;
                matched = true;
            }
            if (matched) {
                coveredTerms++;
                matchedTerms.add(normalizedTerm);
                if (bestTerm.isBlank()) {
                    bestTerm = normalizedTerm;
                }
            }
        }

        double coverageRatio = scoringTerms == null || scoringTerms.isEmpty()
            ? 1.0D
            : (double) coveredTerms / scoringTerms.size();
        int coverageScore = (int) Math.round(coverageRatio * COVERAGE_SCORE);
        int totalScore = titleScore
            + fileNameScore
            + keywordScore
            + tagScore
            + companyScore
            + industryScore
            + contentScore
            + sourceScore
            + phraseScore
            + coverageScore;
        int titleMemoryCalibratedScore = titleMemoryRawScore <= 0
            ? 0
            : calibrateTitleMemoryScore(titleMemoryRawScore, totalScore + bm25Score);
        totalScore += titleMemoryCalibratedScore;
        Map<String, Integer> fieldScores = new HashMap<>();
        fieldScores.put("bm25", bm25Score);
        fieldScores.put("title", titleScore);
        fieldScores.put("fileName", fileNameScore);
        fieldScores.put("keywords", keywordScore);
        fieldScores.put("tags", tagScore);
        fieldScores.put("companies", companyScore);
        fieldScores.put("industries", industryScore);
        fieldScores.put("content", contentScore);
        fieldScores.put("source", sourceScore);
        fieldScores.put("phrase", phraseScore);
        fieldScores.put("coverage", coverageScore);
        fieldScores.put("baseToken", bm25Score);
        fieldScores.put("memoryRecallRaw", Math.max(0, titleMemoryRawScore));
        fieldScores.put("memoryRecall", titleMemoryCalibratedScore);
        SearchScoreBreakdown breakdown = new SearchScoreBreakdown(
            bm25Score,
            titleScore,
            keywordScore,
            tagScore,
            companyScore,
            industryScore,
            contentScore,
            sourceScore,
            phraseScore,
            coverageScore,
            coverageRatio,
            fieldScores
        );
        String snippet = quickSnippet(document.getContent(), bestTerm);
        return new FrontendQuickScore(
            totalScore + bm25Score,
            matchedTerms,
            breakdown,
            snippet,
            quickMatchedChunk(document, snippet, bestTerm, totalScore + bm25Score)
        );
    }

    private FrontendQuickCorpus frontendQuickCorpus(List<SearchDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return FrontendQuickCorpus.empty();
        }
        Map<String, FrontendQuickDocumentVector> vectors = new HashMap<>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        double totalLength = 0.0D;
        for (SearchDocument document : documents) {
            FrontendQuickDocumentVector vector = frontendQuickVector(document);
            vectors.put(document.getDocId(), vector);
            totalLength += vector.length();
            for (String term : vector.termFrequency().keySet()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }
        double averageLength = totalLength <= 0.0D ? 1.0D : totalLength / documents.size();
        return new FrontendQuickCorpus(vectors, documentFrequency, documents.size(), averageLength);
    }

    private FrontendQuickDocumentVector frontendQuickVector(SearchDocument document) {
        Map<String, Double> termFrequency = new HashMap<>();
        addFrontendWeightedTerms(termFrequency, document.getTitle(), FRONTEND_TITLE_WEIGHT);
        addFrontendWeightedIndexTerms(termFrequency, TitleAwareTerms.extract(tokenizer, document.getTitle()), FRONTEND_TITLE_WEIGHT);
        addFrontendWeightedTerms(termFrequency, document.getFileName(), FRONTEND_FILENAME_WEIGHT);
        addFrontendWeightedIndexTerms(termFrequency, TitleAwareTerms.extract(tokenizer, document.getFileName()), FRONTEND_FILENAME_WEIGHT);
        addFrontendWeightedTerms(termFrequency, document.getKeywords(), FRONTEND_KEYWORD_WEIGHT);
        addFrontendWeightedTerms(termFrequency, document.getTags(), FRONTEND_TAG_WEIGHT);
        addFrontendWeightedTerms(termFrequency, document.getCompanies(), FRONTEND_COMPANY_WEIGHT);
        addFrontendWeightedTerms(termFrequency, document.getIndustries(), FRONTEND_INDUSTRY_WEIGHT);
        addFrontendWeightedTerms(termFrequency, quickContentText(document.getContent()), FRONTEND_CONTENT_WEIGHT);
        addFrontendWeightedTerms(termFrequency, document.getSource(), FRONTEND_SOURCE_WEIGHT);
        double length = termFrequency.values().stream()
            .mapToDouble(Double::doubleValue)
            .sum();
        return new FrontendQuickDocumentVector(termFrequency, Math.max(1.0D, length));
    }

    private void addFrontendWeightedTerms(Map<String, Double> termFrequency, List<String> values, double weight) {
        if (values == null || values.isEmpty()) {
            return;
        }
        for (String value : values) {
            addFrontendWeightedTerms(termFrequency, value, weight);
        }
    }

    private void addFrontendWeightedIndexTerms(Map<String, Double> termFrequency, List<String> terms, double weight) {
        if (termFrequency == null || terms == null || terms.isEmpty() || weight <= 0.0D) {
            return;
        }
        for (String term : terms) {
            String normalized = normalizeSearchText(term);
            if (!normalized.isBlank()) {
                termFrequency.merge(normalized, weight, Double::sum);
            }
        }
    }

    private void addFrontendWeightedTerms(Map<String, Double> termFrequency, String value, double weight) {
        if (termFrequency == null || value == null || value.isBlank() || weight <= 0.0D) {
            return;
        }
        for (String token : tokenizer.searchTokens(value)) {
            String normalized = normalizeSearchText(token);
            if (!normalized.isBlank()) {
                termFrequency.merge(normalized, weight, Double::sum);
            }
        }
    }

    private double frontendBm25Score(SearchDocument document, List<String> scoringTerms, FrontendQuickCorpus corpus) {
        if (document == null || corpus == null || corpus.documentCount() <= 0 || scoringTerms == null || scoringTerms.isEmpty()) {
            return 0.0D;
        }
        FrontendQuickDocumentVector vector = corpus.vectors().get(document.getDocId());
        if (vector == null || vector.termFrequency().isEmpty()) {
            return 0.0D;
        }
        Set<String> uniqueTerms = new LinkedHashSet<>();
        scoringTerms.stream()
            .filter(Objects::nonNull)
            .map(this::normalizeSearchText)
            .filter(term -> !term.isBlank())
            .forEach(uniqueTerms::add);
        double score = 0.0D;
        double averageLength = Math.max(1.0D, corpus.averageLength());
        for (String term : uniqueTerms) {
            double tf = vector.termFrequency().getOrDefault(term, 0.0D);
            if (tf <= 0.0D) {
                continue;
            }
            int df = Math.max(0, corpus.documentFrequency().getOrDefault(term, 0));
            double idf = Math.log(1.0D + (corpus.documentCount() - df + 0.5D) / (df + 0.5D));
            double denominator = tf + FRONTEND_BM25_K1 * (1.0D - FRONTEND_BM25_B + FRONTEND_BM25_B * vector.length() / averageLength);
            score += idf * (tf * (FRONTEND_BM25_K1 + 1.0D) / denominator);
        }
        return score;
    }

    private boolean isFrontendQuickRelevant(SearchResult result, List<String> significantTerms) {
        return isFrontendQuickRelevant(result, significantTerms, QueryRecallMode.HYBRID);
    }

    private boolean isFrontendQuickRelevant(SearchResult result,
                                            List<String> significantTerms,
                                            QueryRecallMode recallMode) {
        if (result == null || result.score() <= 0 || result.scoreBreakdown() == null) {
            return false;
        }
        if (hasStrongTitleSignal(result.scoreBreakdown())) {
            return true;
        }
        if (hasMemoryRecallSignal(result.scoreBreakdown())) {
            return recallMode == QueryRecallMode.MEMORY_FIRST
                || result.scoreBreakdown().coverageRatio() >= 0.5D;
        }
        int termCount = significantTerms == null || significantTerms.isEmpty() ? 0 : significantTerms.size();
        if (termCount <= 1) {
            return result.scoreBreakdown().baseTokenScore() > 0
                || result.scoreBreakdown().titleScore() > 0
                || result.scoreBreakdown().keywordScore() > 0
                || result.scoreBreakdown().phraseScore() > 0;
        }
        return result.scoreBreakdown().coverageRatio() >= frontendQuickMinCoverageRatio(termCount)
            && result.scoreBreakdown().baseTokenScore() > 0;
    }

    private boolean hasStrongTitleSignal(SearchScoreBreakdown breakdown) {
        if (breakdown == null || breakdown.phraseScore() <= 0) {
            return false;
        }
        int fileNameScore = breakdown.fieldScores() == null
            ? 0
            : breakdown.fieldScores().getOrDefault("fileName", 0);
        return breakdown.titleScore() > 0 || fileNameScore > 0;
    }

    private boolean hasMemoryRecallSignal(SearchScoreBreakdown breakdown) {
        return breakdown != null
            && breakdown.fieldScores() != null
            && breakdown.fieldScores().getOrDefault("memoryRecall", 0) > 0;
    }

    private boolean isFrontendQuickWeakRelevant(SearchResult result) {
        if (result == null || result.score() <= 0 || result.scoreBreakdown() == null) {
            return false;
        }
        SearchScoreBreakdown breakdown = result.scoreBreakdown();
        if (breakdown.baseTokenScore() > 0
            || breakdown.titleScore() > 0
            || breakdown.keywordScore() > 0
            || breakdown.tagScore() > 0
            || breakdown.phraseScore() > 0) {
            return true;
        }
        Map<String, Integer> fieldScores = breakdown.fieldScores();
        if (fieldScores == null || fieldScores.isEmpty()) {
            return false;
        }
        return fieldScores.getOrDefault("title", 0) > 0
            || fieldScores.getOrDefault("fileName", 0) > 0
            || fieldScores.getOrDefault("content", 0) > 0
            || fieldScores.getOrDefault("keyword", 0) > 0
            || fieldScores.getOrDefault("tag", 0) > 0
            || fieldScores.getOrDefault("memoryRecall", 0) > 0;
    }

    private QueryRecallMode routeQueryRecall(String keyword, List<String> significantTerms) {
        String compact = normalizeSearchText(keyword).replace(" ", "");
        if (compact.isBlank() || !TitleAwareTerms.containsCjk(compact)) {
            return QueryRecallMode.LEXICAL_FIRST;
        }
        int termCount = significantTerms == null ? 0 : significantTerms.size();
        if (compact.length() <= 8 || termCount <= 4) {
            return QueryRecallMode.MEMORY_FIRST;
        }
        return QueryRecallMode.HYBRID;
    }

    private boolean shouldRunTitleMemoryRecall(QueryRecallMode recallMode, String keyword) {
        return recallMode != QueryRecallMode.LEXICAL_FIRST
            || TitleAwareTerms.containsCjk(normalizeSearchText(keyword));
    }

    private double frontendQuickMinCoverageRatio(int termCount) {
        if (termCount <= 1) {
            return 1.0D;
        }
        if (termCount == 2) {
            return 0.5D;
        }
        if (termCount <= 5) {
            return 0.6D;
        }
        return 0.45D;
    }

    private boolean matchesQuickFilter(List<String> values, List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return true;
        }
        for (String term : terms) {
            if (listContainsNormalized(values, term)) {
                return true;
            }
        }
        return false;
    }

    private String quickContentText(String content) {
        String normalized = normalizeSearchText(content);
        if (normalized.length() <= FRONTEND_CONTENT_SCAN_CHARS) {
            return normalized;
        }
        return normalized.substring(0, FRONTEND_CONTENT_SCAN_CHARS);
    }

    private String quickSnippet(String content, String term) {
        String text = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (text.isBlank()) {
            return "";
        }
        if (term == null || term.isBlank()) {
            return text.length() <= properties.getSummaryLength()
                ? text
                : text.substring(0, properties.getSummaryLength()) + "...";
        }
        String normalizedText = text.toLowerCase(Locale.ROOT);
        String normalizedTerm = term.toLowerCase(Locale.ROOT);
        int index = normalizedText.indexOf(normalizedTerm);
        if (index < 0) {
            return text.length() <= properties.getSummaryLength()
                ? text
                : text.substring(0, properties.getSummaryLength()) + "...";
        }
        int start = Math.max(0, index - FRONTEND_SNIPPET_RADIUS);
        int end = Math.min(text.length(), index + normalizedTerm.length() + FRONTEND_SNIPPET_RADIUS);
        return (start > 0 ? "..." : "")
            + text.substring(start, end)
            + (end < text.length() ? "..." : "");
    }

    private SearchMatchedChunk quickMatchedChunk(SearchDocument document, String snippet, String term, int score) {
        if (snippet == null || snippet.isBlank() || term == null || term.isBlank()) {
            return null;
        }
        return new SearchMatchedChunk(
            document.getDocId(),
            document.getFileName(),
            "quick-match",
            "frontend",
            document.getDocId() + "#quick-0",
            0,
            0.0F,
            snippet,
            snippet,
            Math.max(1.0F, score),
            normalizeTenant(document.getTenantId()),
            normalizeUser(document.getUserId()),
            normalizeVisibility(document.getVisibility()),
            cleanList(document.getPermissionRoles())
        );
    }

    /**
     * Performs the first hit operation.
     *
     * @param hits the hits value
     * @return the operation result
     */
    private LuceneSearchHit firstHit(List<LuceneSearchHit> hits) {
        return hits == null || hits.isEmpty() ? null : hits.get(0);
    }

    /**
     * Converts the value to matched chunks.
     *
     * @param hits the hits value
     * @return the converted matched chunks
     */
    private List<SearchMatchedChunk> toMatchedChunks(List<LuceneSearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        return hits.stream()
            .map(hit -> new SearchMatchedChunk(
                hit.docId(),
                hit.fileName(),
                hit.section(),
                hit.chunkType(),
                hit.chunkId(),
                hit.chunkIndex(),
                hit.positionRatio(),
                hit.chunkText(),
                hit.chunkText(),
                hit.score(),
                normalizeTenant(hit.tenantId()),
                normalizeUser(hit.userId()),
                normalizeVisibility(hit.visibility()),
                cleanList(hit.permissionRoles())
            ))
            .toList();
    }

    /**
     * Converts the value to library item.
     *
     * @param document the document value
     * @return the converted library item
     */
    private LibraryDocumentItem toLibraryItem(SearchDocument document) {
        return new LibraryDocumentItem(
            document.getDocId(),
            document.getTitle(),
            buildSummary(document.getContent(), Set.of()),
            document.getSource(),
            document.getDate(),
            resolvePrimaryCategory(document),
            document.getTags(),
            document.getFileName(),
            document.getDocumentType(),
            "/api/v1/search/documents/" + document.getDocId(),
            "/api/v1/search/documents/" + document.getDocId() + "/file",
            document.getUploadedAt(),
            document.getUpdatedAt(),
            versionGroupIdOf(document),
            versionOf(document),
            isLatestVersion(document),
            normalizeTenant(document.getTenantId()),
            normalizeUser(document.getUserId()),
            normalizeVisibility(document.getVisibility()),
            cleanList(document.getPermissionRoles()),
            normalizeLifecycleStatus(document.getLifecycleStatus()),
            document.getIndexedAt(),
            document.getDeletedAt(),
            document.getErrorMessage()
        );
    }

    /**
     * Loads the all documents.
     *
     * @return the operation result
     */
    private List<SearchDocument> loadAllDocuments() {
        return store.listDocumentIds(0).stream()
            .map(store::get)
            .flatMap(Optional::stream)
            .toList();
    }

    /**
     * Loads the latest documents.
     *
     * @return the operation result
     */
    private List<SearchDocument> loadLatestDocuments() {
        return loadAllDocuments().stream()
            .filter(this::isLatestVersion)
            .filter(document -> !isDeleted(document))
            .toList();
    }

    private List<SearchDocument> loadLatestDocuments(SearchPermissionContext permissionContext) {
        SearchPermissionContext context = permissionContext == null ? SearchPermissionContext.system() : permissionContext;
        return loadLatestDocuments().stream()
            .filter(document -> canAccess(document, context))
            .toList();
    }

    /**
     * Performs the count latest documents operation.
     *
     * @return the operation result
     */
    private int countLatestDocuments() {
        return (int) loadAllDocuments().stream()
            .filter(this::isLatestVersion)
            .filter(document -> !isDeleted(document))
            .count();
    }

    private int countLatestDocuments(SearchPermissionContext permissionContext) {
        SearchPermissionContext context = permissionContext == null ? SearchPermissionContext.system() : permissionContext;
        return (int) loadAllDocuments().stream()
            .filter(this::isLatestVersion)
            .filter(document -> !isDeleted(document))
            .filter(document -> canAccess(document, context))
            .count();
    }

    private int onlineDocumentCount(SearchPermissionContext permissionContext) {
        if (!properties.isRealtimeDocumentCountEnabled()) {
            return -1;
        }
        return countLatestDocuments(permissionContext);
    }

    /**
     * Builds the categories.
     *
     * @param documents the documents value
     * @return the built categories
     */
    private List<LibraryCategory> buildCategories(List<SearchDocument> documents) {
        Map<String, Integer> counts = new HashMap<>();
        for (SearchDocument document : documents) {
            List<String> tags = document.getTags();
            if (tags == null || tags.isEmpty()) {
                counts.merge(UNCATEGORIZED, 1, Integer::sum);
                continue;
            }
            for (String tag : tags) {
                String category = normalizeCategory(tag);
                if (!category.isEmpty()) {
                    counts.merge(category, 1, Integer::sum);
                }
            }
        }
        List<LibraryCategory> categories = new ArrayList<>();
        categories.add(new LibraryCategory(ALL_CATEGORY, documents.size()));
        for (String category : store.listCategories()) {
            counts.putIfAbsent(category, 0);
        }
        counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .map(entry -> new LibraryCategory(entry.getKey(), entry.getValue()))
            .forEach(categories::add);
        return categories;
    }

    /**
     * Performs the count documents by category operation.
     *
     * @param category the category value
     * @return the operation result
     */
    private int countDocumentsByCategory(String category) {
        return (int) loadAllDocuments().stream()
            .filter(document -> matchesCategory(document, category))
            .count();
    }

    /**
     * Returns whether matches category.
     *
     * @param document the document value
     * @param category the category value
     * @return whether the condition is satisfied
     */
    private boolean matchesCategory(SearchDocument document, String category) {
        if (category.isEmpty() || ALL_CATEGORY.equals(category)) {
            return true;
        }
        if (UNCATEGORIZED.equals(category)) {
            return document.getTags() == null || document.getTags().isEmpty();
        }
        return document.getTags() != null
            && document.getTags().stream().anyMatch(tag -> normalizeCategory(tag).equals(category));
    }

    private boolean matchesLibraryTitle(SearchDocument document, String normalizedTitle) {
        if (normalizedTitle == null || normalizedTitle.isBlank()) {
            return true;
        }
        return containsNormalizedCompact(document.getTitle(), normalizedTitle)
            || containsNormalizedCompact(document.getFileName(), normalizedTitle);
    }

    /**
     * Resolves the primary category.
     *
     * @param document the document value
     * @return the resolved primary category
     */
    private String resolvePrimaryCategory(SearchDocument document) {
        if (document.getTags() == null || document.getTags().isEmpty()) {
            return UNCATEGORIZED;
        }
        return normalizeCategory(document.getTags().get(0));
    }

    /**
     * Performs the score document operation.
     *
     * @param document the document value
     * @param keyword the keyword value
     * @param significantTerms the significant terms value
     * @param queryTokens the query tokens value
     * @param baseTokenScore the base token score value
     * @return the operation result
     */
    private ScoredDocument scoreDocument(SearchDocument document,
                                         String keyword,
                                         List<String> significantTerms,
                                         List<String> queryTokens,
                                         int baseTokenScore,
                                         int titleMemoryRawScore,
                                         int titleMemoryCalibratedScore) {
        List<String> scoringTerms = significantTerms == null || significantTerms.isEmpty()
            ? queryTokens
            : significantTerms;
        String phrase = normalizeSearchText(keyword);
        Set<String> titleTerms = titleAwareTermSet(document.getTitle());
        Set<String> fileNameTerms = titleAwareTermSet(document.getFileName());
        int titleScore = 0;
        int fileNameScore = 0;
        int keywordScore = 0;
        int tagScore = 0;
        int companyScore = 0;
        int industryScore = 0;
        int contentScore = 0;
        int sourceScore = 0;
        int phraseScore = 0;
        int coveredTerms = 0;
        Set<String> matchedTerms = new LinkedHashSet<>();

        if (!phrase.isBlank()) {
            if (containsNormalized(document.getTitle(), phrase) || containsTitleAwareTerm(titleTerms, phrase)) {
                phraseScore += TITLE_PHRASE_SCORE;
                matchedTerms.add(phrase);
            }
            if (containsNormalized(document.getFileName(), phrase) || containsTitleAwareTerm(fileNameTerms, phrase)) {
                phraseScore += FILENAME_PHRASE_SCORE;
                matchedTerms.add(phrase);
            }
            if (listContainsNormalized(document.getKeywords(), phrase)) {
                phraseScore += KEYWORD_PHRASE_SCORE;
                matchedTerms.add(phrase);
            }
            if (containsNormalized(document.getContent(), phrase)) {
                phraseScore += CONTENT_PHRASE_SCORE;
                matchedTerms.add(phrase);
            }
        }

        for (String term : scoringTerms) {
            String normalizedTerm = normalizeSearchText(term);
            if (normalizedTerm.isBlank()) {
                continue;
            }
            boolean matched = false;
            if (containsNormalized(document.getTitle(), normalizedTerm) || containsTitleAwareTerm(titleTerms, normalizedTerm)) {
                titleScore += TITLE_TERM_SCORE;
                matched = true;
            }
            if (containsNormalized(document.getFileName(), normalizedTerm) || containsTitleAwareTerm(fileNameTerms, normalizedTerm)) {
                fileNameScore += FILENAME_TERM_SCORE;
                matched = true;
            }
            if (listContainsNormalized(document.getKeywords(), normalizedTerm)) {
                keywordScore += KEYWORD_TERM_SCORE;
                matched = true;
            }
            if (listContainsNormalized(document.getTags(), normalizedTerm)) {
                tagScore += TAG_TERM_SCORE;
                matched = true;
            }
            if (listContainsNormalized(document.getCompanies(), normalizedTerm)) {
                companyScore += COMPANY_TERM_SCORE;
                matched = true;
            }
            if (listContainsNormalized(document.getIndustries(), normalizedTerm)) {
                industryScore += INDUSTRY_TERM_SCORE;
                matched = true;
            }
            if (containsNormalized(document.getContent(), normalizedTerm)) {
                contentScore += CONTENT_TERM_SCORE;
                matched = true;
            }
            if (containsNormalized(document.getSource(), normalizedTerm)) {
                sourceScore += SOURCE_TERM_SCORE;
                matched = true;
            }
            if (matched) {
                coveredTerms++;
                matchedTerms.add(normalizedTerm);
            }
        }

        double coverageRatio = scoringTerms == null || scoringTerms.isEmpty()
            ? 1.0D
            : (double) coveredTerms / scoringTerms.size();
        int coverageScore = (int) Math.round(coverageRatio * COVERAGE_SCORE);
        int totalScore = baseTokenScore
            + titleScore
            + fileNameScore
            + keywordScore
            + tagScore
            + companyScore
            + industryScore
            + contentScore
            + sourceScore
            + phraseScore
            + coverageScore;
        Map<String, Integer> fieldScores = new HashMap<>();
        fieldScores.put("title", titleScore);
        fieldScores.put("fileName", fileNameScore);
        fieldScores.put("keywords", keywordScore);
        fieldScores.put("tags", tagScore);
        fieldScores.put("companies", companyScore);
        fieldScores.put("industries", industryScore);
        fieldScores.put("content", contentScore);
        fieldScores.put("source", sourceScore);
        fieldScores.put("phrase", phraseScore);
        fieldScores.put("coverage", coverageScore);
        fieldScores.put("baseToken", baseTokenScore);
        fieldScores.put("memoryRecallRaw", titleMemoryRawScore);
        fieldScores.put("memoryRecall", titleMemoryCalibratedScore);
        SearchScoreBreakdown breakdown = new SearchScoreBreakdown(
            baseTokenScore,
            titleScore,
            keywordScore,
            tagScore,
            companyScore,
            industryScore,
            contentScore,
            sourceScore,
            phraseScore,
            coverageScore,
            coverageRatio,
            fieldScores
        );
        return new ScoredDocument(totalScore, matchedTerms, breakdown);
    }

    /**
     * Performs the score for token operation.
     *
     * @param token the token value
     * @return the operation result
     */
    private int scoreForToken(String token) {
        return token.length() >= 3 ? 3 : 1;
    }

    /**
     * Returns whether is relevant result.
     *
     * @param result the result value
     * @param significantTerms the significant terms value
     * @return whether the condition is satisfied
     */
    private boolean isRelevantResult(SearchResult result, List<String> significantTerms) {
        if (result == null || result.score() <= 0) {
            return false;
        }
        SearchScoreBreakdown breakdown = result.scoreBreakdown();
        if (breakdown == null || significantTerms == null || significantTerms.isEmpty()) {
            return true;
        }
        if (breakdown.phraseScore() > 0) {
            return true;
        }
        if (hasMemoryRecallSignal(breakdown)) {
            return true;
        }
        if (breakdown.coverageRatio() <= 0.0D
            && breakdown.baseTokenScore() >= luceneRecallScoreThreshold(significantTerms.size())) {
            return true;
        }
        return breakdown.coverageRatio() >= minCoverageRatio(significantTerms.size());
    }

    private int luceneRecallScoreThreshold(int termCount) {
        if (termCount <= 1) {
            return 12;
        }
        if (termCount <= 3) {
            return 18;
        }
        return 24;
    }

    /**
     * Performs the min coverage ratio operation.
     *
     * @param termCount the term count value
     * @return the operation result
     */
    private double minCoverageRatio(int termCount) {
        if (termCount <= 1) {
            return 1.0D;
        }
        if (termCount == 2) {
            return 0.5D;
        }
        if (termCount <= 5) {
            return 0.6D;
        }
        return 0.45D;
    }

    /**
     * Performs the significant query terms operation.
     *
     * @param keyword the keyword value
     * @param queryTokens the query tokens value
     * @return the operation result
     */
    private List<String> significantQueryTerms(String keyword, List<String> queryTokens) {
        if (queryTokens == null || queryTokens.isEmpty()) {
            return List.of();
        }
        List<String> compactCjkTerms = compactCjkSignificantTerms(keyword);
        if (!compactCjkTerms.isEmpty()) {
            return compactCjkTerms;
        }
        Set<String> terms = new LinkedHashSet<>();
        queryTokens.stream()
            .filter(token -> token != null && token.length() >= 2)
            .forEach(terms::add);
        return new ArrayList<>(terms);
    }

    private List<String> compactCjkSignificantTerms(String keyword) {
        String compact = normalizeSearchText(keyword).replace(" ", "");
        if (!TitleAwareTerms.containsCjk(compact) || compact.length() < 4 || compact.length() > 8) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        terms.add(compact);
        for (int size = 2; size <= Math.min(4, compact.length()); size++) {
            for (int i = 0; i + size <= compact.length(); i++) {
                terms.add(compact.substring(i, i + size));
            }
        }
        return new ArrayList<>(terms);
    }

    /**
     * Returns whether contains normalized.
     *
     * @param value the value value
     * @param token the token value
     * @return whether the condition is satisfied
     */
    private boolean containsNormalized(String value, String token) {
        return containsNormalizedCompact(value, token);
    }

    private boolean containsNormalizedCompact(String value, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String normalizedValue = normalizeSearchText(value);
        String normalizedToken = normalizeSearchText(token);
        if (normalizedValue.isBlank() || normalizedToken.isBlank()) {
            return false;
        }
        return normalizedValue.contains(normalizedToken)
            || normalizedValue.replace(" ", "").contains(normalizedToken.replace(" ", ""));
    }

    private Set<String> titleAwareTermSet(String value) {
        Set<String> terms = new LinkedHashSet<>();
        for (String term : TitleAwareTerms.extract(tokenizer, value)) {
            String normalized = normalizeSearchText(term);
            if (!normalized.isBlank()) {
                terms.add(normalized);
                terms.add(normalized.replace(" ", ""));
            }
        }
        return terms;
    }

    private boolean containsTitleAwareTerm(Set<String> terms, String token) {
        if (terms == null || terms.isEmpty() || token == null || token.isBlank()) {
            return false;
        }
        String normalized = normalizeSearchText(token);
        return terms.contains(normalized) || terms.contains(normalized.replace(" ", ""));
    }

    /**
     * Returns whether list contains normalized.
     *
     * @param values the values value
     * @param token the token value
     * @return whether the condition is satisfied
     */
    private boolean listContainsNormalized(List<String> values, String token) {
        if (values == null || values.isEmpty() || token == null || token.isBlank()) {
            return false;
        }
        String normalizedToken = normalizeSearchText(token);
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(this::normalizeSearchText)
            .anyMatch(value -> value.equals(normalizedToken) || value.contains(normalizedToken));
    }

    /**
     * Normalizes the search text.
     *
     * @param value the value value
     * @return the operation result
     */
    private String normalizeSearchText(String value) {
        return TitleAwareTerms.normalize(value);
    }

    /**
     * Builds the summary.
     *
     * @param content the content value
     * @param matches the matches value
     * @return the built summary
     */
    private String buildSummary(String content, Set<String> matches) {
        String text = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (text.length() <= properties.getSummaryLength()) {
            return text;
        }
        for (String token : matches) {
            int index = text.toLowerCase(Locale.ROOT).indexOf(token.toLowerCase(Locale.ROOT));
            if (index >= 0) {
                int start = Math.max(0, index - 40);
                int end = Math.min(text.length(), start + properties.getSummaryLength());
                String prefix = start > 0 ? "..." : "";
                String suffix = end < text.length() ? "..." : "";
                return prefix + text.substring(start, end) + suffix;
            }
        }
        return text.substring(0, properties.getSummaryLength()) + "...";
    }

    /**
     * Performs the result comparator operation.
     *
     * @return the operation result
     */
    private Comparator<SearchResult> resultComparator() {
        return Comparator
            .comparingInt(SearchResult::score).reversed()
            .thenComparing(SearchResult::date, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(SearchResult::title, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /**
     * Performs the document comparator operation.
     *
     * @return the operation result
     */
    private Comparator<SearchDocument> documentComparator() {
        return Comparator
            .comparing(SearchDocument::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(SearchDocument::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(SearchDocument::getTitle, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /**
     * Saves the original file.
     *
     * @param file the file value
     * @param docId the doc id value
     * @param originalFileName the original file name value
     * @return the saved original file
     */
    private Path saveOriginalFile(MultipartFile file, String docId, String originalFileName) {
        try {
            Path root = Path.of(properties.getFilePath()).toAbsolutePath().normalize();
            Files.createDirectories(root);
            Path target = root.resolve(docId + "_" + originalFileName).normalize();
            if (!target.startsWith(root)) {
                throw new IllegalArgumentException("invalid file name");
            }
            file.transferTo(target);
            return target;
        } catch (IOException ex) {
            throw new IllegalStateException("failed to save uploaded file", ex);
        }
    }

    /**
     * Normalizes the limit.
     *
     * @param limit the limit value
     * @return the operation result
     */
    private int normalizeLimit(Integer limit) {
        int value = limit == null || limit <= 0 ? properties.getDefaultLimit() : limit;
        return Math.min(value, properties.getMaxLimit());
    }

    /**
     * Normalizes the page.
     *
     * @param page the page value
     * @return the operation result
     */
    private int normalizePage(Integer page) {
        return page == null || page <= 0 ? 1 : page;
    }

    /**
     * Performs the page offset operation.
     *
     * @param page the page value
     * @param pageSize the page size value
     * @return the operation result
     */
    private long pageOffset(int page, int pageSize) {
        return (long) Math.max(0, page - 1) * Math.max(1, pageSize);
    }

    /**
     * Converts the value to tal pages.
     *
     * @param total the total value
     * @param pageSize the page size value
     * @return the converted tal pages
     */
    private int totalPages(int total, int pageSize) {
        return Math.max(1, (int) Math.ceil((double) Math.max(0, total) / Math.max(1, pageSize)));
    }

    /**
     * Performs the empty search page operation.
     *
     * @param keyword the keyword value
     * @param pageSize the page size value
     * @param pageNumber the page number value
     * @param startedAt the started at value
     * @param message the message value
     * @return the operation result
     */
    private SearchPage emptySearchPage(String keyword, int pageSize, int pageNumber, long startedAt, String message) {
        return emptySearchPage(keyword, pageSize, pageNumber, startedAt, message, SearchPermissionContext.system());
    }

    private SearchPage emptySearchPage(String keyword,
                                       int pageSize,
                                       int pageNumber,
                                       long startedAt,
                                       String message,
                                       SearchPermissionContext permissionContext) {
        int documentCount = onlineDocumentCount(permissionContext);
        return new SearchPage(
            keyword,
            List.of(),
            List.of(),
            0,
            pageSize,
            pageNumber,
            pageSize,
            1,
            false,
            (System.nanoTime() - startedAt) / 1_000_000,
            documentCount,
            documentCount == 0 ? "library_empty" : message
        );
    }

    /**
     * Returns whether has keyword.
     *
     * @param keyword the keyword value
     * @return whether the condition is satisfied
     */
    private boolean hasKeyword(String keyword) {
        return keyword != null && !keyword.isBlank();
    }

    private String safeLogQuery(String keyword) {
        String value = keyword == null ? "" : keyword.replaceAll("[\\r\\n\\t]+", " ").trim();
        return value.length() <= 80 ? value : value.substring(0, 80);
    }

    private String safeLogValue(String value, int maxLength) {
        String normalized = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").trim();
        int limit = Math.max(1, maxLength);
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private int lengthOf(String value) {
        return value == null ? 0 : value.length();
    }

    /**
     * Returns whether no filters.
     *
     * @param tag the tag value
     * @param company the company value
     * @param industry the industry value
     * @return whether the condition is satisfied
     */
    private boolean noFilters(String tag, String company, String industry) {
        return isBlank(tag) && isBlank(company) && isBlank(industry);
    }

    /**
     * Returns whether contains.
     *
     * @param value the value value
     * @param token the token value
     * @return whether the condition is satisfied
     */
    private boolean contains(String value, String token) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(token);
    }

    /**
     * Normalizes the text.
     *
     * @param value the value value
     * @return the operation result
     */
    private String normalizeText(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeTenant(String value) {
        return isBlank(value) ? SearchPermissionContext.DEFAULT_TENANT : value.trim();
    }

    private String normalizeUser(String value) {
        return isBlank(value) ? SearchPermissionContext.ANONYMOUS_USER : value.trim();
    }

    private String normalizeVisibility(String value) {
        if (isBlank(value)) {
            return "tenant";
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "public", "private", "role" -> value.trim().toLowerCase(Locale.ROOT);
            default -> "tenant";
        };
    }

    private String normalizeLifecycleStatus(String value) {
        if (isBlank(value)) {
            return DocumentLifecycleStatus.INDEXED;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case DocumentLifecycleStatus.UPLOADED,
                DocumentLifecycleStatus.PARSING,
                DocumentLifecycleStatus.INDEXED,
                DocumentLifecycleStatus.FAILED,
                DocumentLifecycleStatus.DELETED -> normalized;
            default -> DocumentLifecycleStatus.INDEXED;
        };
    }

    private boolean canAccess(SearchDocument document, SearchPermissionContext permissionContext) {
        if (document == null || isDeleted(document)) {
            return false;
        }
        SearchPermissionContext context = permissionContext == null ? SearchPermissionContext.system() : permissionContext;
        if (properties.isTenantIsolationEnabled()
            && !normalizeTenant(document.getTenantId()).equals(normalizeTenant(context.tenantId()))) {
            return false;
        }
        String visibility = normalizeVisibility(document.getVisibility());
        if ("public".equals(visibility) || "tenant".equals(visibility)) {
            return true;
        }
        if (normalizeUser(document.getUserId()).equals(normalizeUser(context.userId()))) {
            return true;
        }
        return "role".equals(visibility) && intersects(cleanList(document.getPermissionRoles()), cleanList(context.roles()));
    }

    private boolean intersects(List<String> left, List<String> right) {
        if (left == null || left.isEmpty() || right == null || right.isEmpty()) {
            return false;
        }
        Set<String> normalizedRight = right.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
        return left.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .anyMatch(normalizedRight::contains);
    }

    private boolean isDeleted(SearchDocument document) {
        return document != null && DocumentLifecycleStatus.DELETED.equalsIgnoreCase(nullToEmpty(document.getLifecycleStatus()));
    }

    /**
     * Normalizes the category.
     *
     * @param value the value value
     * @return the operation result
     */
    private String normalizeCategory(String value) {
        String normalized = normalizeText(value);
        return normalized.isEmpty() ? ALL_CATEGORY : normalized;
    }

    /**
     * Performs the clean list operation.
     *
     * @param values the values value
     * @return the operation result
     */
    private List<String> cleanList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
    }

    /**
     * Parses the list.
     *
     * @param value the value value
     * @return the parsed list
     */
    private List<String> parseList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String[] parts = value.split("[,\\uFF0C;\\uFF1B\\r\\n]+");
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                values.add(part.trim());
            }
        }
        return cleanList(values);
    }

    /**
     * Performs the exact terms operation.
     *
     * @param values the values value
     * @return the operation result
     */
    private List<String> exactTerms(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = tokenizer.normalizeExactTerm(value);
            if (!normalized.isBlank()) {
                terms.add(normalized);
            }
        }
        return new ArrayList<>(terms);
    }

    /**
     * Performs the generate doc id operation.
     *
     * @return the operation result
     */
    private String generateDocId() {
        return LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * Performs the safe file name operation.
     *
     * @param fileName the file name value
     * @return the operation result
     */
    private String safeFileName(String fileName) {
        String resolved = isBlank(fileName) ? "document.txt" : Path.of(fileName).getFileName().toString();
        return resolved.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /**
     * Performs the strip extension operation.
     *
     * @param fileName the file name value
     * @return the operation result
     */
    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    /**
     * Resolves the document type.
     *
     * @param requestedType the requested type value
     * @param fileName the file name value
     * @return the resolved document type
     */
    private String resolveDocumentType(String requestedType, String fileName) {
        String normalized = normalizeText(requestedType);
        if (!normalized.isEmpty() && !"auto".equals(normalized)) {
            return switch (normalized) {
                case "markdown", "pdf", "word", "excel", "presentation", "text", "sql" -> normalized;
                case "md" -> "markdown";
                case "doc", "docx" -> "word";
                case "csv", "xls", "xlsx" -> "excel";
                case "ppt", "pptx" -> "presentation";
                case "txt" -> "text";
                default -> inferDocumentType(fileName);
            };
        }
        return inferDocumentType(fileName);
    }

    /**
     * Performs the infer document type operation.
     *
     * @param fileName the file name value
     * @return the operation result
     */
    private String inferDocumentType(String fileName) {
        String extension = extensionOf(fileName);
        return switch (extension) {
            case "md" -> "markdown";
            case "sql" -> "sql";
            case "pdf" -> "pdf";
            case "doc", "docx" -> "word";
            case "csv", "xls", "xlsx" -> "excel";
            case "ppt", "pptx" -> "presentation";
            default -> "text";
        };
    }

    /**
     * Performs the extension of operation.
     *
     * @param fileName the file name value
     * @return the operation result
     */
    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Returns whether is blank.
     *
     * @param value the value value
     * @return whether the condition is satisfied
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Performs the null to empty operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * Builds the search message.
     *
     * @param resultCount the result count value
     * @param documentCount the document count value
     * @param queryTokens the query tokens value
     * @return the built search message
     */
    private String buildSearchMessage(int resultCount, int documentCount, List<String> queryTokens) {
        if (documentCount == 0) {
            return "library_empty";
        }
        if (resultCount == 0 && !queryTokens.isEmpty()) {
            return "no_match";
        }
        if (resultCount == 0) {
            return "no_documents";
        }
        return "ok";
    }

    /**
     * Builds the library message.
     *
     * @param resultCount the result count value
     * @param documentCount the document count value
     * @param title the title value
     * @return the built library message
     */
    private String buildLibraryMessage(int resultCount, int documentCount, String title) {
        if (documentCount == 0) {
            return "library_empty";
        }
        if (resultCount == 0 && !title.isEmpty()) {
            return "title_not_found";
        }
        if (resultCount == 0) {
            return "no_documents";
        }
        return "ok";
    }

    @FunctionalInterface
    private interface IndexLookup {
        /**
         * Finds the find.
         *
         * @param term the term value
         * @return the matching find
         */
        List<String> find(String term);
    }

    private record ScoredDocument(
        int totalScore,
        Set<String> matchedTerms,
        SearchScoreBreakdown breakdown
    ) {
    }

    private record FrontendQuickScore(
        int score,
        Set<String> matchedTerms,
        SearchScoreBreakdown breakdown,
        String bestSnippet,
        SearchMatchedChunk matchedChunk
    ) {
    }

    private record FrontendQuickCorpus(
        Map<String, FrontendQuickDocumentVector> vectors,
        Map<String, Integer> documentFrequency,
        int documentCount,
        double averageLength
    ) {
        private static FrontendQuickCorpus empty() {
            return new FrontendQuickCorpus(Map.of(), Map.of(), 0, 1.0D);
        }
    }

    private record FrontendQuickDocumentVector(
        Map<String, Double> termFrequency,
        double length
    ) {
    }

    private record LuceneSearchOutcome(SearchPage page, FallbackMode fallbackMode) {

        private static LuceneSearchOutcome success(SearchPage page) {
            return new LuceneSearchOutcome(page, FallbackMode.NORMAL);
        }

        private static LuceneSearchOutcome skipped() {
            return new LuceneSearchOutcome(null, FallbackMode.NORMAL);
        }

        private static LuceneSearchOutcome emptyResult() {
            return new LuceneSearchOutcome(null, FallbackMode.EMPTY_LUCENE_RESULT);
        }

        private static LuceneSearchOutcome failure() {
            return new LuceneSearchOutcome(null, FallbackMode.LUCENE_FAILURE);
        }
    }

    private enum FallbackMode {
        NORMAL,
        EMPTY_LUCENE_RESULT,
        LUCENE_FAILURE
    }

    private enum QueryRecallMode {
        MEMORY_FIRST,
        HYBRID,
        LEXICAL_FIRST
    }
}
