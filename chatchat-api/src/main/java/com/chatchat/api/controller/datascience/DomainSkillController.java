package com.chatchat.api.controller.datascience;

import com.chatchat.chat.skills.domain.DomainSkillImportTaskService;
import com.chatchat.chat.skills.domain.DomainSkillRemoteImporter;
import com.chatchat.chat.skills.domain.DomainSkillService;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/data-science/domain-skills")
public class DomainSkillController {
    private final DomainSkillService service;
    private final DomainSkillImportTaskService importTaskService;

    @GetMapping
    public ApiResponse<?> workspace(@RequestParam(value = "keyword", defaultValue = "") String keyword,
                                    @RequestParam(value = "category", defaultValue = "") String category,
                                    @RequestParam(value = "status", defaultValue = "") String status,
                                    @RequestParam(value = "page", defaultValue = "0") int page,
                                    @RequestParam(value = "pageSize", defaultValue = "12") int pageSize,
                                    HttpServletRequest request) {
        Scope scope = scope(request);
        return ApiResponse.success(service.workspace(scope.tenantId(), keyword, category, status, page, pageSize));
    }

    @PostMapping
    public ApiResponse<?> create(@RequestBody SkillRequest body, HttpServletRequest request) {
        return call(() -> {
            Scope scope = scope(request);
            requireAdmin(scope);
            return service.save(scope.tenantId(), scope.ownerId(), body.toCommand(body.id()));
        });
    }

    @PostMapping("/categories")
    public ApiResponse<?> createCategory(@RequestBody CategoryRequest body,
                                         HttpServletRequest request) {
        return call(() -> {
            Scope scope = scope(request);
            requireAdmin(scope);
            return service.createCategory(scope.tenantId(), body == null ? "" : body.name());
        });
    }

    @PutMapping("/categories/{categoryId}")
    public ApiResponse<?> renameCategory(@PathVariable("categoryId") String categoryId,
                                         @RequestBody CategoryRequest body,
                                         HttpServletRequest request) {
        return call(() -> {
            Scope scope = scope(request);
            requireAdmin(scope);
            return service.renameCategory(scope.tenantId(), categoryId, body == null ? "" : body.name());
        });
    }

    @DeleteMapping("/categories/{categoryId}")
    public ApiResponse<?> deleteCategory(@PathVariable("categoryId") String categoryId,
                                         HttpServletRequest request) {
        return call(() -> {
            Scope scope = scope(request);
            requireAdmin(scope);
            service.deleteCategory(scope.tenantId(), categoryId);
            return true;
        });
    }

    @PutMapping("/{id}")
    public ApiResponse<?> update(@PathVariable("id") String id,
                                 @RequestBody SkillRequest body,
                                 HttpServletRequest request) {
        return call(() -> {
            Scope scope = scope(request);
            requireAdmin(scope);
            return service.save(scope.tenantId(), scope.ownerId(), body.toCommand(id));
        });
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<?> importFile(@RequestPart("file") MultipartFile file,
                                     @RequestParam(value = "name", required = false) String name,
                                     @RequestParam("category") String category,
                                     HttpServletRequest request) {
        return call(() -> {
            Scope scope = scope(request);
            requireAdmin(scope);
            try {
                return importTaskService.enqueueFile(scope.tenantId(), scope.ownerId(), file.getBytes(),
                    file.getOriginalFilename(), name, category);
            } catch (IOException ex) {
                throw new IllegalArgumentException("Unable to read skill file", ex);
            }
        });
    }

    @PostMapping("/import-url")
    public ApiResponse<?> importUrl(@RequestBody ImportUrlRequest body, HttpServletRequest request) {
        return call(() -> {
            Scope scope = scope(request);
            requireAdmin(scope);
            if (body == null) throw new IllegalArgumentException("Skill URL is required");
            ImportHttpRequest http = body.request();
            DomainSkillRemoteImporter.DownloadRequest downloadRequest = http == null
                ? DomainSkillRemoteImporter.DownloadRequest.defaults()
                : new DomainSkillRemoteImporter.DownloadRequest(http.method(), http.queryParams(), http.headers(),
                    http.body(), http.allowPrivateNetwork());
            return importTaskService.enqueueUrl(scope.tenantId(), scope.ownerId(), body.url(), body.name(), body.category(),
                downloadRequest);
        });
    }

    @GetMapping("/imports/{taskId}")
    public ApiResponse<?> importStatus(@PathVariable("taskId") String taskId, HttpServletRequest request) {
        return call(() -> {
            Scope scope = scope(request);
            requireAdmin(scope);
            return importTaskService.status(scope.tenantId(), taskId);
        });
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<?> publish(@PathVariable("id") String id, HttpServletRequest request) {
        return call(() -> {
            Scope scope = scope(request);
            requireAdmin(scope);
            return service.publish(scope.tenantId(), id);
        });
    }

    @PostMapping("/{id}/recall")
    public ApiResponse<?> recall(@PathVariable("id") String id, HttpServletRequest request) {
        return call(() -> {
            Scope scope = scope(request);
            requireAdmin(scope);
            return service.recall(scope.tenantId(), id);
        });
    }

    @PostMapping("/{id}/reindex")
    public ApiResponse<?> reindex(@PathVariable("id") String id, HttpServletRequest request) {
        return call(() -> {
            Scope scope = scope(request);
            requireAdmin(scope);
            return service.reindex(scope.tenantId(), id);
        });
    }

    @PostMapping("/categories/reindex")
    public ApiResponse<?> reindexCategory(@RequestBody CategoryReindexRequest body,
                                          HttpServletRequest request) {
        return call(() -> {
            Scope scope = scope(request);
            requireAdmin(scope);
            return service.reindexCategory(scope.tenantId(), body == null ? "" : body.category());
        });
    }

    @DeleteMapping("/{id}")
    public ApiResponse<?> delete(@PathVariable("id") String id, HttpServletRequest request) {
        return call(() -> {
            Scope scope = scope(request);
            requireAdmin(scope);
            service.delete(scope.tenantId(), id);
            return true;
        });
    }

    private Scope scope(HttpServletRequest request) {
        return new Scope(attribute(request, ApiAuthenticationFilter.CURRENT_TENANT_ID, "default"),
            first(attribute(request, ApiAuthenticationFilter.CURRENT_USERNAME, ""),
                attribute(request, ApiAuthenticationFilter.CURRENT_USER_ID, "default")));
    }

    private String attribute(HttpServletRequest request, String key, String fallback) {
        Object value = request.getAttribute(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private String first(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private void requireAdmin(Scope scope) {
        if (!"admin".equalsIgnoreCase(scope.ownerId())) throw new SecurityException("Only admin can manage domain skills");
    }

    private ApiResponse<?> call(Action action) {
        try {
            return ApiResponse.success(action.run());
        } catch (IllegalArgumentException ex) {
            return ApiResponse.badRequest(ex.getMessage());
        } catch (IllegalStateException ex) {
            return ApiResponse.error(409, ex.getMessage());
        } catch (SecurityException ex) {
            return ApiResponse.error(403, ex.getMessage());
        }
    }

    private interface Action { Object run(); }

    private record Scope(String tenantId, String ownerId) {}
    private record SkillRequest(String id, String name, String category, String description, String markdownContent) {
        DomainSkillService.SaveSkillCommand toCommand(String id) {
            return new DomainSkillService.SaveSkillCommand(id, name, category, description, markdownContent);
        }
    }
    private record CategoryRequest(String name) {}
    private record CategoryReindexRequest(String category) {}
    private record ImportUrlRequest(String url, String name, String category, ImportHttpRequest request) {}
    private record ImportHttpRequest(String method, Map<String, String> queryParams, Map<String, String> headers,
                                     String body, boolean allowPrivateNetwork) {}
}
