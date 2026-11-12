package com.chatchat.api.runtime.coding.nativecline;

import com.chatchat.common.runtime.coding.CodingExecutionEvent;
import java.util.*;

/** Read projection of committed Native receipts for the existing parent task
 * progress stream. Never publishes grants, tool arguments, source or stdout. */
public final class NativeCodingProgress {
    private NativeCodingProgress(){}
    /** Same journal envelope consumed by chat and exploration, including snapshots after refresh. */
    public static Map<String,Object> eventView(CodingExecutionEvent event){
        return Map.of("eventId",event.eventId(),"sequence",event.sequence(),"type","RUNTIME_OBSERVATION",
            "createTime",event.occurredAt().toString(),"runId",event.scope().runId(),
            "payload",Map.of("metadata",trace(event)));
    }
    public static Map<String,Object> trace(CodingExecutionEvent event){
        var payload=new LinkedHashMap<String,Object>();
        for(String key:List.of("normalizedType","nativeEventType","nativeEventId","nativeEvidenceRef","sessionId","operationId","tool","toolCallId","requestId","model","providerModel","status","startedAt","finishedAt","durationMs","exitCode","nativeOccurredAt")){
            Object value=event.payload().get(key);
            if(value instanceof Number||value instanceof Boolean||value instanceof String text&&text.length()<=512)payload.put(key,value);
        }
        var trace=new LinkedHashMap<String,Object>();
        trace.put("eventKind","CODING_TRACE");trace.put("eventId",event.eventId());trace.put("taskId",event.taskId());trace.put("runId",event.scope().runId());
        String operation=Objects.toString(payload.get("operationId"),event.operationId());
        trace.put("stepId",operation==null?event.taskId():operation);if(operation!=null)trace.put("operationId",operation);
        trace.put("sequence",event.sequence());trace.put("occurredAt",event.occurredAt().toString());trace.put("codingEventType",event.type().name());trace.put("eventState",event.status().name());
        trace.put("summary",payload.getOrDefault("normalizedType",event.type().name()));trace.put("payload",Collections.unmodifiableMap(payload));trace.put("evidenceRefs",event.evidenceRefs());
        return Collections.unmodifiableMap(trace);
    }
}
