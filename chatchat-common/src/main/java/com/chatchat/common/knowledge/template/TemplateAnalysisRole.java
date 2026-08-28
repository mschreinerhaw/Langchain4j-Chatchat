package com.chatchat.common.knowledge.template;

/** Business role played by a template in the current analysis, independent of template type. */
public enum TemplateAnalysisRole {
    TARGET,
    CAUSE,
    CONTEXT,
    DIMENSION,
    VALIDATION,
    EXPLANATION,
    IRRELEVANT;

    public static TemplateAnalysisRole from(Object value) {
        if (value == null) return null;
        try {
            return valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
