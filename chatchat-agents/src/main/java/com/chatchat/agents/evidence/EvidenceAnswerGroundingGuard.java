package com.chatchat.agents.evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EvidenceAnswerGroundingGuard {

    private static final Pattern EVIDENCE_GRADE_PATTERN =
        Pattern.compile("(?i)(?:^|[^A-Za-z])(?:evidenceGrade|evidence grade|grade)\\s*[:=]\\s*\"?([ABC])\"?");
    public static final String EVIDENCE_ANSWER_CONTRACT = "evidence_answer_v1";
    private static final String INSUFFICIENT_EVIDENCE_ANSWER = "根据当前证据不足，无法确认。";
    private static final Pattern DOCUMENT_REF_PATTERN =
        Pattern.compile("doc://([^\\s\"',;，。、\\]\\)}]+)#chunk=(\\d+)");
    private static final Pattern WEB_REF_PATTERN =
        Pattern.compile("web://([^\\s\"',;，。、\\]\\)}]+)#result=(\\d+)");

    public GroundingResult guard(String answer, List<String> observations) {
        List<String> safeObservations = observations == null ? List.of() : observations;
        List<Map<String, Object>> availableCitations = extractCitationMaps(String.join("\n", safeObservations));
        List<Map<String, Object>> answerCitations = extractCitationMaps(answer);
        List<String> missingInfo = new ArrayList<>();

        if (answer == null || answer.isBlank()) {
            missingInfo.add("answer text is missing");
        }
        if (containsEvidence(safeObservations) && availableCitations.isEmpty()) {
            missingInfo.add("evidence citations are missing from observations");
        }
        if (!availableCitations.isEmpty() && answerCitations.isEmpty()) {
            missingInfo.add("answer citations are missing");
        }
        if (hasUnknownCitation(answerCitations, availableCitations)) {
            missingInfo.add("answer cites evidence not returned by evidence tools");
        }
        EvidenceGovernance governance = governance(safeObservations, availableCitations);
        if (governance.requiresMinGrade() && !answerCitations.isEmpty()
            && !citesAtLeastGrade(answerCitations, availableCitations, governance.minAnswerEvidenceGrade())) {
            missingInfo.add("answer must cite at least one " + governance.minAnswerEvidenceGrade() + "-grade evidence");
        }
        if (governance.conflictDetected()) {
            missingInfo.add("evidence conflict requires review before answer assembly");
        }

        String confidence = missingInfo.isEmpty()
            ? answerCitations.isEmpty() ? "medium" : "high"
            : "low";
        EvidenceAnswer evidenceAnswer = new EvidenceAnswer(
            finalAnswerText(answer, missingInfo),
            answerCitations,
            confidence,
            missingInfo
        );
        return new GroundingResult(
            EVIDENCE_ANSWER_CONTRACT,
            evidenceAnswer,
            availableCitations,
            missingInfo.isEmpty() ? "grounded" : "needs_review"
        );
    }

    public boolean containsEvidence(List<String> observations) {
        return observations != null && observations.stream()
            .filter(value -> value != null)
            .anyMatch(value -> value.contains("evidence_v1")
                || value.contains("document_evidence_v1")
                || value.contains("web_evidence_v1")
                || value.contains("doc://")
                || value.contains("web://"));
    }

    public List<Map<String, Object>> extractCitationMaps(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> citations = new ArrayList<>();
        Matcher documentMatcher = DOCUMENT_REF_PATTERN.matcher(text);
        while (documentMatcher.find()) {
            String fileId = documentMatcher.group(1);
            String chunkValue = documentMatcher.group(2);
            String refId = "doc://" + fileId + "#chunk=" + chunkValue;
            if (!seen.add(refId)) {
                continue;
            }
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("refId", refId);
            citation.put("type", "DOCUMENT");
            citation.put("fileId", fileId);
            citation.put("fileName", null);
            citation.put("section", null);
            citation.put("chunkIndex", parseInteger(chunkValue));
            String evidenceGrade = gradeNear(text, documentMatcher.start(), documentMatcher.end());
            if (evidenceGrade != null) {
                citation.put("evidenceGrade", evidenceGrade);
            }
            citations.add(citation);
        }

        Matcher webMatcher = WEB_REF_PATTERN.matcher(text);
        while (webMatcher.find()) {
            String source = webMatcher.group(1);
            String resultValue = webMatcher.group(2);
            String refId = "web://" + source + "#result=" + resultValue;
            if (!seen.add(refId)) {
                continue;
            }
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("refId", refId);
            citation.put("type", "WEB");
            citation.put("source", source);
            citation.put("resultIndex", parseInteger(resultValue));
            citations.add(citation);
        }
        return List.copyOf(citations);
    }

    private EvidenceGovernance governance(List<String> observations, List<Map<String, Object>> availableCitations) {
        String joined = observations == null ? "" : String.join("\n", observations);
        boolean hasGovernancePolicy = joined.contains("evidenceGovernancePolicy")
            || joined.contains("minAnswerEvidenceGrade")
            || joined.contains("citationRequired");
        boolean hasGrades = availableCitations.stream().anyMatch(citation -> citation.containsKey("evidenceGrade"));
        String minGrade = firstGradeAfter(joined, "minAnswerEvidenceGrade");
        if (minGrade == null && (hasGovernancePolicy || hasGrades)) {
            minGrade = "A";
        }
        boolean conflictDetected = joined.matches("(?is).*\"?conflictDetected\"?\\s*[:=]\\s*true.*")
            || joined.matches("(?is).*\"?evidenceConflict\"?\\s*[:=]\\s*true.*")
            || joined.matches("(?is).*\"?conflictPolicy\"?\\s*[:=]\\s*\"?review_on_conflict\"?.*(supports|contradicts).*");
        return new EvidenceGovernance(minGrade != null, minGrade == null ? "A" : minGrade, conflictDetected);
    }

    private boolean citesAtLeastGrade(List<Map<String, Object>> answerCitations,
                                      List<Map<String, Object>> availableCitations,
                                      String minGrade) {
        Set<String> answerRefIds = new LinkedHashSet<>();
        for (Map<String, Object> citation : answerCitations == null ? List.<Map<String, Object>>of() : answerCitations) {
            Object refId = citation.get("refId");
            if (refId != null) {
                answerRefIds.add(String.valueOf(refId));
            }
        }
        for (Map<String, Object> citation : availableCitations == null ? List.<Map<String, Object>>of() : availableCitations) {
            Object refId = citation.get("refId");
            if (refId == null || !answerRefIds.contains(String.valueOf(refId))) {
                continue;
            }
            Object grade = citation.get("evidenceGrade");
            if (grade != null && gradeRank(String.valueOf(grade)) <= gradeRank(minGrade)) {
                return true;
            }
        }
        return false;
    }

    private String gradeNear(String text, int start, int end) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int afterEnd = Math.min(text.length(), end + 220);
        Matcher afterMatcher = EVIDENCE_GRADE_PATTERN.matcher(text.substring(end, afterEnd));
        if (afterMatcher.find()) {
            return afterMatcher.group(1).toUpperCase();
        }
        int windowStart = Math.max(0, start - 160);
        Matcher matcher = EVIDENCE_GRADE_PATTERN.matcher(text.substring(windowStart, start));
        return matcher.find() ? matcher.group(1).toUpperCase() : null;
    }

    private String firstGradeAfter(String text, String field) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Pattern pattern = Pattern.compile("(?i)\"?" + Pattern.quote(field) + "\"?\\s*[:=]\\s*\"?([ABC])\"?");
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).toUpperCase() : null;
    }

    private int gradeRank(String grade) {
        if ("A".equalsIgnoreCase(grade)) {
            return 1;
        }
        if ("B".equalsIgnoreCase(grade)) {
            return 2;
        }
        return 3;
    }

    private boolean hasUnknownCitation(List<Map<String, Object>> answerCitations,
                                       List<Map<String, Object>> availableCitations) {
        if (answerCitations.isEmpty() || availableCitations.isEmpty()) {
            return false;
        }
        Set<String> availableRefIds = new LinkedHashSet<>();
        for (Map<String, Object> citation : availableCitations) {
            Object refId = citation.get("refId");
            if (refId != null) {
                availableRefIds.add(String.valueOf(refId));
            }
        }
        return answerCitations.stream()
            .map(citation -> citation.get("refId"))
            .filter(value -> value != null)
            .map(String::valueOf)
            .anyMatch(refId -> !availableRefIds.contains(refId));
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String finalAnswerText(String answer, List<String> missingInfo) {
        if (answer != null && !answer.isBlank()) {
            return answer;
        }
        if (missingInfo == null || missingInfo.isEmpty()) {
            return "";
        }
        return INSUFFICIENT_EVIDENCE_ANSWER;
    }

    public record GroundingResult(
        String contractVersion,
        EvidenceAnswer evidenceAnswer,
        List<Map<String, Object>> availableCitations,
        String groundingStatus
    ) {

        public GroundingResult {
            availableCitations = availableCitations == null
                ? List.of()
                : availableCitations.stream()
                    .map(item -> item == null ? Map.<String, Object>of() : new LinkedHashMap<>(item))
                    .toList();
            groundingStatus = groundingStatus == null || groundingStatus.isBlank() ? "needs_review" : groundingStatus;
        }
    }

    private record EvidenceGovernance(
        boolean requiresMinGrade,
        String minAnswerEvidenceGrade,
        boolean conflictDetected
    ) {
    }
}
