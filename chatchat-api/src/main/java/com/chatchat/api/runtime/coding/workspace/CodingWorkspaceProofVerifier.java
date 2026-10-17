package com.chatchat.api.runtime.coding.workspace;

import com.chatchat.agents.runtime.coding.CodingExecutionProofVerifier;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** A workspace tool receipt proves only that the reviewed parser/proposal operation stopped. */
@Component
@ConditionalOnProperty(prefix="chatchat.coding",name="enabled",havingValue="true")
public final class CodingWorkspaceProofVerifier implements CodingExecutionProofVerifier {
    private final JdbcRuntimeEvidenceStore evidence;
    public CodingWorkspaceProofVerifier(JdbcRuntimeEvidenceStore evidence){this.evidence=evidence;}
    public boolean confirmsStopped(KernelDataScope scope,String task,String operation,String handle,String ref){
        if(ref==null || !ref.startsWith("runtime-evidence:") || !handle.equals("runtime-workspace:"+operation))return false;
        return evidence.find(scope,ref.substring("runtime-evidence:".length())).filter(record ->
            "CODING_WORKSPACE_TOOL_RECEIPT".equals(record.evidenceType())
            && task.equals(record.payload().get("taskId")) && operation.equals(record.payload().get("operationId"))
            && handle.equals(record.payload().get("executionHandle"))
            && Boolean.TRUE.equals(record.payload().get("executorStopped"))
            && Boolean.FALSE.equals(record.payload().get("sqlExecuted"))
            && evidence.contentHash(record.payload()).equals(record.contentSha256())).isPresent();
    }
}
