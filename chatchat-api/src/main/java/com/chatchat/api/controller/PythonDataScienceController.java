package com.chatchat.api.controller;

import com.chatchat.api.datascience.*;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController @RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1+"/data-science/python")
public class PythonDataScienceController {
    private final PythonDataScienceService service;

    @GetMapping("/workbench") public ApiResponse<PythonDataScienceService.Workbench> workbench(HttpServletRequest request){ Scope s=scope(request); return ApiResponse.success(service.workbench(s.tenant(),s.user())); }
    @PostMapping("/assets") public ApiResponse<?> createAsset(@RequestBody PythonDataScienceService.AssetRequest body,HttpServletRequest request){ return call(()->{Scope s=scope(request);return service.createAsset(s.tenant(),s.user(),body);}); }
    @PostMapping("/scripts") public ApiResponse<?> saveScript(@RequestBody PythonDataScienceService.ScriptRequest body,HttpServletRequest request){ return call(()->{Scope s=scope(request);return service.saveScript(s.tenant(),s.user(),body);}); }
    @GetMapping("/scripts/{id}/versions") public ApiResponse<?> versions(@PathVariable String id,HttpServletRequest request){ return call(()->{Scope s=scope(request);return service.versions(s.tenant(),s.user(),id);}); }
    @PostMapping("/scripts/{id}/execute") public ApiResponse<?> execute(@PathVariable String id,@RequestBody(required=false) Map<String,Object> body,HttpServletRequest request){ return call(()->{Scope s=scope(request);return service.testScript(s.tenant(),s.user(),id,body);}); }
    @PostMapping("/scripts/{id}/publish") public ApiResponse<?> publish(@PathVariable String id,@RequestBody PythonDataScienceService.PublishRequest body,HttpServletRequest request){ return call(()->{Scope s=scope(request);return service.publish(s.tenant(),s.user(),id,body);}); }

    private Scope scope(HttpServletRequest r){ return new Scope(attr(r,ApiAuthenticationFilter.CURRENT_TENANT_ID,"default"),first(attr(r,ApiAuthenticationFilter.CURRENT_USERNAME,""),attr(r,ApiAuthenticationFilter.CURRENT_USER_ID,"default"))); }
    private String attr(HttpServletRequest r,String key,String fallback){Object value=r.getAttribute(key);return value==null||String.valueOf(value).isBlank()?fallback:String.valueOf(value);}
    private String first(String... values){return Arrays.stream(values).filter(v->v!=null&&!v.isBlank()).findFirst().orElse("default");}
    private ApiResponse<?> call(Action action){try{return ApiResponse.success(action.run());}catch(IllegalArgumentException ex){return ApiResponse.badRequest(ex.getMessage());}catch(IllegalStateException ex){return ApiResponse.error(409,ex.getMessage());}}
    @GetMapping("/environments") public ApiResponse<?> environments(){return call(service::publishedEnvironments);}
    private interface Action{Object run();} private record Scope(String tenant,String user){}
}
