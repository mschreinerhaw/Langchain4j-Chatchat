package com.chatchat.agents.orchestration.analysis.nodes.synthesis;

import com.chatchat.agents.orchestration.analysis.protocol.AnalysisArtifactProtocol;
import com.chatchat.agents.orchestration.analysis.report.AnalyticalInsightBlock;
import com.chatchat.agents.orchestration.analysis.report.ReportComposer;
import com.chatchat.agents.orchestration.analysis.report.VerifiedReportDataCatalog;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.common.runtime.summary.analysis.governance.DataAnalysisLayerGovernanceContract;
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
 * Publication boundary between the final analysis model and evidence provenance.
 *
 * <p>The model owns business analysis, calculations and narrative coherence. Runtime preserves
 * the model's complete report and audits whether declared evidence identifiers exist. Evidence
 * binding is publication metadata; it is never a semantic veto or a second analysis engine.</p>
 */
final class GovernedFinalClaimContract {

    static final String SCHEMA_VERSION = "governed_management_synthesis.v4";
    private static final String LEGACY_SCHEMA_VERSION_V3 = "governed_management_synthesis.v3";
    private static final String LEGACY_SCHEMA_VERSION_V2 = "governed_management_synthesis.v2";
    private static final String LEGACY_SCHEMA_VERSION = "governed_final_claim_selection.v1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    GovernedFinalClaimContract() {
        this(com.chatchat.common.runtime.summary.analysis.contract.AnalysisAcceptanceContract.standard());
    }

    GovernedFinalClaimContract(com.chatchat.common.runtime.summary.analysis.contract.AnalysisAcceptanceContract policy) {
        this(policy, null, "");
    }

    GovernedFinalClaimContract(com.chatchat.common.runtime.summary.analysis.contract.AnalysisAcceptanceContract policy,
        SemanticClaimReviewer reviewer, String question) {
        java.util.Objects.requireNonNull(policy);
        // Compatibility constructor: semantic review is intentionally outside Runtime publication.
    }

    Compilation compile(List<AnalysisSummaryResult> summaries) {
        Map<String, Claim> claims = new LinkedHashMap<>();
        Set<String> observedEvidenceSignatures = new LinkedHashSet<>();
        boolean claimContractObserved = false;
        if (summaries != null) {
            for (AnalysisSummaryResult summary : summaries) {
                if (summary == null || summary.evidence() == null) continue;
                List<Map<String, Object>> artifacts = AnalysisArtifactProtocol.normalize(summary);
                if (!artifacts.isEmpty()) {
                    claimContractObserved = true;
                    for (Map<String, Object> artifact : artifacts) {
                        String claimId = text(artifact.get("artifactId"));
                        String claim = text(artifact.get("text"));
                        String claimClass = text(artifact.get("claimClass"));
                        List<String> recordRefs = strings(artifact.get("recordRefs"));
                        List<String> supportingValues = strings(artifact.get("supportingValues"));
                        List<String> basisClaimIds = strings(artifact.get("basisClaimIds"));
                        boolean directEvidence = !recordRefs.isEmpty() && !supportingValues.isEmpty();
                        if (claimId.isBlank() || claim.isBlank()
                            || ("OBSERVED_RETURNED_FACT".equals(claimClass)
                                ? !directEvidence : !directEvidence && basisClaimIds.isEmpty())) continue;
                        Claim admitted = new Claim(
                            claimId, claim, claimClass, text(artifact.get("confidence")),
                            text(artifact.get("significance")), strings(artifact.get("caveats")),
                            text(artifact.get("status")), strings(artifact.get("reviewReasons")),
                            text(artifact.get("sourceScope")), recordRefs, supportingValues,
                            basisClaimIds, analysisFields(artifact));
                        claims.putIfAbsent(claimId, admitted);
                        if (admitted.observedFact()) {
                            observedEvidenceSignatures.add(evidenceSignature(
                                admitted.sourceScope(), recordRefs, supportingValues));
                        }
                    }
                    continue;
                }
                // Reducers retain empty protocol fields for backward-compatible summaries. An
                // actual decision is the activation signal: it proves that candidate Claims were
                // evaluated, while an empty list must not turn ordinary document/workflow answers
                // into a governed data-analysis publication.
                claimContractObserved = claimContractObserved
                    || !maps(summary.evidence().get("claimAdmissionDecisions")).isEmpty()
                    || !maps(summary.evidence().get("observedFactClaims")).isEmpty()
                    || !maps(summary.evidence().get("analysisItems")).isEmpty();
                boolean admissionDecisionsDeclared =
                    !maps(summary.evidence().get("claimAdmissionDecisions")).isEmpty();
                Set<String> explicitlyAdmitted = publishableClaimIds(summary.evidence());
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
                    Claim admitted = new Claim(
                        claimId, claim, claimClass,
                        text(insight.get("confidence")),
                        text(insight.get("significance")),
                        strings(insight.get("caveats")),
                        text(insight.get("governanceStatus")),
                        strings(insight.get("reviewReasons")),
                        claimSource(summary, insight), recordRefs, supportingValues, List.of(),
                        analysisFields(insight));
                    claims.putIfAbsent(claimId, admitted);
                    if ("OBSERVED_RETURNED_FACT".equals(claimClass)) {
                        observedEvidenceSignatures.add(evidenceSignature(
                            admitted.sourceScope(), recordRefs, supportingValues));
                    }
                }
                for (Map<String, Object> factClaim : maps(
                    summary.evidence().get("observedFactClaims"))) {
                    String claimId = text(factClaim.get("claimId"));
                    String claim = text(factClaim.get("claim"));
                    List<String> recordRefs = strings(factClaim.get("recordRefs"));
                    List<String> supportingValues = strings(factClaim.get("supportingValues"));
                    if (claimId.isBlank() || claim.isBlank() || recordRefs.isEmpty()
                        || supportingValues.isEmpty()) continue;
                    String source = claimSource(summary, factClaim);
                    String signature = evidenceSignature(source, recordRefs, supportingValues);
                    if (!observedEvidenceSignatures.add(signature)) continue;
                    claims.putIfAbsent(claimId, new Claim(
                        claimId, claim, "OBSERVED_RETURNED_FACT",
                        text(factClaim.get("confidence")), text(factClaim.get("significance")),
                        strings(factClaim.get("caveats")), "SUPPORTED", List.of(),
                        source, recordRefs, supportingValues, List.of(), analysisFields(factClaim)));
                }
                // Dynamic analysis agenda items are the Worker's principal analytical work
                // product. They already passed record-reference and exact-value validation in
                // AnalysisNodeProtocol, so retain them as governed Driver inputs even
                // when the model did not duplicate the same reasoning in its optional insights
                // array. Dropping this channel reduces the Driver to execution-metadata claims.
                for (Map<String, Object> item : maps(summary.evidence().get("analysisItems"))) {
                    String status = text(item.get("status")).toUpperCase(java.util.Locale.ROOT);
                    String finding = text(item.get("finding"));
                    List<String> recordRefs = strings(item.get("basisRecordRefs"));
                    List<String> supportingValues = strings(item.get("supportingValues"));
                    if ("NOT_APPLICABLE".equals(status) || finding.isBlank()
                        || recordRefs.isEmpty() || supportingValues.isEmpty()) {
                        continue;
                    }
                    String source = claimSource(summary, Map.of("recordRefs", recordRefs));
                    String itemId = text(item.get("itemId"));
                    String claimId = "analysis-item:" + DataAnalysisLayerGovernanceContract
                        .fingerprint(List.of(source, itemId, finding,
                            recordRefs.stream().sorted().toList(),
                            supportingValues.stream().sorted().toList()));
                    List<String> limitations = strings(item.get("limitations"));
                    List<String> reviewReasons = "REVIEW_REQUIRED".equals(status)
                        ? (limitations.isEmpty()
                            ? List.of("Worker marked this evidence-bound analysis item for human review.")
                            : limitations)
                        : List.of();
                    claims.putIfAbsent(claimId, new Claim(
                        claimId, finding, "GOVERNED_ANALYSIS_ITEM",
                        text(item.get("confidence")), text(item.get("businessMeaning")),
                        limitations, status, reviewReasons, source,
                        recordRefs, supportingValues, List.of(), analysisFields(item)));
                }
            }
        }
        boolean changed;
        do {
            changed = claims.entrySet().removeIf(entry -> entry.getValue().basisClaimIds().stream()
                .anyMatch(id -> id.equals(entry.getKey()) || !claims.containsKey(id)));
        } while (changed);
        return new Compilation(claims, claimContractObserved);
    }

    Projection project(String modelOutput, Compilation compilation) {
        return project(modelOutput, compilation, VerifiedReportDataCatalog.empty());
    }

    Projection project(String modelOutput, Compilation compilation, VerifiedReportDataCatalog dataCatalog) {
        if (compilation == null || compilation.claims().isEmpty()) {
            return new Projection(false, "NO_ADMITTED_CLAIMS", "", List.of());
        }
        Map<String, Object> payload = parseObject(modelOutput);
        String schemaVersion = text(payload.get("schemaVersion"));
        if (payload.isEmpty() || (!SCHEMA_VERSION.equals(schemaVersion)
            && !LEGACY_SCHEMA_VERSION_V3.equals(schemaVersion)
            && !LEGACY_SCHEMA_VERSION_V2.equals(schemaVersion)
            && !LEGACY_SCHEMA_VERSION.equals(schemaVersion))) {
            return withheld("FINAL_CLAIM_SELECTION_PROTOCOL_INVALID");
        }
        List<NarrativeFinding> narrativeFindings = maps(payload.get("findings")).stream()
            .map(this::narrativeFinding).filter(java.util.Objects::nonNull).limit(20).toList();
        if (SCHEMA_VERSION.equals(schemaVersion) || !narrativeFindings.isEmpty()
            || payload.containsKey("coverage")) {
            return projectNarrative(payload, compilation, narrativeFindings, dataCatalog);
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
        Set<String> coveredSources = selected.stream().map(compilation.claims()::get)
            .filter(java.util.Objects::nonNull).map(Claim::sourceScope)
            .filter(source -> source != null && !source.isBlank())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> requiredSources = compilation.claims().values().stream()
            .map(Claim::sourceScope).filter(source -> source != null && !source.isBlank())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        boolean incompleteSourceCoverage = !coveredSources.containsAll(requiredSources);
        boolean incompleteObservedFactCoverage = compilation.claims().values().stream()
            .filter(Claim::observedFact)
            .anyMatch(observed -> selected.stream().map(compilation.claims()::get)
                .filter(java.util.Objects::nonNull)
                .noneMatch(selectedClaim -> evidenceCovers(selectedClaim, observed)));
        if (selected.isEmpty() || unknownClaim) {
            return withheld(unknownClaim
                ? "UNKNOWN_FINAL_CLAIM_ID" : "EMPTY_FINAL_CLAIM_SELECTION");
        }
        if (invalidAssociationBasis) {
            return withheld("INVALID_METRIC_ASSOCIATION_BASIS");
        }
        if (invalidReviewBasis) {
            return withheld("INVALID_MANAGEMENT_REVIEW_BASIS");
        }
        if (incompleteSourceCoverage) {
            return withheld("INCOMPLETE_ANALYSIS_SOURCE_COVERAGE");
        }
        if (incompleteObservedFactCoverage) {
            return withheld("INCOMPLETE_OBSERVED_FACT_COVERAGE");
        }
        String reportMarkdown = reportBody(payload);
        if (reportMarkdown.isBlank()) return withheld("MODEL_REPORT_MARKDOWN_REQUIRED");
        return new Projection(true, "MODEL_ANALYSIS_PUBLISHED_WITH_EVIDENCE_AUDIT",
            reportMarkdown, List.copyOf(selected), Map.of("publicationMode", "MODEL_REPORT_MARKDOWN"));
    }

    DriverAudit inspectDriverAudit(String modelOutput, Compilation compilation,
                                   Collection<String> reportIds) {
        Map<String, Object> payload = parseObject(modelOutput);
        if (!SCHEMA_VERSION.equals(text(payload.get("schemaVersion")))
            && !LEGACY_SCHEMA_VERSION_V3.equals(text(payload.get("schemaVersion")))) {
            return DriverAudit.invalid("DRIVER_REVIEW_PROTOCOL_MISSING");
        }
        Map<String, Object> review = object(payload.get("driverReview"));
        Map<String, Object> reasoning = object(payload.get("driverReasoning"));
        Set<String> requiredReviewFields = Set.of(
            "status", "requirementCoverage", "claimConsistency", "evidenceSufficiency",
            "crossWorkerConflicts", "duplicateEvidence", "unsupportedInferences",
            "missingCriticalDimensions", "claimAssessments", "challenges");
        if (!review.keySet().containsAll(requiredReviewFields)
            || !reasoning.containsKey("derivedClaims")) {
            return DriverAudit.invalid("DRIVER_REVIEW_FIELDS_INCOMPLETE");
        }
        String reviewStatus = text(review.get("status")).toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("PASS", "CHALLENGE").contains(reviewStatus)) {
            return DriverAudit.invalid("DRIVER_REVIEW_STATUS_INVALID");
        }
        List<ClaimAssessment> assessments = maps(review.get("claimAssessments")).stream()
            .map(this::claimAssessment).filter(java.util.Objects::nonNull).toList();
        Set<String> assessed = assessments.stream().map(ClaimAssessment::claimId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (assessments.size() != assessed.size()
            || !assessed.containsAll(compilation.claims().keySet())
            || assessments.stream().anyMatch(item -> !compilation.claims().containsKey(item.claimId()))) {
            return new DriverAudit(false, "DRIVER_REVIEW_CLAIM_COVERAGE_INCOMPLETE", "INVALID",
                Map.of("expectedClaimCount", compilation.claims().size(),
                    "assessmentCount", assessments.size(),
                    "missingClaimIds", compilation.claims().keySet().stream().filter(id -> !assessed.contains(id)).toList(),
                    "unknownClaimIds", assessed.stream().filter(id -> !compilation.claims().containsKey(id)).toList(),
                    "duplicateClaimIds", assessments.stream().map(ClaimAssessment::claimId).distinct()
                        .filter(id -> assessments.stream().filter(item -> item.claimId().equals(id)).count() > 1).toList()),
                List.of(), List.of());
        }
        Set<String> validReports = reportIds == null ? Set.of() : reportIds.stream()
            .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        List<DriverChallenge> challenges = maps(review.get("challenges")).stream()
            .map(this::driverChallenge).filter(java.util.Objects::nonNull).toList();
        boolean invalidChallenge = challenges.stream().anyMatch(challenge ->
            challenge.claimIds().stream().anyMatch(id -> !compilation.claims().containsKey(id))
                || (!challenge.targetReportId().isBlank()
                    && !validReports.contains(challenge.targetReportId())));
        if (invalidChallenge || ("CHALLENGE".equals(reviewStatus) && challenges.isEmpty())
            || ("PASS".equals(reviewStatus) && !challenges.isEmpty())) {
            return DriverAudit.invalid("DRIVER_CHALLENGE_INVALID");
        }
        List<DerivedClaim> derivedClaims = maps(reasoning.get("derivedClaims")).stream()
            .map(this::derivedClaim).filter(java.util.Objects::nonNull).toList();
        boolean duplicateDerivedId = derivedClaims.stream().map(DerivedClaim::derivedClaimId)
            .distinct().count() != derivedClaims.size();
        boolean invalidDerived = duplicateDerivedId || derivedClaims.stream().anyMatch(derived ->
            derived.basisClaimIds().stream().anyMatch(id -> !compilation.claims().containsKey(id)));
        if (invalidDerived) return DriverAudit.invalid("DRIVER_DERIVED_CLAIM_INVALID");
        Map<String, Object> reviewSummary = new LinkedHashMap<>();
        reviewSummary.put("status", reviewStatus);
        reviewSummary.put("requirementCoverage", value(review, "requirementCoverage", List.of()));
        reviewSummary.put("claimConsistency", value(review, "claimConsistency", List.of()));
        reviewSummary.put("evidenceSufficiency", value(review, "evidenceSufficiency", Map.of()));
        reviewSummary.put("crossWorkerConflicts", value(review, "crossWorkerConflicts", List.of()));
        reviewSummary.put("duplicateEvidence", value(review, "duplicateEvidence", List.of()));
        reviewSummary.put("unsupportedInferences", value(review, "unsupportedInferences", List.of()));
        reviewSummary.put("missingCriticalDimensions", value(review, "missingCriticalDimensions", List.of()));
        reviewSummary.put("claimAssessments", assessments.stream().map(ClaimAssessment::toMap).toList());
        return new DriverAudit(true, "DRIVER_REVIEW_ADMITTED", reviewStatus,
            Map.copyOf(reviewSummary), challenges, derivedClaims);
    }

    Compilation includeDriverDerivedClaims(Compilation compilation, DriverAudit audit) {
        if (compilation == null || audit == null || !audit.valid()
            || audit.derivedClaims().isEmpty()) return compilation;
        Map<String, Claim> claims = new LinkedHashMap<>(compilation.claims());
        for (DerivedClaim derived : audit.derivedClaims()) {
            List<Claim> basis = derived.basisClaimIds().stream().map(claims::get)
                .filter(java.util.Objects::nonNull).toList();
            if (basis.size() != derived.basisClaimIds().size()) continue;
            List<String> recordRefs = basis.stream().flatMap(item -> item.recordRefs().stream())
                .distinct().toList();
            List<String> values = basis.stream().flatMap(item -> item.supportingValues().stream())
                .distinct().toList();
            claims.putIfAbsent(derived.derivedClaimId(), new Claim(
                derived.derivedClaimId(), derived.text(), "DRIVER_DERIVED_CLAIM", "MEDIUM",
                "Management-level synthesis derived from admitted lower-layer Claims.",
                derived.caveats(), "REVIEW_REQUIRED", derived.caveats(), "DRIVER",
                recordRefs, values, derived.basisClaimIds(), Map.of()));
        }
        return new Compilation(claims, compilation.claimContractObserved());
    }

    private ClaimAssessment claimAssessment(Map<String, Object> source) {
        String claimId = text(source.get("claimId"));
        String verdict = text(source.get("verdict")).toUpperCase(java.util.Locale.ROOT);
        if (claimId.isBlank() || !Set.of("ACCEPT", "DOWNGRADE", "REJECT").contains(verdict)) {
            return null;
        }
        return new ClaimAssessment(claimId, verdict, text(source.get("reason")));
    }

    private DriverChallenge driverChallenge(Map<String, Object> source) {
        String targetLayer = text(source.get("targetLayer")).toUpperCase(java.util.Locale.ROOT);
        String reason = text(source.get("reason"));
        String correction = text(source.get("requiredCorrection"));
        List<String> claimIds = strings(source.get("claimIds"));
        if (!Set.of("WORKER_REPORT", "REDUCER_REPORT").contains(targetLayer)
            || reason.isBlank() || correction.isBlank() || claimIds.isEmpty()) return null;
        return new DriverChallenge(targetLayer, text(source.get("targetReportId")),
            claimIds, reason, correction);
    }

    private DerivedClaim derivedClaim(Map<String, Object> source) {
        String narrative = text(source.get("text"));
        List<String> basis = strings(source.get("basisClaimIds"));
        if (narrative.isBlank() || basis.isEmpty()) return null;
        String id = text(source.get("derivedClaimId"));
        if (id.isBlank()) id = "driver-derived:" + DataAnalysisLayerGovernanceContract
            .fingerprint(List.of(narrative, basis));
        return new DerivedClaim(id, narrative, basis, strings(source.get("caveats")));
    }

    private Projection projectNarrative(Map<String, Object> payload, Compilation compilation,
                                         List<NarrativeFinding> findings, VerifiedReportDataCatalog dataCatalog) {
        if (findings.isEmpty()) {
            return withheld("EMPTY_MANAGEMENT_FINDINGS");
        }
        DemandAnalysis demandAnalysis = demandAnalysis(payload.get("demandAnalysis"));
        List<MetricAssociation> metricAssociations = maps(payload.get("metricAssociations")).stream()
            .map(this::metricAssociation).filter(java.util.Objects::nonNull).limit(8).toList();
        ManagementReview managementReview = managementReview(payload.get("managementReview"));

        return acceptClaims(payload, compilation, findings, demandAnalysis, metricAssociations,
            managementReview, dataCatalog);
    }

    /**
     * Preserve the analysis model's report and audit only evidence identifier binding.
     *
     * <p>Runtime deliberately does not decide whether an aggregation, comparison, explanation
     * or business conclusion is semantically correct. Those decisions belong to the analysis
     * model and its mandatory coherence pass. Missing or unknown evidence identifiers are
     * exposed as audit metadata without deleting or rewriting the finding.</p>
     */
    private Projection acceptClaims(Map<String, Object> payload, Compilation compilation,
        List<NarrativeFinding> findings, DemandAnalysis demandAnalysis,
        List<MetricAssociation> associations, ManagementReview review,
        VerifiedReportDataCatalog catalog) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        List<Map<String, Object>> bindings = new ArrayList<>();
        Map<NarrativeFinding, String> blockIds = new java.util.IdentityHashMap<>();

        for (int index = 0; index < findings.size(); index++) {
            NarrativeFinding finding = findings.get(index);
            String findingId = "F" + (index + 1);
            blockIds.put(finding, findingId);
            List<String> known = finding.basisClaimIds().stream()
                .filter(compilation.claims()::containsKey).distinct().toList();
            List<String> unknown = finding.basisClaimIds().stream()
                .filter(id -> !compilation.claims().containsKey(id)).distinct().toList();
            selected.addAll(known);
            bindings.add(Map.of(
                "findingId", findingId,
                "declaredBasisClaimIds", finding.basisClaimIds(),
                "boundBasisClaimIds", known,
                "unboundBasisClaimIds", unknown,
                "status", unknown.isEmpty() && !known.isEmpty() ? "BOUND"
                    : known.isEmpty() ? "UNBOUND" : "PARTIALLY_BOUND"));
        }
        review.items().forEach(item -> item.basisClaimIds().stream()
            .filter(compilation.claims()::containsKey).forEach(selected::add));
        associations.forEach(item -> item.basisClaimIds().stream()
            .filter(compilation.claims()::containsKey).forEach(selected::add));

        String reportMarkdown = reportBody(payload);
        if (reportMarkdown.isBlank()) return withheld("MODEL_REPORT_MARKDOWN_REQUIRED");
        Map<String, Object> report = new LinkedHashMap<>(evidenceReportMetadata(
            compilation, findings, demandAnalysis, catalog, blockIds));
        report.put("evidenceBindingAudit", Map.of(
            "mode", "PROVENANCE_ONLY",
            "modelAnalysisPreserved", true,
            "findings", List.copyOf(bindings),
            "knownEvidenceClaimIds", List.copyOf(compilation.claims().keySet())));
        report.put("publicationMode", "MODEL_REPORT_MARKDOWN");
        return new Projection(true, "MODEL_ANALYSIS_PUBLISHED_WITH_EVIDENCE_AUDIT",
            reportMarkdown, List.copyOf(selected), Map.copyOf(report));
    }

    private String reportBody(Map<String, Object> payload) {
        // A missing or oversized deliverable requires model repair, never field composition
        // or silent truncation of a model-authored report.
        Object body = payload.get("reportMarkdown");
        return body instanceof String report && report.length() <= 60_000 ? report : "";
    }

    private NarrativeFinding narrativeFinding(Map<String, Object> source) {
        String text = text(source.get("text"));
        List<String> basis = strings(source.get("basisClaimIds"));
        if (text.isBlank() || basis.isEmpty()) return null;
        String section = text(source.get("section")).toUpperCase(java.util.Locale.ROOT);
        if ("EVIDENCE".equals(section)) section = "DEEP_DIVE";
        if ("EXCEPTION".equals(section)) section = "RISK_OPPORTUNITY";
        if (!Set.of("CORE", "OVERALL", "KEY_DRIVER", "DEEP_DIVE",
            "RISK_OPPORTUNITY", "LIMITATION", "ACTION").contains(section)) section = "DEEP_DIVE";
        return new NarrativeFinding(section, text, basis,
            boundedText(source.get("question"), 300), boundedText(source.get("baseline"), 600),
            boundedText(source.get("comparison"), 600), boundedText(source.get("driver"), 600),
            boundedText(source.get("implication"), 600), boundedText(source.get("confidence"), 200),
            boundedText(source.get("dataRef"), 200), boundedText(source.get("visualizationIntent"), 40));
    }

    String appendNarrativeInstruction(String prompt, Compilation compilation) {
        List<Map<String, Object>> ledger = compilation == null
            ? List.of()
            : compilation.claims().values().stream().map(Claim::toPromptMap).toList();
        return (prompt == null ? "" : prompt)
            + "\n\nFinal deliverable: return only the complete model-authored Markdown report. "
            + "Choose its title, organization, depth, tables and explanatory narrative from the user's "
            + "question and available evidence. Do not return JSON as the report envelope, findings fields or a review form. "
            + "When adaptiveAnalysisPrompt.output is supplied, use its order as localized H2 section guidance; "
            + "explicit user formatting takes precedence, overlapping sections may be combined and empty ones omitted. "
            + "Support important comparisons with compact Markdown tables, preserving labels, units, periods and "
            + "source values. Existing table controls can visualize those datasets without a computed dataRef. "
            + "Runtime will publish this body without composing sections or filling business conclusions. "
            + AnalysisSynthesisContract.narrativeCoherenceInstruction()
            + com.chatchat.agents.orchestration.analysis.report.ReportVisualizationAudit.instruction()
            + "The Evidence provenance ledger below is the lossless completed-analysis hand-off and not a required outline; "
            + "it is not raw material for a new analysis. "
            + "Preserve its separate observation, interpretation and implication fields and its method/scope semantics. "
            + "Question-level synthesis artifacts are valid only through their basisClaimIds. Use the analysis-layer "
            + "ranking, conflict and sufficiency judgments from the pipeline context; focus this call on clear expression. "
            + "Completed analysis artifact ledger: " + ModelProtocolJson.compact(ledger);
    }

    Projection publishNarrative(String body, Compilation compilation) {
        return new Projection(true, "MODEL_ANALYSIS_PUBLISHED_WITH_EVIDENCE_AUDIT", body, List.of(),
            Map.of("schemaVersion", ReportComposer.VERSION, "publicationMode", "MODEL_REPORT_MARKDOWN",
                "evidenceClaims", compilation.artifacts(compilation.claims().keySet()),
                "evidenceBindingAudit", Map.of("mode", "PROVENANCE_ONLY",
                    "modelAnalysisPreserved", true, "bindingStatus", "PENDING_SENTENCE_LEVEL_AUDIT")));
    }

    /** Compatibility entry point: new generations always author Markdown, even for legacy callers. */
    String appendSelectionInstruction(String prompt, Compilation compilation) {
        return appendNarrativeInstruction(prompt, compilation);
    }
    private Projection withheld(String reason) {
        return new Projection(false, reason, "", List.of());
    }

    // Structured data is retained solely as evidence metadata, never rendered as the report body.
    private Map<String, Object> evidenceReportMetadata(Compilation compilation,
            List<NarrativeFinding> findings, DemandAnalysis demandAnalysis,
            VerifiedReportDataCatalog dataCatalog, Map<NarrativeFinding, String> blockIds) {
        ReportComposer composer = new ReportComposer();
        List<AnalyticalInsightBlock> blocks = new ArrayList<>();
        List<String> sectionOrder = List.of("CORE", "DEEP_DIVE", "OVERALL", "KEY_DRIVER", "RISK_OPPORTUNITY", "LIMITATION", "ACTION");
        for (NarrativeFinding finding : findings.stream()
            .sorted(java.util.Comparator.comparingInt(item -> sectionOrder.indexOf(item.section()))).toList()) {
            List<Claim> basis = finding.basisClaimIds().stream().map(compilation.claims()::get)
                .filter(java.util.Objects::nonNull).toList();
            String interpretation = (finding.baseline().isBlank() ? "" : "比较基准：" + finding.baseline() + "\n")
                + (finding.comparison().isBlank() ? "" : "比较结果：" + finding.comparison() + "\n") + finding.driver();
            blocks.add(composer.compose(blockIds.get(finding), finding.section(), finding.question(),
                finding.text(), interpretation, finding.implication(), finding.confidence(),
                basis.stream().flatMap(claim -> claim.caveats().stream()).distinct().toList(),
                basis.stream().map(Claim::toArtifactMap).toList(), finding.dataRef(),
                finding.visualizationIntent(), dataCatalog));
        }
        Map<String, Object> report = Map.of("schemaVersion", ReportComposer.VERSION,
            "decisionQuestion", demandAnalysis == null ? "" : demandAnalysis.decisionGoal(),
            "blocks", List.copyOf(blocks), "executiveSummaryIds", blocks.stream()
                .filter(block -> block.presentation().primaryConclusion()).map(AnalyticalInsightBlock::id).limit(5).toList());
        return report;
    }

    private boolean evidenceCovers(Claim candidate, Claim observed) {
        if (candidate.claimId().equals(observed.claimId())) return true;
        return !observed.recordRefs().isEmpty() && !observed.supportingValues().isEmpty()
            && candidate.recordRefs().containsAll(observed.recordRefs())
            && candidate.supportingValues().containsAll(observed.supportingValues());
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

    private String claimSource(AnalysisSummaryResult summary, Map<String, Object> claim) {
        List<String> references = strings(claim == null ? null : claim.get("recordRefs"));
        if (!references.isEmpty()) {
            String reference = references.get(0);
            int marker = reference.indexOf(".records[");
            if (marker > 0) return reference.substring(0, marker);
        }
        if (summary == null || summary.position() == null) return "";
        for (String key : List.of("datasetReference", "groupId", "scope")) {
            String value = text(summary.position().get(key));
            if (!value.isBlank()) return value;
        }
        return summary.scope();
    }

    private String evidenceSignature(String source, List<String> recordRefs,
                                     List<String> supportingValues) {
        return DataAnalysisLayerGovernanceContract.fingerprint(List.of(
            source == null ? "" : source,
            recordRefs == null ? List.of() : recordRefs.stream().sorted().toList(),
            supportingValues == null ? List.of() : supportingValues.stream().sorted().toList()));
    }

    private Map<String, Object> analysisFields(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("observation", "interpretation", "implication",
            "operation", "method", "inputFields", "outputUnit", "grain", "timeScope",
            "populationScope", "semanticBasis", "alternativeExplanations", "priority",
            "findingIndex")) {
            Object value = source.get(key);
            if (value instanceof Collection<?> collection) {
                List<String> values = collection.stream().filter(java.util.Objects::nonNull)
                    .map(String::valueOf).map(String::trim).filter(item -> !item.isBlank()).toList();
                if (!values.isEmpty()) result.put(key, values);
            } else if (!text(value).isBlank()) {
                result.put(key, text(value));
            }
        }
        return Map.copyOf(result);
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

    private Set<String> publishableClaimIds(Map<String, Object> evidence) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Map<String, Object> decision : maps(evidence.get("claimAdmissionDecisions"))) {
            if (Boolean.TRUE.equals(decision.get("admitted"))
                || Boolean.TRUE.equals(decision.get("reviewRequired"))) {
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

    private Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null) result.put(String.valueOf(key), item);
        });
        return Map.copyOf(result);
    }

    private Object value(Map<String, Object> source, String key, Object fallback) {
        return source == null ? fallback : source.getOrDefault(key, fallback);
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

        List<Map<String, Object>> artifacts(Collection<String> claimIds) {
            if (claimIds == null) return List.of();
            return claimIds.stream().map(claims::get).filter(java.util.Objects::nonNull)
                .map(Claim::toArtifactMap).toList();
        }
    }

    record Projection(boolean modelSelectionAccepted, String reason, String markdown,
                      List<String> selectedClaimIds, Map<String, Object> analyticalReport) {
        Projection(boolean accepted, String reason, String markdown, List<String> ids) {
            this(accepted, reason, markdown, ids, Map.of());
        }
    }

    record DriverAudit(boolean valid, String reason, String status,
                       Map<String, Object> review,
                       List<DriverChallenge> challenges,
                       List<DerivedClaim> derivedClaims) {
        DriverAudit {
            review = review == null ? Map.of() : Map.copyOf(review);
            challenges = challenges == null ? List.of() : List.copyOf(challenges);
            derivedClaims = derivedClaims == null ? List.of() : List.copyOf(derivedClaims);
        }

        static DriverAudit invalid(String reason) {
            return new DriverAudit(false, reason, "INVALID", Map.of(), List.of(), List.of());
        }

        Map<String, Object> toMap() {
            return Map.of(
                "schemaVersion", "driver_review.v1",
                "valid", valid,
                "reason", reason,
                "status", status,
                "review", review,
                "challenges", challenges.stream().map(DriverChallenge::toMap).toList(),
                "derivedClaims", derivedClaims.stream().map(DerivedClaim::toMap).toList());
        }
    }

    record ClaimAssessment(String claimId, String verdict, String reason) {
        Map<String, Object> toMap() {
            return Map.of("claimId", claimId, "verdict", verdict, "reason", reason);
        }
    }

    record DriverChallenge(String targetLayer, String targetReportId,
                           List<String> claimIds, String reason,
                           String requiredCorrection) {
        Map<String, Object> toMap() {
            return Map.of(
                "targetLayer", targetLayer,
                "targetReportId", targetReportId,
                "claimIds", claimIds,
                "reason", reason,
                "requiredCorrection", requiredCorrection);
        }
    }

    record DerivedClaim(String derivedClaimId, String text,
                        List<String> basisClaimIds, List<String> caveats) {
        Map<String, Object> toMap() {
            return Map.of(
                "derivedClaimId", derivedClaimId,
                "text", text,
                "basisClaimIds", basisClaimIds,
                "caveats", caveats);
        }
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

    private record NarrativeFinding(String section, String text,
                                    List<String> basisClaimIds, String question, String baseline,
                                    String comparison, String driver, String implication, String confidence,
                                    String dataRef, String visualizationIntent) {
        private NarrativeFinding(String section, String text, List<String> basisClaimIds) {
            this(section, text, basisClaimIds, "", "", "", "", "", "", "", "");
        }

        String groundedText() {
            return String.join(" ", text, question, baseline, comparison, driver, implication, confidence);
        }

        private NarrativeFinding {
            basisClaimIds = basisClaimIds == null ? List.of() : List.copyOf(basisClaimIds);
        }
    }

    private record ClaimCoverage(String claimId, String disposition, String reason) {
    }

    private record Claim(String claimId, String text, String claimClass,
                         String confidence, String significance, List<String> caveats,
                         String governanceStatus, List<String> reviewReasons,
                         String sourceScope, List<String> recordRefs,
                         List<String> supportingValues, List<String> basisClaimIds,
                         Map<String, Object> analysis) {
        private Claim {
            recordRefs = recordRefs == null ? List.of() : List.copyOf(recordRefs);
            supportingValues = supportingValues == null ? List.of() : List.copyOf(supportingValues);
            reviewReasons = reviewReasons == null ? List.of() : List.copyOf(reviewReasons);
            basisClaimIds = basisClaimIds == null ? List.of() : List.copyOf(basisClaimIds);
            analysis = analysis == null ? Map.of() : Map.copyOf(analysis);
        }

        private boolean observedFact() {
            return "OBSERVED_RETURNED_FACT".equals(claimClass);
        }

        private Map<String, Object> toPromptMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("claimId", claimId);
            result.put("claim", text);
            result.put("claimClass", claimClass);
            if (confidence != null && !confidence.isBlank()) result.put("confidence", confidence);
            if (significance != null && !significance.isBlank()) result.put("significance", significance);
            if (caveats != null && !caveats.isEmpty()) result.put("caveats", caveats);
            if (governanceStatus != null && !governanceStatus.isBlank()) {
                result.put("governanceStatus", governanceStatus);
            }
            if (!reviewReasons.isEmpty()) result.put("reviewReasons", reviewReasons);
            if (sourceScope != null && !sourceScope.isBlank()) result.put("sourceScope", sourceScope);
            if (!recordRefs.isEmpty()) result.put("recordRefs", recordRefs);
            if (!supportingValues.isEmpty()) result.put("supportingValues", supportingValues);
            if (!basisClaimIds.isEmpty()) result.put("basisClaimIds", basisClaimIds);
            result.putAll(analysis);
            return Map.copyOf(result);
        }

        private Map<String, Object> toArtifactMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schemaVersion", AnalysisArtifactProtocol.SCHEMA_VERSION);
            result.put("artifactId", claimId);
            result.put("artifactType", "BUSINESS_CLAIM");
            result.put("sourceStage", claimClass.startsWith("DRIVER_") ? "DRIVER"
                : claimClass.startsWith("REDUCER_") ? "REDUCER" : "WORKER");
            result.put("sourceScope", sourceScope == null ? "" : sourceScope);
            result.put("claimClass", claimClass);
            result.put("text", text);
            result.put("status", governanceStatus == null || governanceStatus.isBlank()
                ? "SUPPORTED" : governanceStatus);
            result.put("confidence", confidence == null ? "" : confidence);
            result.put("significance", significance == null ? "" : significance);
            result.put("recordRefs", recordRefs);
            result.put("supportingValues", supportingValues);
            result.put("basisClaimIds", basisClaimIds);
            result.put("caveats", caveats == null ? List.of() : caveats);
            result.put("reviewReasons", reviewReasons);
            result.putAll(analysis);
            return Map.copyOf(result);
        }
    }
}
