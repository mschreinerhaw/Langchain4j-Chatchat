package com.chatchat.api.runtime.coding.analysis;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.datascience.sql.*;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.evidence.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Static SQL diagnostics in the same analysis budget. No SQL execution or metadata discovery. */
@Service @ConditionalOnProperty(prefix="chatchat.coding",name="enabled",havingValue="true")
public final class CodingAnalysisSqlService {
    private final DurableCodingTaskService tasks;private final JdbcRuntimeEvidenceStore evidence;private final SqlGlotToolchain parser;
    public CodingAnalysisSqlService(DurableCodingTaskService tasks,JdbcRuntimeEvidenceStore evidence,SqlGlotToolchain parser){this.tasks=tasks;this.evidence=evidence;this.parser=parser;}
    public boolean available(){return parser.available();}
    @SuppressWarnings("unchecked") public Map<String,Object> validate(CodingTaskContract c,CodingJournal.Lease lease,String operation,String dialect,String sql) {
        if(!CodingProtocol.ANALYSIS_VERSION.equals(c.schemaVersion())||!c.authorization().allowedTools().contains("sql_validate")
            ||!operation.matches("workspace-[0-9a-f]{64}")||!SqlStudioService.DIALECTS.contains(dialect)||sql==null||sql.isBlank()||sql.length()>64000)
            throw new SecurityException("SQL analysis validation is outside task authority");
        String hash=evidence.contentHash(Map.of("dialect",dialect,"sql",sql)),id="coding:analysis-sql:"+operation;
        var old=evidence.find(c.scope(),id);Map<String,Object> response;
        if(old.isPresent()) {
            if(!hash.equals(old.get().payload().get("requestHash")))throw new IllegalArgumentException("SQL validation operation conflict");
            response=(Map<String,Object>)old.get().payload().get("response");
        } else {
            var admitted=tasks.reserve(c.scope(),c.taskId(),lease,tasks.get(c.scope(),c.taskId()).revision(),operation,hash,
                CodingJournal.OperationKind.TOOL,new CodingTaskOutcome.Counters(0,0,1,0,0,0,0,"0"));
            if(!admitted.admitted())throw new IllegalStateException("SQL validation budget denied");
            var start=tasks.startIntent(c.scope(),c.taskId(),lease,admitted.outcome().revision(),operation,"runtime-workspace:"+operation);
            if(!start.dispatchIntentCreated())throw new IllegalStateException("SQL validation outcome unknown; reconcile before retry");
            response=parser.validate(dialect,List.of(new SqlOutputBundle.Candidate("analysis.sql",sql)),Map.of());
            var payload=Map.<String,Object>of("taskId",c.taskId(),"operationId",operation,"requestHash",hash,"executionHandle","runtime-workspace:"+operation,
                "executorStopped",true,"sqlExecuted",false,"response",response);
            evidence.register(new EvidenceRecord(null,id,c.scope(),"CODING_WORKSPACE_TOOL_RECEIPT","CodingAnalysisSqlService",evidence.contentHash(payload),payload,null,System.currentTimeMillis(),Map.of()),
                new EvidenceLineage(null,id,c.scope(),List.of(c.inputSnapshotRef().substring(17)),c.taskId(),"CodingAnalysisSqlService",operation,Map.of()));
        }
        var state=tasks.get(c.scope(),c.taskId());
        if(tasks.verifiedOperation(c.scope(),c.taskId(),operation).isEmpty())state=tasks.reconcileStopped(c.scope(),c.taskId(),lease,state.revision(),operation,"runtime-evidence:"+id);
        tasks.settle(c.scope(),c.taskId(),lease,state.revision(),operation,new CodingTaskOutcome.Counters(0,0,1,0,0,0,0,"0"),true);
        return response;
    }
}
