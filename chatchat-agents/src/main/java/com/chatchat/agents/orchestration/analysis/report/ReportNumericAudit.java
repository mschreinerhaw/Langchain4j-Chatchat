package com.chatchat.agents.orchestration.analysis.report;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Advisory numeric traceability check. Token equality is not entity, unit or factual verification. */
public final class ReportNumericAudit {
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z\\d_])[-+]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?(?![\\d_])");

    public Map<String, Object> audit(String markdown, VerifiedReportDataCatalog catalog) {
        Set<String> evidence = new HashSet<>();
        catalog.promptView().forEach(item -> collect(item.get("metric"), evidence));
        catalog.promptView().forEach(item -> collect(item.get("rows"), evidence));
        catalog.datasetPromptView().forEach(item -> collect(item.get("rows"), evidence));
        Set<String> matched = new LinkedHashSet<>(), unmatched = new LinkedHashSet<>();
        boolean inFence = false;
        String marker = "";
        for (String line : (markdown == null ? "" : markdown).split("\\R")) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("```") || stripped.startsWith("~~~")) {
                if (!inFence) {
                    marker = stripped.substring(0, 3);
                    inFence = true;
                } else if (stripped.startsWith(marker)) inFence = false;
                continue;
            }
            if (inFence || stripped.startsWith("#")) continue;
            String prose = line.replaceAll("\\b\\d{4}-\\d{2}-\\d{2}\\b", "")
                .replaceFirst("^\\s*\\d+[.)]\\s+", "");
            var numbers = NUMBER.matcher(prose);
            while (numbers.find() && matched.size() + unmatched.size() < 200) {
                String number = canonical(numbers.group());
                if (evidence.contains(number)) matched.add(number);
                else unmatched.add(number);
            }
        }
        return Map.of("mode", "ADVISORY_NUMERIC_TOKEN_TRACEABILITY", "matchedTokens", List.copyOf(matched),
            "unmatchedTokens", List.copyOf(unmatched), "evidenceValueCount", evidence.size(),
            "semanticVerification", "NOT_CERTIFIED", "publicationVeto", false);
    }
    private void collect(Object value, Set<String> numbers) {
        if (value instanceof Map<?, ?> map) { map.values().forEach(item -> collect(item, numbers)); return; }
        if (value instanceof List<?> list) { list.forEach(item -> collect(item, numbers)); return; }
        if (value instanceof Number || value instanceof String) {
            try { numbers.add(canonical(value.toString())); } catch (NumberFormatException ignored) { }
        }
    }
    private String canonical(String value) { return new BigDecimal(value.replace(",", "")).stripTrailingZeros().toPlainString(); }
}
