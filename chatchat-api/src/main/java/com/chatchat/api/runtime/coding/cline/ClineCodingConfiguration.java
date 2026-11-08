package com.chatchat.api.runtime.coding.cline;

import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.api.runtime.coding.data.CodingDataSupplyService;
import com.chatchat.api.runtime.coding.data.CodingInputSnapshotService;
import com.chatchat.api.runtime.coding.evidence.CodingEvidenceCollector;
import com.chatchat.api.runtime.coding.evidence.CodingValidationPlanService;
import com.chatchat.api.runtime.coding.execution.CodingPythonExecutionBridge;
import com.chatchat.api.runtime.coding.workspace.CodingWorkspaceService;
import com.chatchat.common.runtime.coding.CodingAgentSPI;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "chatchat.coding.cline", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "chatchat.coding", name = "enabled", havingValue = "true")
public class ClineCodingConfiguration {
    /** Native business dispatch requires the existing analysis service at startup;
     * an analysis-disabled test configuration must fail before accepting work. */
    @Bean
    @ConditionalOnProperty(prefix="chatchat.coding.cline",name="backend",havingValue="native")
    org.springframework.beans.factory.SmartInitializingSingleton nativeCodingBusinessBinding(
            com.chatchat.agents.orchestration.AgentOrchestrator orchestrator,
            org.springframework.beans.factory.ObjectProvider<com.chatchat.api.runtime.coding.analysis.CodingAnalysisService> coding) {
        return () -> orchestrator.setAnalysisCodingPort(coding.getObject());
    }

    @Bean
    @Primary
    CodingAgentSPI clineCodingAgent(DurableCodingTaskService tasks, CodingPythonExecutionBridge python,
            CodingInputSnapshotService inputs, CodingValidationPlanService plans,
            CodingEvidenceCollector collector,
            ClineModelReceiptStore receipts, ObjectMapper mapper,
            com.chatchat.common.mcp.license.McpLicenseEntitlementPort licenseEntitlements,
            org.springframework.beans.factory.ObjectProvider<CodingWorkspaceService> workspace,
            org.springframework.beans.factory.ObjectProvider<CodingDataSupplyService> dataSupply,
            @Value("${chatchat.coding.cline.node-url:http://127.0.0.1:8791}") String nodeUrl,
            @Value("${chatchat.coding.cline.shared-secret:}") String secret,
            @Value("${chatchat.coding.cline.shared-secret-file:}") String secretFile,
            @Value("${chatchat.coding.cline.backend:sdk}") String backend) {
        if(!java.util.Set.of("sdk","native").contains(backend))throw new IllegalArgumentException("Unknown Coding backend");
        var adapter = new ClineCodingAdapter(tasks, python, inputs, plans, collector, receipts,
            URI.create(nodeUrl), CodingSecrets.resolve(secret,secretFile), mapper);
        adapter.setWorkspace(workspace.getIfAvailable());
        adapter.setDataSupply(dataSupply.getIfAvailable());
        adapter.setLicenseEntitlements(licenseEntitlements);
        return "native".equals(backend)?new ClineNativeCodingAdapter(adapter,URI.create(nodeUrl),CodingSecrets.resolve(secret,secretFile),mapper):adapter;
    }
}
