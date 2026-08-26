package com.chatchat.agents.orchestration.protocol;

import com.chatchat.agents.runtime.McpEvidenceGovernanceBridge;
import com.chatchat.agents.runtime.protocol.RuntimeAnalysisContextProtocol;
import com.chatchat.agents.runtime.protocol.RuntimeAnalysisSummaryProtocol;
import com.chatchat.agents.runtime.protocol.RuntimeEvidenceProtocol;
import com.chatchat.agents.runtime.AgentRuntimeProperties;
import com.chatchat.agents.orchestration.AnalysisTaskDispatcher;
import com.chatchat.agents.orchestration.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.HierarchicalAnalysisReducer;
import com.chatchat.agents.orchestration.LocalAnalysisTaskDispatcher;
import com.chatchat.common.runtime.summary.ModelSummaryDispatcher;
import com.chatchat.common.runtime.summary.ModelSummaryReducer;
import com.chatchat.common.runtime.protocol.RuntimeProtocolRegistry;
import com.chatchat.agents.runtime.protocol.RuntimeResultAnalysisAdapter;
import com.chatchat.agents.runtime.protocol.RuntimeResultAnalysisProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

import java.util.List;

/** Runtime OS composition root; implementations point inward to stable Runtime protocol ports. */
@Configuration
public class RuntimeProtocolConfiguration {

    @Bean
    public RuntimeProtocolRegistry runtimeProtocolRegistry(
        ObjectMapper objectMapper,
        List<RuntimeResultAnalysisAdapter> resultAdapters,
        AnalysisTaskDispatcher summaryDispatcher,
        ModelSummaryReducer<AnalysisSummaryResult, HierarchicalAnalysisReducer.Context,
            HierarchicalAnalysisReducer.Result> summaryReducer
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
            .register(ModelSummaryDispatcher.class, summaryDispatcher)
            .register(ModelSummaryReducer.class, summaryReducer)
            .build();
    }

    /** Convenience composition for non-Spring tests and embedded runtimes. */
    public RuntimeProtocolRegistry runtimeProtocolRegistry(
        ObjectMapper objectMapper,
        List<RuntimeResultAnalysisAdapter> resultAdapters
    ) {
        return runtimeProtocolRegistry(objectMapper, resultAdapters,
            new LocalAnalysisTaskDispatcher(4), new HierarchicalAnalysisReducer());
    }

    @Bean
    @ConditionalOnMissingBean(AnalysisTaskDispatcher.class)
    public AnalysisTaskDispatcher analysisTaskDispatcher(AgentRuntimeProperties properties) {
        return new LocalAnalysisTaskDispatcher(properties.analysisSummaryWorkerCount());
    }

    @Bean
    @ConditionalOnMissingBean(ModelSummaryReducer.class)
    public ModelSummaryReducer<AnalysisSummaryResult, HierarchicalAnalysisReducer.Context,
        HierarchicalAnalysisReducer.Result> hierarchicalAnalysisReducer() {
        return new HierarchicalAnalysisReducer();
    }
}
