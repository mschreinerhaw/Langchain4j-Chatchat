package com.chatchat.api.controller;

import com.chatchat.knowledgebase.search.SearchDocument;
import com.chatchat.knowledgebase.search.DocumentFileResource;
import com.chatchat.knowledgebase.search.DocumentSearchEvidenceService;
import com.chatchat.knowledgebase.search.DocumentSearchRequest;
import com.chatchat.knowledgebase.search.DocumentSearchResult;
import com.chatchat.knowledgebase.search.LibraryCategory;
import com.chatchat.knowledgebase.search.LibraryPage;
import com.chatchat.knowledgebase.search.SearchMatchedChunk;
import com.chatchat.knowledgebase.search.SearchPage;
import com.chatchat.knowledgebase.search.SearchPermissionContext;
import com.chatchat.knowledgebase.search.SearchResult;
import com.chatchat.knowledgebase.search.SearchService;
import com.chatchat.knowledgebase.search.SearchDocumentVersionItem;
import com.chatchat.knowledgebase.search.SearchFeedbackEntity;
import com.chatchat.knowledgebase.search.SearchFeedbackService;
import com.chatchat.knowledgebase.search.TitleExistsResult;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/search")
@Tag(name = "AI Search", description = "Investment research document search APIs")
public class SearchController {

    private static final int SEARCH_RESULT_MAX_CHUNKS = 3;
    private static final int SEARCH_RESULT_CHUNK_MAX_CHARS = 1200;
    private static final int SEARCH_RESULT_SUMMARY_MAX_CHARS = 800;

    private final SearchService searchService;
    private final SearchFeedbackService searchFeedbackService;
    private final DocumentSearchEvidenceService documentSearchEvidenceService;

    /**
     * Searches the search.
     *
     * @param keyword the keyword value
     * @param tag the tag value
     * @param company the company value
     * @param industry the industry value
     * @param docIds the doc ids value
     * @param page the page value
     * @param pageSize the page size value
     * @param limit the limit value
     * @return the operation result
     */
    @GetMapping
    @Operation(summary = "Legacy document search endpoint")
    public ApiResponse<SearchPage> search(@RequestParam(value = "keyword", required = false) String keyword,
                                          @RequestParam(value = "tag", required = false) String tag,
                                          @RequestParam(value = "company", required = false) String company,
                                          @RequestParam(value = "industry", required = false) String industry,
                                          @RequestParam(value = "docIds", required = false) String docIds,
                                          @RequestParam(value = "page", required = false) Integer page,
                                          @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                          @RequestParam(value = "limit", required = false) Integer limit,
                                          @RequestParam(value = "tenantId", required = false) String tenantId,
                                          @RequestParam(value = "userId", required = false) String userId,
                                          @RequestParam(value = "roles", required = false) String roles) {
        return ApiResponse.success(searchService.search(
            keyword,
            tag,
            company,
            industry,
            docIds,
            page,
            pageSize == null ? limit : pageSize,
            permissionContext(tenantId, userId, roles)
        ));
    }

    @GetMapping("/frontend")
    @Operation(summary = "Search documents for the web document search page")
    public ApiResponse<SearchPage> frontendSearch(@RequestParam(value = "keyword", required = false) String keyword,
                                                  @RequestParam(value = "tag", required = false) String tag,
                                                  @RequestParam(value = "company", required = false) String company,
                                                  @RequestParam(value = "industry", required = false) String industry,
                                                  @RequestParam(value = "docIds", required = false) String docIds,
                                                  @RequestParam(value = "page", required = false) Integer page,
                                                  @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                                  @RequestParam(value = "limit", required = false) Integer limit,
                                                  @RequestParam(value = "tenantId", required = false) String tenantId,
                                                  @RequestParam(value = "userId", required = false) String userId,
                                                  @RequestParam(value = "roles", required = false) String roles) {
        SearchPage pageResult = searchService.frontendQuickSearch(
            keyword,
            tag,
            company,
            industry,
            docIds,
            page,
            pageSize == null ? limit : pageSize,
            permissionContext(tenantId, userId, roles)
        );
        return ApiResponse.success(lightweightSearchPage(pageResult));
    }

    @PostMapping
    @Operation(summary = "Compatibility endpoint for document_search evidence requests")
    public ApiResponse<DocumentSearchResult> documentSearchCompat(@RequestBody DocumentSearchRequest request) {
        return documentSearch(request);
    }

    @PostMapping("/document-search")
    @Operation(summary = "Search documents and return standard evidence chunks")
    public ApiResponse<DocumentSearchResult> documentSearch(@RequestBody DocumentSearchRequest request) {
        try {
            return ApiResponse.success(documentSearchEvidenceService.search(request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.badRequest(ex.getMessage());
        }
    }

    @PostMapping("/feedback")
    @Operation(summary = "Record search result feedback for Rocchio expansion")
    public ApiResponse<SearchFeedbackEntity> feedback(@RequestBody SearchFeedbackService.SearchFeedbackRequest request) {
        try {
            return ApiResponse.success(searchFeedbackService.record(request), "Search feedback recorded");
        } catch (IllegalArgumentException ex) {
            return ApiResponse.badRequest(ex.getMessage());
        }
    }

    /**
     * Lists the library.
     *
     * @param category the category value
     * @param title the title value
     * @param page the page value
     * @param pageSize the page size value
     * @param limit the limit value
     * @return the library list
     */
    @GetMapping("/library")
    @Operation(summary = "List research library documents by category and title")
    public ApiResponse<LibraryPage> listLibrary(@RequestParam(value = "category", required = false) String category,
                                                @RequestParam(value = "title", required = false) String title,
                                                @RequestParam(value = "page", required = false) Integer page,
                                                @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                                @RequestParam(value = "limit", required = false) Integer limit,
                                                @RequestParam(value = "tenantId", required = false) String tenantId,
                                                @RequestParam(value = "userId", required = false) String userId,
                                                @RequestParam(value = "roles", required = false) String roles) {
        return ApiResponse.success(searchService.listLibrary(
            category,
            title,
            page,
            pageSize == null ? limit : pageSize,
            permissionContext(tenantId, userId, roles)
        ));
    }

    /**
     * Creates the category.
     *
     * @param request the request value
     * @return the created category
     */
    @PostMapping("/library/categories")
    @Operation(summary = "Create one user-defined research library category")
    public ApiResponse<LibraryCategory> createCategory(@RequestBody CategoryCreateRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            return ApiResponse.badRequest("category name is required");
        }
        return ApiResponse.success(searchService.createCategory(request.name()), "Category created");
    }

    /**
     * Performs the title exists operation.
     *
     * @param title the title value
     * @return the operation result
     */
    @GetMapping("/documents/title-exists")
    @Operation(summary = "Check whether one document title already exists")
    public ApiResponse<TitleExistsResult> titleExists(@RequestParam("title") String title,
                                                      @RequestParam(value = "tenantId", required = false) String tenantId,
                                                      @RequestParam(value = "userId", required = false) String userId,
                                                      @RequestParam(value = "roles", required = false) String roles) {
        return ApiResponse.success(searchService.titleExists(title, permissionContext(tenantId, userId, roles)));
    }

    /**
     * Returns the document.
     *
     * @param docId the doc id value
     * @return the document
     */
    @GetMapping("/documents/{docId}")
    @Operation(summary = "Get one document detail")
    public ApiResponse<SearchDocument> getDocument(@PathVariable("docId") String docId,
                                                   @RequestParam(value = "tenantId", required = false) String tenantId,
                                                   @RequestParam(value = "userId", required = false) String userId,
                                                   @RequestParam(value = "roles", required = false) String roles) {
        return searchService.get(docId, permissionContext(tenantId, userId, roles))
            .map(ApiResponse::success)
            .orElseGet(() -> ApiResponse.notFound("document not found: " + docId));
    }

    /**
     * Lists the document versions.
     *
     * @param docId the doc id value
     * @return the document versions list
     */
    @GetMapping("/documents/{docId}/versions")
    @Operation(summary = "List versions of one document")
    public ApiResponse<List<SearchDocumentVersionItem>> listDocumentVersions(@PathVariable("docId") String docId,
                                                                             @RequestParam(value = "tenantId", required = false) String tenantId,
                                                                             @RequestParam(value = "userId", required = false) String userId,
                                                                             @RequestParam(value = "roles", required = false) String roles) {
        SearchPermissionContext context = permissionContext(tenantId, userId, roles);
        if (searchService.get(docId, context).isEmpty()) {
            return ApiResponse.notFound("document not found: " + docId);
        }
        return ApiResponse.success(searchService.listVersions(docId, context));
    }

    /**
     * Returns the document version.
     *
     * @param docId the doc id value
     * @param version the version value
     * @return the document version
     */
    @GetMapping("/documents/{docId}/versions/{version}")
    @Operation(summary = "Get one document version detail")
    public ApiResponse<SearchDocument> getDocumentVersion(@PathVariable("docId") String docId,
                                                          @PathVariable("version") Integer version,
                                                          @RequestParam(value = "tenantId", required = false) String tenantId,
                                                          @RequestParam(value = "userId", required = false) String userId,
                                                          @RequestParam(value = "roles", required = false) String roles) {
        return searchService.getVersion(docId, version, permissionContext(tenantId, userId, roles))
            .map(ApiResponse::success)
            .orElseGet(() -> ApiResponse.notFound("document version not found: " + docId + " v" + version));
    }

    /**
     * Deletes the document.
     *
     * @param docId the doc id value
     * @return the operation result
     */
    @DeleteMapping("/documents/{docId}")
    @Operation(summary = "Delete one uploaded document")
    public ApiResponse<Void> deleteDocument(@PathVariable("docId") String docId,
                                            @RequestParam(value = "tenantId", required = false) String tenantId,
                                            @RequestParam(value = "userId", required = false) String userId,
                                            @RequestParam(value = "roles", required = false) String roles) {
        if (!searchService.deleteDocument(docId, permissionContext(tenantId, userId, roles))) {
            return ApiResponse.notFound("document not found: " + docId);
        }
        return ApiResponse.success(null, "document deleted");
    }

    @PostMapping("/documents/{docId}/reindex")
    @Operation(summary = "Rebuild search index for one document")
    public ApiResponse<SearchDocument> reindexDocument(@PathVariable("docId") String docId,
                                                       @RequestParam(value = "tenantId", required = false) String tenantId,
                                                       @RequestParam(value = "userId", required = false) String userId,
                                                       @RequestParam(value = "roles", required = false) String roles) {
        return searchService.reindexDocument(docId, permissionContext(tenantId, userId, roles))
            .map(document -> ApiResponse.success(document, "document reindexed"))
            .orElseGet(() -> ApiResponse.notFound("document not found: " + docId));
    }

    /**
     * Returns the document file.
     *
     * @param docId the doc id value
     * @return the document file
     */
    @GetMapping("/documents/{docId}/file")
    @Operation(summary = "Get original uploaded document file")
    public ResponseEntity<Resource> getDocumentFile(@PathVariable("docId") String docId,
                                                    @RequestParam(value = "tenantId", required = false) String tenantId,
                                                    @RequestParam(value = "userId", required = false) String userId,
                                                    @RequestParam(value = "roles", required = false) String roles) {
        return searchService.getFileResource(docId, permissionContext(tenantId, userId, roles))
            .map(file -> ResponseEntity.ok()
                .contentType(mediaTypeFor(file))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encodeFileName(file.fileName()))
                .body(file.resource()))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Returns the document version file.
     *
     * @param docId the doc id value
     * @param version the version value
     * @return the document version file
     */
    @GetMapping("/documents/{docId}/versions/{version}/file")
    @Operation(summary = "Get original uploaded document file for one version")
    public ResponseEntity<Resource> getDocumentVersionFile(@PathVariable("docId") String docId,
                                                           @PathVariable("version") Integer version,
                                                           @RequestParam(value = "tenantId", required = false) String tenantId,
                                                           @RequestParam(value = "userId", required = false) String userId,
                                                           @RequestParam(value = "roles", required = false) String roles) {
        return searchService.getVersionFileResource(docId, version, permissionContext(tenantId, userId, roles))
            .map(file -> ResponseEntity.ok()
                .contentType(mediaTypeFor(file))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encodeFileName(file.fileName()))
                .body(file.resource()))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Saves the document.
     *
     * @param document the document value
     * @return the saved document
     */
    @PostMapping("/documents")
    @Operation(summary = "Create or update one searchable document")
    public ApiResponse<SearchDocument> saveDocument(@RequestBody SearchDocument document) {
        return ApiResponse.success(searchService.createOrUpdate(document), "Document indexed");
    }

    /**
     * Performs the upload document operation.
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
    @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload and index one local document")
    public ApiResponse<SearchDocument> uploadDocument(@RequestParam("file") MultipartFile file,
                                                     @RequestParam(value = "title", required = false) String title,
                                                     @RequestParam(value = "source", required = false) String source,
                                                     @RequestParam(value = "date", required = false) String date,
                                                     @RequestParam(value = "tags", required = false) String tags,
                                                     @RequestParam(value = "companies", required = false) String companies,
                                                     @RequestParam(value = "industries", required = false) String industries,
                                                     @RequestParam(value = "keywords", required = false) String keywords,
                                                     @RequestParam(value = "documentType", required = false) String documentType,
                                                     @RequestParam(value = "content", required = false) String fallbackContent,
                                                     @RequestParam(value = "tenantId", required = false) String tenantId,
                                                     @RequestParam(value = "userId", required = false) String userId,
                                                     @RequestParam(value = "roles", required = false) String roles,
                                                     @RequestParam(value = "visibility", required = false) String visibility,
                                                     @RequestParam(value = "permissionRoles", required = false) String permissionRoles) {
        SearchDocument document = searchService.upload(
            file,
            title,
            source,
            date,
            tags,
            companies,
            industries,
            keywords,
            documentType,
            fallbackContent,
            permissionContext(tenantId, userId, roles),
            visibility,
            parseCsv(permissionRoles)
        );
        return ApiResponse.success(document, "Document uploaded and indexed");
    }

    public record CategoryCreateRequest(String name) {
    }

    /**
     * Performs the media type for operation.
     *
     * @param file the file value
     * @return the operation result
     */
    private MediaType mediaTypeFor(DocumentFileResource file) {
        String fileName = file.fileName() == null ? "" : file.fileName().toLowerCase();
        if ("pdf".equals(file.documentType()) || fileName.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF;
        }
        if ("word".equals(file.documentType()) || fileName.endsWith(".doc") || fileName.endsWith(".docx")) {
            return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        }
        if ("excel".equals(file.documentType()) || fileName.endsWith(".xls") || fileName.endsWith(".xlsx")) {
            return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        }
        if ("presentation".equals(file.documentType()) || fileName.endsWith(".ppt") || fileName.endsWith(".pptx")) {
            return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.presentationml.presentation");
        }
        if ("markdown".equals(file.documentType()) || fileName.endsWith(".md")) {
            return MediaType.parseMediaType("text/markdown");
        }
        return MediaType.TEXT_PLAIN;
    }

    /**
     * Performs the encode file name operation.
     *
     * @param fileName the file name value
     * @return the operation result
     */
    private String encodeFileName(String fileName) {
        String safeName = fileName == null || fileName.isBlank() ? "document" : fileName;
        return URLEncoder.encode(safeName, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private SearchPermissionContext permissionContext(String tenantId, String userId, String roles) {
        return SearchPermissionContext.of(tenantId, userId, parseCsv(roles));
    }

    private SearchPage lightweightSearchPage(SearchPage page) {
        if (page == null || page.results() == null || page.results().isEmpty()) {
            return page;
        }
        return new SearchPage(
            page.keyword(),
            page.queryTokens(),
            page.results().stream().map(this::lightweightSearchResult).toList(),
            page.total(),
            page.limit(),
            page.page(),
            page.pageSize(),
            page.totalPages(),
            page.hasMore(),
            page.tookMs(),
            page.documentCount(),
            page.message()
        );
    }

    private SearchResult lightweightSearchResult(SearchResult result) {
        if (result == null) {
            return null;
        }
        return new SearchResult(
            result.docId(),
            result.title(),
            truncate(result.summary(), SEARCH_RESULT_SUMMARY_MAX_CHARS),
            result.source(),
            result.date(),
            result.fileName(),
            result.documentType(),
            result.detailPath(),
            result.tags(),
            result.companies(),
            result.industries(),
            result.score(),
            result.scoreBreakdown(),
            result.matchedKeywords(),
            lightweightMatchedChunks(result.matchedChunks()),
            result.versionGroupId(),
            result.version(),
            result.latestVersion(),
            result.tenantId(),
            result.userId(),
            result.visibility(),
            result.permissionRoles(),
            result.lifecycleStatus(),
            result.indexedAt(),
            result.deletedAt(),
            result.errorMessage()
        );
    }

    private List<SearchMatchedChunk> lightweightMatchedChunks(List<SearchMatchedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        return chunks.stream()
            .limit(SEARCH_RESULT_MAX_CHUNKS)
            .map(chunk -> new SearchMatchedChunk(
                chunk.fileId(),
                chunk.fileName(),
                chunk.section(),
                chunk.chunkType(),
                chunk.chunkId(),
                chunk.chunkIndex(),
                chunk.positionRatio(),
                truncate(chunk.content(), SEARCH_RESULT_CHUNK_MAX_CHARS),
                truncate(chunk.text(), SEARCH_RESULT_CHUNK_MAX_CHARS),
                chunk.score(),
                chunk.tenantId(),
                chunk.userId(),
                chunk.visibility(),
                chunk.permissionRoles()
            ))
            .toList();
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "...";
    }

    private List<String> parseCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split("[,\\uFF0C;\\uFF1B\\r\\n]+")).stream()
            .filter(part -> part != null && !part.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }
}
