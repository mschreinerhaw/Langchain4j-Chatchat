package com.chatchat.agents.runtime.evaluation.regression;

import java.util.List;

/** Published semantic vocabulary used by deterministic regression evaluation. */
public interface AgentRegressionSemanticPolicy {

    Profile profile(String caseId);

    record Profile(List<String> connectorTerms, List<RelationRule> relationRules) {
        public Profile {
            connectorTerms = connectorTerms == null ? List.of() : List.copyOf(connectorTerms);
            relationRules = relationRules == null ? List.of() : List.copyOf(relationRules);
        }
    }

    record RelationRule(String source, String target, String relation, String sourceLabel, String targetLabel) {
    }
}
