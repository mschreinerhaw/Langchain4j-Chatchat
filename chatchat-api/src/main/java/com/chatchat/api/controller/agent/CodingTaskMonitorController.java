package com.chatchat.api.controller.agent;

import com.chatchat.agents.runtime.AgentRuntime;
import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated projections of the existing task journal. No parallel task state or queue. */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="chatchat.coding",name="enabled",havingValue="true")
@RequestMapping("/api/v1/agent/runtime/runs/{runId}/coding")
public class CodingTaskMonitorController {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.chatchat.api.runtime.coding.execution.CodingExecutionFeedbackService feedback;
    private final AgentRuntime runtime;
    private final DurableCodingTaskService tasks;
    private final CodingAgentSPI coding;
    private final CodingInvocationContext identity;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.chatchat.api.runtime.coding.data.CodingInputSnapshotService inputs;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.chatchat.api.runtime.coding.data.CodingDataSupplyService dataSupply;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.chatchat.api.runtime.coding.cline.ClineModelReceiptStore modelReceipts;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.chatchat.api.runtime.coding.evidence.CodingEvidenceCollector evidenceCollector;

    @GetMapping("/{taskId}/visual-report")
    public ApiResponse<?> reviewVisualReport(@PathVariable("runId") String runId,@PathVariable("taskId") String taskId,
            @RequestParam("resultRef") String resultRef,HttpServletRequest request) {
        return authorized(runId,request,scope->{
            tasks.get(scope,taskId);
            if(evidenceCollector==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            return ApiResponse.success(evidenceCollector.reviewVisualReport(scope,taskId,resultRef));
        });
    }

    @GetMapping("/{taskId}/inputs")
    public ApiResponse<?> inputFiles(@PathVariable("runId") String runId,@PathVariable("taskId") String taskId,
            @RequestParam(name="operationId",required=false) String operation,HttpServletRequest request) {
        return authorized(runId,request,scope->{
            var contract=tasks.contract(scope,taskId);
            if(inputs==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            if(operation!=null && !operation.matches("cline-[0-9a-f]{64}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            boolean frozen=operation!=null && tasks.privateState(scope,taskId,"inputs:"+operation)!=null;
            var snapshots=dataSupply!=null?dataSupply.snapshots(contract,frozen?operation:null):List.of(inputs.requireAuthorized(contract));
            return ApiResponse.success(Map.of("binding",frozen?"OPERATION_FROZEN":"TASK_LATEST","snapshots",
                snapshots.stream().map(s->{var description=new LinkedHashMap<String,Object>(inputs.describe(scope,taskId,s));
                    description.put("reference",s.reference());description.put("fileName",s.fileName());return description;}).toList()));
        });
    }

    @GetMapping
    public ApiResponse<?> list(@PathVariable("runId") String runId, HttpServletRequest request) {
        return authorized(runId,request,scope -> {
            var ids = new LinkedHashSet<String>();
            for (var observation : runtime.observations(runId)) {
                var meta = observation.metadata();
                if (String.valueOf(meta.get("eventKind")).startsWith("CODING_") && meta.get("taskId") instanceof String id) ids.add(id);
            }
            var result = new ArrayList<Map<String,Object>>();
            for (String id : ids) {
                try {
                    var outcome = tasks.get(scope,id);
                    var contract = tasks.contract(scope,id);
                    var item = new LinkedHashMap<String,Object>();
                    item.put("taskId",id); item.put("outcome",outcome);
                    item.put("objective",contract.objective().instruction().split("\n",2)[0]);
                    item.put("configuration",contract.runtimeContext());
                    if(feedback!=null)item.put("executions",feedback.timeline(scope,id));
                    if(modelReceipts!=null) {
                        var calls=new ArrayList<CodingModelCallTelemetry>();
                        for(var operation:tasks.startedModelOperations(scope,id))
                            modelReceipts.telemetry(contract,operation.operationId(),operation.fencingToken()).ifPresent(calls::add);
                        item.put("modelReceipts",calls);
                    }
                    item.put("allowedTools",contract.authorization().allowedTools());
                    item.put("canCancel",!outcome.status().terminal() && !outcome.cancellationRequested());
                    item.put("canRetry",false); // Retries require the existing parent workflow and its original authorization.
                    item.put("canResume",!outcome.status().terminal());
                    item.put("attemptId",id+":fence-"+tasks.ownership(scope,id).fencingToken());
                    result.add(item);
                } catch (CodingContractException unavailable) {
                    if (unavailable.error().code() != CodingErrorCode.NOT_FOUND) throw unavailable;
                }
            }
            return ApiResponse.success(Map.of("tasks",result,"source","DURABLE_CODING_JOURNAL"));
        });
    }

    @GetMapping("/{taskId}/events")
    public ApiResponse<?> events(@PathVariable("runId") String runId, @PathVariable("taskId") String taskId,
            @RequestParam(name="afterSequence",defaultValue="0") long after,
            @RequestParam(name="limit",defaultValue="128") int limit, HttpServletRequest request) {
        return authorized(runId,request,scope -> {
            var page = tasks.events(scope,taskId,after,limit);
            long cursor = page.isEmpty() ? after : page.get(page.size()-1).sequence();
            return ApiResponse.success(Map.of("events",page,"nextSequence",cursor,
                "hasMore",cursor < tasks.get(scope,taskId).lastEventSequence()));
        });
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<?> cancel(@PathVariable("runId") String runId, @PathVariable("taskId") String taskId,
            HttpServletRequest request) {
        return authorized(runId,request,scope -> ApiResponse.success(coding.cancel(scope,taskId,"Cancelled from task monitor")));
    }

    @PostMapping("/{taskId}/resume")
    public ApiResponse<?> resume(@PathVariable("runId") String runId, @PathVariable("taskId") String taskId,
            HttpServletRequest request) {
        return authorized(runId,request,scope -> ApiResponse.success(coding.resume(scope,taskId)));
    }

    private <T> T authorized(String runId,HttpServletRequest request,Function<KernelDataScope,T> operation) {
        var run = runtime.find(runId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Object tenant=request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID);
        Object user=request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID);
        if (tenant==null || user==null || run.request()==null || !Objects.equals(tenant,run.request().getTenantId())
                || !Objects.equals(user,run.request().getUserId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var scope=new KernelDataScope(tenant.toString(),user.toString(),null,null,runId,null,Map.of());
        return identity.as(scope,EnumSet.of(CodingTaskAccess.Action.READ,CodingTaskAccess.Action.STOP,
            CodingTaskAccess.Action.RECOVER,CodingTaskAccess.Action.WORKER,CodingTaskAccess.Action.EVIDENCE),() -> operation.apply(scope));
    }
}
