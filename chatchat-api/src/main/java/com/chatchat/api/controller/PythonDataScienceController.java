package com.chatchat.api.controller;

import com.chatchat.api.datascience.*;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;

import java.util.*;

@RestController @RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1+"/data-science/python")
public class PythonDataScienceController {
    private final PythonDataScienceService service;
    private final PythonCodeAssistantService codeAssistant;

    @GetMapping("/workbench") public ApiResponse<PythonDataScienceService.Workbench> workbench(HttpServletRequest request){ Scope s=scope(request); return ApiResponse.success(service.workbench(s.tenant(),s.user())); }
    @PostMapping("/assets") public ApiResponse<?> createAsset(@RequestBody PythonDataScienceService.AssetRequest body,HttpServletRequest request){ return call(()->{Scope s=scope(request);return service.createAsset(s.tenant(),s.user(),body);}); }
    @PostMapping("/scripts") public ApiResponse<?> saveScript(@RequestBody PythonDataScienceService.ScriptRequest body,HttpServletRequest request){ return call(()->{Scope s=scope(request);return service.saveScript(s.tenant(),s.user(),body);}); }
    @PostMapping("/script-folders") public ApiResponse<?> saveFolder(@RequestBody PythonDataScienceService.FolderRequest body,HttpServletRequest request){return call(()->{Scope s=scope(request);return service.saveFolder(s.tenant(),s.user(),body);});}
    @DeleteMapping("/script-folders/{id}") public ApiResponse<?> deleteFolder(@PathVariable("id") String id,HttpServletRequest request){return call(()->{Scope s=scope(request);service.deleteFolder(s.tenant(),s.user(),id);return true;});}
    @GetMapping("/scripts/{id}/versions") public ApiResponse<?> versions(@PathVariable("id") String id,HttpServletRequest request){ return call(()->{Scope s=scope(request);return service.versions(s.tenant(),s.user(),id);}); }
    @PostMapping("/scripts/{id}/execute") public ApiResponse<?> execute(@PathVariable("id") String id,@RequestBody(required=false) Map<String,Object> body,HttpServletRequest request){ return call(()->{Scope s=scope(request);Map<String,Object> requestBody=body==null?Map.of():body;Object nested=requestBody.get("parameters");Map<String,Object> parameters;if(nested instanceof Map<?,?> map){parameters=new LinkedHashMap<>();map.forEach((key,value)->parameters.put(String.valueOf(key),value));}else parameters=requestBody;String inputSchema=nested instanceof Map<?,?>?String.valueOf(requestBody.getOrDefault("inputSchema","{}")):"{}";return service.testScript(s.tenant(),s.user(),id,parameters,inputSchema);}); }
    @PostMapping("/scripts/{id}/publish") public ApiResponse<?> publish(@PathVariable("id") String id,@RequestBody PythonDataScienceService.PublishRequest body,HttpServletRequest request){ return call(()->{Scope s=scope(request);return service.publish(s.tenant(),s.user(),id,body);}); }
    @GetMapping("/models") public ApiResponse<?> models(){return ApiResponse.success(codeAssistant.models());}
    @PostMapping("/assist") public ApiResponse<?> assist(@RequestBody PythonCodeAssistantService.AssistRequest body){return call(()->codeAssistant.assist(body));}
    @PostMapping(value="/data-files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public ApiResponse<?> uploadDataFile(@RequestPart("file") MultipartFile file,@RequestParam(name="purpose",defaultValue="") String purpose,@RequestParam(name="retention",defaultValue="PERMANENT") String retention,HttpServletRequest request){return call(()->{Scope s=scope(request);return service.uploadDataFile(s.tenant(),s.user(),file,purpose,retention);});}
    @GetMapping("/data-files/{id}/download") public ResponseEntity<byte[]> downloadDataFile(@PathVariable("id") String id,HttpServletRequest request){Scope s=scope(request);var data=service.downloadDataFile(s.tenant(),s.user(),id);return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(data.content().length).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(data.fileName(),java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(data.content());}
    @DeleteMapping("/data-files/{id}") public ApiResponse<?> deleteDataFile(@PathVariable("id") String id,HttpServletRequest request){return call(()->{Scope s=scope(request);service.deleteDataFile(s.tenant(),s.user(),id);return true;});}
    @PostMapping("/system-examples/{id}/data") public ApiResponse<?> importExampleData(@PathVariable("id") String id,HttpServletRequest request){return call(()->{Scope s=scope(request);return service.importExampleData(s.tenant(),s.user(),id);});}

    private Scope scope(HttpServletRequest r){ return new Scope(attr(r,ApiAuthenticationFilter.CURRENT_TENANT_ID,"default"),first(attr(r,ApiAuthenticationFilter.CURRENT_USERNAME,""),attr(r,ApiAuthenticationFilter.CURRENT_USER_ID,"default"))); }
    private String attr(HttpServletRequest r,String key,String fallback){Object value=r.getAttribute(key);return value==null||String.valueOf(value).isBlank()?fallback:String.valueOf(value);}
    private String first(String... values){return Arrays.stream(values).filter(v->v!=null&&!v.isBlank()).findFirst().orElse("default");}
    private ApiResponse<?> call(Action action){try{return ApiResponse.success(action.run());}catch(IllegalArgumentException ex){return ApiResponse.badRequest(ex.getMessage());}catch(IllegalStateException ex){return ApiResponse.error(409,ex.getMessage());}}
    @GetMapping("/environments") public ApiResponse<?> environments(){return call(service::publishedEnvironments);}
    private interface Action{Object run();} private record Scope(String tenant,String user){}
}
