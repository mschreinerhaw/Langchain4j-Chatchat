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
 * Publication boundary between governed Claim admission and the final language model.
 *
 * <p>The Driver may synthesize completed Worker/Reducer reports into management-level findings,
 * while every finding remains bound to admitted evidence Claims. Governance validates lineage,
 * observed-fact coverage and numeric grounding; it does not reduce the Driver to copying a Claim
 * ledger. User-facing Markdown is rendered from the admitted grounded synthesis.</p>
 */
final class GovernedFinalClaimContract {

    static final String SCHEMA_VERSION = "governed_management_synthesis.v4";
    private static final String LEGACY_SCHEMA_VERSION_V3 = "governed_management_synthesis.v3";
    private static final String LEGACY_SCHEMA_VERSION_V2 = "governed_management_synthesis.v2";
    private static final String LEGACY_SCHEMA_VERSION = "governed_final_claim_selection.v1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_FALLBACK_CLAIMS = 30;

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
                        if (claimId.isBlank() || claim.isBlank() || recordRefs.isEmpty()
                            || supportingValues.isEmpty()) continue;
                        Claim admitted = new Claim(
                            claimId, claim, claimClass, text(artifact.get("confidence")),
                            text(artifact.get("significance")), strings(artifact.get("caveats")),
                            text(artifact.get("status")), strings(artifact.get("reviewReasons")),
                            text(artifact.get("sourceScope")), recordRefs, supportingValues,
                            strings(artifact.get("basisClaimIds")));
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
                        claimSource(summary, insight), recordRefs, supportingValues, List.of());
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
                        source, recordRefs, supportingValues, List.of()));
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
                        recordRefs, supportingValues, List.of()));
                }
            }
        }
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
            return deterministic(compilation, "FINAL_CLAIM_SELECTION_PROTOCOL_INVALID");
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
            return deterministic(compilation, unknownClaim
                ? "UNKNOWN_FINAL_CLAIM_ID" : "EMPTY_FINAL_CLAIM_SELECTION");
        }
        if (invalidAssociationBasis) {
            return deterministic(compilation, "INVALID_METRIC_ASSOCIATION_BASIS");
        }
        if (invalidReviewBasis) {
            return deterministic(compilation, "INVALID_MANAGEMENT_REVIEW_BASIS");
        }
        if (incompleteSourceCoverage) {
            return deterministic(compilation, "INCOMPLETE_ANALYSIS_SOURCE_COVERAGE");
        }
        if (incompleteObservedFactCoverage) {
            return deterministic(compilation, "INCOMPLETE_OBSERVED_FACT_COVERAGE");
        }
        return render(compilation, headlineClaimIds, sections, selected,
            demandAnalysis, metricAssociations, managementReview,
            true, "CLAIM_SELECTION_ADMITTED");
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
            return DriverAudit.invalid("DRIVER_REVIEW_CLAIM_COVERAGE_INCOMPLETE");
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
            derived.basisClaimIds().stream().anyMatch(id -> !compilation.claims().containsKey(id))
                || !numbersGrounded(derived.text(), derived.basisClaimIds(), compilation));
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
                recordRefs, values, derived.basisClaimIds()));
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
            return deterministic(compilation, "EMPTY_MANAGEMENT_FINDINGS");
        }
        List<ClaimCoverage> coverage = maps(payload.get("coverage")).stream()
            .map(this::claimCoverage).filter(java.util.Objects::nonNull).toList();
        DemandAnalysis demandAnalysis = demandAnalysis(payload.get("demandAnalysis"));
        List<MetricAssociation> metricAssociations = maps(payload.get("metricAssociations")).stream()
            .map(this::metricAssociation).filter(java.util.Objects::nonNull).limit(8).toList();
        ManagementReview managementReview = managementReview(payload.get("managementReview"));

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        findings.forEach(finding -> selected.addAll(finding.basisClaimIds()));
        metricAssociations.forEach(association -> selected.addAll(association.basisClaimIds()));
        managementReview.items().forEach(item -> selected.addAll(item.basisClaimIds()));
        coverage.stream().filter(item -> "USED".equals(item.disposition()))
            .map(ClaimCoverage::claimId).forEach(selected::add);

        Set<String> rejectedByDriver = maps(object(payload.get("driverReview"))
            .get("claimAssessments")).stream()
            .filter(item -> "REJECT".equals(text(item.get("verdict")).toUpperCase(
                java.util.Locale.ROOT)))
            .map(item -> text(item.get("claimId")))
            .filter(id -> !id.isBlank())
            .collect(java.util.stream.Collectors.toSet());

        findings = ensureSourceFindingCoverage(findings, compilation, rejectedByDriver);
        findings.forEach(finding -> selected.addAll(finding.basisClaimIds()));

        boolean unknownBasis = selected.stream().anyMatch(id -> !compilation.claims().containsKey(id));
        boolean rejectedClaimPublished = selected.stream().anyMatch(rejectedByDriver::contains);
        boolean invalidFindingBasis = findings.stream().anyMatch(finding ->
            finding.basisClaimIds().isEmpty()
                || finding.basisClaimIds().stream().anyMatch(id -> !compilation.claims().containsKey(id)));
        boolean ungroundedNumbers = findings.stream().anyMatch(finding ->
            !numbersGrounded(finding.groundedText(), finding.basisClaimIds(), compilation))
            || managementReview.items().stream().anyMatch(item ->
                !numbersGrounded(item.text(), item.basisClaimIds(), compilation));

        Map<String, ClaimCoverage> coverageByClaim = new LinkedHashMap<>();
        boolean invalidCoverage = false;
        for (ClaimCoverage item : coverage) {
            if (!compilation.claims().containsKey(item.claimId())
                || coverageByClaim.putIfAbsent(item.claimId(), item) != null
                || ("USED".equals(item.disposition()) && !selected.contains(item.claimId()))) {
                invalidCoverage = true;
            }
        }
        Set<String> requiredObserved = compilation.claims().values().stream()
            .filter(Claim::observedFact).map(Claim::claimId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        boolean incompleteCoverageMatrix = !coverageByClaim.keySet().containsAll(requiredObserved);
        if (unknownBasis || invalidFindingBasis) {
            return deterministic(compilation, "INVALID_MANAGEMENT_FINDING_BASIS");
        }
        if (rejectedClaimPublished) {
            return deterministic(compilation, "DRIVER_REJECTED_CLAIM_SELECTED_FOR_PUBLICATION");
        }
        if (invalidCoverage || incompleteCoverageMatrix) {
            return deterministic(compilation, "INCOMPLETE_CLAIM_COVERAGE_MATRIX");
        }
        if (ungroundedNumbers) {
            return deterministic(compilation, "UNGROUNDED_MANAGEMENT_FINDING_VALUE");
        }
        return renderNarrative(compilation, findings, selected, demandAnalysis,
            metricAssociations, managementReview, dataCatalog,
            SCHEMA_VERSION.equals(text(payload.get("schemaVersion")))
                || findings.stream().anyMatch(finding -> !finding.dataRef().isBlank()));
    }

    private List<NarrativeFinding> ensureSourceFindingCoverage(
        List<NarrativeFinding> findings, Compilation compilation, Set<String> rejectedClaimIds
    ) {
        List<NarrativeFinding> result = new ArrayList<>(findings);
        Set<String> coveredSources = result.stream()
            .flatMap(finding -> finding.basisClaimIds().stream())
            .map(compilation.claims()::get)
            .filter(java.util.Objects::nonNull)
            .map(Claim::sourceScope)
            .filter(source -> source != null && !source.isBlank())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, List<Claim>> bySource = new LinkedHashMap<>();
        for (Claim claim : compilation.claims().values()) {
            if (claim.sourceScope() == null || claim.sourceScope().isBlank()
                || rejectedClaimIds.contains(claim.claimId())) continue;
            bySource.computeIfAbsent(claim.sourceScope(), ignored -> new ArrayList<>()).add(claim);
        }
        for (Map.Entry<String, List<Claim>> entry : bySource.entrySet()) {
            if (coveredSources.contains(entry.getKey())) continue;
            Claim representative = entry.getValue().stream()
                .sorted(java.util.Comparator
                    .comparing(Claim::observedFact)
                    .thenComparing(claim -> claim.significance() == null
                        || claim.significance().isBlank()))
                .findFirst().orElse(null);
            if (representative != null) {
                result.add(new NarrativeFinding("DEEP_DIVE", representative.text(),
                    List.of(representative.claimId())));
            }
        }
        return List.copyOf(result);
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

    private ClaimCoverage claimCoverage(Map<String, Object> source) {
        String claimId = text(source.get("claimId"));
        String disposition = text(source.get("disposition")).toUpperCase(java.util.Locale.ROOT);
        if (claimId.isBlank() || !Set.of("USED", "SUPPORTING_CONTEXT", "NOT_MATERIAL")
            .contains(disposition)) return null;
        return new ClaimCoverage(claimId, disposition, text(source.get("reason")));
    }

    private boolean numbersGrounded(String narrative, List<String> basisClaimIds,
                                    Compilation compilation) {
        Set<String> allowed = new LinkedHashSet<>();
        for (String claimId : basisClaimIds) {
            Claim claim = compilation.claims().get(claimId);
            if (claim == null) continue;
            allowed.addAll(numberTokens(claim.text()));
            claim.supportingValues().forEach(value -> allowed.addAll(numberTokens(value)));
        }
        return allowed.containsAll(numberTokens(narrative));
    }

    private Set<String> numberTokens(String value) {
        if (value == null || value.isBlank()) return Set.of();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("[-+]?\\d+(?:[.,]\\d+)*%?").matcher(value);
        Set<String> result = new LinkedHashSet<>();
        while (matcher.find()) result.add(matcher.group().replace(",", ""));
        return result;
    }

    String appendSelectionInstruction(String prompt, Compilation compilation) {
        if (compilation == null || compilation.claims().isEmpty()) return prompt;
        List<Map<String, Object>> ledger = compilation.claims().values().stream()
            .map(Claim::toPromptMap).toList();
        return (prompt == null ? "" : prompt)
            + "\n\nFinal management-synthesis contract (binding): the Worker and Reducer reports above are "
            + "the primary analytical input. The ledger below is their evidence index and factual boundary; it is "
            + "not the final report and must not be copied as a row inventory. Act as the management-level Driver: "
            + "combine related findings, explain their business meaning, identify tensions and evaluate the quality "
            + "of the completed analysis. Every synthesized finding must cite the admitted claim IDs that support it. "
            + "A ledger Claim marked REVIEW_REQUIRED is an evidence-bound analytical interpretation retained for "
            + "human judgment, not a rejected payload. You may use it when useful, but explicitly qualify it as an "
            + "interpretation, preserve its reviewReasons/caveats, and never present it as a verified fact. Claims "
            + "marked PARTIAL must retain their scope limitation; Claims marked SUPPORTED may be stated at their "
            + "recorded confidence. "
            + "You may paraphrase and combine supported claims into a more useful management conclusion, but may not "
            + "invent a value, entity state, comparison, threshold, relationship or cause absent from those claims. "
            + "Never report a requested dimension as missing or unavailable when the ledger contains a claim that "
            + "answers it. Do not omit a supported dimension merely because its evidence is a direct observation. "
            + "Use a supported-first reporting order. Missing history limits only trend, change, stability and causal "
            + "extensions; it never invalidates current-period values, composition, ranking, outcome or transaction "
            + "findings already present in the ledger. Every objective-relevant sourceScope with admitted Claims must "
            + "contribute at least one substantive finding. Marking all of its Claims as SUPPORTING_CONTEXT or "
            + "NOT_MATERIAL while discussing its gaps is not an answer. When multiple compatible Claims jointly answer "
            + "one question, synthesize them into a multi-Claim finding and explain the business meaning. "
            + "Evidence gaps are advisory review context, never a publication veto. Publish every supported finding "
            + "and use a gap only to qualify the specific unsupported extension; do not reject the whole analysis or "
            + "request repair merely because gaps exist or are numerous. "
            + "Execute the analysisMethodologyContract and analysisTree carried in the Driver pipeline context. "
            + "Build the reasoning chain from question and baseline through overall result, decomposition, contribution, "
            + "explanation, validation, impact and action. Missing baseline limits only dependent comparisons. Rank "
            + "findings by objective relevance, materiality and confidence; rank anomalies by degree and business impact. "
            + "Prefer a small number of decision-useful findings over repeated factual restatements. Complete the "
            + "analysisItemCoverage matrix for the dynamic analysisAgenda carried by Worker/Reducer reports. Every "
            + "ANSWERED or PARTIAL item must point to findings and basis Claims; REVIEW_REQUIRED must retain its "
            + "qualification, and NOT_APPLICABLE must explain the scope mismatch. "
            + "coverage matrix for every OBSERVED_RETURNED_FACT, including facts used in a finding, retained only as "
            + "supporting context, or consciously excluded as not material. Coverage does not require publishing "
            + "every fact; it prevents accidental loss during synthesis. Return only one JSON object with this shape: "
            + "{\"schemaVersion\":\"" + SCHEMA_VERSION + "\","
            + "\"driverReview\":{\"status\":\"PASS|CHALLENGE\","
            + "\"requirementCoverage\":[],\"claimConsistency\":[],\"evidenceSufficiency\":{},"
            + "\"crossWorkerConflicts\":[],\"duplicateEvidence\":[],\"unsupportedInferences\":[],"
            + "\"missingCriticalDimensions\":[],"
            + "\"claimAssessments\":[{\"claimId\":\"\",\"verdict\":\"ACCEPT|DOWNGRADE|REJECT\",\"reason\":\"\"}],"
            + "\"challenges\":[{\"targetLayer\":\"WORKER_REPORT|REDUCER_REPORT\","
            + "\"targetReportId\":\"\",\"claimIds\":[],\"reason\":\"\","
            + "\"requiredCorrection\":\"\"}]},"
            + "\"driverReasoning\":{\"derivedClaims\":[{\"derivedClaimId\":\"\","
            + "\"text\":\"\",\"basisClaimIds\":[],\"caveats\":[]}]},"
            + "\"findings\":[{\"section\":\"CORE|OVERALL|KEY_DRIVER|DEEP_DIVE|RISK_OPPORTUNITY|LIMITATION|ACTION\","
            + "\"text\":\"management-level synthesized conclusion\",\"basisClaimIds\":[],"
            + "\"question\":\"\",\"baseline\":\"\",\"comparison\":\"\",\"driver\":\"\","
            + "\"implication\":\"\",\"confidence\":\"\",\"dataRef\":\"\",\"visualizationIntent\":\"\"}],"
            + "\"coverage\":[{\"claimId\":\"\","
            + "\"disposition\":\"USED|SUPPORTING_CONTEXT|NOT_MATERIAL\",\"reason\":\"\"}],"
            + "\"analysisItemCoverage\":[{\"itemId\":\"\","
            + "\"status\":\"ANSWERED|PARTIAL|NOT_APPLICABLE|REVIEW_REQUIRED\","
            + "\"basisClaimIds\":[],\"findingIndexes\":[],\"reason\":\"\"}],"
            + "\"demandAnalysis\":{\"decisionGoal\":\"\",\"priorityQuestions\":[]},"
            + "\"metricAssociations\":[{\"title\":\"\",\"basisClaimIds\":[],"
            + "\"candidateMetrics\":[],\"analysisMethod\":\"\",\"validationNeeded\":[]}],"
            + "\"managementReview\":{"
            + "\"overallAssessment\":{\"text\":\"\",\"basisClaimIds\":[]},"
            + "\"identifiedProblems\":[{\"text\":\"\",\"basisClaimIds\":[]}],"
            + "\"improvementSuggestions\":[{\"text\":\"\",\"basisClaimIds\":[]}],"
            + "\"nextWorkDirections\":[{\"text\":\"\",\"basisClaimIds\":[]}]}}. "
            + AnalysisSynthesisContract.instruction()
            + "Put one to three decisive answers to the decision question in CORE. Use OVERALL for scale/trend/baseline, KEY_DRIVER for "
            + "decomposition and contribution, DEEP_DIVE for the most material questions, RISK_OPPORTUNITY for supported "
            + "impact, and LIMITATION only after findings. A finding's numbers must already occur in its cited "
            + "claims or supporting values. demandAnalysis explains the decision goal and open questions without adding facts. "
            + "metricAssociations are optional, explicitly unverified follow-up directions grounded in selected "
            + "admitted claims. Each must cite selected basisClaimIds and state candidate metrics, method and "
            + "validation needed. Never introduce a new observed value, entity state, threshold or causal claim. "
            + "Act as the manager reviewing completed Worker analysis reports: managementReview must synthesize "
            + "what the analyses collectively established, detect evidence/coverage/method gaps, and provide "
            + "specific improvements and prioritized next work. Do not repeat the ledger as a row inventory. Every "
            + "managementReview section is secondary to business findings and must never become a gap-only substitute "
            + "for the requested analysis. Consolidate repeated gaps into a short limitation instead of enumerating them. "
            + "non-empty review item must cite selected basisClaimIds; it is an evaluation of the admitted analysis, "
            + "not permission to create a new business fact. "
            + "Unknown IDs, missing finding bases, invented numeric values, or an incomplete observed-fact coverage "
            + "matrix invalidate the synthesis. Execute three mandatory stages before returning. DRIVER_REVIEW "
            + "independently checks requirement coverage, every admitted Claim, evidence sufficiency, cross-Worker "
            + "conflict, duplicate evidence, unsupported inference and missing critical dimensions. Agreement from "
            + "reports sharing the same lineage is not independent confirmation. DRIVER_REASONING may create derived "
            + "Claims only from admitted basisClaimIds, with alternatives and caveats. DRIVER_DECISION writes findings "
            + "only after review. You may downgrade or reject lower-layer Claims. A material error must set review "
            + "status CHALLENGE, identify the target report and Claims, and request a concrete correction. Claims with "
            + "a REJECT verdict must not enter findings; DOWNGRADE or REVIEW_REQUIRED Claims may enter only with their "
            + "uncertainty made explicit. A CHALLENGE is a management review note, not a publication "
            + "veto: still return all supported findings and expose disputed items for human judgment. Runtime "
            + "governance organizes evidence and labels uncertainty; it does not replace the human reviewer. Assess "
            + "every ledger Claim exactly once. "
            + "Do not return Markdown. Admitted claim ledger: " + ModelProtocolJson.compact(ledger);
    }

    private Projection deterministic(Compilation compilation, String reason) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        compilation.claims().values().stream().filter(Claim::observedFact)
            .map(Claim::claimId).forEach(selected::add);
        Set<String> representedSources = new LinkedHashSet<>();
        for (Claim claim : compilation.claims().values()) {
            String source = claim.sourceScope();
            if (source != null && !source.isBlank() && representedSources.add(source)) {
                selected.add(claim.claimId());
            }
        }
        for (String claimId : compilation.claims().keySet()) {
            if (selected.size() >= MAX_FALLBACK_CLAIMS) break;
            selected.add(claimId);
        }
        return render(compilation, List.copyOf(selected), List.of(), selected,
            DemandAnalysis.empty(), List.of(), ManagementReview.empty(), false, reason);
    }

    private Projection renderNarrative(Compilation compilation,
                                       List<NarrativeFinding> findings,
                                       Collection<String> selected,
                                       DemandAnalysis demandAnalysis,
                                       List<MetricAssociation> metricAssociations,
                                       ManagementReview managementReview, VerifiedReportDataCatalog dataCatalog,
                                       boolean structuredComposition) {
        StringBuilder answer = new StringBuilder("# 数据分析报告\n");
        if (demandAnalysis != null && !demandAnalysis.decisionGoal().isBlank()) {
            answer.append("\n分析问题：").append(demandAnalysis.decisionGoal()).append('\n');
        }
        appendNarrativeSection(answer, "一、分析结论", findings, "CORE");
        appendNarrativeSection(answer, "二、关键发现", findings, "DEEP_DIVE");
        appendNarrativeSection(answer, "三、结构性拆解", findings, "OVERALL");
        appendNarrativeSection(answer, "四、驱动因素", findings, "KEY_DRIVER");
        appendNarrativeSection(answer, "五、业务含义", findings, "RISK_OPPORTUNITY");
        appendNarrativeSection(answer, "六、数据边界", findings, "LIMITATION");
        appendNarrativeSection(answer, "七、下一步行动与补充数据", findings, "ACTION");
        if (demandAnalysis != null && !demandAnalysis.emptyValue()) {
            if (!demandAnalysis.priorityQuestions().isEmpty()) answer.append("\n### 待验证问题\n\n");
            demandAnalysis.priorityQuestions().forEach(question ->
                answer.append("- 未决问题：").append(question).append('\n'));
        }
        if (metricAssociations != null && !metricAssociations.isEmpty()) {
            answer.append("\n## 指标联想与待验证方向\n\n");
            for (MetricAssociation association : metricAssociations) {
                answer.append("- ").append(association.title());
                if (!association.candidateMetrics().isEmpty()) {
                    answer.append("；候选指标：")
                        .append(String.join("、", association.candidateMetrics()));
                }
                if (!association.analysisMethod().isBlank()) {
                    answer.append("；分析方法：").append(association.analysisMethod());
                }
                if (!association.validationNeeded().isEmpty()) {
                    answer.append("；验证所需：")
                        .append(String.join("、", association.validationNeeded()));
                }
                answer.append('\n');
            }
        }
        if (managementReview != null && !managementReview.emptyValue()) {
            answer.append("\n## 管理复盘与下一步\n\n");
            if (managementReview.overallAssessment() != null) {
                answer.append("- 总体评价：")
                    .append(managementReview.overallAssessment().text()).append('\n');
            }
            managementReview.identifiedProblems().forEach(item ->
                answer.append("- 分析问题：").append(item.text()).append('\n'));
            managementReview.improvementSuggestions().forEach(item ->
                answer.append("- 改进建议：").append(item.text()).append('\n'));
            managementReview.nextWorkDirections().forEach(item ->
                answer.append("- 下一步：").append(item.text()).append('\n'));
        }
        if (!structuredComposition) return new Projection(true, "GROUNDED_MANAGEMENT_SYNTHESIS_ADMITTED",
            answer.toString().trim(), List.copyOf(selected));
        ReportComposer composer = new ReportComposer();
        List<AnalyticalInsightBlock> blocks = new ArrayList<>();
        List<String> sectionOrder = List.of("CORE", "DEEP_DIVE", "OVERALL", "KEY_DRIVER", "RISK_OPPORTUNITY", "LIMITATION", "ACTION");
        for (NarrativeFinding finding : findings.stream().distinct()
            .sorted(java.util.Comparator.comparingInt(item -> sectionOrder.indexOf(item.section()))).toList()) {
            List<Claim> basis = finding.basisClaimIds().stream().map(compilation.claims()::get).toList();
            String interpretation = (finding.baseline().isBlank() ? "" : "比较基准：" + finding.baseline() + "\n")
                + (finding.comparison().isBlank() ? "" : "比较结果：" + finding.comparison() + "\n") + finding.driver();
            blocks.add(composer.compose("F" + (blocks.size() + 1), finding.section(), finding.question(),
                finding.text(), interpretation, finding.implication(), finding.confidence(),
                basis.stream().flatMap(claim -> claim.caveats().stream()).distinct().toList(),
                basis.stream().map(Claim::toArtifactMap).toList(), finding.dataRef(),
                finding.visualizationIntent(), dataCatalog));
        }
        Map<String, Object> report = Map.of("schemaVersion", ReportComposer.VERSION,
            "decisionQuestion", demandAnalysis == null ? "" : demandAnalysis.decisionGoal(),
            "blocks", List.copyOf(blocks), "executiveSummaryIds", blocks.stream()
                .filter(block -> block.presentation().primaryConclusion()).map(AnalyticalInsightBlock::id).limit(5).toList());
        return new Projection(true, "GROUNDED_MANAGEMENT_SYNTHESIS_ADMITTED",
            composer.markdown(demandAnalysis == null ? "" : demandAnalysis.decisionGoal(), blocks), List.copyOf(selected), report);
    }

    private void appendNarrativeSection(StringBuilder answer, String title,
                                        List<NarrativeFinding> findings, String section) {
        List<NarrativeFinding> sectionFindings = findings.stream()
            .filter(finding -> section.equals(finding.section())).distinct().toList();
        if (sectionFindings.isEmpty()) return;
        answer.append("\n## ").append(title).append("\n\n");
        int index = 0;
        for (NarrativeFinding finding : sectionFindings) {
            if ("DEEP_DIVE".equals(section)) {
                answer.append("### ").append(finding.question().isBlank()
                    ? "发现 " + (++index) : finding.question()).append("\n\n");
                answer.append(finding.text()).append("\n\n");
            } else {
                answer.append("- ").append(finding.text()).append("\n\n");
            }
            appendFindingDetail(answer, "比较基准", finding.baseline());
            appendFindingDetail(answer, "比较结果", finding.comparison());
            appendFindingDetail(answer, "驱动解释", finding.driver());
            appendFindingDetail(answer, "业务影响", finding.implication());
            appendFindingDetail(answer, "判断可信度", finding.confidence());
        }
    }

    private void appendFindingDetail(StringBuilder answer, String label, String value) {
        if (!value.isBlank()) answer.append("**").append(label).append("**：")
            .append(value).append("\n\n");
    }

    private boolean evidenceCovers(Claim candidate, Claim observed) {
        if (candidate.claimId().equals(observed.claimId())) return true;
        return !observed.recordRefs().isEmpty() && !observed.supportingValues().isEmpty()
            && candidate.recordRefs().containsAll(observed.recordRefs())
            && candidate.supportingValues().containsAll(observed.supportingValues());
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
                         List<String> supportingValues, List<String> basisClaimIds) {
        private Claim {
            recordRefs = recordRefs == null ? List.of() : List.copyOf(recordRefs);
            supportingValues = supportingValues == null ? List.of() : List.copyOf(supportingValues);
            reviewReasons = reviewReasons == null ? List.of() : List.copyOf(reviewReasons);
            basisClaimIds = basisClaimIds == null ? List.of() : List.copyOf(basisClaimIds);
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
            return Map.copyOf(result);
        }
    }
}
