package com.chatchat.api.runtime.coding.nativecline;

import com.chatchat.agents.runtime.coding.CodingExecutionProofVerifier;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Only the trusted control adapter can register these original Host receipts.
 * A result JSON, model message or user-provided stop flag is never proof. */
@Component
@ConditionalOnProperty(prefix="chatchat.runtime.coding.unified",name="enabled",havingValue="true")
public final class NativeS3ExecutionProofVerifier implements CodingExecutionProofVerifier {
    public static final String TYPE="CODING_NATIVE_HOST_OBSERVATION";
    public static final String STOP_TYPE="CODING_NATIVE_EXECUTOR_STOP";
    private final JdbcRuntimeEvidenceStore evidence;
    public NativeS3ExecutionProofVerifier(JdbcRuntimeEvidenceStore evidence){this.evidence=evidence;}
    @Override public boolean confirmsStopped(KernelDataScope scope,String task,String operation,String handle,String reference){
        if(reference==null||!reference.startsWith("runtime-evidence:")||handle==null||!handle.startsWith("native-s3:"))return false;
        var found=evidence.find(scope,reference.substring("runtime-evidence:".length()));if(found.isEmpty())return false;
        var record=found.get();var payload=record.payload();
        if(!task.equals(payload.get("taskId"))||!(payload.get("sessionId") instanceof String session)
                ||!handle.equals("native-s3:"+session+":"+operation))return false;
        if(TYPE.equals(record.evidenceType()))return operation.equals(payload.get("operationId"))&&Boolean.TRUE.equals(payload.get("stopped"))
            &&Set.of("NATIVE_ROOT_HOST_OBSERVATION","PROVIDER_HTTP_OBSERVATION").contains(payload.get("source"));
        if(STOP_TYPE.equals(record.evidenceType()))return Boolean.TRUE.equals(payload.get("workerStopped"))&&payload.get("containerId") instanceof String id&&id.matches("[a-f0-9]{64}");
        return false;
    }
}
