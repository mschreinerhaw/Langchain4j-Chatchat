package com.chatchat.runtime.temporal.contract.nativecoding;

/** Small durable reference only. Authorized data, grants, source and logs stay
 * in the existing Runtime journal / Silo, never in Workflow History. */
public record NativeAnalysisReference(String tenantId,String userId,String runId,String taskId,
                                     String contractSha256,long deadlineEpochMillis) {
    public NativeAnalysisReference {
        for(String value:new String[]{tenantId,userId,runId,taskId})
            if(value==null||value.isBlank()||value.length()>256)throw new IllegalArgumentException("Bounded analysis identity required");
        if(contractSha256==null||!contractSha256.matches("[a-f0-9]{64}")||deadlineEpochMillis<=0)
            throw new IllegalArgumentException("Frozen contract hash and deadline required");
    }
}
