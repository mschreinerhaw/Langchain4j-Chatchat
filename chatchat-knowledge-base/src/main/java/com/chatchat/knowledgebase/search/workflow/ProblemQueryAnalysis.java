package com.chatchat.knowledgebase.search.workflow;

import java.util.List;

/** Normalized question signals consumed by routing and ranking stages. */
public record ProblemQueryAnalysis(String intent, List<String> entities, List<String> domains) {
    public ProblemQueryAnalysis {
        intent = intent == null ? "GENERAL" : intent;
        entities = entities == null ? List.of() : List.copyOf(entities);
        domains = domains == null || domains.isEmpty() ? List.of("general") : List.copyOf(domains);
    }
}
