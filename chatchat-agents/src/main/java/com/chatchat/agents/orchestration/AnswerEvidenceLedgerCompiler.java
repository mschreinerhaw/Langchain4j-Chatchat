package com.chatchat.agents.orchestration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds the immutable evidence manifest and sentence-level claim ledger used by the final answer gate. */
final class AnswerEvidenceLedgerCompiler {

    static final String CLAIM_LEDGER_VERSION = "claim_ledger_v1";
    static final String EVIDENCE_MANIFEST_VERSION = "evidence_manifest_v1";
    private static final Pattern EVIDENCE_REF = Pattern.compile(
        "(?:doc://[^\\s\\[\\]()<>，。；、！？,;]+#chunk=[^\\s\\[\\]()<>，。；、！？,;]+"
            + "|web://[^\\s\\[\\]()<>，。；、！？,;]+#result=[^\\s\\[\\]()<>，。；、！？,;]+"
            + "|https?://[^\\s\\[\\]()<>，。；、！？,;]+)");
    private static final Pattern EVIDENCE_BLOCK = Pattern.compile(
        "(?is)(?:citation|refId|sourceRef)\\s*[:=]\\s*((?:doc://[^\\s,;，。；、！？]+#chunk=[^\\s,;，。；、！？]+"
            + "|web://[^\\s,;，。；、！？]+#result=[^\\s,;，。；、！？]+)).*?"
            + "(?:content|text|snippet)\\s*[:=]\\s*(.*?)(?=\\n\\s*\\[Evidence|"
            + "\\n\\s*(?:citation|refId|sourceRef)\\s*[:=]|\\z)");
    private static final Pattern NUMBER_OR_DATE = Pattern.compile(
        "(?:\\d+(?:[.,]\\d+)?%?|\\d{4}(?:[-/年]\\d{1,2}(?:[-/月]\\d{1,2}日?)?))");
    private static final Pattern WORD = Pattern.compile("[a-z][a-z0-9_-]{2,}|[\\p{IsHan}]{2,}");

    Result compile(String answer, Map<String, Object> metadata, List<String> observations,
                   List<Map<String, Object>> toolEvidence) {
        Map<String, EvidenceItem> manifestItems = manifestItems(metadata, observations, toolEvidence);
        List<Map<String, Object>> claims = claims(answer, manifestItems);
        int material = claims.size();
        int verified = (int) claims.stream()
            .filter(item -> String.valueOf(item.get("verification")).startsWith("VERIFIED")).count();
        int criticalUnbound = (int) claims.stream()
            .filter(item -> "UNBOUND".equals(item.get("verification")))
            .filter(item -> "HIGH".equals(item.get("risk"))).count();
        int unknown = (int) claims.stream()
            .filter(item -> "UNKNOWN_REFERENCE".equals(item.get("verification"))).count();
        boolean evidenceApplicable = manifestItems.values().stream()
            .anyMatch(item -> "TRUSTED".equals(item.trustStatus())) || unknown > 0;
        double coverage = !evidenceApplicable || material == 0 ? 1.0 : round((double) verified / material);
        String status = unknown > 0 || criticalUnbound > 0
            ? "FAIL" : !evidenceApplicable ? "NOT_APPLICABLE" : coverage >= 0.999 ? "PASS" : "PARTIAL";

        Map<String, Object> claimLedger = new LinkedHashMap<>();
        claimLedger.put("contractVersion", CLAIM_LEDGER_VERSION);
        claimLedger.put("status", status);
        claimLedger.put("coverage", coverage);
        claimLedger.put("materialClaimCount", material);
        claimLedger.put("verifiedClaimCount", verified);
        claimLedger.put("criticalUnboundClaimCount", criticalUnbound);
        claimLedger.put("unknownReferenceCount", unknown);
        claimLedger.put("claims", List.copyOf(claims));
        claimLedger.put("ledgerHash", sha256(stableText(claims)));

        List<Map<String, Object>> manifest = manifestItems.values().stream().map(EvidenceItem::toMap).toList();
        Map<String, Object> evidenceManifest = new LinkedHashMap<>();
        evidenceManifest.put("contractVersion", EVIDENCE_MANIFEST_VERSION);
        evidenceManifest.put("tenantId", text(metadata == null ? null : metadata.get("tenantId")));
        evidenceManifest.put("runId", firstText(metadata, "agentRunId", "runId", "requestId"));
        evidenceManifest.put("evidenceCount", manifest.size());
        evidenceManifest.put("items", manifest);
        evidenceManifest.put("manifestHash", sha256(stableText(manifest)));
        return new Result(Map.copyOf(claimLedger), Map.copyOf(evidenceManifest), status, coverage,
            criticalUnbound, unknown);
    }

    private Map<String, EvidenceItem> manifestItems(Map<String, Object> metadata, List<String> observations,
                                                    List<Map<String, Object>> toolEvidence) {
        Map<String, String> contents = evidenceContents(observations);
        Map<String, EvidenceItem> items = new LinkedHashMap<>();
        addCitationObjects(items, metadata == null ? null : metadata.get("availableEvidenceCitations"), contents);
        Map<String, Object> evidenceAnswer = map(metadata == null ? null : metadata.get("evidenceAnswer"));
        addCitationObjects(items, evidenceAnswer.get("citations"), contents);
        String joined = observations == null ? "" : String.join("\n", observations);
        Matcher refs = EVIDENCE_REF.matcher(joined);
        while (refs.find()) {
            String ref = cleanRef(refs.group());
            items.putIfAbsent(ref, evidence(ref, evidenceType(ref), contents.get(ref), null, true));
        }
        int index = 0;
        for (Map<String, Object> tool : toolEvidence == null ? List.<Map<String, Object>>of() : toolEvidence) {
            index++;
            String toolName = text(tool.get("toolName"));
            String ref = "tool://" + (toolName.isBlank() ? "unknown" : toolName) + "#result=" + index;
            String content = firstNonBlank(tool.get("outputPreview"), tool.get("bodyPreview"),
                tool.get("stdoutPreview"), tool.get("sampleRows"));
            boolean trusted = Boolean.TRUE.equals(tool.get("success"));
            items.putIfAbsent(ref, evidence(ref, "TOOL", content, toolName, trusted));
        }
        return items;
    }

    private void addCitationObjects(Map<String, EvidenceItem> items, Object raw, Map<String, String> contents) {
        if (!(raw instanceof List<?> list)) return;
        for (Object value : list) {
            Map<String, Object> citation = map(value);
            String ref = firstNonBlank(citation.get("refId"), citation.get("sourceRef"), citation.get("url"));
            if (ref.isBlank()) continue;
            String content = firstNonBlank(citation.get("text"), citation.get("content"),
                citation.get("snippet"), contents.get(ref));
            items.putIfAbsent(ref, evidence(ref, firstNonBlank(citation.get("type"), evidenceType(ref)),
                content, firstNonBlank(citation.get("toolName"), citation.get("source")), true));
        }
    }

    private Map<String, String> evidenceContents(List<String> observations) {
        Map<String, String> values = new LinkedHashMap<>();
        String joined = observations == null ? "" : String.join("\n", observations);
        Matcher matcher = EVIDENCE_BLOCK.matcher(joined);
        while (matcher.find()) {
            values.putIfAbsent(cleanRef(matcher.group(1)), limit(normalize(matcher.group(2)), 4_000));
        }
        return values;
    }

    private List<Map<String, Object>> claims(String answer, Map<String, EvidenceItem> manifest) {
        if (answer == null || answer.isBlank()) return List.of();
        List<Map<String, Object>> claims = new ArrayList<>();
        int id = 0;
        for (String block : answer.replace("\r", "").split("\n+")) {
            String line = block.trim();
            if (line.isBlank() || line.startsWith("#") || line.matches("^[|: \\-]+$") || line.startsWith("```")) {
                continue;
            }
            line = line.replaceFirst("^\\s*(?:[-*+]\\s+|\\d+[.)\\u3001]\\s*)", "").trim();
            for (String sentence : line.split("(?<=[\\u3002\\uFF01\\uFF1F!?])")) {
                String claimText = sentence.trim();
                if (!isMaterial(claimText)) continue;
                id++;
                Set<String> refs = refs(claimText);
                List<String> known = refs.stream().filter(manifest::containsKey)
                    .filter(ref -> "TRUSTED".equals(manifest.get(ref).trustStatus())).toList();
                List<String> unknown = refs.stream().filter(ref -> !manifest.containsKey(ref)).toList();
                List<String> inferred = refs.isEmpty() ? inferredEvidenceRefs(claimText, manifest) : List.of();
                String verification = !unknown.isEmpty() ? "UNKNOWN_REFERENCE"
                    : !known.isEmpty() ? "VERIFIED"
                    : !inferred.isEmpty() ? "VERIFIED_VALUE_MATCH"
                    : manifest.isEmpty() ? "NO_EXTERNAL_EVIDENCE" : "UNBOUND";
                String risk = NUMBER_OR_DATE.matcher(claimText).find() || strongClaim(claimText) ? "HIGH" : "NORMAL";
                Map<String, Object> claim = new LinkedHashMap<>();
                claim.put("claimId", "C" + id);
                claim.put("text", claimText);
                claim.put("material", true);
                claim.put("risk", risk);
                claim.put("evidenceRefs", known.isEmpty() ? inferred : known);
                claim.put("unknownRefs", unknown);
                claim.put("verification", verification);
                claim.put("bindingMode", !known.isEmpty() ? "EXPLICIT_REFERENCE"
                    : !inferred.isEmpty() ? "DETERMINISTIC_VALUE_MATCH" : "NONE");
                claim.put("claimHash", sha256(claimText));
                claims.add(Map.copyOf(claim));
            }
        }
        return List.copyOf(claims);
    }

    /** Narrow structured-value binding: all values and at least one meaningful term must match. */
    private List<String> inferredEvidenceRefs(String claimText, Map<String, EvidenceItem> manifest) {
        List<String> claimValues = matchedValues(claimText);
        if (claimValues.isEmpty()) return List.of();
        Set<String> claimTerms = terms(claimText);
        long trustedEvidenceCount = manifest.values().stream()
            .filter(item -> "TRUSTED".equals(item.trustStatus()) && !item.contentPreview().isBlank())
            .count();
        List<String> matches = new ArrayList<>();
        for (EvidenceItem item : manifest.values()) {
            if (!"TRUSTED".equals(item.trustStatus()) || item.contentPreview().isBlank()) continue;
            String evidenceText = normalizedComparable(item.contentPreview());
            boolean allValuesPresent = claimValues.stream().map(this::normalizedComparable).allMatch(evidenceText::contains);
            Set<String> evidenceTerms = terms(item.contentPreview());
            if (allValuesPresent && (trustedEvidenceCount == 1
                || claimTerms.stream().anyMatch(evidenceTerms::contains))) {
                matches.add(item.evidenceId());
            }
        }
        return List.copyOf(matches);
    }

    private List<String> matchedValues(String value) {
        List<String> values = new ArrayList<>();
        Matcher matcher = NUMBER_OR_DATE.matcher(value == null ? "" : value);
        while (matcher.find()) values.add(matcher.group());
        return List.copyOf(values);
    }

    private Set<String> terms(String value) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = WORD.matcher((value == null ? "" : value).toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String term = matcher.group();
            if (term.codePoints().allMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                == Character.UnicodeScript.HAN)) {
                for (int index = 0; index < term.length() - 1; index++) values.add(term.substring(index, index + 2));
            } else if (!Set.of("the", "and", "with", "from", "this", "that").contains(term)) {
                values.add(term);
            }
        }
        return values;
    }

    private Set<String> refs(String value) {
        Set<String> refs = new LinkedHashSet<>();
        Matcher matcher = EVIDENCE_REF.matcher(value == null ? "" : value);
        while (matcher.find()) refs.add(cleanRef(matcher.group()));
        return refs;
    }

    private boolean isMaterial(String value) {
        if (value == null || value.length() < 8) return false;
        if (value.startsWith("|") || value.startsWith(">")
            || value.contains("以下为本次工具调用证明")
            || value.contains("完整结构化结果保留在运行元数据中")
            || (value.contains("证据类型") && value.contains("摘要="))
            || value.contains("不作为事实依据")) {
            return false;
        }
        String normalized = value.replaceAll("[`*_>#]", "").trim();
        if (normalized.length() < 8 || normalized.matches("^(证据|参考|来源|说明|结论|风险|限制)[:：]?$")) return false;
        return normalized.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    private boolean strongClaim(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return text.matches(".*(必须|确定|证明|导致|属于|符合|不符合|完成|成功|失败|增长|下降|最高|最低|"
            + "must|proves?|causes?|always|never).*" );
    }

    private EvidenceItem evidence(String ref, String type, String content, String toolName, boolean trusted) {
        String normalized = normalize(content);
        return new EvidenceItem(ref, firstNonBlank(type, "UNKNOWN"), toolName == null ? "" : toolName,
            trusted ? "TRUSTED" : "UNAVAILABLE", normalized, sha256(normalized));
    }

    private String evidenceType(String ref) {
        if (ref == null) return "UNKNOWN";
        if (ref.startsWith("doc://")) return "DOCUMENT";
        if (ref.startsWith("web://") || ref.startsWith("http")) return "WEB";
        if (ref.startsWith("tool://")) return "TOOL";
        return "UNKNOWN";
    }

    private String cleanRef(String value) {
        return value == null ? "" : value.replaceAll("[。；，！？;,.!?）)\\]]+$", "").trim();
    }

    private String firstText(Map<String, Object> values, String... keys) {
        if (values == null) return "";
        for (String key : keys) {
            String value = text(values.get(key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = text(value);
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private String normalizedComparable(String value) {
        return (value == null ? "" : value).toLowerCase(Locale.ROOT).replace(",", "").replaceAll("\\s+", "");
    }

    private String normalize(Object value) {
        return value == null ? "" : String.valueOf(value).replaceAll("\\s+", " ").trim();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String limit(String value, int max) {
        return value == null || value.length() <= max ? (value == null ? "" : value) : value.substring(0, max);
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private String stableText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    record Result(Map<String, Object> claimLedger, Map<String, Object> evidenceManifest,
                  String status, double coverage, int criticalUnboundClaims, int unknownReferences) {
        String reviewerContext() {
            return "Answer evidence preflight (contractVersion=" + CLAIM_LEDGER_VERSION + "): status=" + status
                + ", coverage=" + coverage + ", criticalUnboundClaims=" + criticalUnboundClaims
                + ", unknownReferences=" + unknownReferences
                + ". Reject unsupported high-risk claims and unknown citations; require each numeric, date, causal, "
                + "or definitive claim to carry a returned evidence reference near that claim.";
        }
    }

    private record EvidenceItem(String evidenceId, String type, String toolName, String trustStatus,
                                String contentPreview, String contentHash) {
        Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("evidenceId", evidenceId);
            value.put("type", type);
            value.put("toolName", toolName);
            value.put("trustStatus", trustStatus);
            value.put("contentPreview", contentPreview);
            value.put("contentHash", contentHash);
            return Map.copyOf(value);
        }
    }
}
