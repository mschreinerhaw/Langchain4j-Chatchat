package com.chatchat.agents.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Enforces that model summaries do not mutate SQL metadata evidence. */
class SqlMetadataGroundingGuard {

    private static final Pattern IDENTIFIER = Pattern.compile("(?i)(?<![\\p{L}\\p{N}_])([a-z][a-z0-9]*(?:_[a-z0-9]+)+(?:\\.[a-z][a-z0-9_]*)?)(?![\\p{L}\\p{N}_])");
    private static final Pattern INFERRED_LAYER = Pattern.compile("(?i)\\b(ODS|DWD|DWS|ADS|DIM)\\b\\s*(?:层|层级|数据层)");
    private static final List<String> FORBIDDEN_INFERRED_LABELS = List.of(
        "可能表名", "表名示例", "补充推荐", "常见的表", "常见表", "工具未返回，补充", "推测分层"
    );

    List<String> violations(String answer, Map<String, Object> factMetadata) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        Set<String> allowed = allowedIdentifiers(factMetadata);
        List<String> violations = new ArrayList<>();
        for (String phrase : FORBIDDEN_INFERRED_LABELS) {
            if (answer.contains(phrase)) {
                violations.add("unsupported_inference_phrase:" + phrase);
            }
        }
        Matcher layerMatcher = INFERRED_LAYER.matcher(answer);
        while (layerMatcher.find()) {
            violations.add("inferred_database_layer:" + layerMatcher.group());
        }
        Matcher identifierMatcher = IDENTIFIER.matcher(answer);
        while (identifierMatcher.find()) {
            String identifier = normalize(identifierMatcher.group(1));
            if (!allowed.contains(identifier) && !runtimeIdentifier(identifier)) {
                violations.add("unsupported_identifier:" + identifierMatcher.group(1));
            }
        }
        return violations.stream().distinct().toList();
    }

    String rewritePrompt(String query, String evidence, String draft, List<String> violations) {
        return """
            你是 Agent Runtime 的事实一致性重写器。请依据下方权威工具证据重写模型总结。

            不可违反的规则：
            1. 保留模型的分析和归纳能力，但不得新增、改名或猜测数据库、Schema、物理表、字段或数据分层。
            2. ADS/DWS/DWD/ODS/DIM 等分层只有工具明确返回分层字段时才能陈述；不能根据表名前缀推断。
            3. 不得输出“可能表名示例”“常见表”“补充推荐”等未检索对象。
            4. 可以解释已返回表和字段与用户问题的关系；如果证据不足，应明确说明缺口。
            5. 只返回修正后的中文分析总结，不要重复粘贴权威证据表格，不要解释重写过程。

            用户问题：
            %s

            权威工具证据：
            %s

            当前草稿发现的违规项：
            %s

            待重写草稿：
            %s
            """.formatted(
            query == null ? "" : query,
            evidence == null ? "" : evidence,
            violations == null ? List.of() : violations,
            draft == null ? "" : draft
        );
    }

    String safeSummary() {
        return "以上分析严格以本次工具返回的物理表和字段为依据。工具未明确返回的数据库分层、表名或字段不作推断；如需进一步判断表间关系，应继续检索相应元数据。";
    }

    private Set<String> allowedIdentifiers(Map<String, Object> factMetadata) {
        Set<String> allowed = new LinkedHashSet<>();
        Object values = factMetadata == null ? null : factMetadata.get("evidenceIdentifiers");
        if (values instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                if (value != null && !String.valueOf(value).isBlank()) {
                    allowed.add(normalize(String.valueOf(value)));
                }
            }
        }
        return allowed;
    }

    private boolean runtimeIdentifier(String value) {
        return Set.of("sql_metadata_search", "mcp_sql_metadata_search", "sql_query_execute").contains(value);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("`", "").replace("\"", "").trim().toLowerCase(Locale.ROOT);
    }
}
