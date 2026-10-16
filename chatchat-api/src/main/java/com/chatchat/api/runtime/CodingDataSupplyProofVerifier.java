package com.chatchat.api.runtime;
import com.chatchat.agents.runtime.coding.CodingExecutionProofVerifier;
import com.chatchat.common.kernel.KernelDataScope;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Component @ConditionalOnProperty(prefix="chatchat.coding",name="enabled",havingValue="true")
public final class CodingDataSupplyProofVerifier implements CodingExecutionProofVerifier {
    private final JdbcRuntimeEvidenceStore evidence;
    public CodingDataSupplyProofVerifier(JdbcRuntimeEvidenceStore evidence){this.evidence=evidence;}
    public boolean confirmsStopped(KernelDataScope scope,String task,String operation,String handle,String ref) {
        if(ref==null||!ref.startsWith("runtime-evidence:")||!handle.equals("runtime-data:"+operation))return false;
        return evidence.find(scope,ref.substring(17)).filter(r->"CODING_DATA_RECEIPT".equals(r.evidenceType())
            &&task.equals(r.payload().get("taskId"))&&operation.equals(r.payload().get("operationId"))&&Boolean.TRUE.equals(r.payload().get("executorStopped"))
            &&evidence.contentHash(r.payload()).equals(r.contentSha256())).isPresent();
    }
}
