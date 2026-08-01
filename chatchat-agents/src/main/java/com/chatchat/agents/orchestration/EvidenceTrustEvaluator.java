package com.chatchat.agents.orchestration;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class EvidenceTrustEvaluator {

    private static final double MIN_SCORE = 0.30d;
    private static final double UNTRUSTED_DOMAIN_DOWNGRADE = 0.12d;
    private static final double MIN_USABLE_SCORE = 0.20d;
    private static final Set<String> TRUSTED_DOMAIN_HINTS = Set.of(
        "docs.", "developer.", "learn.microsoft.com", "github.com", "wikipedia.org",
        "openai.com", "spring.io", "oracle.com", "ietf.org", "w3.org", "apache.org"
    );

    /**
     * Evaluates whether web evidence is trustworthy enough for Agent reasoning.
     *
     * @param evidenceChunks the evidence chunks value
     * @return the operation result
     */
    public TrustResult evaluate(List<Map<String, Object>> evidenceChunks) {
        if (evidenceChunks == null || evidenceChunks.isEmpty()) {
            return new TrustResult(List.of(), Map.of(
                "version", "agent_evidence_trust_policy_v1",
                "usableCount", 0,
                "ignoredLowScoreCount", 0,
                "downgradedDomainCount", 0,
                "contradictionDetected", false,
                "requestMoreEvidence", true,
                "reason", "No evidence chunks available"
            ));
        }

        List<Map<String, Object>> usable = new ArrayList<>();
        int ignoredLowScore = 0;
        int downgradedDomain = 0;
        for (Map<String, Object> chunk : evidenceChunks) {
            double originalScore = numberValue(chunk.get("score"), 0.0d).doubleValue();
            if (originalScore < MIN_SCORE) {
                ignoredLowScore++;
                continue;
            }
            String domain = firstNonBlank(stringValue(chunk.get("domain")), domain(stringValue(chunk.get("source_url"))));
            boolean trustedDomain = isTrustedDomain(domain);
            double adjustedScore = trustedDomain ? originalScore : Math.max(0.0d, originalScore - UNTRUSTED_DOMAIN_DOWNGRADE);
            if (!trustedDomain) {
                downgradedDomain++;
            }
            if (adjustedScore < MIN_USABLE_SCORE) {
                ignoredLowScore++;
                continue;
            }

            Map<String, Object> trusted = new LinkedHashMap<>(chunk);
            trusted.put("trust_score", round(adjustedScore));
            trusted.put("trust_policy", Map.of(
                "trusted_domain", trustedDomain,
                "domain", domain == null ? "unknown" : domain,
                "original_score", round(originalScore),
                "adjusted_score", round(adjustedScore),
                "domain_downgrade", trustedDomain ? 0.0d : UNTRUSTED_DOMAIN_DOWNGRADE
            ));
            usable.add(trusted);
        }

        ContradictionAssessment contradictionAssessment = contradictionAssessment(usable);
        boolean contradiction = contradictionAssessment.detected();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("version", "agent_evidence_trust_policy_v1");
        metadata.put("minScore", MIN_SCORE);
        metadata.put("minUsableScore", MIN_USABLE_SCORE);
        metadata.put("usableCount", usable.size());
        metadata.put("ignoredLowScoreCount", ignoredLowScore);
        metadata.put("downgradedDomainCount", downgradedDomain);
        metadata.put("contradictionDetected", contradiction);
        metadata.put("conflictAttribution", contradictionAssessment.attribution());
        metadata.put("requestMoreEvidence", usable.isEmpty() || contradiction);
        metadata.put("reason", contradiction
            ? "Potential contradiction detected across evidence chunks"
            : usable.isEmpty() ? "No evidence passed trust policy" : "Evidence passed trust policy");
        return new TrustResult(usable, metadata);
    }

    private ContradictionAssessment contradictionAssessment(List<Map<String, Object>> chunks) {
        List<Map<String, Object>> positive = new ArrayList<>();
        List<Map<String, Object>> negative = new ArrayList<>();
        for (Map<String, Object> chunk : chunks) {
            String text = String.join(" ",
                stringValue(chunk.get("content")),
                stringValue(chunk.get("snippet")),
                stringValue(chunk.get("excerpt"))
            ).toLowerCase(Locale.ROOT);
            if (containsAny(text, " is supported", " supports ", " enabled", " available", " compatible", " can ",
                "支持", "可以", "兼容", "已启用")) {
                positive.add(sourceAttribution(chunk, "positive"));
            }
            if (containsAny(text, " not supported", " does not support", " unsupported", " disabled", " unavailable",
                "不支持", "不能", "无法", "不兼容", "未启用")) {
                negative.add(sourceAttribution(chunk, "negative"));
            }
        }
        if (positive.isEmpty() || negative.isEmpty()) {
            return new ContradictionAssessment(false, List.of());
        }
        List<Map<String, Object>> attribution = new ArrayList<>(positive.size() + negative.size());
        attribution.addAll(positive);
        attribution.addAll(negative);
        return new ContradictionAssessment(true, List.copyOf(attribution));
    }

    private Map<String, Object> sourceAttribution(Map<String, Object> chunk, String stance) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("stance", stance);
        source.put("sourceRef", firstNonBlank(
            stringValue(chunk.get("source_ref")),
            firstNonBlank(stringValue(chunk.get("source_url")), stringValue(chunk.get("url")))
        ));
        source.put("title", firstNonBlank(stringValue(chunk.get("title")), "unknown"));
        source.put("score", numberValue(chunk.get("trust_score"), numberValue(chunk.get("score"), 0.0d)));
        return Map.copyOf(source);
    }

    private boolean isTrustedDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return false;
        }
        String normalized = domain.toLowerCase(Locale.ROOT);
        return TRUSTED_DOMAIN_HINTS.stream().anyMatch(normalized::contains);
    }

    private boolean containsAny(String value, String... needles) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String domain(String url) {
        try {
            return url == null || url.isBlank() ? null : URI.create(url).getHost();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Number numberValue(Object value, Number fallback) {
        if (value instanceof Number number) {
            return number;
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private double round(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record TrustResult(
        List<Map<String, Object>> usableEvidence,
        Map<String, Object> metadata
    ) {
    }

    private record ContradictionAssessment(boolean detected, List<Map<String, Object>> attribution) {
    }
}
