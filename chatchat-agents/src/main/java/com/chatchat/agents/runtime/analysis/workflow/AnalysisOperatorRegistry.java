package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.common.runtime.analysis.spi.AnalysisCapabilityOperator;

import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.spi.AnalysisCapabilityOperator;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class AnalysisOperatorRegistry {
    private final List<AnalysisCapabilityOperator> operators;

    public AnalysisOperatorRegistry(List<AnalysisCapabilityOperator> operators) {
        this.operators = operators == null ? List.of() : List.copyOf(operators);
    }

    public Optional<AnalysisCapabilityOperator> resolve(AnalysisCapability capability, AnalysisContext context) {
        return operators.stream().filter(operator -> operator.capability() == capability)
            .filter(operator -> operator.available(context)).findFirst();
    }
}
