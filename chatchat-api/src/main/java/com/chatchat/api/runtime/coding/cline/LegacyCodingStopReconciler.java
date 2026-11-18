package com.chatchat.api.runtime.coding.cline;

import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.execution.CodingPythonExecutionBridge;
import com.chatchat.api.runtime.coding.nativecline.NativeSiloTaskService;
import com.chatchat.api.runtime.coding.workspace.CodingWorkspaceProofVerifier;
import com.chatchat.common.kernel.KernelDataScope;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import static com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.*;

/** Reconciles an existing legacy stop using committed receipts without dispatching an SDK session. */
@Component
@ConditionalOnProperty(prefix="chatchat.coding",name={"enabled","cline.enabled"},havingValue="true")
public final class LegacyCodingStopReconciler {
    private static final org.slf4j.Logger log=LoggerFactory.getLogger(LegacyCodingStopReconciler.class);
    private final DurableCodingTaskService tasks;
    private final CodingInvocationContext identity;
    private final ClineModelReceiptStore models;
    private final CodingWorkspaceProofVerifier workspace;
    private final CodingPythonExecutionBridge python;
    private final JdbcTemplate jdbc;
    private final String owner="legacy-stop-"+UUID.randomUUID();
    private String cursor="";

    public LegacyCodingStopReconciler(DurableCodingTaskService tasks,CodingInvocationContext identity,
            ClineModelReceiptStore models,CodingWorkspaceProofVerifier workspace,
            CodingPythonExecutionBridge python,JdbcTemplate jdbc){
        this.tasks=tasks;this.identity=identity;this.models=models;this.workspace=workspace;
        this.python=python;this.jdbc=jdbc;
    }

    @Scheduled(initialDelay=15000,fixedDelay=15000)
    public void scan(){
        var rows=jdbc.queryForList("select id,tenant_id,user_id,run_id,task_id from runtime_coding_task_view "
            +"where status in ('CREATED','RUNNING') and summary_json like '%\"stopPending\":true%' "
            +"and (task_id like 'sql-coding-%' or task_id like 'analysis-coding-%' or task_id like 'python-coding-%') "
            +"and id>? order by id limit 24",cursor);
        if(rows.isEmpty()){cursor="";return;}
        for(var row:rows){
            cursor=row.get("id").toString();
            var scope=new KernelDataScope(row.get("tenant_id").toString(),row.get("user_id").toString(),
                null,null,row.get("run_id").toString(),null,Map.of());
            String task=row.get("task_id").toString();
            try{identity.as(scope,Set.of(READ,WORKER,STOP,EVIDENCE),()->{reconcile(scope,task);return null;});}
            catch(RuntimeException unavailable){log.warn("Legacy stop reconciliation unresolved taskId={} errorType={}",
                task,unavailable.getClass().getSimpleName());}
        }
    }

    private void reconcile(KernelDataScope scope,String task){
        var outcome=tasks.get(scope,task);
        if(outcome.status().terminal()||outcome.executionControl().stopReason()==null
                ||NativeSiloTaskService.supports(tasks.contract(scope,task)))return;
        var held=tasks.ownership(scope,task);
        if(held.leaseUntil().isAfter(Instant.now())&&!owner.equals(held.owner()))return;
        var proofs=new LinkedHashMap<String,String>();
        for(String operation:tasks.unresolvedOperations(scope,task)){
            String proof=proof(scope,task,operation);
            if(proof==null)return;
            proofs.put(operation,proof);
        }
        var lease=owner.equals(held.owner())&&held.leaseUntil().isAfter(Instant.now())?held:
            tasks.claim(scope,task,owner,Duration.ofMinutes(2),outcome.revision());
        for(var entry:proofs.entrySet()){
            String operation=entry.getKey();
            if("mcp".equals(entry.getValue()))python.reconcile(scope,task,lease,operation);
            else tasks.reconcileStopped(scope,task,lease,tasks.get(scope,task).revision(),operation,entry.getValue());
        }
        var current=tasks.get(scope,task);
        if(!current.status().terminal()&&tasks.unresolvedOperations(scope,task).isEmpty())
            tasks.finishStoppedAfterReconciliation(scope,task,lease,current.revision());
    }

    private String proof(KernelDataScope scope,String task,String operation){
        if(operation.matches("model-[1-9][0-9]*")){
            String ref=models.proofRef(scope,task,operation);
            if(models.confirmsStopped(scope,task,operation,"runtime-model:"+operation,ref))return ref;
            return models.localTerminationProof(scope,task,operation).orElse(null);
        }
        if(operation.matches("workspace-[a-f0-9]{64}")){
            for(String ref:List.of("runtime-evidence:coding:workspace:"+operation+":receipt",
                    "runtime-evidence:knowledge-tool:"+operation))
                if(workspace.confirmsStopped(scope,task,operation,"runtime-workspace:"+operation,ref))return ref;
        }
        if(operation.matches("cline-[a-f0-9]{64}")){
            var remote=python.get(scope,task,operation);
            if(remote.executorStopped()&&remote.manifestHash()!=null)return "mcp";
        }
        return null;
    }
}
