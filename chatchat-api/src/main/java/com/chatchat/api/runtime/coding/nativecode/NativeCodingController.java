package com.chatchat.api.runtime.coding.nativecode;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.runtime.coding.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Independent authenticated project API. Owner and tenant come only from existing authentication. */
@RestController
@RequestMapping("/api/v1/coding/projects")
@ConditionalOnProperty(prefix="chatchat.coding.native",name="enabled",havingValue="true")
public final class NativeCodingController {
    private final NativeCodingTaskService service;private final NativeCodingWorkspaceService workspace;
    private final DurableCodingTaskService tasks;private final CodingAgentSPI agent;private final CodingInvocationContext identity;
    public NativeCodingController(NativeCodingTaskService service,NativeCodingWorkspaceService workspace,DurableCodingTaskService tasks,
            CodingAgentSPI agent,CodingInvocationContext identity){this.service=service;this.workspace=workspace;this.tasks=tasks;this.agent=agent;this.identity=identity;}
    @PutMapping("/{requestId}") public ApiResponse<?> submit(@PathVariable String requestId,@RequestBody NativeCodingTaskService.Request body,HttpServletRequest http){
        if(!requestId.matches("[A-Za-z0-9_-]{1,96}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid request id");
        String task=taskId(owner(http),requestId);
        return with(task,http,EnumSet.allOf(CodingTaskAccess.Action.class),scope->ApiResponse.success(service.submit(scope,task,body)));
    }
    @GetMapping("/{taskId}") public ApiResponse<?> status(@PathVariable String taskId,HttpServletRequest http){
        return with(taskId,http,EnumSet.of(CodingTaskAccess.Action.READ),scope->ApiResponse.success(tasks.get(scope,taskId)));
    }
    @GetMapping("/{taskId}/files") public ApiResponse<?> files(@PathVariable String taskId,HttpServletRequest http){
        return with(taskId,http,EnumSet.of(CodingTaskAccess.Action.READ),scope->ApiResponse.success(workspace.current(tasks.contract(scope,taskId))));
    }
    @GetMapping("/{taskId}/history") public ApiResponse<?> history(@PathVariable String taskId,HttpServletRequest http){
        return with(taskId,http,EnumSet.of(CodingTaskAccess.Action.READ),scope->ApiResponse.success(workspace.history(tasks.contract(scope,taskId))));
    }
    @GetMapping("/{taskId}/diff/{version}") public ApiResponse<?> diff(@PathVariable String taskId,@PathVariable long version,HttpServletRequest http){
        return with(taskId,http,EnumSet.of(CodingTaskAccess.Action.READ),scope->ApiResponse.success(workspace.diff(tasks.contract(scope,taskId),version)));
    }
    @PostMapping("/{taskId}/resume") public ApiResponse<?> resume(@PathVariable String taskId,HttpServletRequest http){
        return with(taskId,http,EnumSet.allOf(CodingTaskAccess.Action.class),scope->ApiResponse.success(agent.resume(scope,taskId)));
    }
    @PostMapping("/{taskId}/cancel") public ApiResponse<?> cancel(@PathVariable String taskId,HttpServletRequest http){
        return with(taskId,http,EnumSet.allOf(CodingTaskAccess.Action.class),scope->ApiResponse.success(agent.cancel(scope,taskId,"Project owner cancelled")));
    }
    @GetMapping("/{taskId}/events") public ApiResponse<?> events(@PathVariable String taskId,HttpServletRequest http){
        return with(taskId,http,EnumSet.of(CodingTaskAccess.Action.READ),scope->{var current=tasks.get(scope,taskId);
            return ApiResponse.success(tasks.events(scope,taskId,Math.max(0,current.lastEventSequence()-128),128));});
    }
    private <T>T with(String task,HttpServletRequest http,Set<CodingTaskAccess.Action> actions,Function<KernelDataScope,T> operation){
        if(!task.matches("native-[0-9a-f]{48}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid native task id");
        var owner=owner(http);var scope=new KernelDataScope(owner.get(0),owner.get(1),null,null,task,null,Map.of());
        return identity.as(scope,actions,()->operation.apply(scope));
    }
    private List<String> owner(HttpServletRequest http){
        Object tenant=http.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID),user=http.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID);
        if(tenant==null||user==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN);return List.of(tenant.toString(),user.toString());
    }
    static String taskId(List<String> owner,String request){return "native-"+NativeCodeWorkspace.sha((owner.get(0)+"\0"+owner.get(1)+"\0"+request).getBytes(StandardCharsets.UTF_8)).substring(0,48);}
}
