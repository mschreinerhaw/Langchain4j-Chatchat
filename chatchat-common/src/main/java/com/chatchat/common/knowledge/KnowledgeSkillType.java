package com.chatchat.common.knowledge;

/** Platform-whitelisted knowledge capabilities. Models may instantiate, but not extend, this set. */
public enum KnowledgeSkillType {
    CONCEPT_LOOKUP(KnowledgeType.CONCEPT),
    METRIC_LOOKUP(KnowledgeType.METRIC),
    RULE_LOOKUP(KnowledgeType.RULE),
    POLICY_INTERPRETATION(KnowledgeType.POLICY),
    METHODOLOGY_LOOKUP(KnowledgeType.METHOD),
    CONSTRAINT_LOOKUP(KnowledgeType.CONSTRAINT),
    PROCEDURE_LOOKUP(KnowledgeType.PROCEDURE),
    EVIDENCE_EXPLANATION(KnowledgeType.INTERPRETATION),
    FAQ_LOOKUP(KnowledgeType.FAQ);

    private final KnowledgeType knowledgeType;

    KnowledgeSkillType(KnowledgeType knowledgeType) {
        this.knowledgeType = knowledgeType;
    }

    public KnowledgeType knowledgeType() {
        return knowledgeType;
    }
}
