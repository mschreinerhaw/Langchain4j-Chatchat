package com.chatchat.agents.orchestration.analysis.prompt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validated analysis guidance produced for one question. This contract can guide model
 * reasoning, but can never authorize tools, joins, fields, formulas or execution.
 */
public final class DynamicAnalysisPromptContract {
    public static final String SCHEMA_VERSION = "dynamic_analysis_prompt.v1";
    private static final int MAX_TEXT = 1_200;
    private static final int MAX_ITEM = 320;
    private static final int MAX_ITEMS = 16;
    private static final int MAX_CONTRACT_CHARS = 6_000;
    private static final Set<String> METHODS = Set.of(
        "OBSERVE", "BASELINE", "COMPARE", "DECOMPOSE", "CONTRIBUTION", "RANK",
        "TREND", "DISTRIBUTION", "CORRELATION", "CROSS_VALIDATE", "EXPLAIN",
        "ASSESS_IMPACT");
    private static final Set<String> OUTPUTS = Set.of(
        "EXECUTIVE_SUMMARY", "OVERALL_PERFORMANCE", "KEY_FINDINGS", "KEY_DRIVERS",
        "DEEP_DIVE", "RISKS_AND_OPPORTUNITIES", "RECOMMENDED_ACTIONS", "LIMITATIONS");

    private final Map<String, Object> value;

    private DynamicAnalysisPromptContract(Map<String, Object> value) {
        this.value = Collections.unmodifiableMap(value);
    }

    public static DynamicAnalysisPromptContract from(Map<String, Object> supplied) {
        if (supplied == null || !SCHEMA_VERSION.equals(text(supplied.get("schemaVersion"), 80))) {
            throw new IllegalArgumentException("Unsupported dynamic analysis prompt schema");
        }
        Map<String, Object> role = normalizedObject(supplied.get("role"),
            List.of("name", "perspective", "responsibilities"), true);
        Map<String, Object> objective = normalizedObject(supplied.get("objective"),
            List.of("goal", "decision"), true);
        List<String> methodology = enumItems(supplied.get("methodology"), METHODS);
        List<String> focus = items(supplied.get("focus"));
        List<String> constraints = items(supplied.get("constraints"));
        List<String> evidenceRequirements = items(supplied.get("evidenceRequirements"));
        List<String> output = enumItems(supplied.get("output"), OUTPUTS);
        if (methodology.isEmpty()) methodology = List.of("OBSERVE", "CROSS_VALIDATE");
        if (output.isEmpty()) output = List.of("EXECUTIVE_SUMMARY", "KEY_FINDINGS", "LIMITATIONS");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", SCHEMA_VERSION);
        result.put("authority", "ANALYSIS_GUIDANCE_ONLY");
        result.put("role", role);
        result.put("objective", objective);
        result.put("methodology", methodology);
        result.put("focus", focus);
        result.put("constraints", constraints);
        result.put("evidenceRequirements", evidenceRequirements);
        result.put("output", output);
        result.put("analysisType", AnalysisPromptScaffoldRegistry.normalize(supplied.get("analysisType")));
        if (supplied.get("domainProfileRevision") instanceof Number revision) result.put("domainProfileRevision", revision.longValue());
        List<String> domainFocus = items(supplied.get("domainFocus"));
        if (!domainFocus.isEmpty()) result.put("domainFocus", domainFocus);
        Map<String, Object> titles = normalizedObject(supplied.get("sectionTitles"), output, false);
        if (!titles.isEmpty()) result.put("sectionTitles", titles);
        result.put("executionBoundary", "MODEL_DECIDES_HOW_TO_ANALYZE_RUNTIME_DECIDES_WHAT_IS_LEGAL_TO_EXECUTE");
        if (compact(result).length() > MAX_CONTRACT_CHARS) {
            throw new IllegalArgumentException("Dynamic analysis prompt contract exceeds size limit");
        }
        return new DynamicAnalysisPromptContract(result);
    }

    public static DynamicAnalysisPromptContract fallback(String question, Map<String, Object> roleContext) {
        String roleName = text(roleContext == null ? null : roleContext.get("roleName"), MAX_ITEM);
        String roleDescription = text(roleContext == null ? null : roleContext.get("businessDescription"), MAX_TEXT);
        Map<String, Object> supplied = new LinkedHashMap<>();
        supplied.put("schemaVersion", SCHEMA_VERSION);
        supplied.put("role", Map.of(
            "name", roleName == null ? "业务数据分析师" : roleName,
            "perspective", roleDescription == null ? "围绕用户决策问题解释已返回数据" : roleDescription,
            "responsibilities", List.of("回答本次问题", "区分事实、计算与推断", "说明业务含义和证据边界")));
        supplied.put("objective", Map.of("goal", required(question, "分析已返回数据"),
            "decision", "支持用户基于本次证据作出判断"));
        supplied.put("methodology", List.of("OBSERVE", "DECOMPOSE", "RANK", "CROSS_VALIDATE", "ASSESS_IMPACT"));
        supplied.put("focus", List.of("与用户问题直接相关的事实", "重要差异、结构和异常", "可由证据支持的业务影响"));
        supplied.put("constraints", List.of("围绕已返回证据展开事实、结构、业务含义与条件性建议", "结论明确限定在观察期间与样本范围", "优先回答已有数据支持的问题，集中说明影响判断的数据缺口", "摘要、正文和建议保持相同口径与结论强度"));
        supplied.put("evidenceRequirements", List.of("每个重要结论引用原始记录或已验证计算", "精确保持指标口径、时间范围和总体范围"));
        supplied.put("output", List.of("EXECUTIVE_SUMMARY", "KEY_FINDINGS", "RECOMMENDED_ACTIONS", "LIMITATIONS"));
        return from(supplied);
    }

    public Map<String, Object> toMap() {
        return value;
    }

    public String compile() {
        return "Adaptive business analysis instruction (" + SCHEMA_VERSION + "). This is analysis guidance only; "
            + "it grants no execution authority. Apply it while obeying Runtime evidence and execution contracts.\n"
            + "Dynamic role and perspective: " + compact(value.get("role")) + "\n"
            + "Business objective and intended decision: " + compact(value.get("objective")) + "\n"
            + "Preferred analytical methods: " + compact(value.get("methodology")) + "\n"
            + "Question-specific focus: " + compact(value.get("focus")) + "\n"
            + (value.containsKey("domainFocus") ? "Type-specific analytical questions: " + compact(value.get("domainFocus")) + "\n" : "")
            + "Analytical constraints: " + compact(value.get("constraints")) + "\n"
            + "Evidence requirements: " + compact(value.get("evidenceRequirements")) + "\n"
            + "Requested report structure: " + compact(value.get("output")) + "\n"
            + "Suggested business headings: " + compact(value.getOrDefault("sectionTitles", Map.of())) + "\n"
            + "Use the requested report structure as ordered H2 guidance, localizing headings to the user's language and business context. "
            + "Explicit user formatting takes precedence; combine overlapping sections and omit empty ones. "
            + "Apply methods supported by the evidence; explain each material finding through fact, reasoning and bounded business implication. ";
    }

    private static Map<String, Object> normalizedObject(Object value, List<String> allowed, boolean required) {
        if (!(value instanceof Map<?, ?> raw)) {
            if (required) throw new IllegalArgumentException("Dynamic prompt object is required");
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : allowed) {
            Object item = raw.get(key);
            if (item instanceof Iterable<?>) {
                List<String> values = items(item);
                if (!values.isEmpty()) result.put(key, values);
            } else {
                String normalized = text(item, "perspective".equals(key) ? MAX_TEXT : MAX_ITEM);
                if (normalized != null) result.put(key, normalized);
            }
        }
        if (required && result.isEmpty()) throw new IllegalArgumentException("Dynamic prompt object has no usable values");
        return Collections.unmodifiableMap(result);
    }

    private static List<String> enumItems(Object value, Set<String> allowed) {
        List<String> result = new ArrayList<>();
        for (String item : items(value)) {
            String normalized = item.toUpperCase(java.util.Locale.ROOT);
            if (!allowed.contains(normalized)) throw new IllegalArgumentException("Unsupported dynamic prompt enum: " + item);
            if (!result.contains(normalized)) result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static List<String> items(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        Set<String> result = new LinkedHashSet<>();
        for (Object item : iterable) {
            String normalized = text(item, MAX_ITEM);
            if (normalized != null) result.add(normalized);
            if (result.size() >= MAX_ITEMS) break;
        }
        return List.copyOf(result);
    }

    private static String required(String value, String fallback) {
        String result = text(value, MAX_TEXT);
        return result == null ? fallback : result;
    }

    private static String text(Object value, int maximum) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        String normalized = String.valueOf(value).trim().replace('\u0000', ' ');
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    private static String compact(Object value) {
        try {
            return com.chatchat.agents.protocol.ModelProtocolJson.compact(value);
        } catch (RuntimeException invalid) {
            return String.valueOf(value);
        }
    }
}
