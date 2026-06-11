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
    private static final int KEYWORD_PHRASE_SCORE = 36;
    private static final int CONTENT_PHRASE_SCORE = 20;
    private static final int TITLE_TERM_SCORE = 12;
    private static final int KEYWORD_TERM_SCORE = 10;
    private static final int TAG_TERM_SCORE = 14;
    private static final int COMPANY_TERM_SCORE = 10;
    private static final int INDUSTRY_TERM_SCORE = 10;
    private static final int CONTENT_TERM_SCORE = 4;
    private static final int SOURCE_TERM_SCORE = 2;
    private static final int COVERAGE_SCORE = 20;

    private final RocksDbSearchStore store;
    private final LuceneSearchStore luceneStore;
    private final SearchTokenizer tokenizer;
    private final DocumentContentExtractor contentExtractor;
    private final SearchProperties properties;

    @PostConstruct
    public void rebuildLuceneIndex() {
        if (!luceneStore.isAvailable()) {
            return;
        }
        try {
            luceneStore.rebuildLatest(loadLatestDocuments());
        } catch (Exception ex) {
            log.warn("Failed to rebuild Lucene search index from RocksDB: {}", ex.getMessage(), ex);
        }
    }

    public SearchDocument createOrUpdate(SearchDocument request) {
        if (request == null) {
            throw new IllegalArgumentException("document payload is required");
        }
        SearchDocument document = normalizeDocument(request);
        SearchIndexData oldIndexData = store.get(document.getDocId())
            .map(this::buildIndexData)
            .orElse(null);
        SearchIndexData indexData = buildIndexData(document);
        store.put(document, indexData, oldIndexData);
        syncLuceneIndex(document);
        return document;
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
                                 String fallbackContent) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }
        if (file.getSize() > properties.getMaxUploadBytes()) {
            throw new IllegalArgumentException("file size exceeds 5MB limit");
        }
        String originalFileName = safeFileName(file.getOriginalFilename());
        if (!contentExtractor.supports(originalFileName)) {
            throw new IllegalArgumentException("unsupported file type: " + originalFileName);
        }

        String resolvedTitle = isBlank(title) ? stripExtension(originalFileName) : title.trim();
        normalizeExistingVersionFamily(resolvedTitle);
        Optional<SearchDocument> latestDocument = findLatestByTitle(resolvedTitle);
        String docId = generateDocId();
        String versionGroupId = latestDocument.map(this::versionGroupIdOf).orElse(docId);
        int version = latestDocument
            .map(document -> nextVersion(versionGroupId, resolvedTitle))
            .orElse(1);
        Path savedFile = saveOriginalFile(file, docId, originalFileName);
        String extractedContent = contentExtractor.extract(savedFile, originalFileName);
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
            .keywords(parseList(keywords))
            .fileName(originalFileName)
            .filePath(savedFile.toString())
            .documentType(resolveDocumentType(documentType, originalFileName))
            .fileSize(file.getSize())
            .uploadedAt(Instant.now().toEpochMilli())
            .updatedAt(Instant.now().toEpochMilli())
            .versionGroupId(versionGroupId)
            .version(version)
            .latestVersion(true)
            .build();

        SearchDocument saved = createOrUpdate(document);
        markPreviousVersionsNotLatest(saved);
        return saved;
    }

    public SearchPage search(String keyword, String tag, String company, String industry, Integer limit) {
        return search(keyword, tag, company, industry, null, null, limit);
    }

    public SearchPage search(String keyword, String tag, String company, String industry, String docIds, Integer limit) {
        return search(keyword, tag, company, industry, docIds, null, limit);
    }

    public SearchPage search(String keyword,
                             String tag,
                             String company,
                             String industry,
                             String docIds,
                             Integer page,
                             Integer limit) {
        long startedAt = System.nanoTime();
        int pageSize = normalizeLimit(limit);
        int pageNumber = normalizePage(page);
        List<String> queryTokens = tokenizer.searchTokens(keyword);
        List<String> scopedDocumentIds = parseList(docIds);
        if (hasKeyword(keyword)
            && queryTokens.isEmpty()
            && noFilters(tag, company, industry)
            && scopedDocumentIds.isEmpty()) {
            return emptySearchPage(keyword, pageSize, pageNumber, startedAt, "no_match");
        }
        List<String> significantTerms = significantQueryTerms(keyword, queryTokens);
        SearchPage lucenePage = searchWithLucene(
            keyword,
            tag,
            company,
            industry,
            docIds,
            pageNumber,
            pageSize,
            queryTokens,
            significantTerms
        );
        if (lucenePage != null) {
            return lucenePage;
        }
        Map<String, Integer> scores = new HashMap<>();
        Map<String, Set<String>> matchedKeywords = new HashMap<>();
        Set<String> candidates = null;

        if (!queryTokens.isEmpty()) {
            candidates = new LinkedHashSet<>();
            for (String token : queryTokens) {
                for (String docId : store.findByKeyword(token)) {
                    candidates.add(docId);
                    scores.merge(docId, scoreForToken(token), Integer::sum);
                    matchedKeywords.computeIfAbsent(docId, ignored -> new LinkedHashSet<>()).add(token);
                }
            }
        }

        candidates = applyFilter(candidates, tokenizer.splitFilter(tag), store::findByTag);
        candidates = applyFilter(candidates, tokenizer.splitFilter(company), store::findByCompany);
        candidates = applyFilter(candidates, tokenizer.splitFilter(industry), store::findByIndustry);
        candidates = applyDocumentScope(candidates, scopedDocumentIds);

        if (candidates == null) {
            candidates = new LinkedHashSet<>(store.listDocumentIds(0));
        }
        if (candidates.isEmpty() && !queryTokens.isEmpty() && noFilters(tag, company, industry) && scopedDocumentIds.isEmpty()) {
            candidates = new LinkedHashSet<>(store.listDocumentIds(0));
        }

        List<SearchResult> allResults = candidates.stream()
            .map(store::get)
            .flatMap(Optional::stream)
            .filter(this::isLatestVersion)
            .map(document -> toResult(document, keyword, queryTokens, significantTerms, scores, matchedKeywords, null))
            .filter(result -> queryTokens.isEmpty() || isRelevantResult(result, significantTerms))
            .sorted(resultComparator())
            .toList();

        List<SearchResult> results = allResults.stream()
            .skip(pageOffset(pageNumber, pageSize))
            .limit(pageSize)
            .toList();

        int documentCount = countLatestDocuments();
        int totalPages = totalPages(allResults.size(), pageSize);
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

    public LibraryPage listLibrary(String category, String title, Integer limit) {
        return listLibrary(category, title, null, limit);
    }

    public LibraryPage listLibrary(String category, String title, Integer page, Integer limit) {
        int pageSize = normalizeLimit(limit);
        int pageNumber = normalizePage(page);
        String normalizedCategory = normalizeCategory(category);
        String normalizedTitle = normalizeText(title);
        List<SearchDocument> allDocuments = loadLatestDocuments();
        List<LibraryCategory> categories = buildCategories(allDocuments);

        List<SearchDocument> matchedDocuments = allDocuments.stream()
            .filter(document -> matchesCategory(document, normalizedCategory))
            .filter(document -> normalizedTitle.isEmpty() || contains(document.getTitle(), normalizedTitle))
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

    private SearchPage searchWithLucene(String keyword,
                                        String tag,
                                        String company,
                                        String industry,
                                        String docIds,
                                        int pageNumber,
                                        int pageSize,
                                        List<String> queryTokens,
                                        List<String> significantTerms) {
        if (queryTokens.isEmpty() || !luceneStore.isAvailable()) {
            return null;
        }
        try {
            List<LuceneSearchHit> hits = luceneStore.search(keyword, properties.getLuceneMaxHits());
            if (hits.isEmpty()) {
                return null;
            }
            Map<String, List<LuceneSearchHit>> hitsByDocId = new HashMap<>();
            Map<String, Integer> scores = new HashMap<>();
            Set<String> candidates = new LinkedHashSet<>();
            for (LuceneSearchHit hit : hits) {
                candidates.add(hit.docId());
                hitsByDocId.computeIfAbsent(hit.docId(), ignored -> new ArrayList<>()).add(hit);
                scores.merge(hit.docId(), Math.max(1, Math.round(hit.score() * 10)), Math::max);
            }
            hitsByDocId.replaceAll((docId, docHits) -> docHits.stream()
                .sorted(Comparator
                    .comparingDouble(LuceneSearchHit::score)
                    .reversed()
                    .thenComparingInt(LuceneSearchHit::chunkIndex))
                .limit(Math.max(1, properties.getLuceneChunksPerDocument()))
                .toList());

            candidates = applyFilter(candidates, tokenizer.splitFilter(tag), store::findByTag);
            candidates = applyFilter(candidates, tokenizer.splitFilter(company), store::findByCompany);
            candidates = applyFilter(candidates, tokenizer.splitFilter(industry), store::findByIndustry);
            candidates = applyDocumentScope(candidates, parseList(docIds));

            Map<String, Set<String>> matchedKeywords = new HashMap<>();
            candidates.forEach(docId -> matchedKeywords.put(docId, new LinkedHashSet<>(queryTokens)));

            List<SearchResult> allResults = candidates.stream()
                .map(store::get)
                .flatMap(Optional::stream)
                .filter(this::isLatestVersion)
                .map(document -> toResult(document, keyword, queryTokens, significantTerms, scores, matchedKeywords, hitsByDocId.get(document.getDocId())))
                .filter(result -> isRelevantResult(result, significantTerms))
                .sorted(resultComparator())
                .toList();
            List<SearchResult> results = allResults.stream()
                .skip(pageOffset(pageNumber, pageSize))
                .limit(pageSize)
                .toList();
            int documentCount = countLatestDocuments();
            int totalPages = totalPages(allResults.size(), pageSize);
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
                0L,
                documentCount,
                buildSearchMessage(allResults.size(), documentCount, queryTokens)
            );
        } catch (Exception ex) {
            log.warn("Lucene search failed, falling back to RocksDB keyword search: {}", ex.getMessage(), ex);
            return null;
        }
    }

    public LibraryCategory createCategory(String name) {
        String category = normalizeCategory(name);
        if (category.isEmpty() || ALL_CATEGORY.equals(category)) {
            throw new IllegalArgumentException("category name is required");
        }
        store.putCategory(category);
        return new LibraryCategory(category, countDocumentsByCategory(category));
    }

    public TitleExistsResult titleExists(String title) {
        String normalizedTitle = normalizeText(title);
        if (normalizedTitle.isEmpty()) {
            return new TitleExistsResult(title, false, null);
        }
        return loadLatestDocuments().stream()
            .filter(document -> normalizeText(document.getTitle()).equals(normalizedTitle))
            .findFirst()
            .map(document -> new TitleExistsResult(title, true, document.getDocId()))
            .orElseGet(() -> new TitleExistsResult(title, false, null));
    }

    public Optional<SearchDocument> get(String docId) {
        if (isBlank(docId)) {
            return Optional.empty();
        }
        return store.get(docId.trim());
    }

    public List<SearchDocumentVersionItem> listVersions(String docId) {
        Optional<SearchDocument> document = get(docId);
        if (document.isEmpty()) {
            return List.of();
        }
        return listVersionDocuments(document.get()).stream()
            .map(this::toVersionItem)
            .toList();
    }

    public Optional<SearchDocument> getVersion(String docId, Integer version) {
        if (version == null || version <= 0) {
            return Optional.empty();
        }
        return get(docId).flatMap(document -> listVersionDocuments(document).stream()
            .filter(candidate -> versionOf(candidate) == version)
            .findFirst());
    }

    public Optional<DocumentFileResource> getVersionFileResource(String docId, Integer version) {
        return getVersion(docId, version).flatMap(this::fileResourceFor);
    }

    public Optional<DocumentFileResource> getFileResource(String docId) {
        return get(docId).flatMap(this::fileResourceFor);
    }

    public boolean deleteDocument(String docId) {
        Optional<SearchDocument> document = get(docId);
        if (document.isEmpty()) {
            return false;
        }
        SearchDocument target = document.get();
        boolean wasLatest = isLatestVersion(target);
        List<SearchDocument> family = listVersionDocuments(target);
        store.delete(target, buildIndexData(target));
        luceneStore.deleteDocument(target.getDocId());
        deleteOriginalFile(target);

        if (wasLatest) {
            family.stream()
                .filter(candidate -> !candidate.getDocId().equals(target.getDocId()))
                .max(Comparator
                    .comparingInt(this::versionOf)
                    .thenComparing(SearchDocument::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .ifPresent(previous -> {
                    SearchIndexData oldIndexData = buildIndexData(previous);
                    previous.setLatestVersion(true);
                    previous.setUpdatedAt(Instant.now().toEpochMilli());
                    store.put(previous, buildIndexData(previous), oldIndexData);
                    syncLuceneIndex(previous);
                });
        }
        return true;
    }

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
            .keywords(cleanList(request.getKeywords()))
            .fileName(request.getFileName())
            .filePath(request.getFilePath())
            .documentType(resolveDocumentType(request.getDocumentType(), request.getFileName()))
            .fileSize(request.getFileSize())
            .uploadedAt(request.getUploadedAt() == null ? existing.map(SearchDocument::getUploadedAt).orElse(now) : request.getUploadedAt())
            .updatedAt(now)
            .versionGroupId(versionGroupId)
            .version(version)
            .latestVersion(latestVersion)
            .build();
    }

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

    private int nextVersion(String versionGroupId, String title) {
        return loadAllDocuments().stream()
            .filter(document -> sameVersionFamily(document, versionGroupId, title))
            .mapToInt(this::versionOf)
            .max()
            .orElse(0) + 1;
    }

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

    private boolean sameVersionFamily(SearchDocument document, String versionGroupId, String title) {
        return versionGroupIdOf(document).equals(versionGroupId)
            || (!isBlank(title) && normalizeText(document.getTitle()).equals(normalizeText(title)));
    }

    private String versionGroupIdOf(SearchDocument document) {
        if (document == null) {
            return "";
        }
        if (!isBlank(document.getVersionGroupId())) {
            return document.getVersionGroupId().trim();
        }
        return document.getDocId();
    }

    private int versionOf(SearchDocument document) {
        if (document == null || document.getVersion() == null || document.getVersion() <= 0) {
            return 1;
        }
        return document.getVersion();
    }

    private boolean isLatestVersion(SearchDocument document) {
        return document == null || !Boolean.FALSE.equals(document.getLatestVersion());
    }

    private SearchIndexData buildIndexData(SearchDocument document) {
        Set<String> keywords = new LinkedHashSet<>();
        keywords.addAll(tokenizer.tokenize(document.getTitle()));
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

    private void syncLuceneIndex(SearchDocument document) {
        if (!luceneStore.isAvailable() || document == null) {
            return;
        }
        try {
            if (isLatestVersion(document)) {
                luceneStore.indexLatest(document);
            } else {
                luceneStore.deleteDocument(document.getDocId());
            }
        } catch (Exception ex) {
            log.warn("Failed to synchronize Lucene index for document {}: {}",
                document.getDocId(), ex.getMessage(), ex);
        }
    }

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

    private SearchResult toResult(SearchDocument document,
                                  String keyword,
                                  List<String> queryTokens,
                                  List<String> significantTerms,
                                  Map<String, Integer> baseScores,
                                  Map<String, Set<String>> matchedKeywords,
                                  List<LuceneSearchHit> luceneHits) {
        String docId = document.getDocId();
        int baseScore = baseScores.getOrDefault(docId, 0);
        Set<String> matches = new LinkedHashSet<>(matchedKeywords.getOrDefault(docId, Set.of()));

        ScoredDocument scored = scoreDocument(document, keyword, significantTerms, queryTokens, baseScore);
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
            isLatestVersion(document)
        );
    }

    private LuceneSearchHit firstHit(List<LuceneSearchHit> hits) {
        return hits == null || hits.isEmpty() ? null : hits.get(0);
    }

    private List<SearchMatchedChunk> toMatchedChunks(List<LuceneSearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        return hits.stream()
            .map(hit -> new SearchMatchedChunk(
                hit.chunkId(),
                hit.chunkIndex(),
                hit.chunkText(),
                hit.score()
            ))
            .toList();
    }

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
            isLatestVersion(document)
        );
    }

    private List<SearchDocument> loadAllDocuments() {
        return store.listDocumentIds(0).stream()
            .map(store::get)
            .flatMap(Optional::stream)
            .toList();
    }

    private List<SearchDocument> loadLatestDocuments() {
        return loadAllDocuments().stream()
            .filter(this::isLatestVersion)
            .toList();
    }

    private int countLatestDocuments() {
        return (int) loadAllDocuments().stream()
            .filter(this::isLatestVersion)
            .count();
    }

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

    private int countDocumentsByCategory(String category) {
        return (int) loadAllDocuments().stream()
            .filter(document -> matchesCategory(document, category))
            .count();
    }

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

    private String resolvePrimaryCategory(SearchDocument document) {
        if (document.getTags() == null || document.getTags().isEmpty()) {
            return UNCATEGORIZED;
        }
        return normalizeCategory(document.getTags().get(0));
    }

    private ScoredDocument scoreDocument(SearchDocument document,
                                         String keyword,
                                         List<String> significantTerms,
                                         List<String> queryTokens,
                                         int baseTokenScore) {
        List<String> scoringTerms = significantTerms == null || significantTerms.isEmpty()
            ? queryTokens
            : significantTerms;
        String phrase = normalizeSearchText(keyword);
        int titleScore = 0;
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
            if (containsNormalized(document.getTitle(), phrase)) {
                phraseScore += TITLE_PHRASE_SCORE;
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
            if (containsNormalized(document.getTitle(), normalizedTerm)) {
                titleScore += TITLE_TERM_SCORE;
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
        fieldScores.put("keywords", keywordScore);
        fieldScores.put("tags", tagScore);
        fieldScores.put("companies", companyScore);
        fieldScores.put("industries", industryScore);
        fieldScores.put("content", contentScore);
        fieldScores.put("source", sourceScore);
        fieldScores.put("phrase", phraseScore);
        fieldScores.put("coverage", coverageScore);
        fieldScores.put("baseToken", baseTokenScore);
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

    private int scoreForToken(String token) {
        return token.length() >= 3 ? 3 : 1;
    }

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
        return breakdown.coverageRatio() >= minCoverageRatio(significantTerms.size());
    }

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

    private List<String> significantQueryTerms(String keyword, List<String> queryTokens) {
        if (queryTokens == null || queryTokens.isEmpty()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        queryTokens.stream()
            .filter(token -> token != null && token.length() >= 2)
            .forEach(terms::add);
        return new ArrayList<>(terms);
    }

    private boolean containsNormalized(String value, String token) {
        return token != null && !token.isBlank() && normalizeSearchText(value).contains(token);
    }

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

    private String normalizeSearchText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

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

    private Comparator<SearchResult> resultComparator() {
        return Comparator
            .comparingInt(SearchResult::score).reversed()
            .thenComparing(SearchResult::date, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(SearchResult::title, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private Comparator<SearchDocument> documentComparator() {
        return Comparator
            .comparing(SearchDocument::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(SearchDocument::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(SearchDocument::getTitle, Comparator.nullsLast(Comparator.naturalOrder()));
    }

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

    private int normalizeLimit(Integer limit) {
        int value = limit == null || limit <= 0 ? properties.getDefaultLimit() : limit;
        return Math.min(value, properties.getMaxLimit());
    }

    private int normalizePage(Integer page) {
        return page == null || page <= 0 ? 1 : page;
    }

    private long pageOffset(int page, int pageSize) {
        return (long) Math.max(0, page - 1) * Math.max(1, pageSize);
    }

    private int totalPages(int total, int pageSize) {
        return Math.max(1, (int) Math.ceil((double) Math.max(0, total) / Math.max(1, pageSize)));
    }

    private SearchPage emptySearchPage(String keyword, int pageSize, int pageNumber, long startedAt, String message) {
        int documentCount = countLatestDocuments();
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

    private boolean hasKeyword(String keyword) {
        return keyword != null && !keyword.isBlank();
    }

    private boolean noFilters(String tag, String company, String industry) {
        return isBlank(tag) && isBlank(company) && isBlank(industry);
    }

    private boolean contains(String value, String token) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(token);
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeCategory(String value) {
        String normalized = normalizeText(value);
        return normalized.isEmpty() ? ALL_CATEGORY : normalized;
    }

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

    private String generateDocId() {
        return LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String safeFileName(String fileName) {
        String resolved = isBlank(fileName) ? "document.txt" : Path.of(fileName).getFileName().toString();
        return resolved.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    private String resolveDocumentType(String requestedType, String fileName) {
        String normalized = normalizeText(requestedType);
        if (!normalized.isEmpty() && !"auto".equals(normalized)) {
            return switch (normalized) {
                case "markdown", "pdf", "word", "excel", "text" -> normalized;
                case "md" -> "markdown";
                case "doc", "docx" -> "word";
                case "csv", "xls", "xlsx" -> "excel";
                case "txt" -> "text";
                default -> inferDocumentType(fileName);
            };
        }
        return inferDocumentType(fileName);
    }

    private String inferDocumentType(String fileName) {
        String extension = extensionOf(fileName);
        return switch (extension) {
            case "md" -> "markdown";
            case "pdf" -> "pdf";
            case "doc", "docx" -> "word";
            case "csv", "xls", "xlsx" -> "excel";
            default -> "text";
        };
    }

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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

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
        List<String> find(String term);
    }

    private record ScoredDocument(
        int totalScore,
        Set<String> matchedTerms,
        SearchScoreBreakdown breakdown
    ) {
    }
}
