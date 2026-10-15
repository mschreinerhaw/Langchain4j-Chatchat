package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.common.runtime.coding.CodingAgentSPI;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "chatchat.coding.cline", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "chatchat.coding", name = "enabled", havingValue = "true")
public class ClineCodingConfiguration {
    @Bean
    @Primary
    CodingAgentSPI clineCodingAgent(DurableCodingTaskService tasks, CodingPythonExecutionBridge python,
            CodingInputSnapshotService inputs, CodingValidationPlanService plans,
            CodingEvidenceCollector collector,
            ClineModelReceiptStore receipts, ObjectMapper mapper,
            @Value("${chatchat.coding.cline.node-url:http://127.0.0.1:8791}") String nodeUrl,
            @Value("${chatchat.coding.cline.shared-secret:}") String secret,
            @Value("${chatchat.coding.cline.shared-secret-file:}") String secretFile) {
        return new ClineCodingAdapter(tasks, python, inputs, plans, collector, receipts,
            URI.create(nodeUrl), CodingSecrets.resolve(secret,secretFile), mapper);
    }
}
