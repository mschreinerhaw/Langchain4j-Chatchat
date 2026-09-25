package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.agents.runtime.federation.ComputeNodeRouter;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisIntent;
import com.chatchat.common.runtime.analysis.model.AnalysisWorkflowType;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Evidence-first inference with a selected, tenant-admitted domain compute provider. */
@Component
public class DomainIntelligenceAnalysisWorkflow extends FederatedAgentAnalysisWorkflow {
    public DomainIntelligenceAnalysisWorkflow(ComputeNodeRouter computeNodes) { super(computeNodes); }

    @Override public AnalysisWorkflowType type() { return AnalysisWorkflowType.DOMAIN_INTELLIGENCE; }
    @Override public String workflowId() { return "problem-analysis.domain-intelligence"; }
    @Override public boolean supports(AnalysisContext context, AnalysisIntent intent) {
        return intent.requiredCapabilities().equals(Set.of(AnalysisCapability.DOMAIN_INTELLIGENCE))
            && context.attributes().get(AnalysisContext.DOMAIN_PROVIDER_ATTRIBUTE) instanceof String id
            && !id.isBlank();
    }
}
