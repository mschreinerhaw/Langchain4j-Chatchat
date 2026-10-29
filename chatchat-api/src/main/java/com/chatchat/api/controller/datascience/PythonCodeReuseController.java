package com.chatchat.api.controller.datascience;

import com.chatchat.api.datascience.python.PythonCodeReuseService;
import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.api.workspace.WorkspaceService;
import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/data-science/python/code-reuse")
@ConditionalOnProperty(prefix="chatchat.assets",name="enabled",havingValue="true")
@ConditionalOnProperty(prefix="chatchat.coding.analysis",name="enabled",havingValue="true")
public class PythonCodeReuseController {
    private final PythonCodeReuseService service;
    private final ObjectAccessService access;
    private final WorkspaceService workspaces;
    private PythonCodeReuseService.Actor actor(HttpServletRequest http){workspaces.requireAvailable(http,"data-science");return new PythonCodeReuseService.Actor(access.tenant(http),access.user(http),access.legacyUser(http));}
    @PostMapping public ApiResponse<?> prepare(HttpServletRequest http,@RequestBody PythonCodeReuseService.Prepare body){return ApiResponse.success(service.prepare(actor(http),body));}
    @PostMapping("/{requestId}/execute") public ApiResponse<?> execute(HttpServletRequest http,@PathVariable("requestId")String id,@RequestBody PythonCodeReuseService.Execute body){return ApiResponse.success(service.execute(actor(http),id,body));}
    @GetMapping("/{requestId}") public ApiResponse<?> status(HttpServletRequest http,@PathVariable("requestId")String id){return ApiResponse.success(service.status(actor(http),id,false));}
    @PostMapping("/{requestId}/cancel") public ApiResponse<?> cancel(HttpServletRequest http,@PathVariable("requestId")String id){return ApiResponse.success(service.status(actor(http),id,true));}
    @PostMapping("/{requestId}/apply") public ApiResponse<?> apply(HttpServletRequest http,@PathVariable("requestId")String id,@RequestBody PythonCodeReuseService.Apply body){return ApiResponse.success(service.apply(actor(http),id,body));}
}
