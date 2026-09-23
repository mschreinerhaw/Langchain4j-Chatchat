package com.chatchat.knowledgebase.search.workflow;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;

/** Keeps the first retrieval pass on the caller's original query. */
@Component
public class OriginalQueryStage implements DocumentRetrievalStage {
    @Override public String id() { return "original-query"; }
    @Override public int order() { return 100; }

    @Override
    public void execute(DocumentRetrievalWorkflowContext context) {
        context.searchPlan(context.originalPlan());
        LinkedHashSet<String> entities = new LinkedHashSet<>();
        if (context.originalPlan().filters() != null) {
            add(entities, context.originalPlan().filters().company());
            add(entities, context.originalPlan().filters().industry());
            context.originalPlan().filters().allTags().forEach(value -> add(entities, value));
        }
        context.originalPlan().queryTokens().stream()
            .filter(token -> token.length() >= 3 && token.chars().anyMatch(Character::isLetter))
            .limit(12).forEach(entities::add);
        List<String> domains = context.originalPlan().filters() == null
            ? List.of("general") : context.originalPlan().filters().allTags();
        context.queryAnalysis(new ProblemQueryAnalysis(context.originalPlan().intent(),
            List.copyOf(entities), domains));
    }

    private void add(LinkedHashSet<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value.trim());
    }
}
