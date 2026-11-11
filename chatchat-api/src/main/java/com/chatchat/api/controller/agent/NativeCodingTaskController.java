package com.chatchat.api.controller.agent;

import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.nativecline.NativeCodingTasks;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.api.assets.UserAssetService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.*;

/** Generic authenticated task API. No tenant, grant, path or Node key in DTOs. */
@RestController
@RequestMapping("/api/v1/coding/tasks")
@ConditionalOnProperty(prefix="chatchat.runtime.coding.unified",name="enabled",havingValue="true")
public final class NativeCodingTaskController {
    private final NativeCodingTasks coding;private final CodingInvocationContext identity;
    public NativeCodingTaskController(NativeCodingTasks coding,CodingInvocationContext identity){this.coding=coding;this.identity=identity;}
    public record Request(String requestId,List<String> fileIds,String instruction,String modelName,List<String> artifactTypes,Map<String,Object> resultSchema){}
    private String actor(HttpServletRequest request,String key){var value=request.getAttribute(key);if(value==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);return value.toString();}
    private KernelDataScope scope(HttpServletRequest request,String run){
        if(run==null||!run.matches("[A-Za-z0-9_-]{1,128}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return new KernelDataScope(actor(request,ApiAuthenticationFilter.CURRENT_TENANT_ID),actor(request,ApiAuthenticationFilter.CURRENT_USER_ID),null,null,run,null,Map.of());
    }
    @PostMapping public ResponseEntity<?> submit(HttpServletRequest http,@RequestBody Request request){
        if(request.requestId()==null||!request.requestId().matches("[A-Za-z0-9_-]{8,64}")||request.fileIds()!=null&&request.fileIds().size()>8)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Stable request ID and at most eight owned files required");
        String run="coding-"+UserAssetService.hash((actor(http,ApiAuthenticationFilter.CURRENT_TENANT_ID)+"\0"+actor(http,ApiAuthenticationFilter.CURRENT_USER_ID)+"\0"+request.requestId()).getBytes(StandardCharsets.UTF_8));
        var scope=scope(http,run);
        return identity.as(scope,Set.of(READ,SUBMIT),()->ResponseEntity.accepted().body(ApiResponse.success(coding.submit(scope,request.requestId(),request.fileIds(),request.instruction(),request.modelName(),request.artifactTypes(),request.resultSchema()))));
    }
    private <T>T authorized(java.util.function.Supplier<T> action){try{return action.get();}catch(com.chatchat.common.runtime.coding.CodingContractException denied){
        if(Set.of(com.chatchat.common.runtime.coding.CodingErrorCode.NOT_FOUND,com.chatchat.common.runtime.coding.CodingErrorCode.AUTHORIZATION_DENIED).contains(denied.error().code()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Task is outside this authenticated account");throw denied;
    }}
    @GetMapping("/runs/{runId}/{taskId}") public ApiResponse<?> get(HttpServletRequest http,@PathVariable("runId")String run,@PathVariable("taskId")String task){var scope=scope(http,run);return identity.as(scope,Set.of(READ),()->ApiResponse.success(authorized(()->coding.get(scope,task))));}
    @PostMapping("/runs/{runId}/{taskId}/cancel") public ApiResponse<?> cancel(HttpServletRequest http,@PathVariable("runId")String run,@PathVariable("taskId")String task){var scope=scope(http,run);return identity.as(scope,Set.of(READ,STOP),()->ApiResponse.success(authorized(()->coding.cancel(scope,task))));}
    @PostMapping("/runs/{runId}/{taskId}/resume") public ApiResponse<?> resume(HttpServletRequest http,@PathVariable("runId")String run,@PathVariable("taskId")String task){var scope=scope(http,run);return identity.as(scope,Set.of(READ,RECOVER),()->ApiResponse.success(authorized(()->coding.resume(scope,task))));}
}
