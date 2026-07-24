package com.chatchat.chat.task;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fail-closed notification gate. The model may recommend sending, but Java
 * accepts that recommendation only when every cited evidence identifier exists
 * in the immutable Agent evidence and every supporting quote is verbatim.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationPolicyEvaluator {

    public static final String PROTOCOL_VERSION = "notification_policy_decision_v1";
    private static final int MAX_REPORT_CHARS = 24_000;
    private static final int MAX_EVIDENCE_ITEMS = 80;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectMapper objectMapper;

    public Decision evaluate(String condition, String report, List<Map<String, Object>> references) {
        String normalizedCondition = text(condition);
        if (normalizedCondition.isBlank()) {
            return Decision.skip("未配置发送条件", List.of(), List.of(), false);
        }
        List<EvidenceItem> evidence = evidenceItems(references);
        if (evidence.isEmpty()) {
            return Decision.skip("本次运行没有可验证的证据，禁止条件通知", List.of(), List.of(), false);
        }
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            return Decision.skip("通知条件判断模型不可用，按严格策略不发送", List.of(), List.of(), false);
        }

        String prompt = """
            你是 Agent Runtime 的通知出口审查器，不负责生成或修改报告。
            任务：仅根据给定发送条件、不可变报告和证据清单，判断本次是否允许发送通知。

            强制规则：
            1. 不得补充、推测或创造任何事实。
            2. send=true 时必须至少给出一个 evidence_match。
            3. evidence_id 必须逐字取自 evidenceInventory[].evidence_id。
            4. fact_quote 必须是对应 evidenceInventory[].content 中连续、逐字一致的非空子串。
            5. 只有 fact_quote 能直接证明发送条件成立时才可 send=true。
            6. 报告仅用于理解上下文，不是独立证据；没有足够证据必须 send=false。
            7. 只输出 JSON，不要 Markdown：
               {"send":true|false,"matched_conditions":["已满足的条件"],"evidence_matches":[{"evidence_id":"原有ID","fact_quote":"证据原文"}],"reason":"简短理由"}

            notificationConditionJson: "%s"
            immutableReportJson: "%s"
            evidenceInventoryJson: %s
            """.formatted(
                ModelProtocolJson.jsonStringContent(normalizedCondition),
                ModelProtocolJson.jsonStringContent(limit(report, MAX_REPORT_CHARS)),
                ModelProtocolJson.compact(evidence.stream().map(EvidenceItem::promptView).toList())
            );
        try {
            String raw = model.chat(prompt);
            Map<String, Object> response = objectMapper.readValue(jsonObject(raw), new TypeReference<>() {});
            return validate(response, evidence);
        } catch (Exception ex) {
            log.warn("Notification condition evaluation failed; notification will be skipped: {}", ex.getMessage());
            return Decision.skip("通知条件判断失败，按严格策略不发送：" + safeMessage(ex), List.of(), List.of(), false);
        }
    }

    private Decision validate(Map<String, Object> response, List<EvidenceItem> evidence) {
        if (!Boolean.TRUE.equals(response.get("send"))) {
            return Decision.skip(firstText(response.get("reason"), "未满足发送条件"),
                stringList(response.get("matched_conditions")), List.of(), true);
        }
        Map<String, EvidenceItem> inventory = new LinkedHashMap<>();
        evidence.forEach(item -> inventory.put(item.id(), item));
        Object matchesValue = response.get("evidence_matches");
        if (!(matchesValue instanceof List<?> rows) || rows.isEmpty()) {
            return Decision.skip("模型建议发送但未提供证据，系统已拒绝发送", List.of(), List.of(), false);
        }
        List<EvidenceMatch> matches = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                return Decision.skip("发送依据格式无效，系统已拒绝发送", List.of(), List.of(), false);
            }
            String evidenceId = text(map.get("evidence_id"));
            String quote = text(map.get("fact_quote"));
            EvidenceItem item = inventory.get(evidenceId);
            if (item == null || quote.length() < 2 || !item.content().contains(quote)) {
                return Decision.skip("发送依据无法在本次证据链中逐字验证，系统已拒绝发送",
                    List.of(), List.of(), false);
            }
            matches.add(new EvidenceMatch(evidenceId, quote));
        }
        List<String> matchedConditions = stringList(response.get("matched_conditions"));
        if (matchedConditions.isEmpty()) {
            return Decision.skip("模型未指出满足的发送条件，系统已拒绝发送", List.of(), List.of(), false);
        }
        return new Decision(true, firstText(response.get("reason"), "满足发送条件"),
            matchedConditions, List.copyOf(matches), true);
    }

    private List<EvidenceItem> evidenceItems(List<Map<String, Object>> references) {
        if (references == null || references.isEmpty()) {
            return List.of();
        }
        List<EvidenceItem> result = new ArrayList<>();
        Set<String> usedIds = new LinkedHashSet<>();
        int index = 0;
        for (Map<String, Object> reference : references) {
            if (reference == null || reference.isEmpty() || result.size() >= MAX_EVIDENCE_ITEMS) {
                continue;
            }
            index++;
            String baseId = firstText(
                reference.get("evidenceId"), reference.get("evidence_id"),
                reference.get("refId"), reference.get("ref_id"),
                reference.get("sourceRef"), reference.get("source_ref"),
                reference.get("url"), "reference:" + index
            );
            String id = uniqueId(baseId, usedIds);
            String content = ModelProtocolJson.compact(reference);
            if (!content.isBlank()) {
                result.add(new EvidenceItem(id, content));
            }
        }
        return List.copyOf(result);
    }

    private String uniqueId(String candidate, Set<String> used) {
        String base = candidate == null || candidate.isBlank() ? "reference" : candidate;
        String value = base;
        int suffix = 2;
        while (!used.add(value)) {
            value = base + "#" + suffix++;
        }
        return value;
    }

    private String jsonObject(String raw) {
        String value = raw == null ? "" : raw.trim();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("model output does not contain a JSON object");
        }
        return value.substring(start, end + 1);
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(this::text).filter(item -> !item.isBlank()).distinct().toList();
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            String candidate = text(value);
            if (!candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String limit(String value, int maxLength) {
        String normalized = value == null ? "" : value;
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private String safeMessage(Exception ex) {
        String message = ex == null ? "" : text(ex.getMessage());
        return message.isBlank() ? "unknown error" : limit(message, 300);
    }

    private record EvidenceItem(String id, String content) {
        private Map<String, Object> promptView() {
            return Map.of("evidence_id", id, "content", content);
        }
    }

    public record EvidenceMatch(String evidenceId, String factQuote) {
    }

    public record Decision(boolean send,
                           String reason,
                           List<String> matchedConditions,
                           List<EvidenceMatch> evidenceMatches,
                           boolean verified) {

        public Decision {
            reason = reason == null ? "" : reason;
            matchedConditions = matchedConditions == null ? List.of() : List.copyOf(matchedConditions);
            evidenceMatches = evidenceMatches == null ? List.of() : List.copyOf(evidenceMatches);
        }

        static Decision skip(String reason, List<String> conditions,
                             List<EvidenceMatch> evidence, boolean verified) {
            return new Decision(false, reason, conditions, evidence, verified);
        }

        public Map<String, Object> audit() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("contractVersion", PROTOCOL_VERSION);
            value.put("decision", send ? "SEND" : "SKIP");
            value.put("reason", reason);
            value.put("matchedConditions", matchedConditions);
            value.put("evidenceMatches", evidenceMatches);
            value.put("verified", verified);
            value.put("evaluatedAt", Instant.now().toString());
            return value;
        }
    }
}
