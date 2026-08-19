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
@RequestMapping(AppConstants.API_V1+"/mcp/python-templates")
public class PythonTemplateController {
    private final PythonDataScienceService service;
    @GetMapping public ApiResponse<?> list(HttpServletRequest r){return ApiResponse.success(service.templates(tenant(r)));}
    @GetMapping("/search") public ApiResponse<?> search(@RequestParam String query,@RequestParam(defaultValue="10") int limit,HttpServletRequest r){return ApiResponse.success(service.search(tenant(r),query,limit));}
    @PostMapping("/{id}/execute") public ApiResponse<?> execute(@PathVariable String id,@RequestBody(required=false) Map<String,Object> body,HttpServletRequest r){try{return ApiResponse.success(service.executeTemplate(tenant(r),user(r),id,body));}catch(IllegalArgumentException ex){return ApiResponse.badRequest(ex.getMessage());}}
    @PostMapping("/{id}/enabled") public ApiResponse<?> enabled(@PathVariable String id,@RequestParam boolean enabled,HttpServletRequest r){try{return ApiResponse.success(service.setTemplateEnabled(tenant(r),id,enabled));}catch(IllegalArgumentException ex){return ApiResponse.badRequest(ex.getMessage());}catch(IllegalStateException ex){return ApiResponse.error(409,ex.getMessage());}}
    private String tenant(HttpServletRequest r){return attr(r,ApiAuthenticationFilter.CURRENT_TENANT_ID,"default");} private String user(HttpServletRequest r){return attr(r,ApiAuthenticationFilter.CURRENT_USERNAME,attr(r,ApiAuthenticationFilter.CURRENT_USER_ID,"default"));} private String attr(HttpServletRequest r,String key,String fallback){Object value=r.getAttribute(key);return value==null||String.valueOf(value).isBlank()?fallback:String.valueOf(value);}
}
