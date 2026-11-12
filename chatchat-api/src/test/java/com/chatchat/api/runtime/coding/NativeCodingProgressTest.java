package com.chatchat.api.runtime.coding;

import com.chatchat.api.runtime.coding.nativecline.NativeCodingProgress;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.kernel.KernelDataScope;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NativeCodingProgressTest {
    @Test void preservesTheCommittedIdentityAndTimestampWithoutPublishingPrivateControlOrToolContent(){
        var at=Instant.parse("2026-11-11T10:00:26Z");
        var zero=new CodingTaskOutcome.Counters(0,0,0,0,0,0,0,"0");
        var usage=new CodingTaskOutcome.Usage("test-budget","USD",zero,zero,false);
        var source=new CodingExecutionEvent(CodingProtocol.VERSION,"original-native-event","native-task",new KernelDataScope("tenant","user",null,null,"parent-attempt",null,Map.of()),42,9,at,CodingExecutionEvent.Type.RECOVERY_RECONCILED,null,null,CodingTaskOutcome.Status.RUNNING,null,List.of("original-evidence"),usage,"private shell arguments",Map.of("normalizedType","MODEL_REQUEST_STARTED","requestId","provider-request","operationId","model-provider-request","apiKey","never-publish-this","command","private command","source","private source"));
        var trace=NativeCodingProgress.trace(source);
        var view=NativeCodingProgress.eventView(source);
        assertThat(view).containsEntry("type","RUNTIME_OBSERVATION").containsEntry("createTime",at.toString())
            .containsEntry("sequence",42L).containsEntry("runId","parent-attempt");
        assertThat(view.get("payload")).isEqualTo(Map.of("metadata",trace));
        assertThat(trace).containsEntry("eventId",source.eventId()).containsEntry("sequence",42L).containsEntry("occurredAt",at.toString()).containsEntry("operationId","model-provider-request");
        assertThat(trace.get("payload").toString()).doesNotContain("never-publish-this","private command","private source");
        assertThat(trace.get("summary")).isEqualTo("MODEL_REQUEST_STARTED");assertThat(source.summary()).isEqualTo("private shell arguments");
    }
}
