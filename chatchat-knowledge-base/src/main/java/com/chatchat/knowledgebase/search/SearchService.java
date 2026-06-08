package com.chatchat.knowledgebase.search;

import com.chatchat.knowledgebase.embedding.service.EmbeddingService;
import dev.langchain4j.data.document.Document;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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

    private final RocksDbSearchStore store;
    private final SearchTokenizer tokenizer;
    private final DocumentContentExtractor contentExtractor;
    private final SearchProperties properties;
    private final ObjectProvider<EmbeddingService> embeddingServiceProvider;

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
        embedDocument(document);
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

        String docId = generateDocId();
        Path savedFile = saveOriginalFile(file, docId, originalFileName);
        String extractedContent = contentExtractor.extract(savedFile, originalFileName);
        String content = !isBlank(extractedContent) ? extractedContent : nullToEmpty(fallbackContent);
        if (isBlank(content)) {
            throw new IllegalArgumentException("document content is empty after extraction");
        }

        SearchDocument document = SearchDocument.builder()
            .docId(docId)
            .title(isBlank(title) ? stripExtension(originalFileName) : title.trim())
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
            .build();

        return createOrUpdate(document);
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
        List<String> queryTokens = tokenizer.tokenize(keyword);
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
        candidates = applyDocumentScope(candidates, parseList(docIds));

        if (candidates == null) {
            candidates = new LinkedHashSet<>(store.listDocumentIds(0));
        }
        if (candidates.isEmpty() && !queryTokens.isEmpty() && noFilters(tag, company, industry) && parseList(docIds).isEmpty()) {
            candidates = new LinkedHashSet<>(store.listDocumentIds(0));
        }

        List<SearchResult> allResults = candidates.stream()
            .map(store::get)
            .flatMap(Optional::stream)
            .map(document -> toResult(document, queryTokens, scores, matchedKeywords))
            .filter(result -> queryTokens.isEmpty() || result.score() > 0)
            .sorted(resultComparator())
            .toList();

        List<SearchResult> results = allResults.stream()
            .skip(pageOffset(pageNumber, pageSize))
            .limit(pageSize)
            .toList();

        int documentCount = store.countDocuments();
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
        List<SearchDocument> allDocuments = loadAllDocuments();
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
        return loadAllDocuments().stream()
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

    public Optional<DocumentFileResource> getFileResource(String docId) {
        return get(docId).flatMap(document -> {
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
        });
    }

    private SearchDocument normalizeDocument(SearchDocument request) {
        String content = request.getContent() == null ? "" : request.getContent().trim();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("content is required");
        }
        long now = Instant.now().toEpochMilli();
        String docId = isBlank(request.getDocId()) ? generateDocId() : request.getDocId().trim();
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
            .uploadedAt(request.getUploadedAt() == null ? now : request.getUploadedAt())
            .updatedAt(now)
            .build();
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
                                  List<String> queryTokens,
                                  Map<String, Integer> baseScores,
                                  Map<String, Set<String>> matchedKeywords) {
        String docId = document.getDocId();
        int score = baseScores.getOrDefault(docId, 0);
        Set<String> matches = new LinkedHashSet<>(matchedKeywords.getOrDefault(docId, Set.of()));

        for (String token : queryTokens) {
            int extra = scoreDocument(document, token);
            if (extra > 0) {
                score += extra;
                matches.add(token);
            }
        }

        return new SearchResult(
            docId,
            document.getTitle(),
            buildSummary(document.getContent(), matches),
            document.getSource(),
            document.getDate(),
            document.getFileName(),
            document.getDocumentType(),
            "/api/v1/search/documents/" + docId,
            document.getTags(),
            document.getCompanies(),
            document.getIndustries(),
            score,
            new ArrayList<>(matches)
        );
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
            document.getUpdatedAt()
        );
    }

    private List<SearchDocument> loadAllDocuments() {
        return store.listDocumentIds(0).stream()
            .map(store::get)
            .flatMap(Optional::stream)
            .toList();
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

    private int scoreDocument(SearchDocument document, String token) {
        String normalizedToken = token.toLowerCase(Locale.ROOT);
        int score = 0;
        if (contains(document.getTitle(), normalizedToken)) {
            score += 8;
        }
        if (contains(document.getContent(), normalizedToken)) {
            score += 3;
        }
        if (contains(document.getSource(), normalizedToken)) {
            score += 1;
        }
        return score;
    }

    private int scoreForToken(String token) {
        return token.length() >= 3 ? 3 : 1;
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

    private void embedDocument(SearchDocument document) {
        if (!properties.isEmbeddingEnabled()) {
            return;
        }
        EmbeddingService embeddingService = embeddingServiceProvider.getIfAvailable();
        if (embeddingService == null) {
            return;
        }
        try {
            embeddingService.embedAndStore(List.of(Document.from(document.getContent())));
        } catch (Exception ex) {
            log.warn("Document {} was saved, but embedding failed: {}", document.getDocId(), ex.getMessage());
        }
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
}
