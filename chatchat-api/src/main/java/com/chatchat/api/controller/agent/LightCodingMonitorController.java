package com.chatchat.api.controller.agent;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.assets.*;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.cline.ClineModelReceiptStore;
import com.chatchat.api.runtime.coding.execution.CodingExecutionFeedbackService;
import com.chatchat.api.runtime.coding.monitor.CodingMonitorProjection;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Read-only Java authority facade. No Node administration, execution controls,
 * provider keys or storage capabilities are exposed to the browser. */
@RestController
@RequestMapping("/api/v1/coding-monitor")
@ConditionalOnProperty(prefix="chatchat.coding",name="enabled",havingValue="true")
public final class LightCodingMonitorController {
    private final DurableCodingTaskService tasks;private final CodingInvocationContext identity;
    private final CodingMonitorProjection projection;private final EnterpriseAdminService accounts;
    private final ObjectProvider<ClineModelReceiptStore> models;private final ObjectProvider<CodingExecutionFeedbackService> executions;
    private final ObjectProvider<UserAssetService> assets;
    public LightCodingMonitorController(DurableCodingTaskService tasks,CodingInvocationContext identity,CodingMonitorProjection projection,
            EnterpriseAdminService accounts,ObjectProvider<ClineModelReceiptStore> models,ObjectProvider<CodingExecutionFeedbackService> executions,ObjectProvider<UserAssetService> assets){
        this.tasks=tasks;this.identity=identity;this.projection=projection;this.accounts=accounts;this.models=models;this.executions=executions;this.assets=assets;
    }
    private String attribute(HttpServletRequest request,String name){Object value=request.getAttribute(name);if(value==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);return value.toString();}
    private KernelDataScope scope(HttpServletRequest request,String run){
        if(run==null||run.isBlank()||run.length()>256)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return new KernelDataScope(attribute(request,ApiAuthenticationFilter.CURRENT_TENANT_ID),attribute(request,ApiAuthenticationFilter.CURRENT_USER_ID),null,null,run,null,Map.of());
    }
    private <T>T read(KernelDataScope scope,String task,Supplier<T> operation){
        return identity.as(scope,Set.of(CodingTaskAccess.Action.READ),()->{
            try{var contract=tasks.contract(scope,task);
                if(!Objects.equals(scope.tenantId(),contract.scope().tenantId())||!Objects.equals(scope.userId(),contract.scope().userId())||!Objects.equals(scope.runId(),contract.scope().runId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            }catch(CodingContractException denied){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Task outside the authenticated account");}
            return operation.get();
        });
    }
    @GetMapping("/tasks") public ApiResponse<?> list(HttpServletRequest request,
            @RequestParam(name="name",defaultValue="")String name,@RequestParam(name="status",defaultValue="")String status,
            @RequestParam(name="from",defaultValue="0")long from,@RequestParam(name="to",defaultValue="9223372036854775807")long to,
            @RequestParam(name="page",defaultValue="0")int page,@RequestParam(name="size",defaultValue="20")int size){
        if(name.length()>256||from<0||to<from||page<0||page>100000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if(!status.isEmpty())try{CodingTaskOutcome.Status.valueOf(status);}catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST);}
        return ApiResponse.success(projection.list(attribute(request,ApiAuthenticationFilter.CURRENT_TENANT_ID),attribute(request,ApiAuthenticationFilter.CURRENT_USER_ID),name,status,from,to,page,size));
    }
    @GetMapping("/tasks/{runId}/{taskId}") public ApiResponse<?> task(HttpServletRequest request,@PathVariable("runId") String runId,@PathVariable("taskId") String taskId){
        var scope=scope(request,runId);return read(scope,taskId,()->{
            var result=new LinkedHashMap<String,Object>();var contract=tasks.contract(scope,taskId);var outcome=tasks.get(scope,taskId);
            result.put("taskId",taskId);result.put("runId",runId);result.put("name",contract.objective().instruction().split("\n",2)[0]);result.put("outcome",outcome);
            var binding=tasks.privateState(scope,taskId,"native-s3:binding");result.put("sessionId",binding==null?null:binding.path("sessionId").asText(null));
            boolean nativeTask=com.chatchat.api.runtime.coding.nativecline.NativeSiloTaskService.supports(contract);
            if(nativeTask)result.put("modelReceipts",tasks.nativeTelemetry(scope,taskId,"model"));
            else{
                var store=models.getIfAvailable();var receipts=new ArrayList<CodingModelCallTelemetry>();
                if(store!=null)for(var op:tasks.startedModelOperations(scope,taskId))store.telemetry(contract,op.operationId(),op.fencingToken()).ifPresent(receipts::add);
                result.put("modelReceipts",receipts);
            }
            var execution=executions.getIfAvailable();
            result.put("executions",nativeTask?tasks.nativeTelemetry(scope,taskId,"execution"):execution==null?List.of():execution.timeline(scope,taskId));
            result.put("repairs",nativeTask?tasks.nativeTelemetry(scope,taskId,"repair"):List.of());
            result.put("readOnly",true);return ApiResponse.success(result);
        });
    }
    @GetMapping("/tasks/{runId}/{taskId}/events") public ApiResponse<?> events(HttpServletRequest request,@PathVariable("runId") String runId,@PathVariable("taskId") String taskId,
            @RequestParam(name="afterSequence",defaultValue="0")long afterSequence,@RequestParam(name="limit",defaultValue="128")int limit){
        var scope=scope(request,runId);return read(scope,taskId,()->{
            if(afterSequence<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            var events=projection.events(scope.tenantId(),scope.userId(),runId,taskId,afterSequence,limit);
            return ApiResponse.success(Map.of("events",events,"nextSequence",events.isEmpty()?afterSequence:events.get(events.size()-1).get("sequence")));
        });
    }
    @GetMapping(value="/tasks/{runId}/{taskId}/events/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletRequest request,@PathVariable("runId") String runId,@PathVariable("taskId") String taskId,@RequestParam(name="afterSequence",defaultValue="0")long afterSequence){
        var scope=scope(request,runId);String cursor=request.getHeader("Last-Event-ID");
        if(cursor!=null)try{afterSequence=Math.max(afterSequence,Long.parseLong(cursor));}catch(NumberFormatException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST);}
        if(afterSequence<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        long after=afterSequence;String authorization=Objects.toString(request.getHeader("Authorization"),"");
        String token=authorization.startsWith("Bearer ")?authorization.substring(7):"";
        return read(scope,taskId,()->{
            var emitter=new SseEmitter(30000L);var subscription=new AtomicReference<CodingAgentSPI.Subscription>();var closed=new AtomicBoolean();
            Runnable close=()->{closed.set(true);var active=subscription.getAndSet(null);if(active!=null)active.close();};
            emitter.onCompletion(close);emitter.onTimeout(close);emitter.onError(error->close.run());
            var active=tasks.subscribe(scope,taskId,after,new CodingAgentSPI.Observer(){
                public void onEvent(CodingExecutionEvent event){
                    try{
                        boolean valid=accounts.resolveSessionByToken(token).filter(user->user.id().equals(scope.userId())&&user.tenantId().equals(scope.tenantId())&&"enabled".equalsIgnoreCase(user.status())).isPresent();
                        if(!valid){emitter.send(SseEmitter.event().name("authentication-required").data(Map.of("status",401)));emitter.complete();close.run();return;}
                        emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name("coding-event").data(CodingMonitorProjection.event(event)));
                    }catch(Exception disconnected){emitter.complete();close.run();}
                }
                public void onError(CodingAgentSPI.Error error){try{emitter.send(SseEmitter.event().name("stream-error").data(Map.of("code",error.code(),"retryable",error.retryable())));}catch(Exception ignored){}emitter.complete();}
                public void onClosed(){emitter.complete();}
            });subscription.set(active);if(closed.get())close.run();return emitter;
        });
    }
    @GetMapping("/tasks/{runId}/{taskId}/artifacts") public ApiResponse<?> artifacts(HttpServletRequest request,@PathVariable("runId") String runId,@PathVariable("taskId") String taskId){
        var scope=scope(request,runId);return read(scope,taskId,()->{
            var service=assets.getIfAvailable();if(service==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            var files=service.list(UserAssetService.Owner.of(scope),null,taskId,100).stream().filter(f->runId.equals(f.runId())).toList();
            return ApiResponse.success(Map.of("files",files,"validationStatus",tasks.get(scope,taskId).validationStatus()));
        });
    }
    @GetMapping("/tasks/{runId}/{taskId}/artifacts/{assetId}/content")
    public ResponseEntity<StreamingResponseBody> content(HttpServletRequest request,@PathVariable("runId") String runId,@PathVariable("taskId") String taskId,@PathVariable("assetId") String assetId,
            @RequestParam(name="download",defaultValue="false")boolean download){
        var scope=scope(request,runId);return read(scope,taskId,()->{
            var service=assets.getIfAvailable();if(service==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            var file=service.get(UserAssetService.Owner.of(scope),assetId);
            if(!taskId.equals(file.taskId())||!runId.equals(file.runId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            if(!download&&file.size()>2L*1024*1024)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Preview is bounded; download the full artifact");
            String lower=file.fileName().toLowerCase(Locale.ROOT);MediaType type=lower.endsWith(".png")?MediaType.IMAGE_PNG:lower.endsWith(".json")?MediaType.APPLICATION_JSON:MediaType.TEXT_PLAIN;
            var disposition=(download?ContentDisposition.attachment():ContentDisposition.inline()).filename(file.fileName(),java.nio.charset.StandardCharsets.UTF_8).build();
            return ResponseEntity.ok().header("X-Content-Type-Options","nosniff").cacheControl(CacheControl.noStore()).contentLength(file.size()).contentType(download?MediaType.APPLICATION_OCTET_STREAM:type)
                .header(HttpHeaders.CONTENT_DISPOSITION,disposition.toString()).body(output->service.copyTask(scope,taskId,assetId,output));
        });
    }
}
