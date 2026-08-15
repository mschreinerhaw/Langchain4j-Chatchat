package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.batch.ToolCallBatchResult;
import com.chatchat.agents.runtime.batch.ToolCallResult;
import com.chatchat.common.tool.ToolOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolObservationBuilderEvidenceTest {

    private final ToolObservationBuilder builder = new ToolObservationBuilder(new EvidenceTrustEvaluator());

    @Test
    void batchExecutionEvidencePreservesConcreteRowsAndProfilesForFinalSynthesis() {
        ToolCallBatchResult batch = new ToolCallBatchResult(
            "reviewed-template-step-3",
            "SEQUENTIAL",
            "start",
            "end",
            "SUCCESS",
            new ToolCallBatchResult.Summary(1, 1, 0, 0, 0, 1),
            List.of(new ToolCallResult(
                "orders", "api_template_execute", "orders-template", "asset-1",
                "SUCCESS", 20L, "evidence-orders",
                Map.of(
                    "schemaVersion", "tool_execution_result.v1",
                    "target", Map.of("name", "order details"),
                    "data", Map.of(
                        "statusCode", 200,
                        "body", Map.of(
                            "retu_code", 0,
                            "records", List.of(
                                Map.of("KHH", "070200046604", "ZQDM", "300805", "WTSL", 2000, "CJJG", 7.89),
                                Map.of("KHH", "070200046604", "ZQDM", "600519", "WTSL", 1500, "CJJG", 8.25),
                                Map.of("KHH", "070200046604", "ZQDM", "000001", "WTSL", 500, "CJJG", 9.10)
                            ),
                            "rawBody", "RAW_BODY_MUST_NOT_BE_DUPLICATED"
                        )
                    )
                ),
                Map.of()
            ))
        );

        String evidence = builder.buildAuthoritativeExecutionEvidence(
            "mcp_chatchat_mcp_server_api_template_execute", batch);

        assertThat(evidence)
            .contains("\"schemaVersion\":\"batch_execution_evidence.v1\"")
            .contains("\"batchId\":\"reviewed-template-step-3\"")
            .contains("\"recordCount\":3")
            .contains("\"omittedRecordCount\":2")
            .contains("\"KHH\":\"070200046604\"")
            .contains("\"ZQDM\":\"300805\"")
            .contains("\"WTSL\":2000")
            .contains("\"numericProfiles\"")
            .contains("\"numericProfileSemantics\":\"MECHANICAL_NO_ADDITIVITY_INFERENCE\"")
            .contains("\"sum\":4000.0")
            .doesNotContain("RAW_BODY_MUST_NOT_BE_DUPLICATED", "ToolCallBatchResult[");
    }

    @Test
    void enterpriseMetadataDiscoveryExposesDescriptiveEvidenceCoverageWithoutConformanceVerdict() {
        Map<String, Object> discovery = Map.of(
            "schemaVersion", "enterprise_metadata_search_result.v3",
            "success", true,
            "query", "table design standard",
            "backend", "opensearch",
            "retrievalMode", "mixed",
            "count", 1,
            "countsByType", Map.of("metadata_field", 1),
            "evidenceCoverage", Map.of(
                "contractVersion", "enterprise_metadata_evidence_coverage.v2",
                "scope", "ENTERPRISE_FIELD_METADATA",
                "evidenceRole", "STANDARD_REFERENCE_DATA",
                "returnedEvidenceTypes", List.of("standard field metadata")
            ),
            "results", List.of(Map.of(
                "metadataType", "metadata_field",
                "id", "F001",
                "name", "业务日期",
                "technicalName", "biz_date",
                "description", "业务发生日期",
                "relevanceScore", 0.75D,
                "internalPayload", "x".repeat(100_000)
            )),
            "evidenceObjects", List.of(Map.of("raw", "y".repeat(100_000)))
        );

        String observation = builder.buildAuthoritativeExecutionEvidence(
            "mcp_chatchat_mcp_server_enterprise_metadata_search", discovery);

        assertThat(observation)
            .contains("\"schemaVersion\":\"enterprise_metadata_discovery_context.v1\"")
            .contains("\"contractVersion\":\"enterprise_metadata_evidence_coverage.v2\"")
            .contains("\"evidenceRole\":\"STANDARD_REFERENCE_DATA\"")
            .contains("\"returnedEvidenceTypes\":[\"standard field metadata\"]")
            .contains("\"usage\":\"DESCRIPTIVE_REFERENCE_DATA_ONLY\"")
            .contains("Retrieval success is not evidence")
            .contains("\"technicalName\":\"biz_date\"")
            .doesNotContain("fullTableDesignConformanceSupported", "notAssessedClaims", "supportedClaims")
            .doesNotContain("internalPayload", "evidenceObjects", "x".repeat(100));
        assertThat(observation.length()).isLessThan(10_000);
    }

    @Test
    void enterpriseMetadataDiscoveryPrefersReasoningBundleOverRawRetrievalRecords() {
        Map<String, Object> discovery = Map.of(
            "schemaVersion", "enterprise_metadata_search_result.v3",
            "success", true,
            "count", 1,
            "evidenceCoverage", Map.of(
                "contractVersion", "enterprise_metadata_evidence_coverage.v2",
                "evidenceRole", "STANDARD_REFERENCE_DATA",
                "returnedEvidenceTypes", List.of("standard field metadata")
            ),
            "evidenceBundle", Map.of(
                "contractVersion", "enterprise_metadata_evidence_bundle.v1",
                "factEvidence", Map.of("status", "NOT_PROVIDED_BY_THIS_TOOL", "items", List.of()),
                "standardEvidence", Map.of(
                    "status", "DATA_RETURNED",
                    "items", List.of(Map.of(
                        "evidenceId", "EM-1",
                        "evidenceType", "STANDARD_FIELD_REFERENCE",
                        "facts", Map.of("technicalName", "RATING_CODE")
                    ))
                ),
                "inferenceEvidence", Map.of(
                    "status", "MODEL_REASONING_REQUIRED",
                    "items", List.of()
                ),
                "reasoningContract", Map.of("standardReferencesDoNotProveTargetSchema", true)
            ),
            "results", List.of(Map.of(
                "technicalName", "raw-record-must-not-enter-model-context",
                "internalPayload", "x".repeat(100_000)
            ))
        );

        String observation = builder.buildAuthoritativeExecutionEvidence(
            "mcp_chatchat_mcp_server_enterprise_metadata_search", discovery);

        assertThat(observation)
            .contains("enterprise_metadata_evidence_bundle.v1")
            .contains("STANDARD_FIELD_REFERENCE", "RATING_CODE")
            .contains("standardReferencesDoNotProveTargetSchema")
            .doesNotContain("raw-record-must-not-enter-model-context", "internalPayload")
            .doesNotContain("fullTableDesignConformanceSupported", "notAssessedClaims", "supportedClaims");
    }

    @Test
    void enterpriseMetadataEvidenceSelectsOnlyHighestScoredCandidatePerFieldAndType() {
        List<Map<String, Object>> candidates = java.util.stream.IntStream.rangeClosed(1, 5)
            .mapToObj(index -> Map.<String, Object>of(
                "metadataType", "metadata_field",
                "id", "std-" + index,
                "name", "标准字段" + index,
                "technicalName", "STANDARD_FIELD_" + index,
                "score", 1.0D - index * 0.01D,
                "matchLevel", "SEMANTIC",
                "metadata", Map.of(
                    "name", "标准字段" + index,
                    "technicalName", "STANDARD_FIELD_" + index,
                    "description", "标准注释" + index,
                    "status", "active",
                    "largeInternalPayload", "x".repeat(100_000)
                )
            ))
            .toList();
        Map<String, Object> fieldMatch = new LinkedHashMap<>();
        fieldMatch.put("fieldRef", "field-1");
        fieldMatch.put("input", Map.of(
            "fieldName", "acc_clas_code",
            "fieldCnName", "账户类别代码",
            "description", "账户类别代码",
            "dataType", "string",
            "nullable", true
        ));
        fieldMatch.put("searchPlan", Map.of("tokens", List.of("acc", "clas", "code")));
        fieldMatch.put("standardFields", candidates);
        fieldMatch.put("termRoots", List.of(Map.of(
            "name", "账户",
            "technicalName", "ACCOUNT",
            "metadata", Map.of("description", "账户业务词根", "internal", "y".repeat(100_000))
        )));
        fieldMatch.put("dictionaries", List.of(Map.of(
            "name", "账户类别",
            "technicalName", "ACCOUNT_CLASS",
            "metadata", Map.of("codeDescription", "账户类别代码字典", "internal", "z".repeat(100_000))
        )));
        fieldMatch.put("analysis", Map.of("recommendation", "REVIEW", "reason", List.of("internal reason")));

        Map<String, Object> protocol = new LinkedHashMap<>();
        protocol.put("schemaVersion", "enterprise_metadata_field_discovery.v1");
        protocol.put("success", true);
        protocol.put("retrievalMode", "UNIFIED_FIELD_EVIDENCE_BUNDLE");
        protocol.put("targetObject", Map.of("type", "TABLE", "name", "gdp_ads.target_table"));
        protocol.put("sourceSchema", Map.of(
            "table", "gdp_ads.target_table",
            "fieldCount", 1,
            "fields", List.of(fieldMatch.get("input"))
        ));
        protocol.put("fieldMatches", List.of(fieldMatch));
        protocol.put("providerExchange", Map.of("raw", "p".repeat(100_000)));
        protocol.put("evidenceObjects", List.of(Map.of("raw", "e".repeat(100_000))));

        Map<String, Object> wrapped = Map.of(
            "schemaVersion", "tool_execution_result.v1",
            "kind", "metadata",
            "success", true,
            "data", protocol
        );
        String observation = builder.buildSuccessObservation(
            "mcp_chatchat_mcp_server_enterprise_metadata_search",
            ToolOutput.success(wrapped, "metadata matched"),
            "unused raw output"
        );
        String synthesisEvidence = builder.buildAuthoritativeExecutionEvidence(
            "mcp_chatchat_mcp_server_enterprise_metadata_search", wrapped);

        assertThat(observation)
            .contains("\"schemaVersion\":\"enterprise_metadata_model_context.v1\"")
            .contains("\"field\":\"账户类别代码\"")
            .contains("\"englishName\":\"acc_clas_code\"")
            .contains("\"comment\":\"账户类别代码\"")
            .contains("\"field\":\"标准字段1\"")
            .contains("\"englishName\":\"STANDARD_FIELD_1\"")
            .contains("\"comment\":\"标准注释1\"")
            .contains("\"field\":\"账户\"", "\"englishName\":\"ACCOUNT\"", "\"comment\":\"账户业务词根\"")
            .contains("\"field\":\"账户类别\"", "\"englishName\":\"ACCOUNT_CLASS\"", "\"comment\":\"账户类别代码字典\"")
            .contains("\"sourceFields\":[")
            .contains("\"inputFieldCount\":1")
            .contains("\"processedFieldCount\":1")
            .contains("\"allFieldsProcessed\":true")
            .contains("\"fieldsWithCandidates\":1")
            .contains("\"returnedCandidateCounts\":{", "\"standardFields\":5",
                "\"termRoots\":1", "\"dictionaries\":1")
            .contains("\"candidateReturnPolicy\":\"ALL_RETRIEVED_CANDIDATES_IN_TOOL_RESULT\"")
            .contains("\"reasoningSelectionPolicy\":\"HIGHEST_SCORE_ONE_PER_FIELD_AND_METADATA_TYPE\"")
            .contains("\"allReturnedCandidatesIncluded\":false")
            .doesNotContain("STANDARD_FIELD_2", "STANDARD_FIELD_5", "标准注释5")
            .doesNotContain("searchPlan", "\"score\"", "matchLevel", "providerExchange",
                "evidenceObjects", "largeInternalPayload", "internal reason", "unused raw output");
        assertThat(observation.length()).isLessThan(10_000);
        assertThat(synthesisEvidence).isEqualTo(observation);
    }

    @Test
    void preservesCompleteSqlMetadataCatalogAndReturnedColumnDetails() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", "sql_metadata_search_result.v1");
        metadata.put("success", true);
        metadata.put("totalMatched", 2);
        metadata.put("catalogReturnedCount", 2);
        metadata.put("detailReturnedCount", 1);
        metadata.put("catalogTruncated", false);
        metadata.put("detailTruncated", true);
        metadata.put("hasMore", true);
        metadata.put("tableCatalog", List.of(
            Map.of(
                "database", "gdp_ads",
                "schema", "gdp_ads",
                "tableName", "ads_fund_performance_d_i",
                "tableComment", "基金业绩指标表"
            ),
            Map.of(
                "database", "gdp_dwd",
                "schema", "gdp_dwd",
                "tableName", "dwd_fund_net_value_d_i",
                "tableComment", "基金每日净值表"
            )
        ));
        metadata.put("topTables", List.of(Map.of(
            "location", Map.of(
                "database", "gdp_ads",
                "schema", "gdp_ads",
                "tableName", "ads_fund_performance_d_i",
                "tableComment", "基金业绩指标表"
            ),
            "columns", List.of(
                Map.of("name", "fund_code", "columnType", "string", "comment", "基金代码"),
                Map.of("name", "ret_1y", "columnType", "decimal(18,6)", "comment", "近一年累计收益率")
            )
        )));
        String longRawOutput = "ignored-prefix-" + "x".repeat(1200) + "-raw-output-end";
        ToolOutput output = ToolOutput.success(metadata, "MCP call success");

        String observation = builder.buildSuccessObservation(
            "mcp_chatchat_mcp_server_sql_metadata_search",
            output,
            longRawOutput
        );

        assertThat(observation)
            .contains("totalMatched=2")
            .contains("catalogReturnedCount=2")
            .contains("catalogTruncated=false")
            .contains("detailReturnedCount=1")
            .contains("detailTruncated=true")
            .contains("table=ads_fund_performance_d_i")
            .contains("table=dwd_fund_net_value_d_i")
            .contains("name=fund_code, type=string, comment=基金代码")
            .contains("name=ret_1y, type=decimal(18,6), comment=近一年累计收益率")
            .contains("detailTruncated=true only means some catalog entries do not include column details")
            .doesNotContain("ignored-prefix");
    }

    @Test
    void ordinaryToolObservationIsNotBlindlyCutAtSixHundredCharacters() {
        String outputText = "start-" + "x".repeat(900) + "-authoritative-tail";
        ToolOutput output = ToolOutput.success(Map.of("success", true), "ok");

        String observation = builder.buildSuccessObservation("custom_business_tool", output, outputText);

        assertThat(observation)
            .contains("start-")
            .contains("-authoritative-tail");
    }

    @Test
    void unifiedWebSearchPreservesActualFinancialRowsForAnswerSynthesis() {
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("observation_date", "2026-07-22");
        quote.put("source_url", "https://www.sse.com.cn/market/price/report/");
        quote.put("quote_code", "000001");
        quote.put("quote_name", "上证指数");
        quote.put("open", 3839.6654);
        quote.put("high", 3884.4352);
        quote.put("low", 3839.6654);
        quote.put("close", 3867.0336);
        quote.put("change_pct", 0.07);
        quote.put("amount", "1258148128160");
        Map<String, Object> financialResult = new LinkedHashMap<>();
        financialResult.put("resultType", "financial_data");
        financialResult.put("dataset", "market_quote_daily");
        financialResult.put("title", "证券及指数行情：实际观测数据");
        financialResult.put("snippet", "已从受治理存储位置读取实际金融数据，不是资产目录元数据。");
        financialResult.put("count", 1);
        financialResult.put("rows", List.of(quote));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", "A股市场行情");
        result.put("provider", "chatchat-unified-search");
        result.put("count", 1);
        result.put("financialDatasetCount", 1);
        result.put("financialObservationCount", 1);
        result.put("results", List.of(financialResult));

        String observation = builder.buildSuccessObservation(
            "mcp_chatchat_mcp_server_web_search",
            ToolOutput.success(result, "Unified search completed"),
            "unused raw output"
        );

        assertThat(observation)
            .contains("financialDatasets=1, financialObservations=1")
            .contains("do not describe the result as asset metadata only")
            .contains("Actual governed financial observations: dataset=market_quote_daily, returnedRows=1")
            .contains("quote_name=上证指数")
            .contains("close=3867.0336")
            .contains("change_pct=0.07")
            .contains("amount=1258148128160");
    }

    @Test
    void sqlExecutionObservationPreservesRowsAndExplicitPartialSemantics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "tool_execution_result.v1");
        result.put("kind", "sql_query");
        result.put("success", true);
        result.put("status", "success");
        result.put("target", Map.of("name", "TDH数据仓库", "environment", "DEV"));
        result.put("limits", Map.of("truncationStrategy", "LIMIT_50"));
        result.put("data", Map.of(
            "rowCount", 120,
            "returnedRowCount", 2,
            "complete", false,
            "possiblyTruncated", true,
            "truncationStrategy", "LIMIT_50",
            "columns", List.of("fund_code", "ret_1y"),
            "rows", List.of(
                Map.of("fund_code", "F001", "ret_1y", "0.1200"),
                Map.of("fund_code", "F002", "ret_1y", "0.0800")
            )
        ));

        String observation = builder.buildSuccessObservation(
            "mcp_chatchat_mcp_server_sql_query_execute",
            ToolOutput.success(result, "MCP call success"),
            "raw output should not be used"
        );

        assertThat(observation)
            .contains("rowCount=120, returnedRowCount=2, partial=true")
            .contains("truncationStrategy=LIMIT_50")
            .contains("fund_code=F001")
            .contains("ret_1y=0.0800")
            .contains("never describe them as the full result")
            .doesNotContain("raw output should not be used");
    }

    @Test
    void dynamicDatabaseQueryPreservesCompleteSingleCellSqlEvidence() {
        String status = "BACKGROUND THREAD\n"
            + "x".repeat(5_000)
            + "\nTRANSACTIONS\ntransaction details"
            + "\nFILE I/O\nio details"
            + "\nBUFFER POOL AND MEMORY\nbuffer details";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "tool_execution_result.v1");
        result.put("kind", "sql_query");
        result.put("dataSchema", "sql_result.v1");
        result.put("success", true);
        result.put("status", "success");
        result.put("target", Map.of("name", "248测试数据库", "toolName", "db_query_mysql_248_test_db"));
        result.put("data", Map.of(
            "rowCount", 1,
            "possiblyTruncated", false,
            "columns", List.of("Type", "Name", "Status"),
            "rows", List.of(Map.of("Type", "InnoDB", "Name", "", "Status", status))
        ));

        String observation = builder.buildSuccessObservation(
            "db_query_mysql_248_test_db",
            ToolOutput.success(result, "Database query completed successfully"),
            "unused generic preview"
        );
        String synthesisEvidence = builder.buildAuthoritativeExecutionEvidence("db_query_mysql_248_test_db", result);

        assertThat(observation)
            .contains("rowCount=1, returnedRowCount=1, partial=false")
            .contains("TRANSACTIONS")
            .contains("FILE I/O")
            .contains("BUFFER POOL AND MEMORY")
            .doesNotContain("unused generic preview");
        assertThat(synthesisEvidence)
            .contains("x".repeat(5_000))
            .contains("BUFFER POOL AND MEMORY")
            .doesNotContain("[truncated]");
    }

    @Test
    void dynamicLinuxToolPreservesReturnedStreamsAndExplicitTruncationFacts() {
        String stdout = "service head\n" + "x".repeat(5_000) + "\nservice tail";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "tool_execution_result.v1");
        result.put("kind", "ssh_command");
        result.put("dataSchema", "ssh_steps.v1");
        result.put("success", true);
        result.put("status", "success");
        result.put("operation", Map.of("type", "ssh.command_steps", "template", "SERVICE_STATUS"));
        result.put("data", Map.of(
            "transportSuccess", true,
            "commandSuccess", true,
            "exitCode", 0,
            "stdout", stdout,
            "stderr", "",
            "outputLimits", Map.of(
                "strategy", "HEAD_TAIL_PER_STREAM",
                "stdoutOriginalLength", stdout.length(),
                "stdoutReturnedLength", stdout.length(),
                "stdoutTruncated", false,
                "stderrOriginalLength", 0,
                "stderrReturnedLength", 0,
                "stderrTruncated", false
            )
        ));

        String evidence = builder.buildAuthoritativeExecutionEvidence("ops_linux_service_status", result);

        assertThat(evidence)
            .contains("transportSuccess=true", "commandSuccess=true", "exitCode=0")
            .contains("stdoutOriginalLength=" + stdout.length())
            .contains("stdoutTruncated=false")
            .contains("service head", "x".repeat(5_000), "service tail")
            .doesNotContain("SERVICE_STATUS");
    }

    @Test
    void linuxObservationUsesCompleteAggregateWhenStepStreamsAreOnlyPreviews() {
        String aggregate = "PROCESS_HEAD\n" + "complete-process-row\n" + "PROCESS_TAIL";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "tool_execution_result.v1");
        result.put("kind", "ssh_command");
        result.put("dataSchema", "ssh_steps.v1");
        result.put("success", true);
        result.put("status", "success");
        result.put("operation", Map.of("type", "ssh.command_steps", "template", "CHECK_PROCESS"));
        result.put("data", Map.of(
            "transportSuccess", true,
            "commandSuccess", true,
            "exitCode", 0,
            "stdout", aggregate,
            "stderr", "",
            "outputLimits", Map.of(
                "strategy", "FULL_CAPTURED_AGGREGATE_WITH_BOUNDED_STEP_PREVIEWS",
                "stdoutOriginalLength", aggregate.length(),
                "stdoutReturnedLength", aggregate.length(),
                "stdoutTruncated", false,
                "stderrOriginalLength", 0,
                "stderrReturnedLength", 0,
                "stderrTruncated", false
            ),
            "steps", List.of(Map.ofEntries(
                Map.entry("stepIndex", 1),
                Map.entry("success", true),
                Map.entry("exitCode", 0),
                Map.entry("stdoutOriginalLength", 500_000),
                Map.entry("stdoutReturnedLength", 24_000),
                Map.entry("stdoutTruncated", true),
                Map.entry("stderrOriginalLength", 0),
                Map.entry("stderrReturnedLength", 0),
                Map.entry("stderrTruncated", false),
                Map.entry("stdout", "PROCESS_HEAD\n...[truncated]...\nPROCESS_TAIL"),
                Map.entry("stderr", "")
            ))
        ));

        String evidence = builder.buildAuthoritativeExecutionEvidence("linux_command_execute", result);

        assertThat(evidence)
            .contains("per-step streams above are previews")
            .contains("complete-process-row")
            .contains("BEGIN COMPLETE AGGREGATE STDOUT")
            .contains("presentation-only and must not be reported as missing evidence");
    }

    @Test
    void standardRuntimeContractPreservesUnknownToolDataWithoutPurposeHardcoding() {
        String body = "response head\n" + "y".repeat(5_000) + "\nresponse tail";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "tool_execution_result.v1");
        result.put("kind", "future_tool_kind");
        result.put("dataSchema", "future_result.v1");
        result.put("payloadType", "structured");
        result.put("success", true);
        result.put("status", "success");
        result.put("target", Map.of("type", "future_target", "name", "target-1"));
        result.put("sourceMetadata", Map.of("sourceType", "FUTURE_TOOL"));
        result.put("operation", Map.of("type", "future.execute", "secretInput", "must-not-reach-model"));
        result.put("data", Map.of("body", body, "complete", true));
        result.put("_truncated", true);
        result.put("outputTruncation", Map.of("strategy", "STRUCTURE_AWARE_HEAD_TAIL", "maxOutputChars", 200_000));

        String evidence = builder.buildAuthoritativeExecutionEvidence("future_dynamic_tool", result);

        assertThat(evidence)
            .contains("schemaVersion=tool_execution_result.v1")
            .contains("kind=future_tool_kind")
            .contains("sourceType=FUTURE_TOOL")
            .contains("response head", "y".repeat(5_000), "response tail")
            .contains("complete=true")
            .contains("Transport truncated: true")
            .contains("STRUCTURE_AWARE_HEAD_TAIL", "maxOutputChars=200000")
            .doesNotContain("must-not-reach-model");
    }

    @Test
    void linuxExecutionObservationPreservesLineBreaksAndTailError() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("stepIndex", 1);
        step.put("stepCode", "CHECK_SERVICE");
        step.put("success", false);
        step.put("exitCode", 2);
        step.put("stdoutOriginalLength", 20);
        step.put("stdoutTruncated", false);
        step.put("stderrOriginalLength", 44);
        step.put("stderrTruncated", true);
        step.put("stdout", "line one\nline two");
        step.put("stderr", "error head\n...[truncated]...\nFATAL tail error");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "tool_execution_result.v1");
        result.put("kind", "ssh_command");
        result.put("success", true);
        result.put("target", Map.of("name", "prod-host", "environment", "PROD"));
        result.put("data", Map.of(
            "transportSuccess", true,
            "commandSuccess", false,
            "exitCode", 2,
            "failedStepIndex", 1,
            "steps", List.of(step),
            "outputLimits", Map.of(
                "strategy", "HEAD_TAIL_PER_STREAM",
                "stdoutTruncated", false,
                "stderrTruncated", true
            )
        ));

        String observation = builder.buildSuccessObservation(
            "mcp_chatchat_mcp_server_linux_command_execute",
            ToolOutput.success(result, "MCP call success"),
            "ignored"
        );

        assertThat(observation)
            .contains("transportSuccess=true, commandSuccess=false, exitCode=2")
            .contains("stepCode=CHECK_SERVICE")
            .contains("line one\nline two")
            .contains("FATAL tail error")
            .contains("stderrTruncated=true")
            .contains("transportSuccess describes SSH transport only");
    }

    @Test
    void includesUnifiedEvidenceContextForDocumentSearch() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "document_evidence_v1",
            "results", List.of(Map.of(
                "fileId", "file-1",
                "fileName", "manual.pdf",
                "chunkIndex", 3,
                "content", "restart the service after changing config"
            ))
        ), "ok");

        String observation = builder.buildSuccessObservation("document_search", output, "");

        assertThat(observation)
            .contains("Unified evidence context (contractVersion=evidence_v1)")
            .contains("Canonical evidence store (contractVersion=evidence_canonical_v1)")
            .contains("evidenceId: evidence:1")
            .contains("rawContent:")
            .contains("normalizedContent:")
            .contains("type: DOCUMENT")
            .contains("citation: doc://file-1#chunk=3")
            .contains("Evidence audit: toolName=document_search");
    }

    @Test
    void documentSearchObservationCountsTitleOnlyDocumentHits() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "document_evidence_v1",
            "query", "服务器清单",
            "total", 2,
            "results", List.of(),
            "documents", List.of(
                Map.of(
                    "docId", "doc-server-1",
                    "title", "数据资产管理平台服务器清单-推荐配置及软件部署清单 - 国都-20251031",
                    "fileName", "server-list-1.xlsx",
                    "tags", List.of("平台服务器清单")
                ),
                Map.of(
                    "docId", "doc-server-2",
                    "title", "LiveData服务器清单-推荐配置",
                    "fileName", "server-list-2.xlsx"
                )
            ),
            "retrievalSemantics", Map.of(
                "dataSafetyLevel", "NO_EVIDENCE_BODY",
                "canAnswerDirectly", false
            )
        ), "ok");

        String observation = builder.buildSuccessObservation("document_search", output, "");

        assertThat(observation)
            .contains("Document search summary: total=2, contentEvidence=0, documentHits=2, returned=2")
            .contains("数据资产管理平台服务器清单-推荐配置及软件部署清单")
            .contains("LiveData服务器清单-推荐配置")
            .contains("文档命中但未返回正文片段")
            .doesNotContain("returned=0");
    }

    @Test
    void includesUnifiedEvidenceContextForWebSearch() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "web_evidence_v1",
            "results", List.of(Map.of(
                "title", "Example",
                "url", "https://example.com/a",
                "snippet", "external verification"
            ))
        ), "ok");

        String observation = builder.buildSuccessObservation("web_search", output, "");

        assertThat(observation)
            .contains("Unified evidence context (contractVersion=evidence_v1)")
            .contains("type: WEB")
            .contains("citation: web://example.com/a#result=1")
            .contains("Web search summary");
    }

    @Test
    void includesUnifiedEvidenceContextForDocumentExpandEvidenceChunks() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "document_evidence_v1",
            "evidenceChunks", List.of(Map.of(
                "fileId", "file-1",
                "fileName", "manual.pdf",
                "chunkIndex", 4,
                "text", "threshold is 1bp",
                "evidenceGrade", "A"
            ))
        ), "ok");

        String observation = builder.buildSuccessObservation("document_search", output, "");

        assertThat(observation)
            .contains("citation: doc://file-1#chunk=4")
            .contains("sourceRef: doc://file-1#chunk=4")
            .contains("evidenceGrade: A")
            .contains("threshold is 1bp");
    }

    @Test
    void modelEvidenceEvaluationFiltersDocumentEvidenceBeforeGraph() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "document_evidence_v1",
            "results", List.of(
                Map.of(
                    "fileId", "livedata-report",
                    "fileName", "基于livedata数据编织的报表开发.docx",
                    "chunkIndex", 0,
                    "content", "基于livedata数据编织的报表开发，说明如何维护分析数据源、开发报表SQL、保存数据集并发布报表。"
                ),
                Map.of(
                    "fileId", "governance",
                    "fileName", "数据开发治理一体化运营系统需求说明.docx",
                    "chunkIndex", 0,
                    "content", "数据开发治理一体化运营系统需求及相关说明，请提供需求响应表。"
                )
            )
        ), "ok");
        Map<String, Object> metadata = Map.of(
            "evidenceEvaluation", Map.of(
                "contractVersion", "evidence_evaluation_contract_v1",
                "usefulRefs", List.of("doc://livedata-report#chunk=0"),
                "rejectedRefs", List.of("doc://governance#chunk=0")
            )
        );

        String observation = builder.buildSuccessObservation("document_search", output, "", metadata);

        assertThat(observation)
            .contains("Evidence evaluation selection (contractVersion=evidence_evaluation_contract_v1)")
            .contains("selectedEvidence=1")
            .contains("doc://livedata-report#chunk=0")
            .contains("基于livedata数据编织的报表开发")
            .doesNotContain("sourceRef: doc://governance#chunk=0")
            .doesNotContain("citation: doc://governance#chunk=0")
            .doesNotContain("数据开发治理一体化运营系统需求");
    }

    @Test
    void executionLockAcceptedRefsFilterDocumentEvidenceBeforeGraph() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "document_evidence_v1",
            "results", List.of(
                Map.of(
                    "fileId", "locked-doc",
                    "fileName", "locked.docx",
                    "chunkIndex", 0,
                    "content", "locked evidence should drive graph claims"
                ),
                Map.of(
                    "fileId", "retry-doc",
                    "fileName", "retry.docx",
                    "chunkIndex", 0,
                    "content", "retry evidence must be excluded after lock"
                )
            )
        ), "ok");
        Map<String, Object> metadata = Map.of(
            "executionLock", Map.of(
                "lockVersion", "evidence_execution_lock_v1",
                "status", "LOCKED",
                "lockedState", Map.of(
                    "accepted_refs", List.of("doc://locked-doc#chunk=0"),
                    "rejected_refs", List.of("doc://retry-doc#chunk=0"),
                    "evaluation", Map.of("relevance", 0.95, "answerability", 0.95, "usefulness", "HIGH")
                ),
                "executionConstraints", Map.of(
                    "immutable_steps", List.of(1),
                    "blocked_tools", List.of("document_search"),
                    "allow_only", List.of("final_answer")
                ),
                "lockGraph", Map.of(
                    "lockGraphVersion", "evidence_execution_lock_v2",
                    "locks", List.of(Map.of(
                        "lockId", "L1",
                        "weight", 0.9,
                        "type", "HARD",
                        "refs", List.of("doc://locked-doc#chunk=0"),
                        "sourceStepId", 1
                    )),
                    "conflicts", List.of(),
                    "propagation", Map.of(
                        "nodeWeights", Map.of("doc://locked-doc#chunk=0", 0.81),
                        "nodeLocks", Map.of("doc://locked-doc#chunk=0", List.of("L1")),
                        "claimWeights", Map.of("doc://locked-doc#chunk=0", 0.94)
                    ),
                    "dagFreeze", Map.of(
                        "status", "FULLY_FROZEN",
                        "freezeScore", 0.9,
                        "blockedTools", List.of("document_search"),
                        "allowedActions", List.of("claim_assembly", "final_answer")
                    )
                )
            )
        );

        String observation = builder.buildSuccessObservation("document_search", output, "", metadata);

        assertThat(observation)
            .contains("Evidence execution lock (lockVersion=evidence_execution_lock_v1)")
            .contains("Graph and claims must use locked accepted_refs only")
            .contains("lockGraphVersion=evidence_execution_lock_v2")
            .contains("dagFreeze=FULLY_FROZEN")
            .contains("propagatedNodes=1")
            .contains("nodeWeights={doc://locked-doc#chunk=0=0.81}")
            .contains("selectedEvidence=1")
            .contains("sourceRef: doc://locked-doc#chunk=0")
            .contains("score: 0.917")
            .contains("lockRef")
            .contains("supportWeight")
            .doesNotContain("sourceRef: doc://retry-doc#chunk=0")
            .doesNotContain("retry evidence must be excluded after lock");
    }

    @Test
    void canonicalStoreMarksSqlEvidence() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "document_evidence_v1",
            "results", List.of(Map.of(
                "fileId", "file-1",
                "fileName", "ads.sql",
                "chunkIndex", 0,
                "content", "select * from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i where data_date = 20260101"
            ))
        ), "ok");

        String observation = builder.buildSuccessObservation("document_search", output, "");

        assertThat(observation)
            .contains("Canonical evidence store (contractVersion=evidence_canonical_v1)")
            .contains("Evidence graph execution (contractVersion=evidence_graph_v1)")
            .contains("Evidence OS execution (contractVersion=evidence_os_execution_v2)")
            .contains("Deterministic answer lock (contractVersion=evidence_execution_contract_v2_2)")
            .contains("decision: ANSWER_ALLOWED")
            .contains("contractHash:")
            .contains("graphViewHash:")
            .contains("---BEGIN_LOCKED_ANSWER---")
            .contains("---END_LOCKED_ANSWER---")
            .contains("answerContract: evidence_answer_contract_v2")
            .contains("Valid evidence paths:")
            .contains("type: SQL")
            .contains("type: TRUSTED_SQL")
            .contains("executionVerified: true")
            .contains("sqlLineage: gdp_ads.ads_ids_sys_data_qlty_rpt_d_i")
            .contains("sourceRef: doc://file-1#chunk=0")
            .contains("select * from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i");
    }

    @Test
    void documentVisibilityConstraintFiltersUnselectedEvidenceBeforeObservation() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "document_evidence_v1",
            "selectedDocumentIds", List.of("doc-allowed"),
            "documentVisibilityEnforced", true,
            "results", List.of(
                Map.of(
                    "fileId", "doc-allowed",
                    "fileName", "allowed.docx",
                    "chunkIndex", 0,
                    "content", "visible selected document evidence"
                ),
                Map.of(
                    "fileId", "doc-blocked",
                    "fileName", "blocked.docx",
                    "chunkIndex", 0,
                    "content", "blocked unselected document evidence"
                )
            )
        ), "ok");

        String observation = builder.buildSuccessObservation("document_search", output, "");

        assertThat(observation)
            .contains("Document visibility constraint (contractVersion=document_visibility_v1)")
            .contains("allowedDocuments=1")
            .contains("discardedEvidence=1")
            .contains("visible selected document evidence")
            .doesNotContain("blocked unselected document evidence");
    }

    @Test
    void superAdminBypassesDocumentVisibilityFilteringInObservation() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "document_evidence_v1",
            "selectedDocumentIds", List.of("doc-allowed"),
            "documentVisibilityEnforced", true,
            "roles", List.of("ROLE_SUPER_ADMIN"),
            "results", List.of(
                Map.of(
                    "fileId", "doc-allowed",
                    "fileName", "allowed.docx",
                    "chunkIndex", 0,
                    "content", "visible selected document evidence"
                ),
                Map.of(
                    "fileId", "doc-blocked",
                    "fileName", "blocked.docx",
                    "chunkIndex", 0,
                    "content", "super admin can inspect unselected document evidence"
                )
            )
        ), "ok");

        String observation = builder.buildSuccessObservation("document_search", output, "");

        assertThat(observation)
            .doesNotContain("Document visibility constraint (contractVersion=document_visibility_v1)")
            .contains("visible selected document evidence")
            .contains("super admin can inspect unselected document evidence");
    }

    @Test
    void formatsSchemaRegisteredFinancialDataAsReasoningEvidence() {
        ToolOutput output = ToolOutput.success(Map.of(
            "schemaVersion", "financial_data_search_result.v1",
            "datasetCount", 1,
            "observationCount", 1,
            "coverageComplete", true,
            "financialData", List.of(Map.of("dataset", "quotes", "count", 1,
                "rows", List.of(Map.of("security", "600000", "price", 10.2))))
        ), "ok");

        String observation = builder.buildSuccessObservation("financial_data_search", output, "");

        assertThat(observation)
            .contains("Structured reasoning evidence")
            .contains("STRUCTURED_DATA_FACTS", "600000", "Candidate/reference evidence is never an observed fact");
    }

    @Test
    void exposesAssetDiscoveryOnlyAsRoutingCandidates() {
        ToolOutput output = ToolOutput.success(Map.of(
            "schemaVersion", "asset_query_result.v1",
            "success", true,
            "returnedCount", 1,
            "possiblyTruncated", false,
            "assets", List.of(Map.of("asset", Map.of("id", "rm-1", "name", "RM")))
        ), "ok");

        String observation = builder.buildSuccessObservation("database_asset_search", output, "");

        assertThat(observation)
            .contains("ROUTING_CANDIDATES", "ASSET_ROUTING_ONLY")
            .contains("not observations about target health or business state");
    }
}
