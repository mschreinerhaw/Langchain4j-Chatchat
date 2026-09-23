package com.chatchat.common.runtime.analysis.routing;

import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisIntent;
import com.chatchat.common.runtime.analysis.model.AnalysisWorkflowType;
import com.chatchat.common.runtime.analysis.spi.AnalysisQueryAnalyzer;
import com.chatchat.common.runtime.analysis.spi.AnalysisWorkflow;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic capability router; models analyze the question, rules choose execution. */
public final class AnalysisWorkflowRouter {
    private static final Map<AnalysisCapability, AnalysisWorkflowType> TYPES = capabilityTypes();
    private final AnalysisQueryAnalyzer analyzer;
    private final List<AnalysisWorkflow> workflows;

    public AnalysisWorkflowRouter(AnalysisQueryAnalyzer analyzer, List<AnalysisWorkflow> workflows) {
        this.analyzer = analyzer;
        this.workflows = workflows == null ? List.of() : List.copyOf(workflows);
    }

    public RoutedWorkflow route(AnalysisContext context) {
        AnalysisIntent intent = context.intent() == null ? analyzer.analyze(context) : context.intent();
        AnalysisContext analyzed = context.withIntent(intent);
        AnalysisWorkflowType target = target(intent.requiredCapabilities());
        AnalysisWorkflow workflow = workflows.stream().filter(candidate -> candidate.type() == target)
            .filter(candidate -> candidate.supports(analyzed, intent))
            .max(Comparator.comparingInt(AnalysisWorkflow::priority))
            .orElseThrow(() -> new IllegalStateException("No analysis workflow available for " + target));
        return new RoutedWorkflow(analyzed, intent, workflow);
    }

    private AnalysisWorkflowType target(Set<AnalysisCapability> capabilities) {
        if (capabilities == null || capabilities.size() != 1) return AnalysisWorkflowType.COMPOSITE;
        return TYPES.getOrDefault(capabilities.iterator().next(), AnalysisWorkflowType.COMPOSITE);
    }

    private static Map<AnalysisCapability, AnalysisWorkflowType> capabilityTypes() {
        Map<AnalysisCapability, AnalysisWorkflowType> values = new EnumMap<>(AnalysisCapability.class);
        values.put(AnalysisCapability.DOCUMENT_SEARCH, AnalysisWorkflowType.DOCUMENT);
        values.put(AnalysisCapability.STRUCTURED_DATA, AnalysisWorkflowType.STRUCTURED_DATA);
        values.put(AnalysisCapability.TOOL_CALL, AnalysisWorkflowType.TOOL);
        values.put(AnalysisCapability.COMPUTATION, AnalysisWorkflowType.COMPUTATION);
        values.put(AnalysisCapability.EXTERNAL_RESEARCH, AnalysisWorkflowType.EXTERNAL_RESEARCH);
        return Map.copyOf(values);
    }

    public record RoutedWorkflow(AnalysisContext context, AnalysisIntent intent, AnalysisWorkflow workflow) { }
}
