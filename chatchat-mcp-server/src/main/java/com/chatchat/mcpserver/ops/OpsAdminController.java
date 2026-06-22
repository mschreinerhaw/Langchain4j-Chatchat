package com.chatchat.mcpserver.ops;

import com.chatchat.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
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

    @PostMapping("/refresh-tools")
    public ApiResponse<Map<String, Object>> refreshTools() {
        publisher.refresh();
        return ApiResponse.success(Map.of("refreshed", true), "Ops MCP tools refreshed");
    }
}
