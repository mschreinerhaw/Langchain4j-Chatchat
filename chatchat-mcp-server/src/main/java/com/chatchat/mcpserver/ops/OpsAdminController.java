package com.chatchat.mcpserver.ops;

import com.chatchat.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ops")
public class OpsAdminController {

    private final SshHostConfigService hostConfigService;
    private final HttpEndpointConfigService httpEndpointConfigService;
    private final CommandTemplateService templateService;
    private final OpsMcpToolPublisher publisher;
    private final LinuxCommandService linuxCommandService;
    private final HttpRequestToolService httpRequestToolService;

    @GetMapping("/ssh-hosts")
    public ApiResponse<List<SshHostConfig>> listHosts() {
        return ApiResponse.success(hostConfigService.listAll());
    }

    @PostMapping("/ssh-hosts")
    public ApiResponse<SshHostConfig> createHost(@RequestBody SshHostConfig request) {
        SshHostConfig saved = hostConfigService.create(request);
        publisher.refresh();
        return ApiResponse.success(saved, "SSH host created");
    }

    @PutMapping("/ssh-hosts/{id}")
    public ApiResponse<SshHostConfig> updateHost(@PathVariable("id") String id,
                                                 @RequestBody SshHostConfig request) {
        SshHostConfig saved = hostConfigService.update(id, request);
        publisher.refresh();
        return ApiResponse.success(saved, "SSH host updated");
    }

    @DeleteMapping("/ssh-hosts/{id}")
    public ApiResponse<Void> deleteHost(@PathVariable("id") String id) {
        hostConfigService.delete(id);
        publisher.refresh();
        return ApiResponse.success(null, "SSH host deleted");
    }

    @PostMapping("/ssh-hosts/test")
    public ApiResponse<LinuxCommandResult> testHost(@RequestBody SshHostConfig request) {
        return ApiResponse.success(linuxCommandService.testConnection(request), "SSH host tested");
    }

    @GetMapping("/http-endpoints")
    public ApiResponse<List<HttpEndpointConfig>> listHttpEndpoints() {
        return ApiResponse.success(httpEndpointConfigService.listAll());
    }

    @PostMapping("/http-endpoints")
    public ApiResponse<HttpEndpointConfig> createHttpEndpoint(@RequestBody HttpEndpointConfig request) {
        HttpEndpointConfig saved = httpEndpointConfigService.create(request);
        publisher.refresh();
        return ApiResponse.success(saved, "HTTP endpoint created");
    }

    @PutMapping("/http-endpoints/{id}")
    public ApiResponse<HttpEndpointConfig> updateHttpEndpoint(@PathVariable("id") String id,
                                                              @RequestBody HttpEndpointConfig request) {
        HttpEndpointConfig saved = httpEndpointConfigService.update(id, request);
        publisher.refresh();
        return ApiResponse.success(saved, "HTTP endpoint updated");
    }

    @DeleteMapping("/http-endpoints/{id}")
    public ApiResponse<Void> deleteHttpEndpoint(@PathVariable("id") String id) {
        httpEndpointConfigService.delete(id);
        publisher.refresh();
        return ApiResponse.success(null, "HTTP endpoint deleted");
    }

    @PostMapping("/http-endpoints/test")
    public ApiResponse<HttpRequestToolResult> testHttpEndpoint(@RequestBody HttpEndpointConfig request) {
        return ApiResponse.success(httpRequestToolService.execute(request, Map.of("sourceTaskId", "asset-center")), "HTTP endpoint tested");
    }

    @GetMapping("/command-templates")
    public ApiResponse<List<CommandTemplateConfig>> listTemplates() {
        return ApiResponse.success(templateService.listAll());
    }

    @PostMapping("/command-templates")
    public ApiResponse<CommandTemplateConfig> createTemplate(@RequestBody CommandTemplateConfig request) {
        return ApiResponse.success(templateService.save(request), "Command template created");
    }

    @PutMapping("/command-templates/{id}")
    public ApiResponse<CommandTemplateConfig> updateTemplate(@PathVariable("id") String id,
                                                             @RequestBody CommandTemplateConfig request) {
        return ApiResponse.success(templateService.update(id, request), "Command template updated");
    }

    @DeleteMapping("/command-templates/{id}")
    public ApiResponse<Void> deleteTemplate(@PathVariable("id") String id) {
        templateService.delete(id);
        return ApiResponse.success(null, "Command template deleted");
    }

    @PostMapping("/refresh-tools")
    public ApiResponse<Map<String, Object>> refreshTools() {
        publisher.refresh();
        return ApiResponse.success(Map.of("refreshed", true), "Ops MCP tools refreshed");
    }
}
