package com.chatchat.agents.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AnswerAssemblyEngine {

    private static final Pattern GRADE_PATTERN =
        Pattern.compile("(?i)(?:^|[^A-Za-z])(?:evidenceGrade|evidence grade|grade)\\s*[:=]\\s*\"?([ABC])\"?");
    private static final Pattern MIN_GRADE_PATTERN =
        Pattern.compile("(?i)\"?minAnswerEvidenceGrade\"?\\s*[:=]\\s*\"?([ABC])\"?");
    private static final Pattern MIN_CITATIONS_PATTERN =
        Pattern.compile("(?i)\"?minAnswerCitations\"?\\s*[:=]\\s*(\\d+)");

    public AnswerAssemblyPolicy plan(List<String> observations) {
        String evidenceText = observations == null ? "" : String.join("\n", observations);
        List<String> missingInfo = new ArrayList<>();
        boolean hasEvidence = containsEvidence(evidenceText);
        boolean conflictDetected = conflictDetected(evidenceText);
        String minGrade = firstMatch(MIN_GRADE_PATTERN, evidenceText, "A");
        int minCitations = intMatch(MIN_CITATIONS_PATTERN, evidenceText, 1);
        int bestGradeRank = bestGradeRank(evidenceText);
        boolean hasRequiredGrade = bestGradeRank > 0 && bestGradeRank <= gradeRank(minGrade);

        AnswerAssemblyMode mode;
        boolean partialAllowed;
        if (!hasEvidence) {
            mode = AnswerAssemblyMode.REFUSE;
            partialAllowed = false;
            missingInfo.add("no evidence available for answer assembly");
        } else if (conflictDetected) {
            mode = AnswerAssemblyMode.REVIEW_REQUIRED;
            partialAllowed = true;
            missingInfo.add("conflicting evidence must be explained before answering");
        } else if (!hasRequiredGrade && hasGovernanceSignal(evidenceText)) {
            mode = AnswerAssemblyMode.PARTIAL;
            partialAllowed = true;
            missingInfo.add("required " + minGrade + "-grade evidence is missing");
        } else {
            mode = AnswerAssemblyMode.FULL;
            partialAllowed = true;
        }

        return new AnswerAssemblyPolicy(
            AnswerAssemblyPolicy.CONTRACT_VERSION,
            mode,
            partialAllowed,
            "place citation immediately after each evidence-backed claim",
            "if evidence conflicts, describe each side with citations and do not resolve without A-grade support",
            minGrade,
            minCitations,
            List.of("answer", "citations", "confidence", "missingInfo"),
            missingInfo
        );
    }

    public String promptInstructions(AnswerAssemblyPolicy policy) {
        AnswerAssemblyPolicy safePolicy = policy == null ? plan(List.of()) : policy;
        return """
            Answer assembly policy:
            - contractVersion: %s
            - mode: %s
            - partialAnswerAllowed: %s
            - minEvidenceGrade: %s
            - minCitations: %d
            - citationPlacement: %s
            - conflictHandling: %s
            - required output fields: answer, citations, confidence, missingInfo
            - If mode is REFUSE, do not answer with facts; state that evidence is unavailable.
            - If mode is REVIEW_REQUIRED, explain the evidence conflict or review need before giving any conclusion.
            - If mode is PARTIAL, answer only the supported portion and list missing evidence.
            """.formatted(
            safePolicy.contractVersion(),
            safePolicy.mode().name(),
            safePolicy.partialAnswerAllowed(),
            safePolicy.minEvidenceGrade(),
            safePolicy.minCitations(),
            safePolicy.citationPlacement(),
            safePolicy.conflictHandling()
        ).trim();
    }

    private boolean containsEvidence(String text) {
        return text != null && (text.contains("evidence_v1")
            || text.contains("document_evidence_v1")
            || text.contains("web_evidence_v1")
            || text.contains("doc://")
            || text.contains("web://"));
    }

    private boolean hasGovernanceSignal(String text) {
        return text != null && (text.contains("evidenceGovernancePolicy")
            || text.contains("minAnswerEvidenceGrade")
            || text.contains("evidenceGrade"));
    }

    private boolean conflictDetected(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.matches("(?is).*\"?conflictdetected\"?\\s*[:=]\\s*true.*")
            || normalized.matches("(?is).*\"?evidenceconflict\"?\\s*[:=]\\s*true.*")
            || (normalized.contains("conflictpolicy") && normalized.contains("review_on_conflict")
                && (normalized.contains("supports") || normalized.contains("contradicts")));
    }

    private int bestGradeRank(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        Matcher matcher = GRADE_PATTERN.matcher(text);
        int best = 0;
        while (matcher.find()) {
            int rank = gradeRank(matcher.group(1));
            if (best == 0 || rank < best) {
                best = rank;
            }
        }
        return best;
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

    private String firstMatch(Pattern pattern, String text, String fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : fallback;
    }

    private int intMatch(Pattern pattern, String text, int fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return fallback;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
