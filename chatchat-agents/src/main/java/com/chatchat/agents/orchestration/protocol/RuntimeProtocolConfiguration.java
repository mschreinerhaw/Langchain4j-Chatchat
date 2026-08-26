package com.chatchat.agents.orchestration.protocol;

import com.chatchat.agents.runtime.McpEvidenceGovernanceBridge;
import com.chatchat.agents.runtime.protocol.RuntimeAnalysisContextProtocol;
import com.chatchat.agents.runtime.protocol.RuntimeAnalysisSummaryProtocol;
import com.chatchat.agents.runtime.protocol.RuntimeEvidenceProtocol;
import com.chatchat.common.runtime.protocol.RuntimeProtocolRegistry;
import com.chatchat.agents.runtime.protocol.RuntimeResultAnalysisAdapter;
import com.chatchat.agents.runtime.protocol.RuntimeResultAnalysisProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Runtime OS composition root; implementations point inward to stable Runtime protocol ports. */
@Configuration
public class RuntimeProtocolConfiguration {

    @Bean
    public RuntimeProtocolRegistry runtimeProtocolRegistry(
        ObjectMapper objectMapper,
        List<RuntimeResultAnalysisAdapter> resultAdapters
    ) {
        RuntimeEvidenceProtocol<?> evidenceBridge = new McpEvidenceGovernanceBridge();
        RuntimeResultAnalysisProtocol resultAnalysisBridge =
            RuntimeProtocolDefaults.resultAnalysis(resultAdapters);
        return RuntimeProtocolRegistry.builder()
            .register(RuntimeEvidenceProtocol.class, evidenceBridge)
            .register(RuntimeResultAnalysisProtocol.class, resultAnalysisBridge)
            .register(RuntimeAnalysisContextProtocol.class,
                RuntimeProtocolDefaults.analysisContext(objectMapper))
            .register(RuntimeAnalysisSummaryProtocol.class,
                RuntimeProtocolDefaults.analysisSummary())
            .build();
    }
}
