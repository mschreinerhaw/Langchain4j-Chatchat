package com.chatchat.knowledgebase.runtime;

import com.chatchat.common.knowledge.KnowledgeIR;
import com.chatchat.common.knowledge.KnowledgeSkillInstance;
import com.chatchat.common.knowledge.KnowledgeSkillPlan;
import dev.langchain4j.skills.Skill;
import dev.langchain4j.skills.SkillResource;
import dev.langchain4j.skills.Skills;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Converts Runtime OS skill instances and Knowledge IR into LangChain4j skills. */
final class LangChain4jDomainSkillAdapter {

    String renderActivatedSkills(KnowledgeSkillPlan plan, List<KnowledgeIR> rankedUnits) {
        if (plan == null || plan.skills().isEmpty()) return "";
        List<Skill> skills = plan.skills().stream()
            .map(skill -> toLangChain4jSkill(skill, rankedUnits))
            .toList();
        String catalog = Skills.from(skills).formatAvailableSkills()
            .replace("<available_skills>", "<activated_domain_skills>")
            .replace("</available_skills>", "</activated_domain_skills>");
        return catalog + "\n"
            + "These task-selected domain skills are active for this run. Apply their knowledge resources "
            + "as analytical methods to the current tool evidence: derive, compare, cross-check, recognize "
            + "patterns and develop useful interpretations. Do not reduce the answer to field restatement, "
            + "and do not present a hypothesis as an observed fact.\n";
    }

    private Skill toLangChain4jSkill(KnowledgeSkillInstance skill, List<KnowledgeIR> rankedUnits) {
        List<SkillResource> resources = new ArrayList<>();
        if (rankedUnits != null) {
            rankedUnits.stream()
                .filter(unit -> unit != null && unit.type() == skill.skillType().knowledgeType())
                .forEach(unit -> resources.add(SkillResource.builder()
                    .relativePath("knowledge/" + resourceName(unit.knowledgeId()) + ".md")
                    .content(resourceContent(unit))
                    .build()));
        }
        return Skill.builder()
            .name(skill.instanceId())
            .description(skill.goal())
            .content("Domain: " + skill.domain() + "\nObjective: " + skill.goal())
            .resources(resources)
            .build();
    }

    private String resourceContent(KnowledgeIR unit) {
        String content = unit.compactPromptRepresentation().isBlank()
            ? unit.semanticDescription() : unit.compactPromptRepresentation();
        return "# " + (unit.title().isBlank() ? unit.knowledgeId() : unit.title()) + "\n\n" + content;
    }

    private String resourceName(String value) {
        String normalized = value == null ? "knowledge" : value.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "knowledge" : normalized;
    }
}
