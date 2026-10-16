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
    public record CreateFolder(String path) { }
    public record RenameFile(String path, Integer expectedVersion) { }
    public record BindTask(String taskId) { }
    public record ApplyFiles(List<String> paths) { }
    @GetMapping("/library-files") public ApiResponse<?> libraryFiles(@RequestParam("kind") String kind, HttpServletRequest request) { gate(request); return ApiResponse.success(service.libraryFiles(request, kind)); }
    @GetMapping("/projects") public ApiResponse<?> projects(HttpServletRequest request) { gate(request); return ApiResponse.success(service.projects(request)); }
    @GetMapping("/reference-files") public ApiResponse<?> references(HttpServletRequest request) { gate(request); return ApiResponse.success(service.references(request)); }
    @PostMapping("/projects") public ApiResponse<?> create(@RequestBody CreateProject body, HttpServletRequest request) { gate(request); return ApiResponse.success(service.create(request, body.name(), body.dialect())); }
    @DeleteMapping("/projects/{projectId}") public ApiResponse<?> deleteProject(@PathVariable("projectId") String projectId, HttpServletRequest request) { gate(request); service.deleteProject(request, projectId); return ApiResponse.success(true); }
    @GetMapping("/projects/{projectId}") public ApiResponse<?> project(@PathVariable("projectId") String projectId, HttpServletRequest request) { gate(request); return ApiResponse.success(service.project(request, projectId)); }
    @GetMapping("/projects/{projectId}/files") public ApiResponse<?> files(@PathVariable("projectId") String projectId, HttpServletRequest request) { gate(request); return ApiResponse.success(service.files(request, projectId)); }
    @GetMapping("/projects/{projectId}/folders") public ApiResponse<?> folders(@PathVariable("projectId") String projectId, HttpServletRequest request) { gate(request); return ApiResponse.success(service.folders(request, projectId)); }
    @PostMapping("/projects/{projectId}/folders") public ApiResponse<?> createFolder(@PathVariable("projectId") String projectId, @RequestBody CreateFolder body, HttpServletRequest request) { gate(request); return ApiResponse.success(service.createFolder(request, projectId, body.path())); }
    @GetMapping("/projects/{projectId}/files/{fileId}") public ApiResponse<?> file(@PathVariable("projectId") String projectId, @PathVariable("fileId") String fileId, HttpServletRequest request) { gate(request); return ApiResponse.success(service.file(request, projectId, fileId)); }
    @PutMapping("/projects/{projectId}/files") public ApiResponse<?> save(@PathVariable("projectId") String projectId, @RequestBody SqlStudioService.FileWrite body, HttpServletRequest request) { gate(request); return ApiResponse.success(service.save(request, projectId, body)); }
    @DeleteMapping("/projects/{projectId}/files/{fileId}") public ApiResponse<?> deleteFile(@PathVariable("projectId") String projectId, @PathVariable("fileId") String fileId, @RequestParam("expectedVersion") Integer expectedVersion, HttpServletRequest request) { gate(request); service.deleteFile(request, projectId, fileId, expectedVersion); return ApiResponse.success(true); }
    @PostMapping("/projects/{projectId}/library-files") public ApiResponse<?> importLibrary(@PathVariable("projectId") String projectId, @RequestBody SqlStudioService.LibraryImport body, HttpServletRequest request) { gate(request); return ApiResponse.success(service.importLibrary(request, projectId, body)); }
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
    @PostMapping("/projects/{projectId}/generations/{generationId}/coding") public ApiResponse<?> submitCoding(@PathVariable("projectId") String projectId, @PathVariable("generationId") String generationId, HttpServletRequest request) { gate(request); return ApiResponse.success(service.submitCoding(request, projectId, generationId)); }
    @PostMapping("/projects/{projectId}/generations/{generationId}/coding/cancel") public ApiResponse<?> cancelCoding(@PathVariable("projectId") String projectId, @PathVariable("generationId") String generationId, HttpServletRequest request) { gate(request); service.cancelCoding(request, projectId, generationId); return ApiResponse.success(true); }
    @PostMapping("/projects/{projectId}/generations/{generationId}/coding/resume") public ApiResponse<?> resumeCoding(@PathVariable("projectId") String projectId,@PathVariable("generationId") String generationId,HttpServletRequest request){gate(request);return ApiResponse.success(service.resumeCoding(request,projectId,generationId));}
    @PostMapping("/projects/{projectId}/generations/{generationId}/apply") public ApiResponse<?> apply(@PathVariable("projectId") String projectId, @PathVariable("generationId") String generationId, @RequestBody ApplyFiles body, HttpServletRequest request) { gate(request); return ApiResponse.success(service.apply(request, projectId, generationId, body.paths())); }
}
