package com.chatchat.common.runtime.analysis.workflow;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight baseline analyzer. An upstream model may provide AnalysisIntent directly. */
public final class StandardAnalysisQueryAnalyzer implements AnalysisQueryAnalyzer {
    private static final Pattern IDENTIFIER = Pattern.compile("(?<![A-Za-z0-9])([A-Za-z]*\\d{6,}|[A-Z]{2,}[-_]?[A-Z0-9]+)(?![A-Za-z0-9])");

    @Override
    public AnalysisIntent analyze(AnalysisContext context) {
        Set<AnalysisCapability> required = declaredCapabilities(context);
        String query = context.query().toLowerCase(Locale.ROOT);
        if (required.isEmpty()) {
            if (containsAny(query, "sql", "database", "dataset", "table", "schema", "数据库", "数据集"))
                required.add(AnalysisCapability.STRUCTURED_DATA);
            if (containsAny(query, "calculate", "compute", "ratio", "drawdown", "计算", "比率", "回撤"))
                required.add(AnalysisCapability.COMPUTATION);
            if (containsAny(query, "latest", "today", "news", "internet", "最新", "今天", "新闻"))
                required.add(AnalysisCapability.EXTERNAL_RESEARCH);
            if (containsAny(query, "execute", "call tool", "position", "执行", "调用", "持仓"))
                required.add(AnalysisCapability.TOOL_CALL);
            if (!context.documentIds().isEmpty() || !context.documentTags().isEmpty()
                || containsAny(query, "document", "manual", "policy", "文档", "说明", "制度"))
                required.add(AnalysisCapability.DOCUMENT_SEARCH);
        }
        if (required.isEmpty()) required.add(AnalysisCapability.DOCUMENT_SEARCH);
        return new AnalysisIntent(intentName(required), entities(context.query()), required,
            required.contains(AnalysisCapability.EXTERNAL_RESEARCH) ? "CURRENT" : "UNSPECIFIED", true);
    }

    private Set<AnalysisCapability> declaredCapabilities(AnalysisContext context) {
        EnumSet<AnalysisCapability> capabilities = EnumSet.noneOf(AnalysisCapability.class);
        Object declared = context.attributes().get("requiredCapabilities");
        if (declared instanceof Iterable<?> values) {
            for (Object value : values) {
                try { capabilities.add(AnalysisCapability.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT))); }
                catch (IllegalArgumentException ignored) { }
            }
        }
        return capabilities;
    }

    private List<AnalysisEntity> entities(String query) {
        List<AnalysisEntity> entities = new ArrayList<>();
        Matcher matcher = IDENTIFIER.matcher(query == null ? "" : query);
        while (matcher.find() && entities.size() < 20) {
            entities.add(new AnalysisEntity("IDENTIFIER", matcher.group(1)));
        }
        return entities;
    }

    private String intentName(Set<AnalysisCapability> capabilities) {
        return capabilities.size() > 1 ? "COMPOSITE_ANALYSIS"
            : capabilities.iterator().next().name() + "_ANALYSIS";
    }

    private boolean containsAny(String query, String... terms) {
        for (String term : terms) if (query.contains(term)) return true;
        return false;
    }
}
