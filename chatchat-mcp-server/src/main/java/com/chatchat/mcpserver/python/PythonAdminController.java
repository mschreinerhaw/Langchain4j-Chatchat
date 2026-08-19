package com.chatchat.mcpserver.python;

import com.chatchat.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import com.chatchat.mcpserver.search.McpTemplateLuceneIndexService;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/python")
public class PythonAdminController {
    private final PythonCapabilityService service;
    private final McpTemplateLuceneIndexService indexes;
    @GetMapping("/environments") public ApiResponse<?> environments(@RequestParam(name="published",defaultValue="false") boolean published){return ApiResponse.success(service.environments(published));}
    @PostMapping("/environments") public ApiResponse<?> createEnvironment(@RequestBody PythonCapabilityService.EnvironmentRequest r){return ApiResponse.success(service.saveEnvironment(r));}
    @PutMapping("/environments/{id}") public ApiResponse<?> updateEnvironment(@PathVariable("id") String id,@RequestBody PythonCapabilityService.EnvironmentRequest r){return ApiResponse.success(service.saveEnvironment(new PythonCapabilityService.EnvironmentRequest(id,r.name(),r.description(),r.dockerImage(),r.pythonVersion(),r.cpuLimit(),r.memoryLimit(),r.diskLimit(),r.tmpfsLimit(),r.runtimeUser(),r.networkPolicy(),r.networkName(),r.requirements(),r.timeoutSeconds())));}
    @PostMapping("/environments/{id}/published") public ApiResponse<?> publishEnvironment(@PathVariable("id") String id,@RequestParam(name="published") boolean published){return ApiResponse.success(service.publishEnvironment(id,published));}
    @GetMapping("/templates") public ApiResponse<?> templates(){return ApiResponse.success(service.templates());}
    @GetMapping("/templates/search") public ApiResponse<?> searchTemplates(@RequestParam(name="query",defaultValue="") String query,@RequestParam(name="categoryId",defaultValue="") String categoryId,@RequestParam(name="limit",defaultValue="20") int limit){return ApiResponse.success(service.searchTemplates(query,categoryId,limit));}
    @GetMapping("/templates/index") public ApiResponse<?> indexOverview(){return ApiResponse.success(service.indexOverview());}
    @PostMapping("/templates/index/rebuild") public ApiResponse<?> rebuildIndex(){indexes.refreshTemplateIndex();return ApiResponse.success(service.indexOverview(),"Python 模板索引已重建");}
    @PutMapping("/templates/{id}/metadata") public ApiResponse<?> updateMetadata(@PathVariable("id") String id,@RequestBody PythonCapabilityService.TemplateMetadataRequest r){PythonTemplate result=service.updateTemplateMetadata(id,r);indexes.refreshTemplateIndex();return ApiResponse.success(result);}
    @PutMapping("/templates/{id}") public ApiResponse<?> synchronize(@PathVariable("id") String id,@RequestBody PythonCapabilityService.TemplateSyncRequest r){return ApiResponse.success(service.synchronizeTemplate(id,r));}
    @PostMapping("/templates/{id}/enabled") public ApiResponse<?> enabled(@PathVariable("id") String id,@RequestParam(name="enabled") boolean enabled){PythonTemplate result=service.setTemplateEnabled(id,enabled);indexes.refreshTemplateIndex();return ApiResponse.success(result);}
    @PostMapping("/templates/{id}/execute") public ApiResponse<?> execute(@PathVariable("id") String id,@RequestBody(required=false) Map<String,Object> args){return ApiResponse.success(service.executeTemplate(id,args));}
    @PostMapping("/runtime/assets/provision") public ApiResponse<?> provision(@RequestBody PythonCapabilityService.ProvisionRequest r){return ApiResponse.success(service.provision(r));}
    @PostMapping("/runtime/preview") public ApiResponse<?> preview(@RequestBody PythonCapabilityService.PreviewRequest r){return ApiResponse.success(service.preview(r));}
}
