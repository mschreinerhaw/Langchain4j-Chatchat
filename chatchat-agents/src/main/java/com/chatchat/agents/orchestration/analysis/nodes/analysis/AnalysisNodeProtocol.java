package com.chatchat.agents.orchestration.analysis.nodes.analysis;

import com.chatchat.agents.orchestration.analysis.protocol.AnalysisArtifactProtocol;

import com.chatchat.agents.orchestration.analysis.contract.AnalysisContextPresentationContract;
import com.chatchat.agents.runtime.context.AgentRoleAnalysisContext;
import com.chatchat.agents.orchestration.analysis.contract.AnalysisObjectiveContractCompiler;
import com.chatchat.agents.orchestration.analysis.contract.AnalysisSemanticContractCompiler;
import com.chatchat.agents.orchestration.analysis.contract.CapabilityEvidenceClaimCompiler;
import com.chatchat.agents.orchestration.analysis.dataset.AnalysisRecordScopeProfiler;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;


import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.analysis.model.DataAnalysisPosition;
import com.chatchat.common.runtime.summary.analysis.contract.DataAnalysisDecisionOperatingModel;
import com.chatchat.common.runtime.summary.analysis.governance.DataAnalysisLayerGovernanceContract;
import com.chatchat.common.runtime.summary.analysis.spi.DataAnalysisSummaryProtocol;
import com.chatchat.common.runtime.summary.analysis.semantic.model.CapabilityEvidenceClaimContract;
import com.chatchat.common.runtime.summary.analysis.semantic.governance.SemanticClaimAdmissionPolicy;
import com.chatchat.common.runtime.summary.analysis.semantic.governance.SemanticClaimGapPolicy;
import com.chatchat.common.runtime.summary.analysis.semantic.governance.SemanticClaimLifecycleContract;
import com.chatchat.common.runtime.summary.analysis.semantic.model.SemanticEvidenceGapContract;
import com.chatchat.common.runtime.summary.analysis.semantic.adapter.SemanticGapAnalysisLoopAdapter;
import com.chatchat.common.runtime.summary.analysis.semantic.model.SemanticOperation;
import com.chatchat.common.tool.DataAnalysisContextProtocol;
import com.chatchat.common.runtime.summary.spi.ModelSummaryModel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Bridges source-neutral summary governance into model analysis and records every chunk's
 * position. It may supplement structural metadata, but never invents business semantics.
 */
public final class AnalysisNodeProtocol
    implements DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope> {

    private static final Logger log = LoggerFactory.getLogger(AnalysisNodeProtocol.class);

    public static final String BRIDGE_SCHEMA_VERSION =
        DataAnalysisSummaryProtocol.BRIDGE_SCHEMA_VERSION;
    public static final String EVIDENCE_SCHEMA_VERSION =
        DataAnalysisSummaryProtocol.EVIDENCE_SCHEMA_VERSION;
    public static final String WORKER_REPORT_SCHEMA_VERSION =
        DataAnalysisDecisionOperatingModel.WORKER_REPORT_SCHEMA_VERSION;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final AnalysisObjectiveContractCompiler objectiveContractCompiler =
        new AnalysisObjectiveContractCompiler();
    private final AnalysisRecordScopeProfiler recordScopeProfiler =
        new AnalysisRecordScopeProfiler();
    private final AnalysisSemanticContractCompiler semanticContractCompiler =
        new AnalysisSemanticContractCompiler();
    private final CapabilityEvidenceClaimCompiler capabilityEvidenceClaimCompiler =
        new CapabilityEvidenceClaimCompiler();
    private final SemanticClaimAdmissionPolicy semanticClaimAdmissionPolicy =
        new SemanticClaimAdmissionPolicy();
    private final SemanticClaimGapPolicy semanticClaimGapPolicy = new SemanticClaimGapPolicy();
    private final SemanticGapAnalysisLoopAdapter semanticGapAnalysisLoopAdapter =
        new SemanticGapAnalysisLoopAdapter();

    /** Applies an explicit producer policy before any model call. */
    public boolean requiresModelSummary(Map<String, Object> governedContext, boolean oversized) {
        // Returned data is analysis work regardless of its shape or the completeness of producer
        // semantics. Producer policy constrains permitted operations and claims; it cannot bypass
        // the Runtime's minimum observation analysis.
        return true;
    }

    public Map<String, Object> govern(String reference,
                                      Map<String, Object> suppliedContext,
                                      List<Map<String, Object>> records) {
        Map<String, Object> supplied = copy(suppliedContext);
        Map<String, Object> validatedRoleContext = AgentRoleAnalysisContext.validate(
            supplied.get(AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY));
        if (validatedRoleContext.isEmpty()) {
            supplied.remove(AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY);
        } else {
            supplied.put(AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY, validatedRoleContext);
        }
        List<String> suppliedSections = new ArrayList<>();
        List<String> missingSemanticSections = new ArrayList<>();
        for (String section : List.of("source", "capability", "business", "schema", "relationships",
            "semantics", "quality", "analysisPolicy", "extensions")) {
            if (supplied.containsKey(section) && supplied.get(section) != null) suppliedSections.add(section);
            else missingSemanticSections.add(section);
        }

        Map<String, Object> source = copy(supplied.get("source"));
        source.putIfAbsent("runtimeReference", safeReference(reference));
        Map<String, Object> schema = copy(supplied.get("schema"));
        List<Map<String, Object>> derivedFields = returnedFields(records);
        if (!schema.containsKey("fields") || schema.get("fields") == null) {
            schema.put("fields", derivedFields);
        }

        Map<String, Object> governed = new LinkedHashMap<>(DataAnalysisContextProtocol.create(
            source,
            supplied.getOrDefault("capability", Map.of()),
            copy(supplied.get("business")),
            schema,
            supplied.getOrDefault("relationships", Map.of()),
            copy(supplied.get("semantics")),
            copy(supplied.get("quality")),
            copy(supplied.get("analysisPolicy")),
            copy(supplied.get("extensions"))
        ));
        supplied.forEach((key, value) -> {
            if (value != null && !List.of(
                "schemaVersion", "source", "capability", "business", "schema", "relationships",
                "semantics", "quality", "analysisPolicy", "extensions", "governance").contains(key)) {
                governed.put(key, value);
            }
        });
        Object suppliedSchemaVersion = supplied.get("schemaVersion");
        if (suppliedSchemaVersion != null
            && !DataAnalysisContextProtocol.SCHEMA_VERSION.equals(String.valueOf(suppliedSchemaVersion))) {
            governed.put("sourceContextSchemaVersion", String.valueOf(suppliedSchemaVersion));
        }
        governed.put("schemaVersion", DataAnalysisContextProtocol.SCHEMA_VERSION);
        governed.put("source", immutable(source));
        governed.put("schema", immutable(schema));
        Map<String, Object> governance = copy(governed.get("governance"));
        governance.putAll(copy(supplied.get("governance")));
        governance.put("bridgeSchemaVersion", BRIDGE_SCHEMA_VERSION);
        governed.put("governance", immutable(governance));
        governed.put("contextCompleteness", Map.of(
            "suppliedSections", List.copyOf(suppliedSections),
            "missingSemanticSections", List.copyOf(missingSemanticSections),
            "derivedFieldNamesOnly", !derivedFields.isEmpty()
                && !(copy(supplied.get("schema")).containsKey("fields")),
            "semanticInferenceAllowed", false
        ));
        return immutable(governed);
    }

    public DataAnalysisPosition position(String reference,
                                  int chunkIndex,
                                  int chunkCount,
                                  int from,
                                  int to,
                                  int totalRecords) {
        return new DataAnalysisPosition(safeReference(reference), chunkIndex, chunkCount,
            from, to, totalRecords);
    }

    public AnalysisSummaryResult summarize(ModelSummaryModel model,
                                           GovernanceIsolationScope isolationScope,
                                           DataAnalysisPosition position,
                                           Map<String, Object> governedContext,
                                           List<Map<String, Object>> records) {
        return summarize(model, isolationScope, position, governedContext, records, null);
    }

    public AnalysisSummaryResult summarize(ModelSummaryModel model,
                                           GovernanceIsolationScope isolationScope,
                                           DataAnalysisPosition position,
                                           Map<String, Object> governedContext,
                                           List<Map<String, Object>> records,
                                           String userObjective) {
        Map<String, Object> objectiveContract = objectiveContractCompiler.compile(
            safeObjective(userObjective), position, governedContext);
        Map<String, Object> recordScopeProfile = recordScopeProfiler.profile(records);
        Map<String, Object> semanticContract = semanticContractCompiler.compile(governedContext);
        try {
            String prompt = compactWorkerPrompt(userObjective, objectiveContract, semanticContract,
                recordScopeProfile, position, governedContext, records);
            var execution = new com.chatchat.agents.orchestration.analysis.graph.FindingAnalysisGraph().execute(
                () -> {
                    String raw = model.generate(prompt);
                    if (raw == null || raw.isBlank()) throw new IllegalStateException("Empty analysis result");
                    return new AnalysisProduct(raw, null);
                },
                product -> product.capsule() == null ? new AnalysisProduct(product.raw(),
                    evidenceCapsule(isolationScope, position, governedContext, records, product.raw(),
                        objectiveContract, semanticContract)) : product,
                product -> product.capsule().evidence().get("invalidInsightCount") instanceof Number invalid
                    && invalid.intValue() > 0,
                product -> {
                    String raw = model.generate(prompt
                        + "\nRepair the previous structured product once. Preserve supported claims. Fix only rejected claims using original records; never invent evidence. If impossible, remove the claim and state the limitation.\nPrevious product: "
                        + product.raw() + "\nRuntime admission decisions: "
                        + ModelProtocolJson.compact(product.capsule().evidence().get("claimAdmissionDecisions")));
                    if (raw == null || raw.isBlank()) return product;
                    EvidenceCapsule candidate = evidenceCapsule(isolationScope, position, governedContext,
                        records, raw, objectiveContract, semanticContract);
                    for (String key : List.of("insights", "facts")) {
                        var retained = maps(candidate.evidence().get(key)).stream()
                            .map(value -> value.get("claim")).collect(java.util.stream.Collectors.toSet());
                        if (!maps(product.capsule().evidence().get(key)).stream()
                            .allMatch(value -> retained.contains(value.get("claim")))) return product;
                    }
                    return new AnalysisProduct(raw, candidate);
                });
            EvidenceCapsule capsule = execution.product().capsule();
            Map<String, Object> evidence = new LinkedHashMap<>(capsule.evidence());
            evidence.put("analysisNodeTransitions", execution.visitedNodes());
            return AnalysisSummaryResult.chunk(isolationScope, position.toMap(), governedContext,
                capsule.content(), "MODEL_SUMMARY", evidence);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException failure) {
            // Do not log the prompt, model output or returned records. The Worker invocation
            // already applied its retry policy; this terminal diagnostic explains why the
            // immutable evidence fallback was produced without leaking business data.
            log.warn("analysisWorkerSummaryUnavailable dataset={} chunk={}/{} errorType={} error={}",
                position.datasetReference(), position.chunkIndex(), position.chunkCount(),
                failure.getClass().getName(), safeError(failure));
        }
        return fallback(isolationScope, position, governedContext, records);
    }

    @Override
    public AnalysisSummaryResult validateProduct(GovernanceIsolationScope scope, DataAnalysisPosition position,
        Map<String, Object> context, List<Map<String, Object>> records, String objective, String productJson) {
        var capsule = evidenceCapsule(scope, position, context, records, productJson,
            objectiveContractCompiler.compile(safeObjective(objective), position, context),
            semanticContractCompiler.compile(context));
        return AnalysisSummaryResult.chunk(scope, position.toMap(), context, capsule.content(),
            "UNIFIED_FINDING_VALIDATION", capsule.evidence());
    }

    private String safeError(RuntimeException failure) {
        String message = failure == null ? "" : failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure == null ? "unknown model failure" : failure.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private String compactWorkerPrompt(String userObjective,
                                       Map<String, Object> objectiveContract,
                                       Map<String, Object> semanticContract,
                                       Map<String, Object> recordScopeProfile,
                                       DataAnalysisPosition position,
                                       Map<String, Object> governedContext,
                                       List<Map<String, Object>> records) {
        return "Execute the data analysis node in a governed analysis graph ("
            + BRIDGE_SCHEMA_VERSION + "). Analyze the returned records in Chinese and deliver a professional "
            + "structured finding product, not a final report or row inventory. Use the original question and agent_role_analysis_context to "
            + "understand the decision goal, business scenario and relevant vocabulary. Context guides analysis "
            + "but is not returned evidence. When analysisRepair is present, revise the prior rejected analysis "
            + "against its supervision reasons and repair requests while reusing the supplied records; do not "
            + "request or assume a new data query. Missing semantic sections remain unknown.\n"
            + "Lead with findings, not row counts or metadata. Complete reasoning for the supplied record range only. "
            + "When chunkCount > 1, do not claim coverage of other chunks or repeat a full report in each chunk. "
            + "Other scheduled chunks are pending Runtime work, not requests for external evidence. "
            + "Use a short summary (at most 3 sentences); place distinct findings in insights and exact values in facts. "
            + "Do not repeat the same narrative in summary, analysisItems and insights. Do not include a report title, "
            + "executive summary, risk chapter or full troubleshooting procedure; the synthesis node composes those from validated findings. "
            + "Establish scope and grain, answer every supported objective aspect, connect "
            + "related returned metrics, identify material patterns and exceptions, explain why they matter, and "
            + "execute analysisMethodologyContract and the assigned analysisTree. Establish an explicit baseline "
            + "before comparison, trend or abnormality claims, then reason from total to component, contribution, "
            + "driver, validation and business impact. If baseline evidence is missing, retain the supported current "
            + "state and qualify only baseline-dependent extensions. Keep decomposition dimensions non-overlapping "
            + "where possible and rank findings by objective relevance, materiality and confidence. "
            + "state precise evidence gaps. When the returned records form a metric catalog (one field identifies "
            + "a metric or state and another carries its returned value), select and analyze the material metrics "
            + "across every objective-relevant dimension instead of describing the catalog or its row count. Tool "
            + "or gateway success, routing, environment, result completeness, truncation and transport status are "
            + "execution diagnostics, not substantive business findings; never use them as the core conclusion. "
            + "Apply a supported-first invariant: exhaust the objective-relevant facts "
            + "and responsible analysis available in the returned records before discussing missing evidence. A "
            + "missing historical series limits trend or stability claims, but never makes a returned current-period "
            + "value, current composition, current ranking or current transaction observation unavailable. Explicitly "
            + "answer the supported current-state part and attach the limitation only to the unsupported extension. "
            + "Do not let openQuestions, limitations, missingEvidence or follow-up requests occupy more analytical "
            + "attention than the supported findings. A value already returned by the producer at its declared grain is an "
            + "observed fact; quoting it is OBSERVE, not AGGREGATE or DERIVE. Derived values require an explicit "
            + "formula and semantic authorization. Calibrated inferences require caveats. Never infer undeclared "
            + "joins, aggregation, causality, completeness or long-term behavior.\n"
            + "Return one JSON object. Required fields: summary; demandAnalysis with decisionGoal, "
            + "answeredQuestions and openQuestions; metricAssociations (empty when none); objectiveAlignment; "
            + "analysisItems (one disposition for every analysisAgenda item applicable to this dataset); "
            + "analysisMethodExecution (baseline, overallFinding, decompositions, contributions, explanations, "
            + "crossValidation, businessImpacts and findingPriorities); insights; facts; conflicts; limitations; "
            + "missingEvidence; recommendedFollowupRequests; "
            + "rawReplayRecommended. Each fact cites recordRefs and exactValues. Each insight contains claimClass "
            + "(OBSERVED_RETURNED_FACT, AUTHORIZED_DERIVED_MEASURE or CALIBRATED_INFERENCE), claim, significance, "
            + "operation, recordRefs, supportingValues, confidence (HIGH|MEDIUM|LOW) and caveats. Include method, inputFields, outputUnit, "
            + "grain, timeScope, populationScope, semanticBasis and alternativeExplanations when applicable. Preserve a complete, decision-useful summary "
            + "even when some candidate analysis remains pending validation. Every objective-relevant returned "
            + "dataset must contribute its material current-state facts to summary/facts/insights; a gap list is not "
            + "a substitute for analyzing the supplied records. analysisItems shape: [{itemId, analysisType, status "
            + "(SUPPORTED|PARTIAL|NOT_APPLICABLE|REVIEW_REQUIRED), finding, businessMeaning, basisRecordRefs, "
            + "supportingValues, method, confidence, timeScope, limitations}]. A SUPPORTED, PARTIAL or "
            + "REVIEW_REQUIRED item must cite exact returned evidence. NOT_APPLICABLE must explain why this dataset "
            + "cannot address it.\n"
            + "Original user question (authoritative analysis intent): " + safeObjective(userObjective) + "\n"
            + "Analysis objective contract: " + ModelProtocolJson.compact(objectiveContract) + "\n"
            + "Producer semantic contract: " + ModelProtocolJson.compact(semanticContract) + "\n"
            + "Returned-record scope: " + ModelProtocolJson.compact(recordScopeProfile) + "\n"
            + "Analysis position: " + ModelProtocolJson.compact(position.toMap()) + "\n"
            + "Evidence reference contract: recordRefs and basisRecordRefs must use "
            + ModelProtocolJson.compact(position.datasetReference() + ".records[" + position.recordFrom() + "]")
            + " for the first supplied record; subsequent indexes are absolute indexes through "
            + position.recordTo() + ". Do not cite run IDs, chunk IDs, sourcePath or field paths instead. "
            + "sourcePath identifies the original JSON location; supportingValues/exactValues must quote "
            + "values in the cited record. Put derived computations in separately authorized claims.\n"
            + "Governed context (includes agent_role_analysis_context when configured): "
            + ModelProtocolJson.compact(governedContext) + "\n"
            + "Returned records: " + ModelProtocolJson.compact(records);
    }

    private String safeObjective(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                "original user question is required for worker chunk analysis");
        }
        return value;
    }

    public AnalysisSummaryResult preserve(GovernanceIsolationScope isolationScope,
                                          DataAnalysisPosition position,
                                          Map<String, Object> governedContext,
                                          List<Map<String, Object>> records) {
        return AnalysisSummaryResult.chunk(isolationScope, position.toMap(), governedContext,
            ModelProtocolJson.compact(records), "STRUCTURED_RECORD_DIRECT",
            rawEvidence(isolationScope, position, governedContext, records, true, false));
    }

    public AnalysisSummaryResult fallback(GovernanceIsolationScope isolationScope,
                                          DataAnalysisPosition position,
                                          Map<String, Object> governedContext,
                                          List<Map<String, Object>> records) {
        return AnalysisSummaryResult.chunk(isolationScope, position.toMap(), governedContext,
            ModelProtocolJson.compact(records), "STRUCTURED_RECORD_FALLBACK",
            rawEvidence(isolationScope, position, governedContext, records, false, true));
    }

    public String finalSynthesisInstruction() {
        return "- Summary-governance bridge (" + BRIDGE_SCHEMA_VERSION + ", "
            + DataAnalysisContextProtocol.GOVERNANCE_VERSION + "): apply each dataset's analysisContext uniformly "
            + "for identity, field semantics, analytical semantics, quality, analysis policy, source extensions, "
            + "business-template requirement matching, and explicit relationships. Treat context as semantic and presentation input, never as "
            + "returned values. For chunk summaries, preserve their recorded dataset, chunk, "
            + "record range, and total-record position; never merge a chunk under another dataset identity. If context "
            + "is incomplete, keep missing semantics and relationships unknown. Every material conclusion must be "
            + "grounded in traceable_chunk_evidence.v1 facts and their exact evidence references. Correlate chunks "
            + "only through explicit relationships or exact crossChunkKeys, surface conflicts instead of silently "
            + "choosing one value, and use an attached raw replay whenever a capsule marks rawReplayRecommended, "
            + "contains unvalidated facts, or lacks a structured capsule.\n"
            + AnalysisContextPresentationContract.synthesisInstruction();
    }

    public Map<String, Object> ledger(List<AnalysisSummaryResult> summaries,
                                     int returnedRecordCount,
                                     int processedRecordCount,
                                     boolean complete) {
        List<AnalysisSummaryResult> safeSummaries = summaries == null ? List.of() : List.copyOf(summaries);
        if (!safeSummaries.isEmpty()) {
            GovernanceIsolationScope scope = safeSummaries.get(0).isolationScope();
            safeSummaries.forEach(summary -> scope.requireSamePartition(summary.isolationScope()));
        }
        long traceableCount = safeSummaries.stream()
            .filter(summary -> EVIDENCE_SCHEMA_VERSION.equals(summary.evidence().get("schemaVersion")))
            .filter(summary -> !String.valueOf(summary.evidence().getOrDefault("contentSha256", "")).isBlank())
            .count();
        long structuredCount = safeSummaries.stream()
            .filter(summary -> Boolean.TRUE.equals(summary.evidence().get("structured")))
            .count();
        long replayableCount = safeSummaries.stream()
            .filter(summary -> Boolean.TRUE.equals(summary.evidence().get("rawReplayAvailable")))
            .count();
        Map<String, Object> ledger = new LinkedHashMap<>();
        ledger.put("schemaVersion", BRIDGE_SCHEMA_VERSION);
        ledger.put("evidenceSchemaVersion", EVIDENCE_SCHEMA_VERSION);
        ledger.put("governanceProtocolVersion", DataAnalysisContextProtocol.GOVERNANCE_VERSION);
        ledger.put("returnedRecordCount", returnedRecordCount);
        ledger.put("processedRecordCount", processedRecordCount);
        ledger.put("complete", complete);
        ledger.put("traceableCount", traceableCount);
        ledger.put("structuredCount", structuredCount);
        ledger.put("replayableCount", replayableCount);
        ledger.put("traceComplete", traceableCount == safeSummaries.size());
        ledger.put("isolationScope",
            safeSummaries.isEmpty() ? Map.of() : safeSummaries.get(0).isolationScope().toMap());
        ledger.put("summaryResults", safeSummaries.stream().map(AnalysisSummaryResult::toMap).toList());
        return Collections.unmodifiableMap(ledger);
    }

    private EvidenceCapsule evidenceCapsule(GovernanceIsolationScope isolationScope,
                                            DataAnalysisPosition position,
                                            Map<String, Object> governedContext,
                                            List<Map<String, Object>> records,
                                            String modelOutput,
                                            Map<String, Object> objectiveContract,
                                            Map<String, Object> semanticContract) {
        Map<String, Object> payload = parseObject(modelOutput);
        String content = string(payload.get("summary"));
        boolean structured = !payload.isEmpty() && content != null && !content.isBlank();
        if (!structured) {
            content = modelOutput.trim();
        }
        List<Map<String, Object>> facts = new ArrayList<>();
        int rejectedFacts = 0;
        for (Map<String, Object> candidate : maps(payload.get("facts"))) {
            String claim = string(candidate.get("claim"));
            List<String> recordRefs = strings(candidate.get("recordRefs")).stream()
                .filter(reference -> validRecordReference(position, reference))
                .distinct()
                .toList();
            List<String> exactValues = strings(candidate.get("exactValues")).stream()
                .filter(value -> !value.isBlank()
                    && exactValueSupported(position, records, recordRefs, value))
                .distinct()
                .toList();
            if (claim == null || claim.isBlank() || recordRefs.isEmpty() || exactValues.isEmpty()) {
                rejectedFacts++;
                continue;
            }
            facts.add(Map.of(
                "claim", claim,
                "recordRefs", recordRefs,
                "exactValues", exactValues
            ));
        }
        Map<String, Object> evidence = new LinkedHashMap<>(rawEvidence(
            isolationScope, position, governedContext, records, structured, false));
        evidence.put("facts", List.copyOf(facts));
        evidence.put("observedFactClaims", observedFactClaims(position, facts));
        evidence.put("entities", maps(payload.get("entities")));
        evidence.put("crossChunkKeys", strings(payload.get("crossChunkKeys")));
        evidence.put("conflicts", strings(payload.get("conflicts")));
        evidence.put("limitations", strings(payload.get("limitations")));
        evidence.put("datasetFindings", strings(payload.get("datasetFindings")));
        evidence.put("metrics", copy(payload.get("metrics")));
        evidence.put("rankings", copy(payload.get("rankings")));
        evidence.put("analyzedRelationships", maps(payload.get("relationships")));
        evidence.put("businessConclusions", strings(payload.get("businessConclusions")));
        evidence.put("unsupportedQuestions", strings(payload.get("unsupportedQuestions")));
        evidence.put("missingEvidence", strings(payload.get("missingEvidence")));
        evidence.put("recommendedFollowupRequests", followupRequests(payload.get("recommendedFollowupRequests")));
        evidence.put("objectiveAlignment", objectiveAlignment(payload.get("objectiveAlignment")));
        Map<String, Object> demandAnalysis = demandAnalysis(payload.get("demandAnalysis"));
        evidence.put("demandAnalysis", demandAnalysis);
        evidence.put("metricAssociations", metricAssociations(payload.get("metricAssociations"), position));
        evidence.put("analysisItems", analysisItems(
            payload.get("analysisItems"), position, records));
        evidence.put("workerAnalysisItemsDeclared", payload.containsKey("analysisItems"));
        evidence.put("analysisDecisionOperatingModelVersion",
            DataAnalysisDecisionOperatingModel.SCHEMA_VERSION);
        evidence.put("analysisParticipantRole",
            DataAnalysisDecisionOperatingModel.ParticipantRole.WORKER.name());
        evidence.put("workerAnalysisReportSchemaVersion", WORKER_REPORT_SCHEMA_VERSION);
        evidence.put("workerDemandAnalysisComplete", workerDemandAnalysisComplete(demandAnalysis));
        evidence.put("workerMetricAssociationAssessmentDeclared",
            payload.containsKey("metricAssociations"));
        evidence.put("analysisObjectiveContract", objectiveContract == null
            ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(objectiveContract)));
        evidence.put("analysisSemanticContract", semanticContract == null
            ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(semanticContract)));
        evidence.put("analysisQuality", copy(payload.get("analysisQuality")));
        evidence.put("analysisDepth", analysisDepth(payload.get("analysisDepth")));
        evidence.put("analysisMethodExecution", copy(payload.get("analysisMethodExecution")));
        evidence.put("analysisDepthContractVersion", "professional_analysis_depth.v1");
        List<Map<String, Object>> proposedInsights = maps(payload.get("insights"));
        InsightValidation insightValidation = validatedInsights(
            position, records, proposedInsights, semanticContract);
        List<Map<String, Object>> validatedInsights = insightValidation.admitted();
        long reviewRequiredInsights = insightValidation.decisions().stream()
            .filter(decision -> Boolean.TRUE.equals(decision.get("reviewRequired"))).count();
        int rejectedInsights = (int) insightValidation.decisions().stream()
            .filter(decision -> !Boolean.TRUE.equals(decision.get("admitted"))).count();
        long invalidInsights = insightValidation.decisions().stream()
            .filter(decision -> "INVALID".equals(decision.get("governanceStatus"))).count();
        evidence.put("insights", validatedInsights);
        evidence.put("proposedInsightCount", proposedInsights.size());
        evidence.put("claimContractVersion", CapabilityEvidenceClaimContract.SCHEMA_VERSION);
        evidence.put("claimAdmissionDecisions", insightValidation.decisions());
        evidence.put("claimLifecycle", insightValidation.claimLifecycle());
        evidence.put("semanticGaps", insightValidation.gaps());
        evidence.put("semanticGapRequests", semanticGapAnalysisLoopAdapter
            .toGapRequests(insightValidation.gapContracts()).stream()
            .map(com.chatchat.common.runtime.summary.analysis.contract.AnalysisLoopContract.GapRequest::toMap)
            .toList());
        evidence.put("rejectedInsightCount", rejectedInsights);
        evidence.put("invalidInsightCount", invalidInsights);
        evidence.put("reviewRequiredInsightCount", reviewRequiredInsights);
        // Governance annotates analytical uncertainty. It does not erase an evidence-bound
        // Worker report or replace the human reviewer.
        evidence.put("analysisNarrativeStatus", reviewRequiredInsights > 0
            ? "PRESERVED_WITH_REVIEW_NOTES" : "PRESERVED_GOVERNED_WORKER_REPORT");
        evidence.put(AnalysisArtifactProtocol.EVIDENCE_KEY,
            AnalysisArtifactProtocol.fromEvidence("WORKER", position.datasetReference(), evidence));
        evidence.put("analysisArtifactSchemaVersion", AnalysisArtifactProtocol.SCHEMA_VERSION);
        evidence.put("rejectedFactCount", rejectedFacts);
        LinkedHashSet<Integer> citedRecords = citedRecordIndexes(position, facts);
        boolean factRecordCoverageComplete = records == null || records.isEmpty()
            || citedRecords.size() == records.size();
        evidence.put("citedRecordCount", citedRecords.size());
        evidence.put("factRecordCoverageComplete", factRecordCoverageComplete);
        evidence.put("rawReplayRecommended",
            truthy(payload.get("rawReplayRecommended")) || rejectedFacts > 0
                || reviewRequiredInsights > 0 || !structured);
        return new EvidenceCapsule(content, Collections.unmodifiableMap(evidence));
    }

    private Map<String, Object> objectiveAlignment(Object value) {
        Map<String, Object> source = copy(value);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("addressedAspects", strings(source.get("addressedAspects")));
        result.put("unsupportedAspects", strings(source.get("unsupportedAspects")));
        String contribution = string(source.get("contribution"));
        if (contribution != null) result.put("contribution", contribution);
        return Collections.unmodifiableMap(result);
    }

    /**
     * Turns only already validated, directly returned facts into publishable observation Claims.
     * This does not derive, aggregate or infer anything and therefore needs no business hardcoding.
     */
    private List<Map<String, Object>> observedFactClaims(DataAnalysisPosition position,
                                                          List<Map<String, Object>> facts) {
        if (facts == null || facts.isEmpty()) return List.of();
        List<Map<String, Object>> claims = new ArrayList<>();
        for (Map<String, Object> fact : facts) {
            String claim = string(fact.get("claim"));
            List<String> recordRefs = strings(fact.get("recordRefs"));
            List<String> supportingValues = strings(fact.get("exactValues"));
            if (claim == null || claim.isBlank() || recordRefs.isEmpty()
                || supportingValues.isEmpty()) continue;
            String claimId = "observed-fact:" + DataAnalysisLayerGovernanceContract.fingerprint(
                List.of(position.datasetReference(), claim,
                    recordRefs.stream().sorted().toList(),
                    supportingValues.stream().sorted().toList()));
            claims.add(Map.of(
                "claimId", claimId,
                "claim", claim,
                "claimClass", "OBSERVED_RETURNED_FACT",
                "operation", "OBSERVE",
                "recordRefs", recordRefs,
                "supportingValues", supportingValues,
                "confidence", "HIGH",
                "significance", "Validated returned observation relevant to the analysis objective.",
                "caveats", List.of()));
        }
        return List.copyOf(claims);
    }

    private Map<String, Object> demandAnalysis(Object value) {
        Map<String, Object> source = copy(value);
        Map<String, Object> result = new LinkedHashMap<>();
        String decisionGoal = string(source.get("decisionGoal"));
        if (decisionGoal != null) result.put("decisionGoal", decisionGoal);
        result.put("answeredQuestions", strings(source.get("answeredQuestions")));
        result.put("openQuestions", strings(source.get("openQuestions")));
        return Collections.unmodifiableMap(result);
    }

    private boolean workerDemandAnalysisComplete(Map<String, Object> demandAnalysis) {
        if (demandAnalysis == null || demandAnalysis.isEmpty()) return false;
        String decisionGoal = string(demandAnalysis.get("decisionGoal"));
        return decisionGoal != null && !decisionGoal.isBlank()
            && (!strings(demandAnalysis.get("answeredQuestions")).isEmpty()
                || !strings(demandAnalysis.get("openQuestions")).isEmpty());
    }

    private List<Map<String, Object>> metricAssociations(Object value,
                                                          DataAnalysisPosition position) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> source : maps(value)) {
            String title = string(source.get("title"));
            if (title == null || title.isBlank()) continue;
            List<String> basisRecordRefs = strings(source.get("basisRecordRefs")).stream()
                .filter(reference -> validRecordReference(position, reference)).distinct().toList();
            String requestedStatus = string(source.get("status"));
            String status = "SUPPORTED".equalsIgnoreCase(requestedStatus)
                && !basisRecordRefs.isEmpty() ? "SUPPORTED" : "PENDING_VALIDATION";
            Map<String, Object> association = new LinkedHashMap<>();
            association.put("title", title);
            association.put("status", status);
            association.put("basisRecordRefs", basisRecordRefs);
            association.put("candidateMetrics", strings(source.get("candidateMetrics")));
            String method = string(source.get("analysisMethod"));
            if (method != null) association.put("analysisMethod", method);
            association.put("validationNeeded", strings(source.get("validationNeeded")));
            result.add(Collections.unmodifiableMap(association));
        }
        return List.copyOf(result);
    }

    private List<Map<String, Object>> analysisItems(Object value,
                                                    DataAnalysisPosition position,
                                                    List<Map<String, Object>> records) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> allowedStatuses = Set.of(
            "SUPPORTED", "PARTIAL", "NOT_APPLICABLE", "REVIEW_REQUIRED");
        for (Map<String, Object> source : maps(value)) {
            String itemId = string(source.get("itemId"));
            String analysisType = string(source.get("analysisType"));
            String status = string(source.get("status"));
            if (itemId == null || itemId.isBlank() || analysisType == null
                || analysisType.isBlank() || status == null) continue;
            status = status.toUpperCase(java.util.Locale.ROOT);
            if (!allowedStatuses.contains(status)) continue;
            List<String> references = strings(source.get("basisRecordRefs")).stream()
                .filter(reference -> validRecordReference(position, reference)).distinct().toList();
            List<String> values = strings(source.get("supportingValues")).stream()
                .filter(exact -> exactValueSupported(position, records, references, exact))
                .distinct().toList();
            if (!"NOT_APPLICABLE".equals(status)
                && (references.isEmpty() || values.isEmpty())) {
                status = "REVIEW_REQUIRED";
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemId", itemId);
            item.put("analysisType", analysisType);
            item.put("status", status);
            putIfPresent(item, "finding", source.get("finding"));
            putIfPresent(item, "businessMeaning", source.get("businessMeaning"));
            item.put("basisRecordRefs", references);
            item.put("supportingValues", values);
            putIfPresent(item, "method", source.get("method"));
            putIfPresent(item, "confidence", source.get("confidence"));
            putIfPresent(item, "timeScope", source.get("timeScope"));
            item.put("limitations", strings(source.get("limitations")));
            result.add(Collections.unmodifiableMap(item));
        }
        return List.copyOf(result);
    }

    private Map<String, Object> analysisDepth(Object value) {
        Map<String, Object> source = copy(value);
        Map<String, Object> result = new LinkedHashMap<>();
        String objectiveMode = string(source.get("objectiveMode"));
        if (objectiveMode != null) {
            objectiveMode = objectiveMode.toUpperCase(java.util.Locale.ROOT);
        }
        if (objectiveMode == null || !Set.of(
            "DESCRIBE", "COMPARE", "DIAGNOSE", "FORECAST", "DECIDE").contains(objectiveMode)) {
            objectiveMode = "DESCRIBE";
        }
        result.put("objectiveMode", objectiveMode);
        for (String key : List.of("addressedDimensions", "unsupportedDimensions",
            "comparisonBasis", "materialDeviations", "impacts", "hypotheses",
            "verificationNeeds", "prioritizedActions")) {
            result.put(key, strings(source.get(key)));
        }
        return Collections.unmodifiableMap(result);
    }

    private List<Map<String, Object>> followupRequests(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> source : maps(value)) {
            String questionId = string(source.get("questionId"));
            String retrievalGoal = string(source.get("retrievalGoal"));
            String priority = string(source.get("priority"));
            String reason = string(source.get("reason"));
            if (retrievalGoal == null || retrievalGoal.isBlank()
                || containsExecutableInstruction(retrievalGoal)
                || containsExecutableInstruction(reason)) continue;
            Map<String, Object> request = new LinkedHashMap<>();
            if (questionId != null) request.put("questionId", questionId);
            request.put("retrievalGoal", retrievalGoal);
            request.put("requiredCapabilities", strings(source.get("requiredCapabilities")));
            String timeHorizon = string(source.get("timeHorizon"));
            String grain = string(source.get("grain"));
            if (timeHorizon != null) request.put("timeHorizon", timeHorizon);
            if (grain != null) request.put("grain", grain);
            request.put("priority", "SUPPORTING".equalsIgnoreCase(priority) ? "SUPPORTING" : "CORE");
            if (reason != null) request.put("reason", reason);
            result.add(Collections.unmodifiableMap(request));
        }
        return List.copyOf(result);
    }

    private boolean containsExecutableInstruction(String value) {
        if (value == null) return false;
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("select ") || normalized.contains("insert ")
            || normalized.contains("update ") || normalized.contains("delete ")
            || normalized.contains("mcp_") || normalized.contains("toolname")
            || normalized.contains("arguments");
    }

    private InsightValidation validatedInsights(DataAnalysisPosition position,
                                                List<Map<String, Object>> records,
                                                List<Map<String, Object>> candidates,
                                                Map<String, Object> semanticContract) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, Object>> decisions = new ArrayList<>();
        List<Map<String, Object>> gaps = new ArrayList<>();
        List<Map<String, Object>> claimLifecycle = new ArrayList<>();
        List<SemanticEvidenceGapContract.Gap> gapContracts = new ArrayList<>();
        Set<String> allowedClasses = Set.of("OBSERVED_RETURNED_FACT",
            "AUTHORIZED_DERIVED_MEASURE", "CALIBRATED_INFERENCE");
        CapabilityEvidenceClaimContract.Capability capability = capabilityEvidenceClaimCompiler.compile(
            position.datasetReference(), semanticContract);
        int candidateIndex = 0;
        for (Map<String, Object> candidate : candidates) {
            candidateIndex++;
            String claimClass = string(candidate.get("claimClass"));
            String claim = string(candidate.get("claim"));
            String significance = string(candidate.get("significance"));
            String method = string(candidate.get("method"));
            String confidence = string(candidate.get("confidence"));
            if (confidence != null) confidence = confidence.toUpperCase(java.util.Locale.ROOT);
            List<String> caveats = strings(candidate.get("caveats"));
            List<String> semanticBasis = strings(candidate.get("semanticBasis"));
            List<String> alternatives = strings(candidate.get("alternativeExplanations"));
            List<String> references = strings(candidate.get("recordRefs")).stream()
                .filter(reference -> validRecordReference(position, reference)).distinct().toList();
            List<String> values = strings(candidate.get("supportingValues")).stream()
                .filter(exact -> exactValueSupported(position, records, references, exact))
                .distinct().toList();
            SemanticOperation operation = SemanticOperation.from(string(candidate.get("operation")));
            if (operation == null && "OBSERVED_RETURNED_FACT".equals(claimClass)) {
                operation = SemanticOperation.OBSERVE;
            }
            CapabilityEvidenceClaimContract.Evidence boundEvidence =
                new CapabilityEvidenceClaimContract.Evidence(
                    position.datasetReference() + "#chunk-" + position.chunkIndex(),
                    capability.capabilityId(), Set.copyOf(references), Set.copyOf(values),
                    sole(capability.declaredGrains()), sole(capability.declaredTimeScopes()),
                    sole(capability.declaredPopulationScopes()), false);
            CapabilityEvidenceClaimContract.Claim proposedClaim =
                new CapabilityEvidenceClaimContract.Claim(
                    claimClass, operation, Set.copyOf(references), Set.copyOf(values),
                    Set.copyOf(semanticBasis), Set.copyOf(strings(candidate.get("inputFields"))),
                    string(candidate.get("outputUnit")), string(candidate.get("grain")),
                    string(candidate.get("timeScope")), string(candidate.get("populationScope")),
                    method, caveats, alternatives);
            CapabilityEvidenceClaimContract.Admission admission = semanticClaimAdmissionPolicy.evaluate(
                capability, boundEvidence, proposedClaim);
            List<String> rejectionCodes = new ArrayList<>(admission.rejectionCodes());
            boolean shapeValid = claimClass != null && allowedClasses.contains(claimClass) && claim != null && !claim.isBlank()
                && significance != null && !significance.isBlank()
                && confidence != null && Set.of("HIGH", "MEDIUM", "LOW").contains(confidence)
                && !references.isEmpty() && !values.isEmpty() && operation != null;
            if (!shapeValid
                || significance == null || significance.isBlank()
                || confidence == null || !Set.of("HIGH", "MEDIUM", "LOW").contains(confidence)
                || references.isEmpty() || values.isEmpty()) rejectionCodes.add("CLAIM_SHAPE_INVALID");
            rejectionCodes = rejectionCodes.stream().distinct().toList();
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("candidateIndex", candidateIndex);
            decision.put("claimClass", claimClass == null ? "" : claimClass);
            decision.put("admitted", rejectionCodes.isEmpty());
            decision.put("reviewRequired", shapeValid && !rejectionCodes.isEmpty());
            decision.put("governanceStatus", rejectionCodes.isEmpty()
                ? "SUPPORTED" : (shapeValid ? "REVIEW_REQUIRED" : "INVALID"));
            decision.put("rejectionCodes", rejectionCodes);
            String semanticGapId = "";
            if (!rejectionCodes.isEmpty()) {
                CapabilityEvidenceClaimContract.Admission finalAdmission =
                    new CapabilityEvidenceClaimContract.Admission(false, rejectionCodes);
                SemanticEvidenceGapContract.Gap gap = semanticClaimGapPolicy.derive(
                    capability, boundEvidence, proposedClaim, finalAdmission);
                if (gap != null) {
                    Map<String, Object> gapMap = gap.toMap();
                    gaps.add(gapMap);
                    gapContracts.add(gap);
                    decision.put("semanticGapId", gap.gapId());
                    decision.put("semanticGapRoute", gap.route().name());
                    semanticGapId = gap.gapId();
                }
            }
            String claimFingerprint = SemanticClaimLifecycleContract.fingerprint(List.of(
                position.datasetReference(), claimClass == null ? "" : claimClass, claim == null ? "" : claim,
                operation == null ? "" : operation.name(), method == null ? "" : method,
                strings(candidate.get("inputFields")).stream().sorted().toList(),
                string(candidate.get("grain")) == null ? "" : string(candidate.get("grain")),
                string(candidate.get("timeScope")) == null ? "" : string(candidate.get("timeScope")),
                string(candidate.get("populationScope")) == null ? "" : string(candidate.get("populationScope"))));
            String evidenceVersion = SemanticClaimLifecycleContract.fingerprint(List.of(
                boundEvidence.evidenceId(), references.stream().sorted().toList(),
                values.stream().sorted().toList()));
            SemanticClaimLifecycleContract.Revision lifecycle = SemanticClaimLifecycleContract.evolve(
                claimFingerprint, evidenceVersion, rejectionCodes.isEmpty(), rejectionCodes,
                semanticGapId, null);
            decision.put("claimId", lifecycle.claimId());
            decision.put("claimFingerprint", lifecycle.claimFingerprint());
            decision.put("claimRevision", lifecycle.revision());
            decision.put("claimState", lifecycle.state().name());
            claimLifecycle.add(lifecycle.toMap());
            decisions.add(Collections.unmodifiableMap(decision));
            if (!shapeValid) continue;
            Map<String, Object> insight = new LinkedHashMap<>();
            insight.put("claimClass", claimClass);
            insight.put("claim", claim);
            insight.put("recordRefs", references);
            insight.put("supportingValues", values);
            insight.put("significance", significance);
            insight.put("operation", operation.name());
            if (method != null) insight.put("method", method);
            insight.put("inputFields", strings(candidate.get("inputFields")));
            putIfPresent(insight, "outputUnit", candidate.get("outputUnit"));
            putIfPresent(insight, "grain", candidate.get("grain"));
            putIfPresent(insight, "timeScope", candidate.get("timeScope"));
            putIfPresent(insight, "populationScope", candidate.get("populationScope"));
            insight.put("confidence", confidence);
            insight.put("caveats", caveats);
            insight.put("semanticBasis", semanticBasis);
            insight.put("alternativeExplanations", alternatives);
            insight.put("claimId", lifecycle.claimId());
            insight.put("claimFingerprint", lifecycle.claimFingerprint());
            insight.put("claimRevision", lifecycle.revision());
            insight.put("governanceStatus", rejectionCodes.isEmpty()
                ? "SUPPORTED" : "REVIEW_REQUIRED");
            insight.put("reviewReasons", rejectionCodes);
            result.add(Collections.unmodifiableMap(insight));
        }
        return new InsightValidation(List.copyOf(result), List.copyOf(decisions), List.copyOf(gaps),
            List.copyOf(gapContracts), List.copyOf(claimLifecycle));
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        String text = string(value);
        if (text != null && !text.isBlank()) target.put(key, text);
    }

    private String sole(Set<String> values) {
        return values == null || values.size() != 1 ? "" : values.iterator().next();
    }

    private record InsightValidation(List<Map<String, Object>> admitted,
                                     List<Map<String, Object>> decisions,
                                     List<Map<String, Object>> gaps,
                                     List<SemanticEvidenceGapContract.Gap> gapContracts,
                                     List<Map<String, Object>> claimLifecycle) {
    }

    private LinkedHashSet<Integer> citedRecordIndexes(DataAnalysisPosition position,
                                                       List<Map<String, Object>> facts) {
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
        if (facts == null) return indexes;
        for (Map<String, Object> fact : facts) {
            for (String reference : strings(fact.get("recordRefs"))) {
                if (reference.equals(position.toMap().get("recordPath"))) {
                    for (int index = position.recordFrom(); index <= position.recordTo(); index++) {
                        indexes.add(index);
                    }
                    continue;
                }
                Integer index = recordIndex(position, reference);
                if (index != null) indexes.add(index);
            }
        }
        return indexes;
    }

    private Map<String, Object> rawEvidence(GovernanceIsolationScope isolationScope,
                                            DataAnalysisPosition position,
                                            Map<String, Object> governedContext,
                                            List<Map<String, Object>> records,
                                            boolean structured,
                                            boolean replayRecommended) {
        String evidenceId = (isolationScope == null
            ? GovernanceIsolationScope.runtime(null, null, null, null, null)
            : isolationScope).partitionKey() + ":" + position.datasetReference()
            + "#chunk-" + position.chunkIndex();
        Map<String, Object> extensions = copy(governedContext == null
            ? null : governedContext.get("extensions"));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", EVIDENCE_SCHEMA_VERSION);
        evidence.put("evidenceId", evidenceId);
        evidence.put("datasetReference", position.datasetReference());
        evidence.put("position", position.toMap());
        evidence.put("contentSha256", ModelProtocolJson.sha256Hex(records));
        evidence.put("recordCount", records == null ? 0 : records.size());
        evidence.put("sourceComplete", sourceComplete(records));
        evidence.put("structured", structured);
        evidence.put("rawReplayAvailable", true);
        evidence.put("rawReplayRecommended", replayRecommended);
        evidence.put("rawReplayLocator", Map.of(
            "resolver", "RUNTIME_EXECUTION_RESULT",
            "datasetReference", position.datasetReference(),
            "chunkIndex", position.chunkIndex(),
            "recordFrom", position.recordFrom(),
            "recordTo", position.recordTo()
        ));
        evidence.put("commandContext", copy(extensions.get("commandContext")));
        evidence.put("relationships", copy(governedContext == null
            ? null : governedContext.get("relationships")));
        return Collections.unmodifiableMap(evidence);
    }

    private boolean sourceComplete(List<Map<String, Object>> records) {
        return records == null || records.stream()
            .filter(Objects::nonNull)
            .noneMatch(record -> Boolean.FALSE.equals(record.get("sourceComplete")));
    }

    private boolean validRecordReference(DataAnalysisPosition position, String reference) {
        if (reference == null || reference.isBlank()) return false;
        String prefix = position.datasetReference() + ".records[";
        if (!reference.startsWith(prefix) || !reference.endsWith("]")) return false;
        String indexText = reference.substring(prefix.length(), reference.length() - 1);
        if (indexText.contains("..")) {
            return reference.equals(position.toMap().get("recordPath"));
        }
        try {
            int index = Integer.parseInt(indexText.replaceAll("[^0-9]", ""));
            return index >= position.recordFrom() && index <= position.recordTo();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean exactValueSupported(DataAnalysisPosition position,
                                        List<Map<String, Object>> records,
                                        List<String> references,
                                        String exactValue) {
        if (records == null || references == null || references.isEmpty()) return false;
        for (String reference : references) {
            if (reference.equals(position.toMap().get("recordPath"))) {
                if (ModelProtocolJson.compact(records).contains(exactValue)) return true;
                continue;
            }
            Integer recordIndex = recordIndex(position, reference);
            if (recordIndex == null) continue;
            int localIndex = recordIndex - position.recordFrom();
            if (localIndex >= 0 && localIndex < records.size()
                && ModelProtocolJson.compact(records.get(localIndex)).contains(exactValue)) {
                return true;
            }
        }
        return false;
    }

    private Integer recordIndex(DataAnalysisPosition position, String reference) {
        String prefix = position.datasetReference() + ".records[";
        if (reference == null || !reference.startsWith(prefix) || !reference.endsWith("]")) return null;
        String value = reference.substring(prefix.length(), reference.length() - 1);
        if (value.contains("..")) return null;
        try {
            return Integer.parseInt(value.replaceAll("[^0-9]", ""));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Map<String, Object> parseObject(String value) {
        if (value == null || value.isBlank()) return Map.of();
        String text = value.trim();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int closing = text.lastIndexOf("```");
            if (firstLine >= 0 && closing > firstLine) {
                text = text.substring(firstLine + 1, closing).trim();
            }
        }
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
        if (!(value instanceof Iterable<?> items)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> mapped = copy(item);
            if (!mapped.isEmpty()) result.add(Collections.unmodifiableMap(mapped));
        }
        return List.copyOf(result);
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> items)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : items) {
            String text = string(item);
            if (text != null && !text.isBlank()) result.add(text);
        }
        return List.copyOf(result);
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private record AnalysisProduct(String raw, EvidenceCapsule capsule) implements java.io.Serializable {}

    private record EvidenceCapsule(String content, Map<String, Object> evidence) implements java.io.Serializable { }

    public AnalysisSummaryResult finalResult(GovernanceIsolationScope isolationScope,
                                             String stage,
                                             String content,
                                             String outcome,
                                             Map<String, Object> coverage,
                                             List<AnalysisSummaryResult> inputs) {
        return AnalysisSummaryResult.finalSummary(isolationScope, stage, content, outcome, coverage, inputs);
    }

    public AnalysisSummaryResult finalResult(GovernanceIsolationScope isolationScope,
                                             String stage,
                                             String content,
                                             String outcome,
                                             Map<String, Object> coverage,
                                             List<AnalysisSummaryResult> inputs,
                                             List<String> upstreamResultIds) {
        return AnalysisSummaryResult.finalSummary(
            isolationScope, stage, content, outcome, coverage, inputs, upstreamResultIds);
    }

    private List<Map<String, Object>> returnedFields(List<Map<String, Object>> records) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (records != null) {
            records.forEach(record -> {
                if (record != null) names.addAll(record.keySet());
            });
        }
        return names.stream().map(name -> Map.<String, Object>of("name", name)).toList();
    }

    private String safeReference(String reference) {
        return reference == null || reference.isBlank() ? "result" : reference;
    }

    private Map<String, Object> copy(Object value) {
        if (!(value instanceof Map<?, ?> map)) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (item != null) result.put(String.valueOf(key), item);
        });
        return result;
    }

    private Map<String, Object> immutable(Map<String, Object> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

}
