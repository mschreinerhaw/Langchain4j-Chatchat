package com.chatchat.api.controller.datascience;

import com.chatchat.api.datascience.sql.SqlStudioService;
import com.chatchat.api.workspace.WorkspaceService;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController @RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/data-science/sql")
public class SqlStudioController {
    private final SqlStudioService service;
    private final WorkspaceService workspaces;
    private void gate(HttpServletRequest request) { workspaces.requireAvailable(request, "data-science"); }
    public record CreateProject(String name, String dialect) { }
    public record RenameFile(String path, Integer expectedVersion) { }
    public record BindTask(String taskId) { }
    public record ApplyFiles(List<String> paths) { }
    @GetMapping("/projects") public ApiResponse<?> projects(HttpServletRequest request) { gate(request); return ApiResponse.success(service.projects(request)); }
    @GetMapping("/reference-files") public ApiResponse<?> references(HttpServletRequest request) { gate(request); return ApiResponse.success(service.references(request)); }
    @PostMapping("/projects") public ApiResponse<?> create(@RequestBody CreateProject body, HttpServletRequest request) { gate(request); return ApiResponse.success(service.create(request, body.name(), body.dialect())); }
    @GetMapping("/projects/{projectId}") public ApiResponse<?> project(@PathVariable("projectId") String projectId, HttpServletRequest request) { gate(request); return ApiResponse.success(service.project(request, projectId)); }
    @GetMapping("/projects/{projectId}/files") public ApiResponse<?> files(@PathVariable("projectId") String projectId, HttpServletRequest request) { gate(request); return ApiResponse.success(service.files(request, projectId)); }
    @GetMapping("/projects/{projectId}/files/{fileId}") public ApiResponse<?> file(@PathVariable("projectId") String projectId, @PathVariable("fileId") String fileId, HttpServletRequest request) { gate(request); return ApiResponse.success(service.file(request, projectId, fileId)); }
    @PutMapping("/projects/{projectId}/files") public ApiResponse<?> save(@PathVariable("projectId") String projectId, @RequestBody SqlStudioService.FileWrite body, HttpServletRequest request) { gate(request); return ApiResponse.success(service.save(request, projectId, body)); }
    @PutMapping("/projects/{projectId}/files/{fileId}/path") public ApiResponse<?> rename(@PathVariable("projectId") String projectId, @PathVariable("fileId") String fileId, @RequestBody RenameFile body, HttpServletRequest request) { gate(request); return ApiResponse.success(service.rename(request, projectId, fileId, body.path(), body.expectedVersion())); }
    @PostMapping(value="/projects/{projectId}/uploads", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<?> upload(@PathVariable("projectId") String projectId, @RequestParam("kind") String kind, @RequestParam("expectedVersion") int expectedVersion,
        @RequestPart("file") MultipartFile file, HttpServletRequest request) { gate(request); return ApiResponse.success(service.upload(request, projectId, kind, expectedVersion, file)); }
    @GetMapping("/projects/{projectId}/files/{fileId}/versions") public ApiResponse<?> versions(@PathVariable("projectId") String projectId, @PathVariable("fileId") String fileId, HttpServletRequest request) { gate(request); return ApiResponse.success(service.versions(request, projectId, fileId)); }
    @GetMapping("/projects/{projectId}/files/{fileId}/download")
    public ResponseEntity<byte[]> download(@PathVariable("projectId") String projectId, @PathVariable("fileId") String fileId, HttpServletRequest request) {
        gate(request); var file = service.download(request, projectId, fileId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.name(), StandardCharsets.UTF_8).build().toString())
            .body(file.content());
    }
    @PostMapping("/projects/{projectId}/generations") public ApiResponse<?> prepare(@PathVariable("projectId") String projectId, @RequestBody SqlStudioService.Generate body, HttpServletRequest request) { gate(request); return ApiResponse.success(service.prepare(request, projectId, body)); }
    @GetMapping("/projects/{projectId}/generations") public ApiResponse<?> generations(@PathVariable("projectId") String projectId, HttpServletRequest request) { gate(request); return ApiResponse.success(service.generations(request, projectId)); }
    @PutMapping("/projects/{projectId}/generations/{generationId}/task") public ApiResponse<?> bind(@PathVariable("projectId") String projectId, @PathVariable("generationId") String generationId, @RequestBody BindTask body, HttpServletRequest request) { gate(request); return ApiResponse.success(service.bind(request, projectId, generationId, body.taskId())); }
    @GetMapping("/projects/{projectId}/generations/{generationId}") public ApiResponse<?> result(@PathVariable("projectId") String projectId, @PathVariable("generationId") String generationId, HttpServletRequest request) { gate(request); return ApiResponse.success(service.result(request, projectId, generationId)); }
    @PostMapping("/projects/{projectId}/generations/{generationId}/apply") public ApiResponse<?> apply(@PathVariable("projectId") String projectId, @PathVariable("generationId") String generationId, @RequestBody ApplyFiles body, HttpServletRequest request) { gate(request); return ApiResponse.success(service.apply(request, projectId, generationId, body.paths())); }
}
