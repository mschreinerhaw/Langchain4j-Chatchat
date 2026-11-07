package com.chatchat.api.runtime.coding.nativecode;

import com.chatchat.agents.runtime.coding.CodingExecutionProofVerifier;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** No dependency on task service: proof verification must not create a bean cycle. */
@Component
@ConditionalOnProperty(prefix="chatchat.coding.native",name="enabled",havingValue="true")
public final class NativeToolProofVerifier implements CodingExecutionProofVerifier {
    private final JdbcRuntimeEvidenceStore evidence;
    public NativeToolProofVerifier(JdbcRuntimeEvidenceStore evidence){this.evidence=evidence;}
    public boolean confirmsStopped(KernelDataScope scope,String task,String operation,String handle,String ref){return valid(evidence,scope,task,operation,handle,ref);}
    static boolean valid(JdbcRuntimeEvidenceStore evidence,KernelDataScope scope,String task,String operation,String handle,String ref){
        if(!("runtime-native-tool:"+operation).equals(handle)||!("runtime-evidence:native-tool:"+operation+":stopped").equals(ref))return false;
        return evidence.find(scope,ref.substring("runtime-evidence:".length())).filter(r->NativeCodingWorkspaceService.TOOL_TYPE.equals(r.evidenceType())
            &&"NativeCodingWorkspaceService".equals(r.sourceNode())&&"SDK_NATIVE_CODE_IO".equals(r.payload().get("executorScope"))
            &&task.equals(r.payload().get("taskId"))&&operation.equals(r.payload().get("operationId"))&&handle.equals(r.payload().get("executionHandle"))
            &&Boolean.TRUE.equals(r.payload().get("executorStopped"))&&evidence.contentHash(r.payload()).equals(r.contentSha256())).isPresent();
    }
}
