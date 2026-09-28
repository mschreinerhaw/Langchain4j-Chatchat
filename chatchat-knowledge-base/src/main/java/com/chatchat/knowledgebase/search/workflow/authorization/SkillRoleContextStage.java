package com.chatchat.knowledgebase.search.workflow;

import org.springframework.stereotype.Component;

import java.util.List;

/** Materializes Skill bindings and role context before PostgreSQL routing. */
@Component
public class SkillRoleContextStage implements DocumentRetrievalStage {
    @Override public String id() { return "skill-role-context"; }
    @Override public int order() { return 150; }

    @Override
    public void execute(DocumentRetrievalWorkflowContext context) {
        List<String> tags = context.originalPlan().filters() == null
            ? List.of() : context.originalPlan().filters().allTags();
        context.skillRoleContext(new SkillRoleRetrievalContext(
            context.originalPlan().effectiveScopedFileIds(), tags,
            context.originalPlan().permissionContext().roles()));
    }
}
