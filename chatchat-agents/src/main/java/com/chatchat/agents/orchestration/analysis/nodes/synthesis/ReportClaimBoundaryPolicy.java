package com.chatchat.agents.orchestration.analysis.nodes.synthesis;

import java.util.*;
import java.util.regex.Pattern;

/** Conservative checks for identifiable scope expansions; not a general semantic proof engine. */
final class ReportClaimBoundaryPolicy {
    private static final List<String> STRONGER = List.of("通常", "习惯", "一贯", "始终", "绝不", "严格控制",
        "极高频", "极高的交易频次", "极强的进攻意愿", "高胜率", "长期稳定", "typically", "habitually", "always");
    private static final Pattern SAMPLE = Pattern.compile("样本|抽样|采样|截断|仅.*[两二2]条|sample|partial|truncated", Pattern.CASE_INSENSITIVE);
    private static final Pattern POPULATION = Pattern.compile("共持有|总共持有|全部持仓|所有交易|整体胜率|历史胜率|total holdings|all trades", Pattern.CASE_INSENSITIVE);
    private static final List<String> SEMANTIC_QUALIFIERS = List.of("浮盈", "浮亏", "累计总资产收益", "历史累计收益");
    List<String> violations(String statement, List<String> evidence) {
        String text = statement == null ? "" : statement.toLowerCase(Locale.ROOT);
        String basis = String.join(" ", evidence).toLowerCase(Locale.ROOT);
        List<String> issues = new ArrayList<>();
        for (String marker : STRONGER) {
            if (asserts(text, marker) && !asserts(basis, marker)) issues.add("CERTAINTY_EXPANSION:" + marker);
        }
        for (String qualifier : SEMANTIC_QUALIFIERS) {
            if (asserts(text, qualifier) && !asserts(basis, qualifier)) issues.add("UNSUPPORTED_METRIC_MEANING:" + qualifier);
        }
        if (SAMPLE.matcher(basis).find()) {
            for (String clause : text.split("[。；;\\n]")) {
                var match = POPULATION.matcher(clause);
                while (match.find()) {
                    if (asserts(clause, match.group()) && !SAMPLE.matcher(clause).find()) {
                        issues.add("POPULATION_SCOPE_EXPANSION"); break;
                    }
                }
            }
        }
        return List.copyOf(issues);
    }
    boolean contradicts(String statement, String limitation) {
        String text = statement.toLowerCase(Locale.ROOT);
        String scope = limitation.toLowerCase(Locale.ROOT);
        if (violations(text, List.of(scope)).contains("POPULATION_SCOPE_EXPANSION")) return true;
        return java.util.stream.Stream.concat(STRONGER.stream(), SEMANTIC_QUALIFIERS.stream())
            .anyMatch(marker -> scope.contains(marker) && !asserts(scope, marker) && asserts(text, marker));
    }

    private boolean asserts(String text, String marker) {
        int start = 0;
        while ((start = text.indexOf(marker, start)) >= 0) {
            String prefix = text.substring(Math.max(0, start - 24), start);
            int boundary = Math.max(Math.max(prefix.lastIndexOf('。'), prefix.lastIndexOf('\n')), prefix.lastIndexOf(';'));
            prefix = prefix.substring(boundary + 1);
            if (!prefix.matches("(?s).*(无法|不能|不足以|尚未|不代表|不可|未能|not |cannot |unknown|whether).*")) return true;
            start += marker.length();
        }
        return false;
    }
}
