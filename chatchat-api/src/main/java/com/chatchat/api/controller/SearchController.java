package com.chatchat.api.controller;

import com.chatchat.knowledgebase.search.SearchDocument;
import com.chatchat.knowledgebase.search.DocumentFileResource;
import com.chatchat.knowledgebase.search.LibraryCategory;
import com.chatchat.knowledgebase.search.LibraryPage;
import com.chatchat.knowledgebase.search.SearchPage;
import com.chatchat.knowledgebase.search.SearchService;
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

@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/search")
@Tag(name = "AI Search", description = "Investment research document search APIs")
public class SearchController {

    private final SearchService searchService;

    @GetMapping
    @Operation(summary = "Search documents by keyword and filters")
    public ApiResponse<SearchPage> search(@RequestParam(value = "keyword", required = false) String keyword,
                                          @RequestParam(value = "tag", required = false) String tag,
                                          @RequestParam(value = "company", required = false) String company,
                                          @RequestParam(value = "industry", required = false) String industry,
                                          @RequestParam(value = "docIds", required = false) String docIds,
                                          @RequestParam(value = "page", required = false) Integer page,
                                          @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                          @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.success(searchService.search(keyword, tag, company, industry, docIds, page, pageSize == null ? limit : pageSize));
    }

    @GetMapping("/library")
    @Operation(summary = "List research library documents by category and title")
    public ApiResponse<LibraryPage> listLibrary(@RequestParam(value = "category", required = false) String category,
                                                @RequestParam(value = "title", required = false) String title,
                                                @RequestParam(value = "page", required = false) Integer page,
                                                @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                                @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.success(searchService.listLibrary(category, title, page, pageSize == null ? limit : pageSize));
    }

    @PostMapping("/library/categories")
    @Operation(summary = "Create one user-defined research library category")
    public ApiResponse<LibraryCategory> createCategory(@RequestBody CategoryCreateRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            return ApiResponse.badRequest("category name is required");
        }
        return ApiResponse.success(searchService.createCategory(request.name()), "Category created");
    }

    @GetMapping("/documents/title-exists")
    @Operation(summary = "Check whether one document title already exists")
    public ApiResponse<TitleExistsResult> titleExists(@RequestParam("title") String title) {
        return ApiResponse.success(searchService.titleExists(title));
    }

    @GetMapping("/documents/{docId}")
    @Operation(summary = "Get one document detail")
    public ApiResponse<SearchDocument> getDocument(@PathVariable("docId") String docId) {
        return searchService.get(docId)
            .map(ApiResponse::success)
            .orElseGet(() -> ApiResponse.notFound("document not found: " + docId));
    }

    @GetMapping("/documents/{docId}/file")
    @Operation(summary = "Get original uploaded document file")
    public ResponseEntity<Resource> getDocumentFile(@PathVariable("docId") String docId) {
        return searchService.getFileResource(docId)
            .map(file -> ResponseEntity.ok()
                .contentType(mediaTypeFor(file))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encodeFileName(file.fileName()))
                .body(file.resource()))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/documents")
    @Operation(summary = "Create or update one searchable document")
    public ApiResponse<SearchDocument> saveDocument(@RequestBody SearchDocument document) {
        return ApiResponse.success(searchService.createOrUpdate(document), "Document indexed");
    }

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
                                                     @RequestParam(value = "content", required = false) String fallbackContent) {
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
            fallbackContent
        );
        return ApiResponse.success(document, "Document uploaded and indexed");
    }

    public record CategoryCreateRequest(String name) {
    }

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
        if ("markdown".equals(file.documentType()) || fileName.endsWith(".md")) {
            return MediaType.parseMediaType("text/markdown");
        }
        return MediaType.TEXT_PLAIN;
    }

    private String encodeFileName(String fileName) {
        String safeName = fileName == null || fileName.isBlank() ? "document" : fileName;
        return URLEncoder.encode(safeName, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
