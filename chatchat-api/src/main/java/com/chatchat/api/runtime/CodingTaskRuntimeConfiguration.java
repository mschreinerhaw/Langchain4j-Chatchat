package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.agents.runtime.config.AgentRuntimeExecutorConfig;
import com.chatchat.common.runtime.evidence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import java.time.Clock;
import java.util.concurrent.Executor;

/** Opt-in composition only. Enabling without a trusted access adapter fails closed at startup. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix="chatchat.coding", name="enabled", havingValue="true")
public class CodingTaskRuntimeConfiguration {
    @Bean
    DurableCodingTaskService codingTaskService(RuntimeExecutionCheckpointPort checkpoints, EvidenceStorePort evidenceStore,
        ObjectMapper mapper, CodingTaskAccess access, ObjectProvider<CodingEvidenceVerifier> references,
        ObjectProvider<CodingExecutionProofVerifier> executionProof,
        @Qualifier(AgentRuntimeExecutorConfig.AGENT_RUNTIME_EXECUTOR) Executor executor, TaskScheduler scheduler) {
        CodingEvidenceVerifier verifier=references.getIfAvailable(()->(scope,reference)->{
            String id=reference.startsWith("runtime-evidence:") ? reference.substring("runtime-evidence:".length()) : reference;
            return id.length()<=256 && (evidenceStore.find(scope,id).isPresent() || evidenceStore.snapshot(scope,id).isPresent());
        });
        return new DurableCodingTaskService(checkpoints,mapper,Clock.systemUTC(),access,verifier,
            executionProof.getIfAvailable(),executor,scheduler);
    }
}
