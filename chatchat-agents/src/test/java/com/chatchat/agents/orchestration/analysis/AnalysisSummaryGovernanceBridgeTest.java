package com.chatchat.agents.orchestration.analysis;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.runtime.context.AgentRoleAnalysisContext;
import com.chatchat.agents.orchestration.analysis.summary.AnalysisSummaryGovernanceBridge;



import com.chatchat.common.runtime.summary.analysis.DataAnalysisPosition;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisSummaryGovernanceBridgeTest {

    private final AnalysisSummaryGovernanceBridge bridge = new AnalysisSummaryGovernanceBridge();
    private final GovernanceIsolationScope isolationScope = GovernanceIsolationScope.runtime(
        "tenant-a", "user-a", "run-a", "request-a", "conversation-a");

    @Test
    void supplementsStructureWithoutInventingMissingBusinessSemantics() {
        Map<String, Object> governed = bridge.govern(
            "portfolio-result",
            Map.of("source", Map.of("displayName", "Portfolio positions")),
            List.of(Map.of("SECURITY_CODE", "600000", "MARKET_VALUE", 12000)));

        assertThat(governed)
            .containsEntry("schemaVersion", "data_analysis_context.v1");
        assertThat(governed.get("governance").toString())
            .contains("summary_governance.v1", "analysis_summary_bridge.v1")
            .contains("PRESERVE_RETURNED_FIELD_KEYS");
        assertThat(governed.get("source").toString())
            .contains("Portfolio positions", "runtimeReference=portfolio-result");
        assertThat(governed.get("schema").toString())
            .contains("SECURITY_CODE", "MARKET_VALUE")
            .doesNotContain("证券代码", "资产市值");
        assertThat(governed.get("contextCompleteness").toString())
            .contains("missingSemanticSections", "capability", "business", "relationships")
            .contains("semanticInferenceAllowed=false");
    }

    @Test
    void modelChunkSummaryCarriesAndRecordsItsExactPosition() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(argThat((String prompt) -> prompt.contains("analysis_summary_bridge.v1")
            && prompt.contains("datasetReference\":\"positions")
            && prompt.contains("recordFrom\":51")
            && prompt.contains("recordTo\":75")
            && prompt.contains("Identify position concentration risk")
            && prompt.contains("objective-relevant findings"))))
            .thenReturn("第 2 分块总结");
        Map<String, Object> context = bridge.govern("positions", Map.of(
                AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY, AgentRoleAnalysisContext.create(
                    "Quality analyst", "Analyze service quality",
                    List.of("Daily review"), List.of("quality"))),
            List.of(Map.of("VALUE", 1)));
        DataAnalysisPosition position =
            bridge.position("positions", 2, 3, 51, 75, 120);

        AnalysisSummaryResult summary = bridge.summarize(
            model::chat, isolationScope, position, context, List.of(Map.of("VALUE", 1)),
            "Identify position concentration risk");
        Map<String, Object> ledger = bridge.ledger(List.of(summary), 120, 25, false);

        assertThat(summary.schemaVersion()).isEqualTo("analysis_summary_result.v1");
        assertThat(summary.content()).isEqualTo("第 2 分块总结");
        assertThat(summary.outcome()).isEqualTo("MODEL_SUMMARY");
        assertThat(summary.toMap().toString())
            .contains("scope=DATASET_CHUNK", "content=第 2 分块总结")
            .contains("chunkIndex=2", "recordFrom=51", "recordTo=75", "totalRecords=120")
            .contains("positions.records[51..75]");
        assertThat(ledger.toString())
            .contains("analysis_summary_bridge.v1", "summary_governance.v1", "第 2 分块总结");
        verify(model).chat(argThat((String prompt) -> prompt.contains("Missing semantic sections remain unknown")
            && prompt.contains("Lead with findings, not row counts or metadata")
            && prompt.contains("already returned by the producer at the declared record grain is an observed fact")
            && prompt.contains("quoting that value is OBSERVE, not AGGREGATE or DERIVE")
            && prompt.contains("self-contained, decision-useful conclusion")
            && prompt.contains("When agent_role_analysis_context is present")
            && prompt.contains("Analyze service quality")
            && prompt.contains("Daily review")));
    }

    @Test
    void workerReceivesOriginalQuestionAndBusinessTemplateMatchContext() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(argThat((String prompt) ->
            prompt.contains("Original user question (authoritative analysis intent): 观察ETF市场资金流向")
                && prompt.contains("templateMatchAnalysis")
                && prompt.contains("ETF_SCALE")
                && prompt.contains("按基金代码关联相邻交易日")
                && prompt.contains("analysis_objective_contract.v1")
                && prompt.contains("professional_data_analysis.v1")
                && prompt.contains("professional_analysis_depth.v1")
                && prompt.contains("A list of values, configuration facts, record counts")
                && prompt.contains("CALIBRATE_CONCLUSION_STRENGTH_TO_EVIDENCE_SCOPE")
                && prompt.contains("analysis_semantic_contract.v1")
                && prompt.contains("analysis_record_scope_profile.v1")
                && prompt.contains("DO_NOT_INFER_UNDECLARED_AGGREGATION_OR_RELATIONSHIPS")
                && prompt.contains("STRUCTURAL_STATISTICS_ONLY_NO_SEMANTIC_INFERENCE")
                && prompt.contains("never infer or change any of those semantics")
                && prompt.contains("semantic decision context")
                && prompt.contains("demandAnalysis")
                && prompt.contains("metricAssociations")
                && prompt.contains("PENDING_VALIDATION")
                && prompt.contains("Worker responsible for completing the analysis")
                && prompt.contains("Do not defer dataset-level reasoning"))))
            .thenReturn("""
                {"summary":"规模上升","objectiveAlignment":{"addressedAspects":["规模"],
                "unsupportedAspects":["精确净资金流"],"contribution":"规模变化仅作为代理指标"},
                "demandAnalysis":{"decisionGoal":"判断资金流向","answeredQuestions":["规模变化"],
                "openQuestions":["净申购来源"]},
                "metricAssociations":[{"title":"规模变化与净申购联动",
                "status":"SUPPORTED","basisRecordRefs":[],
                "candidateMetrics":["规模变化","净申购额"],"analysisMethod":"同周期对照",
                "validationNeeded":["净申购明细"]}],
                "facts":[{"claim":"规模上升391519.6",
                "recordRefs":["etf.records[1]"],"exactValues":["391519.6"]}],
                "entities":[],"crossChunkKeys":[],"conflicts":[],"limitations":[],
                "rawReplayRecommended":false}
                """);
        Map<String, Object> context = bridge.govern("etf", Map.of(
            "templateMatchAnalysis", Map.of(
                "schemaVersion", "template_match_analysis.v2",
                "userQuestion", "观察ETF市场资金流向",
                "selectedTemplateIds", List.of("ETF_SCALE"),
                "candidateEvaluations", List.of(Map.of(
                    "templateId", "ETF_SCALE",
                    "matchedQuestionAspects", List.of("规模", "份额"),
                    "relationshipHints", List.of("按基金代码关联相邻交易日"))))) ,
            List.of(Map.of("CHANGE", 391519.6)));

        AnalysisSummaryResult result = bridge.summarize(
            model::chat, isolationScope, bridge.position("etf", 1, 1, 1, 1, 1),
            context, List.of(Map.of("CHANGE", 391519.6)), "观察ETF市场资金流向");

        assertThat(result.outcome()).isEqualTo("MODEL_SUMMARY");
        assertThat(result.analysisContext().toString())
            .contains("template_match_analysis.v2", "ETF_SCALE");
        assertThat(result.evidence().get("objectiveAlignment").toString())
            .contains("addressedAspects=[规模]", "unsupportedAspects=[精确净资金流]")
            .contains("规模变化仅作为代理指标");
        assertThat(result.evidence().get("analysisObjectiveContract").toString())
            .contains("professional_data_analysis.v1", "CALIBRATED_INFERENCE");
        assertThat(result.evidence().get("demandAnalysis").toString())
            .contains("判断资金流向", "answeredQuestions=[规模变化]", "openQuestions=[净申购来源]");
        assertThat(result.evidence().get("metricAssociations").toString())
            .contains("规模变化与净申购联动", "status=PENDING_VALIDATION", "净申购明细");
        assertThat(result.evidence())
            .containsEntry("analysisDecisionOperatingModelVersion",
                "data_analysis_decision_operating_model.v1")
            .containsEntry("analysisParticipantRole", "WORKER")
            .containsEntry("workerAnalysisReportSchemaVersion", "worker_analysis_report.v1")
            .containsEntry("workerDemandAnalysisComplete", true)
            .containsEntry("workerMetricAssociationAssessmentDeclared", true);
    }

    @Test
    void preservesValidatedProfessionalInsightsAndQualityAssessment() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
            {
              "summary":"Value 42 is the material returned observation.",
              "analysisQuality":{"observedScope":"one returned record","grain":"record",
                "qualitySignals":[],"reconciliationNeeds":[]},
              "insights":[{"claimClass":"OBSERVED_RETURNED_FACT","claim":"Value is 42",
                "significance":"It directly answers the requested level","recordRefs":["metrics.records[1]"],
                "supportingValues":["42"],"confidence":"HIGH","caveats":[]},
                {"claimClass":"AUTHORIZED_DERIVED_MEASURE","claim":"Unsupported derivation",
                "significance":"Would be useful","recordRefs":["metrics.records[1]"],
                "supportingValues":["42"],"confidence":"HIGH","caveats":[]},
                {"claimClass":"CALIBRATED_INFERENCE","claim":"Overconfident inference",
                "significance":"Would be useful","recordRefs":["metrics.records[1]"],
                "supportingValues":["42"],"confidence":"HIGH","caveats":[]}],
              "facts":[{"claim":"Value is 42","recordRefs":["metrics.records[1]"],
                "exactValues":["42"]}],
              "objectiveAlignment":{"addressedAspects":["level"],"unsupportedAspects":[],
                "contribution":"Answers the requested level"},
              "conflicts":[],"limitations":[],"rawReplayRecommended":false
            }
            """);
        List<Map<String, Object>> records = List.of(Map.of("VALUE", 42));

        AnalysisSummaryResult result = bridge.summarize(model::chat, isolationScope,
            bridge.position("metrics", 1, 1, 1, 1, 1),
            bridge.govern("metrics", Map.of(), records), records, "Analyze the returned level");

        assertThat(result.evidence().get("analysisQuality").toString())
            .contains("one returned record", "grain=record");
        assertThat(result.evidence().get("insights"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);
        assertThat(result.evidence().get("insights").toString())
            .contains("OBSERVED_RETURNED_FACT", "Value is 42", "significance", "confidence=HIGH")
            .doesNotContain("Unsupported derivation", "Overconfident inference", "template", "tool");
    }

    @Test
    void removesUnsupportedDerivedClaimFromEvidenceAndSummaryContent() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
            {
              "summary":"The chunk represents 15.11 percent of the total.",
              "analysisQuality":{"observedScope":"partial rows","grain":"record"},
              "insights":[{"claimClass":"AUTHORIZED_DERIVED_MEASURE",
                "claim":"The chunk represents 15.11 percent of the total",
                "significance":"Purports to measure concentration",
                "recordRefs":["scale.records[1]"],"supportingValues":["10","100"],
                "method":"10 / 100 * 100","confidence":"MEDIUM",
                "semanticBasis":["current_scale"],"alternativeExplanations":[],"caveats":[]}],
              "facts":[{"claim":"Returned values are 10 and 100",
                "recordRefs":["scale.records[1]"],"exactValues":["10","100"]}],
              "conflicts":[],"limitations":[],"rawReplayRecommended":false
            }
            """);
        List<Map<String, Object>> records = List.of(Map.of("CURRENT", 10, "TOTAL", 100));

        AnalysisSummaryResult result = bridge.summarize(model::chat, isolationScope,
            bridge.position("scale", 1, 1, 1, 1, 1),
            bridge.govern("scale", Map.of(), records), records, "Observe direction");

        assertThat(result.content())
            .isEqualTo("当前数据未产生通过语义授权校验的分析洞察。")
            .doesNotContain("15.11");
        assertThat(result.evidence())
            .containsEntry("rejectedInsightCount", 1)
            .containsEntry("claimContractVersion", "capability_evidence_claim.v1")
            .containsEntry("rawReplayRecommended", true);
        assertThat(result.evidence().get("claimAdmissionDecisions").toString())
            .contains("CAPABILITY_UNDECLARED", "OPERATION_NOT_AUTHORIZED", "admitted=false");
        assertThat(result.evidence().get("semanticGaps").toString())
            .contains("semantic_evidence_gap.v1", "route=REPLAN", "requiredCapabilityId=scale");
        assertThat(result.evidence().get("semanticGapRequests").toString())
            .contains("Resolve the rejected claim", "requiredCapabilities=[scale]");
        assertThat(result.evidence().get("claimLifecycle").toString())
            .contains("semantic_claim_lifecycle.v1", "revision=1", "state=GAP_CREATED",
                "PROPOSED", "VALIDATING", "REJECTED", "GAP_CREATED", "evidenceVersion");
        assertThat(result.evidence().get("insights")).isEqualTo(List.of());
    }

    @Test
    void admitsDerivedClaimOnlyWithExactProducerDeclaredSemanticBasis() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
            {
              "summary":"The authorized ratio is 50 percent.",
              "analysisQuality":{"observedScope":"complete record","grain":"record"},
              "insights":[{"claimClass":"AUTHORIZED_DERIVED_MEASURE",
                "claim":"The authorized ratio is 50 percent",
                "significance":"Answers the requested ratio",
                "recordRefs":["metrics.records[1]"],"supportingValues":["10","20"],
                "operation":"DERIVE","method":"10 / 20 * 100",
                "inputFields":["NUMERATOR","DENOMINATOR"],"outputUnit":"percent",
                "grain":"account","timeScope":"returned date","populationScope":"returned accounts",
                "confidence":"HIGH",
                "semanticBasis":["ratio = numerator / denominator"],
                "alternativeExplanations":[],"caveats":[]}],
              "facts":[{"claim":"Inputs are 10 and 20","recordRefs":["metrics.records[1]"],
                "exactValues":["10","20"]}],"conflicts":[],"limitations":[],
              "rawReplayRecommended":false
            }
            """);
        List<Map<String, Object>> records = List.of(Map.of("NUMERATOR", 10, "DENOMINATOR", 20));
        Map<String, Object> context = bridge.govern("metrics", Map.of(
            "capability", Map.of("capabilityId", "ratio-metric", "allowedOperations", List.of("DERIVE")),
            "schema", Map.of("fields", List.of(
                Map.of("name", "NUMERATOR"), Map.of("name", "DENOMINATOR"))),
            "semantics", Map.of("allowedDerivedMeasures",
                List.of("ratio = numerator / denominator"), "units", List.of("percent"),
                "grain", "account", "timeScope", "returned date",
                "populationScope", "returned accounts")), records);

        AnalysisSummaryResult result = bridge.summarize(model::chat, isolationScope,
            bridge.position("metrics", 1, 1, 1, 1, 1), context, records, "Calculate ratio");

        assertThat(result.evidence()).containsEntry("rejectedInsightCount", 0);
        assertThat(result.evidence().get("insights").toString())
            .contains("AUTHORIZED_DERIVED_MEASURE", "ratio = numerator / denominator", "operation=DERIVE");
        assertThat(result.evidence().get("claimAdmissionDecisions").toString())
            .contains("admitted=true");
        assertThat(result.content()).contains("authorized ratio is 50 percent");
    }

    @Test
    void doesNotTreatDeclaredFieldNameAsDerivedMeasureAuthorization() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
            {"summary":"Unsupported ratio", "insights":[{
              "claimClass":"AUTHORIZED_DERIVED_MEASURE","operation":"DERIVE",
              "claim":"A ratio was derived","significance":"Requested ratio",
              "recordRefs":["metrics.records[1]"],"supportingValues":["10","20"],
              "method":"10 / 20","inputFields":["NUMERATOR","DENOMINATOR"],
              "outputUnit":"percent","grain":"account","timeScope":"returned date",
              "populationScope":"returned accounts","confidence":"HIGH",
              "semanticBasis":["NUMERATOR"],"caveats":[],"alternativeExplanations":[]
            }],"facts":[],"conflicts":[],"limitations":[]}
            """);
        List<Map<String, Object>> records = List.of(Map.of("NUMERATOR", 10, "DENOMINATOR", 20));
        Map<String, Object> context = bridge.govern("metrics", Map.of(
            "capability", Map.of("capabilityId", "ratio-metric", "allowedOperations", List.of("DERIVE")),
            "schema", Map.of("fields", List.of(
                Map.of("name", "NUMERATOR"), Map.of("name", "DENOMINATOR"))),
            "semantics", Map.of("units", List.of("percent"), "grain", "account",
                "timeScope", "returned date", "populationScope", "returned accounts")), records);

        AnalysisSummaryResult result = bridge.summarize(model::chat, isolationScope,
            bridge.position("metrics", 1, 1, 1, 1, 1), context, records, "Calculate ratio");

        assertThat(result.evidence().get("insights")).isEqualTo(List.of());
        assertThat(result.evidence().get("claimAdmissionDecisions").toString())
            .contains("SEMANTIC_BASIS_MISMATCH", "admitted=false");
    }

    @Test
    void buildsValidatedStructuredFactsAndLosslessReplayLocator() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
            {
              "summary": "返回记录显示 MARKET_VALUE=12000。",
              "facts": [{
                "claim": "MARKET_VALUE 为 12000",
                "recordRefs": ["positions.records[1]"],
                "exactValues": ["12000"]
              }],
              "entities": [{"key":"SECURITY_CODE","value":"600000"}],
              "crossChunkKeys": ["600000"],
              "conflicts": [],
              "limitations": [],
              "rawReplayRecommended": false
            }
            """);
        Map<String, Object> context = bridge.govern(
            "positions",
            Map.of("extensions", Map.of("commandContext", Map.of(
                "templateId", "POSITION_QUERY",
                "description", "Return position values",
                "references", List.of(Map.of("from", "account", "to", "position"))))),
            List.of(Map.of("SECURITY_CODE", "600000", "MARKET_VALUE", 12000)));

        AnalysisSummaryResult result = bridge.summarize(
            model::chat,
            isolationScope,
            bridge.position("positions", 1, 1, 1, 1, 1),
            context,
            List.of(Map.of("SECURITY_CODE", "600000", "MARKET_VALUE", 12000)),
            "分析持仓");

        assertThat(result.content()).isEqualTo("返回记录显示 MARKET_VALUE=12000。");
        assertThat(result.evidence())
            .containsEntry("schemaVersion", "traceable_chunk_evidence.v1")
            .containsEntry("structured", true)
            .containsEntry("rawReplayAvailable", true)
            .containsEntry("rawReplayRecommended", false)
            .containsEntry("citedRecordCount", 1)
            .containsEntry("factRecordCoverageComplete", true)
            .containsEntry("rejectedFactCount", 0);
        assertThat(String.valueOf(result.evidence().get("contentSha256"))).hasSize(64);
        assertThat(result.evidence().toString())
            .contains("positions.records[1]", "MARKET_VALUE 为 12000", "exactValues=[12000]")
            .contains("SECURITY_CODE", "600000", "POSITION_QUERY", "Return position values")
            .contains("resolver=RUNTIME_EXECUTION_RESULT");
    }

    @Test
    void rejectsInventedFactValuesAndRequiresRawReplay() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
            {
              "summary": "模型声称返回值为 999。",
              "facts": [{
                "claim": "VALUE 为 999",
                "recordRefs": ["metrics.records[1]"],
                "exactValues": ["999"]
              }],
              "conflicts": [],
              "limitations": [],
              "rawReplayRecommended": false
            }
            """);

        AnalysisSummaryResult result = bridge.summarize(
            model::chat,
            isolationScope,
            bridge.position("metrics", 1, 1, 1, 1, 1),
            bridge.govern("metrics", Map.of(), List.of(Map.of("VALUE", 42))),
            List.of(Map.of("VALUE", 42)),
            "分析指标");

        assertThat(result.evidence())
            .containsEntry("structured", true)
            .containsEntry("rejectedFactCount", 1)
            .containsEntry("rawReplayRecommended", true);
        assertThat(result.evidence().get("facts")).isEqualTo(List.of());
    }

    @Test
    void rejectsValueThatExistsOnlyInAnotherRecordThanTheCitation() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
            {
              "summary": "错误地把第二条记录的值归给第一条。",
              "facts": [{
                "claim": "第一条 VALUE 为 99",
                "recordRefs": ["metrics.records[1]"],
                "exactValues": ["99"]
              }],
              "conflicts": [],
              "limitations": [],
              "rawReplayRecommended": false
            }
            """);
        List<Map<String, Object>> records = List.of(Map.of("VALUE", 42), Map.of("VALUE", 99));

        AnalysisSummaryResult result = bridge.summarize(
            model::chat,
            isolationScope,
            bridge.position("metrics", 1, 1, 1, 2, 2),
            bridge.govern("metrics", Map.of(), records),
            records,
            "分析指标");

        assertThat(result.evidence())
            .containsEntry("rejectedFactCount", 1)
            .containsEntry("rawReplayRecommended", true);
        assertThat(result.evidence().get("facts")).isEqualTo(List.of());
    }

    @Test
    void preservesExtensibleAssetSemanticsWithoutDomainSpecificBridgeCode() {
        Map<String, Object> governed = bridge.govern(
            "asset-snapshot",
            Map.of(
                "source", Map.of("displayName", "资产中心快照"),
                "semantics", Map.of(
                    "dimensions", List.of("account", "security"),
                    "measures", List.of("marketValue", "profitLoss"),
                    "timeSemantics", Map.of("asOfField", "businessDate"),
                    "units", Map.of("marketValue", "CNY")),
                "quality", Map.of("freshness", "T+1"),
                "analysisPolicy", Map.of("mode", "ANALYZE_RETURNED_RECORDS"),
                "extensions", Map.of("assetCenter", Map.of("valuationBasis", "close_price"))),
            List.of(Map.of("marketValue", 100, "profitLoss", -2)));

        assertThat(governed.get("semantics").toString())
            .contains("businessDate", "marketValue=CNY", "profitLoss");
        assertThat(governed.get("quality").toString()).contains("freshness=T+1");
        assertThat(governed.get("analysisPolicy").toString()).contains("ANALYZE_RETURNED_RECORDS");
        assertThat(governed.get("extensions").toString()).contains("valuationBasis=close_price");
        Map<?, ?> completeness = (Map<?, ?>) governed.get("contextCompleteness");
        assertThat(String.valueOf(completeness.get("missingSemanticSections")))
            .doesNotContain("semantics", "quality", "analysisPolicy", "extensions");
    }

    @Test
    void returnedDataAlwaysRequiresWorkerAnalysisRegardlessOfProducerPolicy() {
        assertThat(bridge.requiresModelSummary(Map.of(
            "source", Map.of("displayName", "资产目录"),
            "analysisPolicy", Map.of("mode", "REFERENCE_ONLY")), true)).isTrue();
        assertThat(bridge.requiresModelSummary(Map.of(
            "source", Map.of("displayName", "持仓结果"),
            "analysisPolicy", Map.of("mode", "ANALYZE_RETURNED_RECORDS")), false)).isTrue();
        assertThat(bridge.requiresModelSummary(Map.of(
            "source", Map.of("displayName", "受限结果"),
            "analysisPolicy", Map.of("enabled", false)), false)).isTrue();
    }

    @Test
    void finalModelContentIsStoredInTheCanonicalGovernedResultObject() {
        Map<String, Object> context = bridge.govern("assets", Map.of(),
            List.of(Map.of("TOTAL_ASSET", 847174.25)));
        AnalysisSummaryResult chunk = bridge.preserve(
            isolationScope, bridge.position("assets", 1, 1, 1, 1, 1), context,
            List.of(Map.of("TOTAL_ASSET", 847174.25)));

        AnalysisSummaryResult result = bridge.finalResult(
            isolationScope,
            "initial", "资产分析总结正文", "MODEL_FINAL_SUMMARY",
            Map.of("returnedRecordCount", 1, "processedRecordCount", 1, "coverageComplete", true),
            List.of(chunk));

        assertThat(result.schemaVersion()).isEqualTo("analysis_summary_result.v1");
        assertThat(result.scope()).isEqualTo("FINAL_SYNTHESIS");
        assertThat(result.content()).isEqualTo("资产分析总结正文");
        assertThat(result.inputSummaryResultIds()).containsExactly("tenant-a:run-a:assets#chunk-1");
        assertThat(result.toMap().toString())
            .contains("MODEL_FINAL_SUMMARY", "summary_governance.v1")
            .contains("GOVERNED_ANALYSIS_SUMMARY", "RETURNED_STRUCTURED_EVIDENCE")
            .contains("coverageComplete=true")
            .contains("final_evidence_lineage.v1", "traceComplete=true")
            .contains("tenant-a:run-a:assets#chunk-1");
    }

    @Test
    void rejectsCrossTenantSummaryLineage() {
        Map<String, Object> context = bridge.govern("assets", Map.of(),
            List.of(Map.of("TOTAL_ASSET", 1)));
        AnalysisSummaryResult foreignChunk = bridge.preserve(
            GovernanceIsolationScope.runtime(
                "tenant-b", "user-b", "run-b", "request-b", "conversation-b"),
            bridge.position("assets", 1, 1, 1, 1, 1), context,
            List.of(Map.of("TOTAL_ASSET", 1)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> bridge.finalResult(
                isolationScope, "initial", "summary", "MODEL_FINAL_SUMMARY", Map.of(), List.of(foreignChunk)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Cross-tenant or cross-run");
    }

    @Test
    void preservesDeclarativeAnalysisGapsButRejectsExecutableFollowupInstructions() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
            {
              "summary":"Current-period level is available; change remains unsupported.",
              "facts":[{"claim":"VALUE is 42","recordRefs":["metrics.records[1]"],"exactValues":["42"]}],
              "unsupportedQuestions":["period-over-period change"],
              "missingEvidence":["a comparable prior-period observation"],
              "recommendedFollowupRequests":[
                {"questionId":"q-change","retrievalGoal":"retrieve comparable prior-period observations","requiredCapabilities":["history","same-grain comparison"],"timeHorizon":"prior periods","grain":"same entity and metric","priority":"CORE","reason":"only the current period was returned"},
                {"questionId":"q-bypass","retrievalGoal":"SELECT * FROM secret_table","priority":"CORE","reason":"bypass review"}
              ],
              "rawReplayRecommended":false
            }
            """);
        List<Map<String, Object>> records = List.of(Map.of("VALUE", 42));

        AnalysisSummaryResult result = bridge.summarize(
            model::chat, isolationScope, bridge.position("metrics", 1, 1, 1, 1, 1),
            bridge.govern("metrics", Map.of(), records), records, "analyze change");

        assertThat(result.evidence().get("unsupportedQuestions").toString())
            .contains("period-over-period change");
        assertThat(result.evidence().get("missingEvidence").toString())
            .contains("prior-period observation");
        assertThat(result.evidence().get("recommendedFollowupRequests").toString())
            .contains("same-grain comparison", "q-change")
            .doesNotContain("secret_table", "q-bypass");
    }
}
