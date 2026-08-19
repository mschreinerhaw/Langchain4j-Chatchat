package com.chatchat.mcpserver.python;

import com.chatchat.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/python")
public class PythonAdminController {
    private final PythonCapabilityService service;
    @GetMapping("/environments") public ApiResponse<?> environments(@RequestParam(defaultValue="false") boolean published){return ApiResponse.success(service.environments(published));}
    @PostMapping("/environments") public ApiResponse<?> createEnvironment(@RequestBody PythonCapabilityService.EnvironmentRequest r){return ApiResponse.success(service.saveEnvironment(r));}
    @PutMapping("/environments/{id}") public ApiResponse<?> updateEnvironment(@PathVariable String id,@RequestBody PythonCapabilityService.EnvironmentRequest r){return ApiResponse.success(service.saveEnvironment(new PythonCapabilityService.EnvironmentRequest(id,r.name(),r.description(),r.dockerImage(),r.pythonVersion(),r.cpuLimit(),r.memoryLimit(),r.timeoutSeconds(),r.networkEnabled())));}
    @PostMapping("/environments/{id}/published") public ApiResponse<?> publishEnvironment(@PathVariable String id,@RequestParam boolean published){return ApiResponse.success(service.publishEnvironment(id,published));}
    @GetMapping("/templates") public ApiResponse<?> templates(){return ApiResponse.success(service.templates());}
    @PutMapping("/templates/{id}") public ApiResponse<?> synchronize(@PathVariable String id,@RequestBody PythonCapabilityService.TemplateSyncRequest r){return ApiResponse.success(service.synchronizeTemplate(id,r));}
    @PostMapping("/templates/{id}/enabled") public ApiResponse<?> enabled(@PathVariable String id,@RequestParam boolean enabled){return ApiResponse.success(service.setTemplateEnabled(id,enabled));}
    @PostMapping("/templates/{id}/execute") public ApiResponse<?> execute(@PathVariable String id,@RequestBody(required=false) Map<String,Object> args){return ApiResponse.success(service.executeTemplate(id,args));}
    @PostMapping("/runtime/assets/provision") public ApiResponse<?> provision(@RequestBody PythonCapabilityService.ProvisionRequest r){return ApiResponse.success(service.provision(r));}
    @PostMapping("/runtime/preview") public ApiResponse<?> preview(@RequestBody PythonCapabilityService.PreviewRequest r){return ApiResponse.success(service.preview(r));}
}
