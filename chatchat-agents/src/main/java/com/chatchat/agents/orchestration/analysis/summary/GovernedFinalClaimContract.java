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
 * <p>The model may select and order admitted claims and propose clearly marked, non-factual
 * demand/metric investigation directions grounded in those claims. It cannot author new business
 * facts. User-facing Markdown is rendered deterministically from the admitted claim ledger.</p>
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
                        text(insight.get("significance")),
                        strings(insight.get("caveats")),
                        claimSource(summary)));
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
        List<String> headlineClaimIds = strings(payload.get("headlineClaimIds"));
        List<Section> sections = maps(payload.get("sections")).stream()
            .map(section -> new Section(sectionType(section.get("sectionType")),
                strings(section.get("claimIds"))))
            .filter(section -> !section.claimIds().isEmpty())
            .toList();
        List<String> requested = new ArrayList<>(headlineClaimIds);
        sections.forEach(section -> requested.addAll(section.claimIds()));
        LinkedHashSet<String> selected = new LinkedHashSet<>(requested);
        DemandAnalysis demandAnalysis = demandAnalysis(payload.get("demandAnalysis"));
        List<MetricAssociation> metricAssociations = maps(payload.get("metricAssociations")).stream()
            .map(this::metricAssociation)
            .filter(java.util.Objects::nonNull)
            .limit(8)
            .toList();
        ManagementReview managementReview = managementReview(payload.get("managementReview"));
        boolean unknownClaim = selected.stream().anyMatch(id -> !compilation.claims().containsKey(id));
        boolean invalidAssociationBasis = metricAssociations.stream()
            .flatMap(association -> association.basisClaimIds().stream())
            .anyMatch(id -> !selected.contains(id) || !compilation.claims().containsKey(id));
        boolean invalidReviewBasis = managementReview.items().stream()
            .flatMap(item -> item.basisClaimIds().stream())
            .anyMatch(id -> !selected.contains(id) || !compilation.claims().containsKey(id));
        if (selected.isEmpty() || unknownClaim) {
            return deterministic(compilation, unknownClaim
                ? "UNKNOWN_FINAL_CLAIM_ID" : "EMPTY_FINAL_CLAIM_SELECTION");
        }
        if (invalidAssociationBasis) {
            return deterministic(compilation, "INVALID_METRIC_ASSOCIATION_BASIS");
        }
        if (invalidReviewBasis) {
            return deterministic(compilation, "INVALID_MANAGEMENT_REVIEW_BASIS");
        }
        return render(compilation, headlineClaimIds, sections, selected,
            demandAnalysis, metricAssociations, managementReview,
            true, "CLAIM_SELECTION_ADMITTED");
    }

    String appendSelectionInstruction(String prompt, Compilation compilation) {
        if (compilation == null || compilation.claims().isEmpty()) return prompt;
        List<Map<String, Object>> ledger = compilation.claims().values().stream()
            .map(Claim::toPromptMap).toList();
        return (prompt == null ? "" : prompt)
            + "\n\nFinal publication contract (binding): the following ledger contains the only business "
            + "claims authorized for publication. Select and order claim IDs; do not paraphrase, combine, "
            + "recalculate or add claims. Select the smallest non-redundant set that answers the original question. "
            + "Prefer claims that explain a material change, comparison, concentration, exception, supported impact "
            + "or action over bare counts, extrema and inventory facts. Do not select a weaker restatement when a "
            + "stronger admitted claim already contains it. Put at most three decisive claims in headlineClaimIds; "
            + "place remaining necessary support under a source-neutral sectionType. Return only one JSON object "
            + "with this exact shape: "
            + "{\"schemaVersion\":\"" + SCHEMA_VERSION + "\",\"headlineClaimIds\":[],"
            + "\"sections\":[{\"sectionType\":\"EVIDENCE|EXCEPTIONS|ACTIONS\",\"claimIds\":[]}],"
            + "\"demandAnalysis\":{\"decisionGoal\":\"\",\"priorityQuestions\":[]},"
            + "\"metricAssociations\":[{\"title\":\"\",\"basisClaimIds\":[],"
            + "\"candidateMetrics\":[],\"analysisMethod\":\"\",\"validationNeeded\":[]}],"
            + "\"managementReview\":{"
            + "\"overallAssessment\":{\"text\":\"\",\"basisClaimIds\":[]},"
            + "\"identifiedProblems\":[{\"text\":\"\",\"basisClaimIds\":[]}],"
            + "\"improvementSuggestions\":[{\"text\":\"\",\"basisClaimIds\":[]}],"
            + "\"nextWorkDirections\":[{\"text\":\"\",\"basisClaimIds\":[]}]}}. "
            + "demandAnalysis explains the decision goal and open questions without adding facts. "
            + "metricAssociations are optional, explicitly unverified follow-up directions grounded in selected "
            + "admitted claims. Each must cite selected basisClaimIds and state candidate metrics, method and "
            + "validation needed. Never introduce a new observed value, entity state, threshold or causal claim. "
            + "Act as the manager reviewing completed Worker analysis reports: managementReview must synthesize "
            + "what the analyses collectively established, detect evidence/coverage/method gaps, and provide "
            + "specific improvements and prioritized next work. Do not repeat the ledger as a row inventory. Every "
            + "non-empty review item must cite selected basisClaimIds; it is an evaluation of the admitted analysis, "
            + "not permission to create a new business fact. "
            + "Unknown IDs invalidate the whole selection. "
            + "Do not return Markdown. Admitted claim ledger: " + ModelProtocolJson.compact(ledger);
    }

    private Projection deterministic(Compilation compilation, String reason) {
        LinkedHashSet<String> selected = compilation.claims().keySet().stream()
            .limit(MAX_FALLBACK_CLAIMS)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return render(compilation, List.copyOf(selected), List.of(), selected,
            DemandAnalysis.empty(), List.of(), ManagementReview.empty(), false, reason);
    }

    private Projection render(Compilation compilation, Collection<String> headlineClaimIds,
                              List<Section> requestedSections, Collection<String> selected,
                              DemandAnalysis demandAnalysis,
                              List<MetricAssociation> metricAssociations,
                              ManagementReview managementReview,
                              boolean modelSelectionAccepted, String reason) {
        List<Claim> claims = selected.stream().map(compilation.claims()::get)
            .filter(java.util.Objects::nonNull).toList();
        if (claims.isEmpty()) return new Projection(false, reason, "", List.of());
        StringBuilder answer = new StringBuilder("# 数据分析结论\n\n## 核心结论\n\n");
        List<Claim> headlines = headlineClaimIds.stream().distinct()
            .map(compilation.claims()::get).filter(java.util.Objects::nonNull).toList();
        if (headlines.isEmpty()) headlines = claims;
        for (Claim claim : headlines) answer.append("- ").append(claim.text()).append('\n');
        if (!requestedSections.isEmpty()) {
            Set<String> rendered = new LinkedHashSet<>();
            headlines.forEach(claim -> rendered.add(claim.claimId()));
            for (Section section : requestedSections) {
                List<Claim> sectionClaims = section.claimIds().stream().distinct()
                    .filter(id -> !rendered.contains(id)).map(compilation.claims()::get)
                    .filter(java.util.Objects::nonNull).toList();
                if (sectionClaims.isEmpty()) continue;
                answer.append("\n## ").append(section.title()).append("\n\n");
                sectionClaims.forEach(claim -> {
                    answer.append("- ").append(claim.text()).append('\n');
                    rendered.add(claim.claimId());
                });
            }
        }
        LinkedHashSet<String> caveats = claims.stream()
            .flatMap(claim -> claim.caveats().stream())
            .filter(value -> value != null && !value.isBlank())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!caveats.isEmpty()) {
            answer.append("\n## 分析边界\n\n");
            caveats.forEach(value -> answer.append("- ").append(value).append('\n'));
        }
        if (demandAnalysis != null && !demandAnalysis.emptyValue()) {
            answer.append("\n## 需求分析\n\n");
            if (!demandAnalysis.decisionGoal().isBlank()) {
                answer.append("- 决策目标：").append(demandAnalysis.decisionGoal()).append('\n');
            }
            demandAnalysis.priorityQuestions().forEach(question ->
                answer.append("- 待回答问题：").append(question).append('\n'));
        }
        if (metricAssociations != null && !metricAssociations.isEmpty()) {
            answer.append("\n## 指标联想与后续分析\n\n")
                .append("> 以下内容是基于已返回数据提出的待验证分析方向，不代表当前数据已经证明。\n\n");
            for (MetricAssociation association : metricAssociations) {
                answer.append("- ").append(association.title());
                if (!association.candidateMetrics().isEmpty()) {
                    answer.append("；候选指标：")
                        .append(String.join("、", association.candidateMetrics()));
                }
                if (!association.analysisMethod().isBlank()) {
                    answer.append("；建议方法：").append(association.analysisMethod());
                }
                if (!association.validationNeeded().isEmpty()) {
                    answer.append("；验证所需：")
                        .append(String.join("、", association.validationNeeded()));
                }
                answer.append('\n');
            }
        }
        if (managementReview != null && !managementReview.emptyValue()) {
            answer.append("\n## 分析复盘与改进方向\n\n");
            if (managementReview.overallAssessment() != null) {
                answer.append("- 总体评价：")
                    .append(managementReview.overallAssessment().text()).append('\n');
            }
            managementReview.identifiedProblems().forEach(item ->
                answer.append("- 发现的问题：").append(item.text()).append('\n'));
            managementReview.improvementSuggestions().forEach(item ->
                answer.append("- 改进建议：").append(item.text()).append('\n'));
            managementReview.nextWorkDirections().forEach(item ->
                answer.append("- 下一步方向：").append(item.text()).append('\n'));
        }
        return new Projection(modelSelectionAccepted, reason, answer.toString().trim(),
            claims.stream().map(Claim::claimId).toList());
    }

    private DemandAnalysis demandAnalysis(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return DemandAnalysis.empty();
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null) map.put(String.valueOf(key), item);
        });
        return new DemandAnalysis(boundedText(map.get("decisionGoal"), 500),
            boundedStrings(map.get("priorityQuestions"), 8, 300));
    }

    private MetricAssociation metricAssociation(Map<String, Object> value) {
        String title = boundedText(value.get("title"), 300);
        List<String> basis = boundedStrings(value.get("basisClaimIds"), 10, 160);
        if (title.isBlank() || basis.isEmpty()) return null;
        return new MetricAssociation(title, basis,
            boundedStrings(value.get("candidateMetrics"), 12, 160),
            boundedText(value.get("analysisMethod"), 500),
            boundedStrings(value.get("validationNeeded"), 12, 300));
    }

    private ManagementReview managementReview(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return ManagementReview.empty();
        Map<String, Object> source = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null) source.put(String.valueOf(key), item);
        });
        ReviewItem overall = reviewItem(source.get("overallAssessment"));
        return new ManagementReview(overall,
            reviewItems(source.get("identifiedProblems")),
            reviewItems(source.get("improvementSuggestions")),
            reviewItems(source.get("nextWorkDirections")));
    }

    private List<ReviewItem> reviewItems(Object value) {
        return maps(value).stream().map(this::reviewItem)
            .filter(java.util.Objects::nonNull).limit(8).toList();
    }

    private ReviewItem reviewItem(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return null;
        Map<String, Object> source = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null) source.put(String.valueOf(key), item);
        });
        String reviewText = boundedText(source.get("text"), 500);
        List<String> basis = boundedStrings(source.get("basisClaimIds"), 12, 160);
        return reviewText.isBlank() || basis.isEmpty() ? null : new ReviewItem(reviewText, basis);
    }

    private String claimSource(AnalysisSummaryResult summary) {
        if (summary == null || summary.position() == null) return "";
        for (String key : List.of("datasetReference", "groupId", "scope")) {
            String value = text(summary.position().get(key));
            if (!value.isBlank()) return value;
        }
        return summary.scope();
    }

    private List<String> boundedStrings(Object value, int maximumItems, int maximumChars) {
        return strings(value).stream().limit(maximumItems)
            .map(item -> item.length() <= maximumChars ? item : item.substring(0, maximumChars))
            .toList();
    }

    private String boundedText(Object value, int maximumChars) {
        String result = text(value);
        return result.length() <= maximumChars ? result : result.substring(0, maximumChars);
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

    private String sectionType(Object value) {
        String type = text(value).toUpperCase(java.util.Locale.ROOT);
        return Set.of("EVIDENCE", "EXCEPTIONS", "ACTIONS").contains(type) ? type : "EVIDENCE";
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

    private record Section(String type, List<String> claimIds) {
        private String title() {
            return switch (type) {
                case "EXCEPTIONS" -> "异常与边界";
                case "ACTIONS" -> "建议动作";
                default -> "关键依据";
            };
        }
    }

    private record DemandAnalysis(String decisionGoal, List<String> priorityQuestions) {
        private DemandAnalysis {
            decisionGoal = decisionGoal == null ? "" : decisionGoal;
            priorityQuestions = priorityQuestions == null ? List.of() : List.copyOf(priorityQuestions);
        }

        private static DemandAnalysis empty() {
            return new DemandAnalysis("", List.of());
        }

        private boolean emptyValue() {
            return decisionGoal.isBlank() && priorityQuestions.isEmpty();
        }
    }

    private record MetricAssociation(String title, List<String> basisClaimIds,
                                     List<String> candidateMetrics, String analysisMethod,
                                     List<String> validationNeeded) {
        private MetricAssociation {
            basisClaimIds = basisClaimIds == null ? List.of() : List.copyOf(basisClaimIds);
            candidateMetrics = candidateMetrics == null ? List.of() : List.copyOf(candidateMetrics);
            analysisMethod = analysisMethod == null ? "" : analysisMethod;
            validationNeeded = validationNeeded == null ? List.of() : List.copyOf(validationNeeded);
        }
    }

    private record ManagementReview(ReviewItem overallAssessment,
                                    List<ReviewItem> identifiedProblems,
                                    List<ReviewItem> improvementSuggestions,
                                    List<ReviewItem> nextWorkDirections) {
        private ManagementReview {
            identifiedProblems = identifiedProblems == null ? List.of() : List.copyOf(identifiedProblems);
            improvementSuggestions = improvementSuggestions == null
                ? List.of() : List.copyOf(improvementSuggestions);
            nextWorkDirections = nextWorkDirections == null ? List.of() : List.copyOf(nextWorkDirections);
        }

        private static ManagementReview empty() {
            return new ManagementReview(null, List.of(), List.of(), List.of());
        }

        private List<ReviewItem> items() {
            List<ReviewItem> result = new ArrayList<>();
            if (overallAssessment != null) result.add(overallAssessment);
            result.addAll(identifiedProblems);
            result.addAll(improvementSuggestions);
            result.addAll(nextWorkDirections);
            return List.copyOf(result);
        }

        private boolean emptyValue() {
            return items().isEmpty();
        }
    }

    private record ReviewItem(String text, List<String> basisClaimIds) {
        private ReviewItem {
            text = text == null ? "" : text;
            basisClaimIds = basisClaimIds == null ? List.of() : List.copyOf(basisClaimIds);
        }
    }

    private record Claim(String claimId, String text, String claimClass,
                         String confidence, String significance, List<String> caveats,
                         String sourceScope) {
        private Map<String, Object> toPromptMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("claimId", claimId);
            result.put("claim", text);
            result.put("claimClass", claimClass);
            if (confidence != null && !confidence.isBlank()) result.put("confidence", confidence);
            if (significance != null && !significance.isBlank()) result.put("significance", significance);
            if (caveats != null && !caveats.isEmpty()) result.put("caveats", caveats);
            if (sourceScope != null && !sourceScope.isBlank()) result.put("sourceScope", sourceScope);
            return Map.copyOf(result);
        }
    }
}
