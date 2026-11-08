package com.chatchat.mcpserver.python;

import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.agents.runtime.coding.*;
import com.chatchat.common.runtime.coding.CodingContractException;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Real JDBC test of the ONLY journal extension. No external executor is invoked. */
public final class NativeJournalGuardProbe {
    interface Checked {void run();}
    static boolean rejects(Checked action){try{action.run();return false;}catch(CodingContractException expected){return true;}}
    public static void main(String[] args)throws Exception{
        var json=new ObjectMapper().findAndRegisterModules();var cfg=json.readTree(Files.readAllBytes(Path.of(System.getenv("CLINE_OPERATION_CONFIG"))));
        var ds=new DriverManagerDataSource(cfg.path("jdbcUrl").asText(),cfg.path("dbUser").asText(),cfg.path("dbPassword").asText());
        var store=new JdbcRuntimeEvidenceStore(new JdbcTemplate(ds),json,new DataSourceTransactionManager(ds));var scheduler=new ThreadPoolTaskScheduler();scheduler.initialize();
        var authority=new NativeOperationAuthority(cfg,store,null,json,scheduler);
        var body=json.createObjectNode().put("epoch","journal-guard");body.set("identity",json.valueToTree(Map.of("tenantId","stage2-journal-guard","userId","alice","sessionId",UUID.randomUUID().toString(),"taskId","guard","expiresAt",Instant.now().plusSeconds(3600).toEpochMilli())));
        var call=authority.call(body);authority.session(call,body);var tasks=authority.tasks;var lease=tasks.ownership(call.scope(),call.task());var before=tasks.privateState(call.scope(),call.task(),"native:session");var next=before.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)next).put("active",false);
        tasks.cancel(call.scope(),call.task(),"Journal guard test");var checks=new LinkedHashMap<String,Boolean>();
        checks.put("normal-dispatch-metadata-blocked-after-stop",rejects(()->tasks.privateState(call.scope(),call.task(),lease,"native:new",null,next)));
        tasks.reconcileNativeState(call.scope(),call.task(),lease,"native:session",before,next);
        checks.put("existing-native-reconciliation-after-stop",tasks.privateState(call.scope(),call.task(),"native:session").equals(next));
        checks.put("cannot-create-new-key",rejects(()->tasks.reconcileNativeState(call.scope(),call.task(),lease,"native:missing",next,before)));
        checks.put("cannot-write-other-protocol",rejects(()->tasks.reconcileNativeState(call.scope(),call.task(),lease,"script:x",next,before)));
        checks.put("CAS-required",rejects(()->tasks.reconcileNativeState(call.scope(),call.task(),lease,"native:session",before,next)));
        var stale=new CodingJournal.Lease(lease.owner(),lease.leaseUntil(),lease.fencingToken()+1);
        checks.put("fence-required",rejects(()->tasks.reconcileNativeState(call.scope(),call.task(),stale,"native:session",next,before)));
        checks.put("no-execution-dispatched",!tasks.hasStartedOperations(call.scope(),call.task()));
        CodingTaskRevisions.mutate(tasks,call.scope(),call.task(),r->tasks.finishStoppedWithoutExecution(call.scope(),call.task(),r));
        boolean passed=checks.values().stream().allMatch(Boolean::booleanValue);System.out.println(json.writeValueAsString(Map.of("case","existing-JDBC-native-reconciliation-guards","pass",passed,"checks",checks)));scheduler.shutdown();if(!passed)throw new IllegalStateException("Journal extension guard failed");
    }
}
