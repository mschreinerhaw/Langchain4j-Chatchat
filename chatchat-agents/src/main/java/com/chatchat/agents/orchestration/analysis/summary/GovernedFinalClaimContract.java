package com.chatchat.agents.orchestration.analysis.summary;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Publication boundary between governed Claim admission and the final language model.
 *
 * <p>The model may select and order admitted claims, but it cannot author new business claims.
 * User-facing Markdown is rendered deterministically from the admitted claim ledger.</p>
 */
final class GovernedFinalClaimContract {

    static final String SCHEMA_VERSION = "governed_final_claim_selection.v1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_FALLBACK_CLAIMS = 30;

    Compilation compile(List<AnalysisSummaryResult> summaries) {
        Map<String, Claim> claims = new LinkedHashMap<>();
        boolean claimContractObserved = false;
        if (summaries != null) {
            for (AnalysisSummaryResult summary : summaries) {
                if (summary == null || summary.evidence() == null) continue;
                // Reducers retain empty protocol fields for backward-compatible summaries. An
                // actual decision is the activation signal: it proves that candidate Claims were
                // evaluated, while an empty list must not turn ordinary document/workflow answers
                // into a governed data-analysis publication.
                claimContractObserved = claimContractObserved
                    || !maps(summary.evidence().get("claimAdmissionDecisions")).isEmpty();
                boolean admissionDecisionsDeclared =
                    !maps(summary.evidence().get("claimAdmissionDecisions")).isEmpty();
                Set<String> explicitlyAdmitted = admittedClaimIds(summary.evidence());
                for (Map<String, Object> insight : maps(summary.evidence().get("insights"))) {
                    String claimId = text(insight.get("claimId"));
                    String claim = text(insight.get("claim"));
                    String claimClass = text(insight.get("claimClass"));
                    List<String> recordRefs = strings(insight.get("recordRefs"));
                    List<String> supportingValues = strings(insight.get("supportingValues"));
                    if (claimId.isBlank() || claim.isBlank() || claimClass.isBlank()
                        || recordRefs.isEmpty() || supportingValues.isEmpty()
                        || (admissionDecisionsDeclared && !explicitlyAdmitted.contains(claimId))) {
                        continue;
                    }
                    claims.putIfAbsent(claimId, new Claim(
                        claimId, claim, claimClass,
                        text(insight.get("confidence")),
                        strings(insight.get("caveats"))));
                }
            }
        }
        return new Compilation(claims, claimContractObserved);
    }

    Projection project(String modelOutput, Compilation compilation) {
        if (compilation == null || compilation.claims().isEmpty()) {
            return new Projection(false, "NO_ADMITTED_CLAIMS", "", List.of());
        }
        Map<String, Object> payload = parseObject(modelOutput);
        if (payload.isEmpty() || !SCHEMA_VERSION.equals(text(payload.get("schemaVersion")))) {
            return deterministic(compilation, "FINAL_CLAIM_SELECTION_PROTOCOL_INVALID");
        }
        List<String> requested = new ArrayList<>();
        requested.addAll(strings(payload.get("headlineClaimIds")));
        for (Map<String, Object> section : maps(payload.get("sections"))) {
            requested.addAll(strings(section.get("claimIds")));
        }
        LinkedHashSet<String> selected = new LinkedHashSet<>(requested);
        boolean unknownClaim = selected.stream().anyMatch(id -> !compilation.claims().containsKey(id));
        if (selected.isEmpty() || unknownClaim) {
            return deterministic(compilation, unknownClaim
                ? "UNKNOWN_FINAL_CLAIM_ID" : "EMPTY_FINAL_CLAIM_SELECTION");
        }
        return render(compilation, selected, true, "CLAIM_SELECTION_ADMITTED");
    }

    String appendSelectionInstruction(String prompt, Compilation compilation) {
        if (compilation == null || compilation.claims().isEmpty()) return prompt;
        List<Map<String, Object>> ledger = compilation.claims().values().stream()
            .map(Claim::toPromptMap).toList();
        return (prompt == null ? "" : prompt)
            + "\n\nFinal publication contract (binding): the following ledger contains the only business "
            + "claims authorized for publication. Select and order claim IDs; do not paraphrase, combine, "
            + "recalculate or add claims. Return only one JSON object with this exact shape: "
            + "{\"schemaVersion\":\"" + SCHEMA_VERSION + "\",\"headlineClaimIds\":[],"
            + "\"sections\":[{\"claimIds\":[]}]}. Unknown IDs invalidate the whole selection. "
            + "Do not return Markdown. Admitted claim ledger: " + ModelProtocolJson.compact(ledger);
    }

    private Projection deterministic(Compilation compilation, String reason) {
        LinkedHashSet<String> selected = compilation.claims().keySet().stream()
            .limit(MAX_FALLBACK_CLAIMS)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return render(compilation, selected, false, reason);
    }

    private Projection render(Compilation compilation, Collection<String> selected,
                              boolean modelSelectionAccepted, String reason) {
        List<Claim> claims = selected.stream().map(compilation.claims()::get)
            .filter(java.util.Objects::nonNull).toList();
        if (claims.isEmpty()) return new Projection(false, reason, "", List.of());
        StringBuilder answer = new StringBuilder("# 数据分析结论\n\n## 核心发现\n\n");
        for (Claim claim : claims) answer.append("- ").append(claim.text()).append('\n');
        LinkedHashSet<String> caveats = claims.stream()
            .flatMap(claim -> claim.caveats().stream())
            .filter(value -> value != null && !value.isBlank())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!caveats.isEmpty()) {
            answer.append("\n## 分析边界\n\n");
            caveats.forEach(value -> answer.append("- ").append(value).append('\n'));
        }
        return new Projection(modelSelectionAccepted, reason, answer.toString().trim(),
            claims.stream().map(Claim::claimId).toList());
    }

    private Set<String> admittedClaimIds(Map<String, Object> evidence) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Map<String, Object> decision : maps(evidence.get("claimAdmissionDecisions"))) {
            if (Boolean.TRUE.equals(decision.get("admitted"))) {
                String claimId = text(decision.get("claimId"));
                if (!claimId.isBlank()) result.add(claimId);
            }
        }
        return Set.copyOf(result);
    }

    private Map<String, Object> parseObject(String value) {
        if (value == null || value.isBlank()) return Map.of();
        String text = value.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return Map.of();
        try {
            return OBJECT_MAPPER.readValue(text.substring(start, end + 1), new TypeReference<>() { });
        } catch (RuntimeException | java.io.IOException ignored) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : collection) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entryValue) -> {
                if (key != null) copy.put(String.valueOf(key), entryValue);
            });
            result.add(copy);
        }
        return List.copyOf(result);
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        return collection.stream().filter(java.util.Objects::nonNull).map(String::valueOf)
            .map(String::trim).filter(item -> !item.isBlank()).distinct().toList();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    record Compilation(Map<String, Claim> claims, boolean claimContractObserved) {
        Compilation {
            claims = claims == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(claims));
        }

        boolean active() {
            return !claims.isEmpty();
        }
    }

    record Projection(boolean modelSelectionAccepted, String reason, String markdown,
                      List<String> selectedClaimIds) {
    }

    private record Claim(String claimId, String text, String claimClass,
                         String confidence, List<String> caveats) {
        private Map<String, Object> toPromptMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("claimId", claimId);
            result.put("claim", text);
            result.put("claimClass", claimClass);
            if (confidence != null && !confidence.isBlank()) result.put("confidence", confidence);
            return Map.copyOf(result);
        }
    }
}
