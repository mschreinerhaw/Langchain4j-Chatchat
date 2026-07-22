package com.chatchat.agents.orchestration;

import com.chatchat.agents.evidence.EvidenceAudit;
import com.chatchat.agents.evidence.DeterministicAnswerCompiler;
import com.chatchat.agents.evidence.EvidenceCanonicalFormatter;
import com.chatchat.agents.evidence.EvidenceChunk;
import com.chatchat.agents.evidence.DocumentSelectionContext;
import com.chatchat.agents.evidence.EvidenceExecutionContract;
import com.chatchat.agents.evidence.EvidenceExecutionContractCompiler;
import com.chatchat.agents.evidence.EvidenceExecutionReport;
import com.chatchat.agents.evidence.EvidenceFormatter;
import com.chatchat.agents.evidence.EvidenceGraph;
import com.chatchat.agents.evidence.EvidenceGraphExecutionEngine;
import com.chatchat.agents.evidence.EvidenceGraphFormatter;
import com.chatchat.agents.evidence.EvidenceGraphView;
import com.chatchat.agents.evidence.EvidenceNormalizer;
import com.chatchat.agents.evidence.EvidenceOsV2Formatter;
import com.chatchat.agents.evidence.EvidencePathExecutor;
import com.chatchat.common.tool.ToolOutput;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds compact, evidence-aware observations from tool output.
 */
class ToolObservationBuilder {

    private static final int WEB_SEARCH_REFERENCE_LIMIT = 10;
    private static final String DOCUMENT_SEARCH_TOOL = "document_search";
    private static final String WEB_SEARCH_TOOL = "web_search";
    private static final String SEARCH_AND_EXTRACT_TOOL = "search_and_extract";

    private final EvidenceTrustEvaluator evidenceTrustEvaluator;
    private final EvidenceNormalizer evidenceNormalizer = new EvidenceNormalizer();
    private final EvidenceFormatter evidenceFormatter = new EvidenceFormatter();
    private final EvidenceCanonicalFormatter evidenceCanonicalFormatter = new EvidenceCanonicalFormatter();
    private final EvidenceGraphExecutionEngine evidenceGraphExecutionEngine = new EvidenceGraphExecutionEngine();
    private final EvidenceGraphFormatter evidenceGraphFormatter = new EvidenceGraphFormatter();
    private final EvidenceGraphView evidenceGraphView = new EvidenceGraphView();
    private final EvidencePathExecutor evidencePathExecutor = new EvidencePathExecutor();
    private final EvidenceOsV2Formatter evidenceOsV2Formatter = new EvidenceOsV2Formatter();
    private final EvidenceExecutionContractCompiler evidenceExecutionContractCompiler = new EvidenceExecutionContractCompiler();
    private final DeterministicAnswerCompiler deterministicAnswerCompiler = new DeterministicAnswerCompiler();

    ToolObservationBuilder(EvidenceTrustEvaluator evidenceTrustEvaluator) {
        this.evidenceTrustEvaluator = evidenceTrustEvaluator == null ? new EvidenceTrustEvaluator() : evidenceTrustEvaluator;
    }

    String buildSuccessObservation(String toolName, ToolOutput output, String outputText) {
        return buildSuccessObservation(toolName, output, outputText, Map.of());
    }

    String buildSuccessObservation(String toolName, ToolOutput output, String outputText, Map<String, Object> reviewMetadata) {
        Object data = output == null ? null : output.getData();
        if (isDocumentSearchToolName(toolName)) {
            return buildDocumentSearchObservation(toolName, output, data, outputText, reviewMetadata);
        }
        if (isSqlMetadataSearchToolName(toolName) && !asMap(data).isEmpty()) {
            return buildSqlMetadataSearchObservation(toolName, output, data);
        }
        if (isSqlExecutionResult(toolName, data)) {
            return buildSqlExecutionObservation(toolName, output, data);
        }
        if (isLinuxExecutionResult(toolName, data)) {
            return buildLinuxCommandObservation(toolName, output, data);
        }
        if (isStandardExecutionResult(data)) {
            return buildStandardExecutionObservation(toolName, output, data);
        }

        StringBuilder observation = new StringBuilder("Tool ")
            .append(toolName)
            .append(" succeeded.");
        if (!isWebEvidenceToolName(toolName)) {
            String message = output == null ? null : output.getMessage();
            if (message != null && !message.isBlank()) {
                observation.append(" Message: ").append(shortObservationText(message, 400));
            }
            String summary = observationText(outputText);
            if (summary != null && !summary.isBlank()) {
                observation.append(" Output summary: ").append(summary);
            }
            return observation.toString();
        }

        String message = output == null ? null : output.getMessage();
        if (message != null && !message.isBlank()) {
            observation.append(" Message: ").append(shortObservationText(message, 400));
        }
        Map<String, Object> root = asMap(data);
        appendUnifiedEvidence(observation, toolName, data, reviewMetadata);
        if (!root.isEmpty()) {
            String financialObservationCount = firstNonBlank(
                stringValue(root.get("financialObservationCount")),
                "0"
            );
            observation.append("\nWeb search summary: query=")
                .append(firstNonBlank(stringValue(root.get("query")), "unknown"))
                .append(", provider=")
                .append(firstNonBlank(stringValue(root.get("provider")), stringValue(root.get("configuredProvider"))))
                .append(", results=")
                .append(firstNonBlank(stringValue(root.get("count")), "unknown"))
                .append(", financialDatasets=")
                .append(firstNonBlank(stringValue(root.get("financialDatasetCount")), "0"))
                .append(", financialObservations=")
                .append(financialObservationCount)
                .append(", referenceUrls=")
                .append(firstNonBlank(stringValue(root.get("reference_url_count")), "unknown"))
                .append(", pageExcerpts=")
                .append(firstNonBlank(stringValue(root.get("page_excerpt_count")), "unknown"))
                .append(", contentMode=")
                .append(firstNonBlank(stringValue(root.get("contentMode")), "unknown"))
                .append('.');
            if (!"0".equals(financialObservationCount)) {
                observation.append("\nFinancial observation rule: financialObservationCount=")
                    .append(financialObservationCount)
                    .append(" proves that actual structured market rows were returned. Use the values in the financial evidence rows above; ")
                    .append("do not describe the result as asset metadata only or claim that A-share market values are missing.");
            }
        }
        List<WebCitation> citations = trustedWebCitations(data, observation);
        if (citations.isEmpty()) {
            String summary = shortObservationText(outputText, 600);
            if (summary != null && !summary.isBlank()) {
                observation.append(" Output summary: ").append(summary);
            }
            return observation.toString();
        }
        observation.append("\nWeb citation map. Use these labels in the final answer when relying on web search evidence:\n");
        for (int i = 0; i < citations.size(); i++) {
            WebCitation citation = citations.get(i);
            observation.append("[网页").append(i + 1).append("] ")
                .append(firstNonBlank(citation.title(), citation.url()))
                .append(" - ")
                .append(citation.url());
            if (citation.snippet() != null && !citation.snippet().isBlank()) {
                observation.append(" - ").append(citation.snippet());
            }
            observation.append("\n");
        }
        observation.append("Citation rule: append the matching [网页N] label immediately after any sentence that uses facts from that page.");
        return normalizeWebCitationLabels(observation.toString());
    }

    private String buildSqlMetadataSearchObservation(String toolName, ToolOutput output, Object data) {
        Map<String, Object> root = asMap(data);
        Map<String, Object> nestedData = asMap(root.get("data"));
        if (!nestedData.isEmpty() && root.get("tableCatalog") == null && root.get("topTables") == null) {
            root = nestedData;
        }

        List<Map<String, Object>> catalog = mapList(root.get("tableCatalog"));
        List<Map<String, Object>> details = mapList(root.get("topTables"));
        if (details.isEmpty()) {
            details = mapList(root.get("results"));
        }

        String totalMatched = firstNonBlank(stringValue(root.get("totalMatched")), String.valueOf(catalog.size()));
        String catalogReturned = firstNonBlank(stringValue(root.get("catalogReturnedCount")), String.valueOf(catalog.size()));
        String detailReturned = firstNonBlank(
            stringValue(root.get("detailReturnedCount")),
            firstNonBlank(stringValue(root.get("returnedDetailCount")), String.valueOf(details.size()))
        );
        boolean catalogTruncated = booleanValue(root.get("catalogTruncated"));
        boolean detailTruncated = booleanValue(root.get("detailTruncated")) || booleanValue(root.get("hasMore"));

        StringBuilder observation = new StringBuilder("Tool ")
            .append(toolName)
            .append(" succeeded.");
        String message = output == null ? null : output.getMessage();
        if (message != null && !message.isBlank()) {
            observation.append(" Message: ").append(shortObservationText(message, 400));
        }
        observation.append("\nSQL metadata search facts (authoritative structured result):")
            .append(" totalMatched=").append(totalMatched)
            .append(", catalogReturnedCount=").append(catalogReturned)
            .append(", catalogTruncated=").append(catalogTruncated)
            .append(", detailReturnedCount=").append(detailReturned)
            .append(", detailTruncated=").append(detailTruncated)
            .append('.');
        observation.append("\nCompleteness rule: catalogTruncated=false means the physical table catalog below is complete. ")
            .append("detailTruncated=true only means some catalog entries do not include column details; it never means table names are unavailable. ")
            .append("Do not claim that table names were hidden or not returned when catalogReturnedCount is positive.");

        observation.append("\nMatched physical table catalog (preserve identifiers exactly):");
        if (catalog.isEmpty()) {
            observation.append("\n- No physical table catalog entries were returned.");
        } else {
            for (int i = 0; i < catalog.size(); i++) {
                Map<String, Object> table = catalog.get(i);
                observation.append("\n- ").append(i + 1).append(". ")
                    .append(tableIdentity(table));
                appendFact(observation, "type", firstNonBlank(stringValue(table.get("tableType")), stringValue(table.get("type"))));
                appendFact(observation, "comment", firstNonBlank(stringValue(table.get("tableComment")), stringValue(table.get("comment"))));
                appendFact(observation, "score", stringValue(table.get("score")));
            }
        }

        observation.append("\nDetailed table metadata returned by the tool:");
        if (details.isEmpty()) {
            observation.append("\n- No column detail payloads were returned.");
        } else {
            for (int i = 0; i < details.size(); i++) {
                Map<String, Object> detail = details.get(i);
                Map<String, Object> location = asMap(detail.get("location"));
                Map<String, Object> table = location.isEmpty() ? detail : location;
                List<Map<String, Object>> columns = mapList(detail.get("columns"));
                observation.append("\nTable ").append(i + 1).append(": ").append(tableIdentity(table));
                appendFact(observation, "comment", firstNonBlank(stringValue(table.get("tableComment")), stringValue(table.get("comment"))));
                observation.append("\n  Columns (").append(columns.size()).append("):");
                if (columns.isEmpty()) {
                    observation.append(" none returned");
                    continue;
                }
                for (Map<String, Object> column : columns) {
                    observation.append("\n  - name=").append(firstNonBlank(
                        stringValue(column.get("name")),
                        firstNonBlank(stringValue(column.get("columnName")), stringValue(column.get("COLUMN_NAME")))
                    ));
                    appendFact(observation, "type", firstNonBlank(
                        stringValue(column.get("columnType")),
                        firstNonBlank(stringValue(column.get("dataType")), stringValue(column.get("COLUMN_TYPE")))
                    ));
                    appendFact(observation, "key", firstNonBlank(stringValue(column.get("columnKey")), stringValue(column.get("COLUMN_KEY"))));
                    appendFact(observation, "nullable", firstNonBlank(stringValue(column.get("nullable")), stringValue(column.get("IS_NULLABLE"))));
                    appendFact(observation, "comment", firstNonBlank(
                        stringValue(column.get("comment")),
                        firstNonBlank(stringValue(column.get("columnComment")), stringValue(column.get("COLUMN_COMMENT")))
                    ));
                }
            }
        }
        observation.append("\nAnswer rule: cite the exact physical identifiers above and list exact returned column names with their table. ")
            .append("Keep model-inferred business recommendations separate from tool facts and never replace physical identifiers with invented examples.");
        return observation.toString();
    }

    private String buildSqlExecutionObservation(String toolName, ToolOutput output, Object data) {
        Map<String, Object> root = unwrapStructuredRoot(data);
        Map<String, Object> resultData = asMap(root.get("data"));
        Map<String, Object> target = asMap(root.get("target"));
        Map<String, Object> limits = asMap(root.get("limits"));
        StringBuilder observation = successObservationHeader(toolName, output);
        observation.append("\nSQL execution facts (authoritative structured result):")
            .append(" success=").append(firstNonBlank(stringValue(root.get("success")), "unknown"))
            .append(", status=").append(firstNonBlank(stringValue(root.get("status")), "unknown"));
        appendFact(observation, "datasource", firstNonBlank(stringValue(target.get("name")), stringValue(target.get("toolName"))));
        appendFact(observation, "environment", stringValue(target.get("environment")));
        appendFact(observation, "durationMs", stringValue(root.get("durationMs")));
        observation.append('.');

        List<Map<String, Object>> resultSets = mapList(resultData.get("results"));
        if (resultSets.isEmpty()) {
            appendSqlResultSet(observation, resultData, 1, limits);
        } else {
            observation.append("\nSQL script resultSetCount=").append(resultSets.size()).append('.');
            for (int i = 0; i < resultSets.size(); i++) {
                appendSqlResultSet(observation, resultSets.get(i), i + 1, limits);
            }
        }
        observation.append("\nSQL completeness rule: rowCount is the tool-reported result size and returnedRowCount is the number of rows available below. ")
            .append("possiblyTruncated=true or complete=false means the rows are partial; never describe them as the full result. ")
            .append("Even when partial, preserve and report the returned rows and metrics exactly.");
        return observation.toString();
    }

    /**
     * Builds complete final-synthesis evidence from the runtime result contract while
     * omitting operation inputs that may contain SQL, shell commands, or request secrets.
     * Legacy payloads retain semantic fallbacks for compatibility.
     */
    String buildAuthoritativeExecutionEvidence(String toolName, Object data) {
        if (isStandardExecutionResult(data)) {
            return buildStandardExecutionObservation(toolName, null, data);
        }
        if (isSqlExecutionResult(toolName, data)) {
            return buildSqlExecutionObservation(toolName, null, data);
        }
        if (isLinuxExecutionResult(toolName, data)) {
            return buildLinuxCommandObservation(toolName, null, data);
        }
        return null;
    }

    private String buildStandardExecutionObservation(String toolName, ToolOutput output, Object data) {
        Map<String, Object> root = unwrapStructuredRoot(data);
        StringBuilder observation = successObservationHeader(toolName, output);
        observation.append("\nStandard execution result (authoritative structured output):")
            .append(" schemaVersion=").append(firstNonBlank(stringValue(root.get("schemaVersion")), "unknown"))
            .append(", kind=").append(firstNonBlank(stringValue(root.get("kind")), "unknown"))
            .append(", dataSchema=").append(firstNonBlank(stringValue(root.get("dataSchema")), "unknown"))
            .append(", payloadType=").append(firstNonBlank(stringValue(root.get("payloadType")), "unknown"))
            .append(", success=").append(firstNonBlank(stringValue(root.get("success")), "unknown"))
            .append(", status=").append(firstNonBlank(stringValue(root.get("status")), "unknown"))
            .append('.');
        appendFact(observation, "durationMs", stringValue(root.get("durationMs")));
        appendFact(observation, "errorMessage", stringValue(root.get("errorMessage")));
        if (!asMap(root.get("target")).isEmpty()) {
            observation.append("\nTarget: ").append(root.get("target"));
        }
        if (!asMap(root.get("sourceMetadata")).isEmpty()) {
            observation.append("\nSource metadata: ").append(root.get("sourceMetadata"));
        }
        if (!asMap(root.get("limits")).isEmpty()) {
            observation.append("\nLimits: ").append(root.get("limits"));
        }
        if (root.containsKey("_truncated")) {
            observation.append("\nTransport truncated: ").append(root.get("_truncated"));
        }
        if (!asMap(root.get("outputTruncation")).isEmpty()) {
            observation.append("\nTransport truncation: ").append(root.get("outputTruncation"));
        }
        if (root.containsKey("data")) {
            observation.append("\n---BEGIN RETURNED DATA---\n")
                .append(root.get("data"))
                .append("\n---END RETURNED DATA---");
        }
        observation.append("\nCompleteness rule: the returned data above is complete relative to this runtime payload only. ")
            .append("Any explicit completeness or truncation fields inside it remain authoritative.");
        return observation.toString();
    }

    private void appendSqlResultSet(StringBuilder observation,
                                    Map<String, Object> result,
                                    int index,
                                    Map<String, Object> limits) {
        List<Map<String, Object>> rows = mapList(result.get("rows"));
        Object rowCount = result.get("rowCount");
        Object returnedRowCount = result.get("returnedRowCount");
        if (returnedRowCount == null) {
            returnedRowCount = rows.size();
        }
        boolean partial = booleanValue(result.get("possiblyTruncated"))
            || Boolean.FALSE.equals(result.get("complete"));
        observation.append("\nResult set ").append(index).append(':')
            .append(" rowCount=").append(rowCount == null ? rows.size() : rowCount)
            .append(", returnedRowCount=").append(returnedRowCount)
            .append(", partial=").append(partial);
        appendFact(observation, "truncationStrategy", firstNonBlank(
            stringValue(result.get("truncationStrategy")),
            stringValue(limits.get("truncationStrategy"))
        ));
        appendFact(observation, "stepName", stringValue(result.get("stepName")));
        appendFact(observation, "analysisHint", stringValue(result.get("analysisHint")));
        observation.append('.');
        Object columns = result.get("columns");
        if (columns != null) {
            observation.append("\n  Columns: ").append(columns);
        }
        Object columnMetadata = result.get("columnMetadata");
        if (columnMetadata != null) {
            observation.append("\n  Column metadata: ").append(columnMetadata);
        }
        if (rows.isEmpty()) {
            observation.append("\n  Rows: none returned.");
            return;
        }
        observation.append("\n  Returned rows:");
        for (int i = 0; i < rows.size(); i++) {
            observation.append("\n  - row ").append(i + 1).append(": ").append(rows.get(i));
        }
    }

    private String buildLinuxCommandObservation(String toolName, ToolOutput output, Object data) {
        Map<String, Object> root = unwrapStructuredRoot(data);
        Map<String, Object> resultData = asMap(root.get("data"));
        Map<String, Object> target = asMap(root.get("target"));
        Map<String, Object> outputLimits = asMap(resultData.get("outputLimits"));
        List<Map<String, Object>> steps = mapList(resultData.get("steps"));
        StringBuilder observation = successObservationHeader(toolName, output);
        observation.append("\nLinux command execution facts (authoritative structured result):")
            .append(" transportSuccess=").append(firstNonBlank(stringValue(resultData.get("transportSuccess")), "unknown"))
            .append(", commandSuccess=").append(firstNonBlank(stringValue(resultData.get("commandSuccess")), "unknown"))
            .append(", exitCode=").append(firstNonBlank(stringValue(resultData.get("exitCode")), "unknown"));
        appendFact(observation, "host", firstNonBlank(stringValue(target.get("name")), stringValue(target.get("address"))));
        appendFact(observation, "environment", stringValue(target.get("environment")));
        appendFact(observation, "failedStepIndex", stringValue(resultData.get("failedStepIndex")));
        observation.append('.');
        if (!outputLimits.isEmpty()) {
            observation.append("\nOutput completeness: strategy=")
                .append(firstNonBlank(stringValue(outputLimits.get("strategy")), "unknown"));
            appendFact(observation, "stdoutOriginalLength", stringValue(outputLimits.get("stdoutOriginalLength")));
            appendFact(observation, "stdoutReturnedLength", stringValue(outputLimits.get("stdoutReturnedLength")));
            appendFact(observation, "stdoutTruncated", stringValue(outputLimits.get("stdoutTruncated")));
            appendFact(observation, "stderrOriginalLength", stringValue(outputLimits.get("stderrOriginalLength")));
            appendFact(observation, "stderrReturnedLength", stringValue(outputLimits.get("stderrReturnedLength")));
            appendFact(observation, "stderrTruncated", stringValue(outputLimits.get("stderrTruncated")));
            observation.append('.');
        }
        if (steps.isEmpty()) {
            appendStream(observation, "stdout", stringValue(resultData.get("stdout")));
            appendStream(observation, "stderr", stringValue(resultData.get("stderr")));
        } else {
            observation.append("\nExecuted steps (").append(steps.size()).append("):");
            for (Map<String, Object> step : steps) {
                observation.append("\nStep ").append(firstNonBlank(stringValue(step.get("stepIndex")), "?"))
                    .append(": success=").append(firstNonBlank(stringValue(step.get("success")), "unknown"))
                    .append(", exitCode=").append(firstNonBlank(stringValue(step.get("exitCode")), "unknown"));
                appendFact(observation, "stepCode", stringValue(step.get("stepCode")));
                appendFact(observation, "stepName", stringValue(step.get("stepName")));
                appendFact(observation, "analysisHint", stringValue(step.get("analysisHint")));
                appendFact(observation, "stdoutOriginalLength", stringValue(step.get("stdoutOriginalLength")));
                appendFact(observation, "stdoutTruncated", stringValue(step.get("stdoutTruncated")));
                appendFact(observation, "stderrOriginalLength", stringValue(step.get("stderrOriginalLength")));
                appendFact(observation, "stderrTruncated", stringValue(step.get("stderrTruncated")));
                observation.append('.');
                appendStream(observation, "step stdout", stringValue(step.get("stdout")));
                appendStream(observation, "step stderr", stringValue(step.get("stderr")));
            }
        }
        observation.append("\nLinux completeness rule: transportSuccess describes SSH transport only; commandSuccess and each exitCode describe command outcome. ")
            .append("A truncated stream remains partial evidence. Always report non-zero exit codes and preserve tail errors shown above.");
        return observation.toString();
    }

    private void appendStream(StringBuilder observation, String label, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        observation.append("\n---BEGIN ").append(label.toUpperCase()).append("---\n")
            .append(value)
            .append("\n---END ").append(label.toUpperCase()).append("---");
    }

    private StringBuilder successObservationHeader(String toolName, ToolOutput output) {
        StringBuilder observation = new StringBuilder("Tool ").append(toolName).append(" succeeded.");
        String message = output == null ? null : output.getMessage();
        if (message != null && !message.isBlank()) {
            observation.append(" Message: ").append(shortObservationText(message, 400));
        }
        return observation;
    }

    private Map<String, Object> unwrapStructuredRoot(Object data) {
        Map<String, Object> root = asMap(data);
        for (String key : List.of("structuredContent", "structured_content", "payload", "result")) {
            Map<String, Object> nested = asMap(root.get(key));
            if (!nested.isEmpty()) {
                return nested;
            }
        }
        return root;
    }

    private void appendFact(StringBuilder target, String name, String value) {
        if (value != null && !value.isBlank()) {
            target.append(", ").append(name).append('=').append(value.replaceAll("[\\r\\n]+", " ").trim());
        }
    }

    private String tableIdentity(Map<String, Object> table) {
        String database = stringValue(table.get("database"));
        String schema = firstNonBlank(stringValue(table.get("schema")), stringValue(table.get("schemaName")));
        String name = firstNonBlank(
            stringValue(table.get("tableName")),
            firstNonBlank(stringValue(table.get("table")), stringValue(table.get("name")))
        );
        return "database=" + firstNonBlank(database, "-")
            + ", schema=" + firstNonBlank(schema, "-")
            + ", table=" + firstNonBlank(name, "-");
    }

    String buildFailureObservation(String toolName, ToolOutput output) {
        String error = firstNonBlank(output.getErrorMessage(), output.getExceptionType());
        if (error == null || error.isBlank()) {
            error = "unknown error";
        }
        return "Tool " + toolName + " failed. Error: " + error
            + ". Evidence from this tool is unavailable; the final answer must explicitly mention this limitation and must not claim successful verification from this tool.";
    }

    private List<WebCitation> trustedWebCitations(Object data, StringBuilder observation) {
        Map<String, Object> root = asMap(data);
        List<Map<String, Object>> evidenceChunks = new ArrayList<>();
        addCandidateList(evidenceChunks, root.get("evidence_chunks"));
        if (evidenceChunks.isEmpty()) {
            return extractWebCitations(data);
        }

        EvidenceTrustEvaluator.TrustResult trustResult = evidenceTrustEvaluator.evaluate(evidenceChunks);
        Map<String, Object> trust = trustResult.metadata();
        observation.append("\nEvidence trust policy: version=")
            .append(firstNonBlank(stringValue(trust.get("version")), "agent_evidence_trust_policy_v1"))
            .append(", usable=")
            .append(firstNonBlank(stringValue(trust.get("usableCount")), "0"))
            .append(", ignoredLowScore=")
            .append(firstNonBlank(stringValue(trust.get("ignoredLowScoreCount")), "0"))
            .append(", downgradedDomains=")
            .append(firstNonBlank(stringValue(trust.get("downgradedDomainCount")), "0"))
            .append(", contradictionDetected=")
            .append(firstNonBlank(stringValue(trust.get("contradictionDetected")), "false"))
            .append('.');
        if (Boolean.TRUE.equals(trust.get("requestMoreEvidence"))) {
            observation.append(" Trust policy requests more evidence before making a strong claim: ")
                .append(firstNonBlank(stringValue(trust.get("reason")), "insufficient trusted evidence"))
                .append('.');
        }
        if (trustResult.usableEvidence().isEmpty()) {
            return List.of();
        }
        return extractWebCitations(Map.of("evidenceSnippets", trustResult.usableEvidence()));
    }

    private String buildDocumentSearchObservation(String toolName,
                                                  ToolOutput output,
                                                  Object data,
                                                  String outputText,
                                                  Map<String, Object> reviewMetadata) {
        StringBuilder observation = new StringBuilder("Tool ")
            .append(toolName)
            .append(" succeeded.");
        String message = output == null ? null : output.getMessage();
        if (message != null && !message.isBlank()) {
            observation.append(" Message: ").append(shortObservationText(message, 400));
        }

        Map<String, Object> root = asMap(data);
        appendUnifiedEvidence(observation, toolName, data, reviewMetadata);
        if (!root.isEmpty()) {
            List<Map<String, Object>> results = new ArrayList<>();
            addCandidateList(results, root.get("results"));
            addCandidateList(results, root.get("items"));
            addCandidateList(results, root.get("records"));
            List<Map<String, Object>> documents = new ArrayList<>();
            addCandidateList(documents, root.get("documents"));
            observation.append("\nDocument search summary: total=")
                .append(firstNonBlank(
                    firstNonBlank(stringValue(root.get("total")), stringValue(root.get("totalCount"))),
                    firstNonBlank(stringValue(root.get("count")), "unknown")
                ))
                .append(", contentEvidence=")
                .append(results.size())
                .append(", documentHits=")
                .append(documents.size())
                .append(", returned=")
                .append(results.size() + documents.size())
                .append(", contentMode=")
                .append(firstNonBlank(stringValue(root.get("contentMode")), "unknown"))
                .append('.');
        }

        List<DocumentEvidence> evidence = extractDocumentEvidence(data);
        if (evidence.isEmpty()) {
            String summary = shortObservationText(outputText, 600);
            if (summary != null && !summary.isBlank()) {
                observation.append(" Output summary: ").append(summary);
            }
            return observation.toString();
        }

        observation.append("\nDocument evidence snippets:\n");
        for (int i = 0; i < evidence.size(); i++) {
            DocumentEvidence item = evidence.get(i);
            observation.append("[文档").append(i + 1).append("] ")
                .append(firstNonBlank(item.title(), "Untitled document"));
            if (item.docId() != null && !item.docId().isBlank()) {
                observation.append(" (docId=").append(item.docId()).append(")");
            }
            if (item.snippet() != null && !item.snippet().isBlank()) {
                observation.append(" - ").append(item.snippet());
            } else {
                observation.append(" - 文档命中但未返回正文片段；只能证明知识库存在该文档，不能作为正文内容结论。");
            }
            observation.append("\n");
        }
        return observation.toString();
    }

    private void appendUnifiedEvidence(StringBuilder observation, String toolName, Object data, Map<String, Object> reviewMetadata) {
        Object evidenceData = trustedUnifiedEvidenceData(toolName, data);
        DocumentSelectionContext selectionContext = isDocumentSearchToolName(toolName)
            ? DocumentSelectionContext.fromToolData(evidenceData)
            : DocumentSelectionContext.unrestricted();
        List<EvidenceChunk> normalizedChunks = evidenceNormalizer.normalize(toolName, evidenceData, WEB_SEARCH_REFERENCE_LIMIT);
        List<EvidenceChunk> evaluatedChunks = applyEvidenceEvaluationSelection(normalizedChunks, reviewMetadata);
        appendEvidenceEvaluationSelection(observation, normalizedChunks, evaluatedChunks, reviewMetadata);
        normalizedChunks = applyLockPropagation(evaluatedChunks, reviewMetadata);
        EvidenceGraph fullGraph = evidenceGraphExecutionEngine.build("tool:" + firstNonBlank(toolName, "unknown"), normalizedChunks);
        DocumentSelectionContext.FilterResult visibility = selectionContext.filter(normalizedChunks);
        List<EvidenceChunk> chunks = visibility.visibleChunks();
        if (selectionContext.active()) {
            observation.append("\nDocument visibility constraint (contractVersion=")
                .append(selectionContext.contractVersion())
                .append("): enforced=true, allowedDocuments=")
                .append(selectionContext.allowedDocumentIds().size())
                .append(", discardedEvidence=")
                .append(visibility.discardedChunks())
                .append(", visibleEvidence=")
                .append(chunks.size())
                .append(", fullGraphNodes=")
                .append(fullGraph.nodes().size())
                .append(". Unselected documents must not be used as answer evidence.\n");
        }
        if (chunks.isEmpty()) {
            return;
        }
        String context = evidenceFormatter.formatContext(chunks);
        if (context == null || context.isBlank()) {
            return;
        }
        observation.append('\n').append(context).append('\n');
        String canonicalStore = evidenceCanonicalFormatter.formatStore(chunks);
        if (canonicalStore != null && !canonicalStore.isBlank()) {
            observation.append(canonicalStore).append('\n');
        }
        EvidenceGraph graph = evidenceGraphView.project(fullGraph, selectionContext);
        String graphContext = evidenceGraphFormatter.format(graph);
        if (graphContext != null && !graphContext.isBlank()) {
            observation.append(graphContext).append('\n');
        }
        EvidenceExecutionReport executionReport = evidencePathExecutor.execute(graph, null, selectionContext);
        String osContext = evidenceOsV2Formatter.format(executionReport);
        if (osContext != null && !osContext.isBlank()) {
            observation.append(osContext).append('\n');
        }
        if (isDocumentSearchToolName(toolName)) {
            EvidenceExecutionContract executionContract = evidenceExecutionContractCompiler.compile(graph, executionReport);
            String deterministicContext = deterministicAnswerCompiler.compile(executionContract);
            if (deterministicContext != null && !deterministicContext.isBlank()) {
                observation.append(deterministicContext).append('\n');
            }
        }
        List<EvidenceAudit> audits = evidenceNormalizer.audits(toolName, data, chunks);
        if (!audits.isEmpty()) {
            long documentCount = chunks.stream().filter(chunk -> chunk.evidenceType() != null && "DOCUMENT".equals(chunk.evidenceType().name())).count();
            long webCount = chunks.stream().filter(chunk -> chunk.evidenceType() != null && "WEB".equals(chunk.evidenceType().name())).count();
            long blockedCount = audits.stream().filter(audit -> "BLOCKED".equals(audit.policyStatus())).count();
            observation.append("Evidence audit: toolName=")
                .append(firstNonBlank(toolName, "unknown"))
                .append(", contractVersion=evidence_v1")
                .append(", documentEvidence=")
                .append(documentCount)
                .append(", webEvidence=")
                .append(webCount)
                .append(", blockedEvidence=")
                .append(blockedCount)
                .append(".\n");
        }
    }

    private List<EvidenceChunk> applyEvidenceEvaluationSelection(List<EvidenceChunk> chunks,
                                                                 Map<String, Object> reviewMetadata) {
        if (chunks == null || chunks.isEmpty() || reviewMetadata == null || reviewMetadata.isEmpty()) {
            return chunks == null ? List.of() : chunks;
        }
        Set<String> usefulRefs = evidenceRefs(reviewMetadata, "usefulEvidenceRefs", "usefulRefs");
        Set<String> rejectedRefs = evidenceRefs(reviewMetadata, "rejectedEvidenceRefs", "rejectedRefs");
        if (usefulRefs.isEmpty() && rejectedRefs.isEmpty()) {
            return chunks;
        }
        List<EvidenceChunk> selected = new ArrayList<>();
        for (EvidenceChunk chunk : chunks) {
            String ref = evidenceRef(chunk);
            if (!usefulRefs.isEmpty()) {
                if (usefulRefs.contains(ref)) {
                    selected.add(chunk);
                }
                continue;
            }
            if (!rejectedRefs.contains(ref)) {
                selected.add(chunk);
            }
        }
        return List.copyOf(selected);
    }

    private void appendEvidenceEvaluationSelection(StringBuilder observation,
                                                   List<EvidenceChunk> originalChunks,
                                                   List<EvidenceChunk> selectedChunks,
                                                   Map<String, Object> reviewMetadata) {
        if (observation == null || reviewMetadata == null || reviewMetadata.isEmpty()) {
            return;
        }
        Object evaluation = reviewMetadata.get("evidenceEvaluation");
        Object executionLock = reviewMetadata.get("executionLock");
        Set<String> usefulRefs = evidenceRefs(reviewMetadata, "usefulEvidenceRefs", "usefulRefs");
        Set<String> rejectedRefs = evidenceRefs(reviewMetadata, "rejectedEvidenceRefs", "rejectedRefs");
        if (evaluation == null && executionLock == null && usefulRefs.isEmpty() && rejectedRefs.isEmpty()) {
            return;
        }
        appendExecutionLockSelection(observation, executionLock);
        observation.append("\nEvidence evaluation selection (contractVersion=evidence_evaluation_contract_v1): originalEvidence=")
            .append(originalChunks == null ? 0 : originalChunks.size())
            .append(", selectedEvidence=")
            .append(selectedChunks == null ? 0 : selectedChunks.size())
            .append(", usefulRefs=")
            .append(usefulRefs)
            .append(", rejectedRefs=")
            .append(rejectedRefs)
            .append(". Graph and claims must use selectedEvidence only.\n");
    }

    private Set<String> evidenceRefs(Map<String, Object> metadata, String directKey, String evaluationKey) {
        Set<String> refs = new LinkedHashSet<>(stringSet(metadata == null ? null : metadata.get(directKey)));
        Object evaluation = metadata == null ? null : metadata.get("evidenceEvaluation");
        if (evaluation instanceof Map<?, ?> map) {
            refs.addAll(stringSet(map.get(evaluationKey)));
        }
        Object executionLock = metadata == null ? null : metadata.get("executionLock");
        if (executionLock instanceof Map<?, ?> lockMap) {
            Object lockedState = lockMap.get("lockedState");
            if (lockedState instanceof Map<?, ?> state) {
                if ("usefulRefs".equals(evaluationKey)) {
                    refs.addAll(stringSet(state.get("accepted_refs")));
                    refs.addAll(stringSet(state.get("acceptedRefs")));
                } else if ("rejectedRefs".equals(evaluationKey)) {
                    refs.addAll(stringSet(state.get("rejected_refs")));
                    refs.addAll(stringSet(state.get("rejectedRefs")));
                }
            }
            Object lockGraph = lockMap.get("lockGraph");
            if ("usefulRefs".equals(evaluationKey) && lockGraph instanceof Map<?, ?> graphMap) {
                Object locks = graphMap.get("locks");
                if (locks instanceof Iterable<?> iterable) {
                    for (Object item : iterable) {
                        Map<String, Object> lock = asMap(item);
                        refs.addAll(stringSet(lock.get("refs")));
                    }
                }
            }
        }
        return refs;
    }

    private void appendExecutionLockSelection(StringBuilder observation, Object executionLock) {
        if (!(executionLock instanceof Map<?, ?> lockMap)) {
            return;
        }
        Object status = lockMap.get("status");
        if (status != null && !"LOCKED".equalsIgnoreCase(String.valueOf(status))) {
            return;
        }
        Map<String, Object> constraints = asMap(lockMap.get("executionConstraints"));
        Map<String, Object> state = asMap(lockMap.get("lockedState"));
        Map<String, Object> lockGraph = asMap(lockMap.get("lockGraph"));
        Map<String, Object> dagFreeze = asMap(lockGraph.get("dagFreeze"));
        Map<String, Object> propagation = asMap(lockGraph.get("propagation"));
        observation.append("\nEvidence execution lock (lockVersion=")
            .append(firstNonBlank(stringValue(lockMap.get("lockVersion")), "evidence_execution_lock_v1"))
            .append("): status=LOCKED, acceptedRefs=")
            .append(stringSet(state.get("accepted_refs")))
            .append(", rejectedRefs=")
            .append(stringSet(state.get("rejected_refs")))
            .append(", blockedTools=")
            .append(stringSet(constraints.get("blocked_tools")))
            .append(", allowOnly=")
            .append(stringSet(constraints.get("allow_only")))
            .append(", lockGraphVersion=")
            .append(firstNonBlank(stringValue(lockGraph.get("lockGraphVersion")), "none"))
            .append(", dagFreeze=")
            .append(firstNonBlank(stringValue(dagFreeze.get("status")), "UNFROZEN"))
            .append(", propagatedNodes=")
            .append(asMap(propagation.get("nodeWeights")).size())
            .append(", nodeWeights=")
            .append(asMap(propagation.get("nodeWeights")))
            .append(". Graph and claims must use locked accepted_refs only.\n");
    }

    private List<EvidenceChunk> applyLockPropagation(List<EvidenceChunk> chunks, Map<String, Object> reviewMetadata) {
        if (chunks == null || chunks.isEmpty() || reviewMetadata == null || reviewMetadata.isEmpty()) {
            return chunks == null ? List.of() : chunks;
        }
        Map<String, Object> executionLock = asMap(reviewMetadata.get("executionLock"));
        Map<String, Object> lockGraph = asMap(executionLock.get("lockGraph"));
        Map<String, Object> propagation = asMap(lockGraph.get("propagation"));
        Map<String, Object> nodeWeights = asMap(propagation.get("nodeWeights"));
        Map<String, Object> nodeLocks = asMap(propagation.get("nodeLocks"));
        if (nodeWeights.isEmpty()) {
            return chunks;
        }
        List<EvidenceChunk> values = new ArrayList<>(chunks.size());
        for (EvidenceChunk chunk : chunks) {
            String ref = evidenceRef(chunk);
            double propagatedWeight = doubleValue(nodeWeights.get(ref));
            if (propagatedWeight <= 0.0) {
                values.add(chunk);
                continue;
            }
            double baseScore = chunk.score() == null ? 0.82 : chunk.score();
            if (baseScore > 1.0) {
                baseScore = baseScore / 100.0;
            }
            double boostedScore = Math.max(baseScore, Math.min(1.0, baseScore + propagatedWeight * 0.12));
            Map<String, Object> citation = new LinkedHashMap<>(chunk.citation());
            citation.put("lockWeight", round(propagatedWeight));
            citation.put("lockGraphVersion", firstNonBlank(stringValue(lockGraph.get("lockGraphVersion")), "evidence_execution_lock_v2"));
            Map<String, Object> trace = new LinkedHashMap<>(chunk.trace());
            trace.put("lockPropagationWeight", round(propagatedWeight));
            trace.put("lockIds", stringSet(nodeLocks.get(ref)));
            values.add(new EvidenceChunk(
                chunk.evidenceType(),
                chunk.contractVersion(),
                chunk.source(),
                chunk.content(),
                round(boostedScore),
                citation,
                chunk.governance(),
                trace
            ));
        }
        return List.copyOf(values);
    }

    private Set<String> stringSet(Object value) {
        Set<String> values = new LinkedHashSet<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                String text = stringValue(item);
                if (text != null && !text.isBlank()) {
                    values.add(text.trim());
                }
            }
            return values;
        }
        String text = stringValue(value);
        if (text == null || text.isBlank()) {
            return values;
        }
        for (String item : text.split("[,;\\n]")) {
            if (!item.isBlank()) {
                values.add(item.trim());
            }
        }
        return values;
    }

    private String evidenceRef(EvidenceChunk chunk) {
        Object refId = chunk == null || chunk.citation() == null ? null : chunk.citation().get("refId");
        return refId == null ? "" : String.valueOf(refId);
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private Object trustedUnifiedEvidenceData(String toolName, Object data) {
        if (!isWebEvidenceToolName(toolName)) {
            return data;
        }
        Map<String, Object> root = asMap(data);
        List<Map<String, Object>> evidenceChunks = new ArrayList<>();
        addCandidateList(evidenceChunks, root.get("evidence_chunks"));
        if (evidenceChunks.isEmpty()) {
            return data;
        }
        EvidenceTrustEvaluator.TrustResult trustResult = evidenceTrustEvaluator.evaluate(evidenceChunks);
        Map<String, Object> trustedRoot = new LinkedHashMap<>(root);
        trustedRoot.put("evidence_chunks", trustResult.usableEvidence());
        return trustedRoot;
    }

    private List<WebCitation> extractWebCitations(Object data) {
        Map<String, Object> root = asMap(data);
        if (root.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        addCandidateList(candidates, root.get("results"));
        addCandidateList(candidates, root.get("items"));
        addCandidateList(candidates, root.get("organic_results"));
        addCandidateList(candidates, root.get("webPages"));
        addCandidateList(candidates, root.get("pageExcerpts"));
        addCandidateList(candidates, root.get("evidenceSnippets"));

        Map<String, WebCitation> byUrl = new LinkedHashMap<>();
        for (Map<String, Object> item : candidates) {
            String url = firstNonBlank(
                stringValue(item.get("url")),
                firstNonBlank(
                    stringValue(item.get("link")),
                    firstNonBlank(stringValue(item.get("href")), stringValue(item.get("source_url")))
                )
            );
            if (url == null || url.isBlank() || byUrl.containsKey(url)) {
                continue;
            }
            byUrl.put(url, new WebCitation(
                url,
                firstNonBlank(
                    stringValue(item.get("title")),
                    firstNonBlank(stringValue(item.get("name")), stringValue(item.get("source")))
                ),
                shortText(firstNonBlank(
                    stringValue(item.get("snippet")),
                    firstNonBlank(
                        stringValue(item.get("excerpt")),
                        firstNonBlank(
                            stringValue(item.get("pageExcerpt")),
                            firstNonBlank(
                                stringValue(item.get("contentExcerpt")),
                                firstNonBlank(stringValue(item.get("summary")), stringValue(item.get("content")))
                            )
                        )
                    )
                ))
            ));
        }

        Object referenceUrlsValue = root.get("reference_urls");
        if (!(referenceUrlsValue instanceof List<?> referenceUrls) || referenceUrls.isEmpty()) {
            return byUrl.values().stream().limit(WEB_SEARCH_REFERENCE_LIMIT).toList();
        }
        List<WebCitation> citations = new ArrayList<>();
        for (Object value : referenceUrls) {
            if (citations.size() >= WEB_SEARCH_REFERENCE_LIMIT) {
                break;
            }
            String url = stringValue(value);
            if (url == null || url.isBlank()) {
                continue;
            }
            WebCitation matched = byUrl.get(url);
            citations.add(matched == null ? new WebCitation(url, url, null) : matched);
        }
        return citations;
    }

    private List<DocumentEvidence> extractDocumentEvidence(Object data) {
        Map<String, Object> root = asMap(data);
        if (root.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> candidates = new ArrayList<>();
        addCandidateList(candidates, root.get("evidenceSnippets"));
        addCandidateList(candidates, root.get("results"));
        addCandidateList(candidates, root.get("items"));
        addCandidateList(candidates, root.get("records"));
        addCandidateList(candidates, root.get("documents"));

        List<DocumentEvidence> evidence = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> item : candidates) {
            if (evidence.size() >= WEB_SEARCH_REFERENCE_LIMIT) {
                break;
            }
            String docId = firstNonBlank(
                firstNonBlank(stringValue(item.get("docId")), stringValue(item.get("documentId"))),
                firstNonBlank(stringValue(item.get("id")), stringValue(item.get("fileId")))
            );
            String title = firstNonBlank(
                firstNonBlank(stringValue(item.get("title")), stringValue(item.get("name"))),
                firstNonBlank(stringValue(item.get("filename")), stringValue(item.get("source")))
            );
            String snippet = shortText(firstNonBlank(
                stringValue(item.get("excerpt")),
                firstNonBlank(
                    stringValue(item.get("contentExcerpt")),
                    firstNonBlank(stringValue(item.get("snippet")), stringValue(item.get("summary")))
                )
            ));
            if ((title == null || title.isBlank()) && (snippet == null || snippet.isBlank())) {
                continue;
            }
            String key = firstNonBlank(docId, "") + "|" + firstNonBlank(title, "") + "|" + firstNonBlank(snippet, "");
            if (!seen.add(key)) {
                continue;
            }
            evidence.add(new DocumentEvidence(docId, title, snippet));
        }
        return evidence;
    }

    private void addCandidateList(List<Map<String, Object>> candidates, Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return;
        }
        for (Object item : collection) {
            Map<String, Object> map = asMap(item);
            if (!map.isEmpty()) {
                candidates.add(map);
            }
        }
    }

    private boolean isWebEvidenceToolName(String toolName) {
        return isWebSearchToolName(toolName) || isSearchAndExtractToolName(toolName);
    }

    private boolean isWebSearchToolName(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return WEB_SEARCH_TOOL.equals(semantic) || semantic.endsWith("_web_search") || semantic.contains("web_search");
    }

    private boolean isSearchAndExtractToolName(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return SEARCH_AND_EXTRACT_TOOL.equals(semantic) || semantic.endsWith("_search_and_extract") || semantic.contains("search_and_extract");
    }

    private boolean isDocumentSearchToolName(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return DOCUMENT_SEARCH_TOOL.equals(semantic)
            || semantic.endsWith("_document_search")
            || (semantic.contains("document") && semantic.contains("search"));
    }

    private boolean isSqlMetadataSearchToolName(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return "sql_metadata_search".equals(semantic) || semantic.endsWith("_sql_metadata_search");
    }

    private boolean isSqlExecutionToolName(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return "sql_query_execute".equals(semantic)
            || semantic.endsWith("_sql_query_execute")
            || "sql_script_execute".equals(semantic)
            || semantic.endsWith("_sql_script_execute");
    }

    private boolean isSqlExecutionResult(String toolName, Object data) {
        if (asMap(data).isEmpty()) {
            return false;
        }
        if (isSqlExecutionToolName(toolName)) {
            return true;
        }
        Map<String, Object> root = unwrapStructuredRoot(data);
        String kind = stringValue(root.get("kind"));
        String dataSchema = stringValue(root.get("dataSchema"));
        Map<String, Object> operation = asMap(root.get("operation"));
        String operationType = stringValue(operation.get("type"));
        return "sql_query".equalsIgnoreCase(kind)
            || "sql_result_sets".equalsIgnoreCase(kind)
            || (dataSchema != null && (dataSchema.toLowerCase().startsWith("sql_result")
                || dataSchema.toLowerCase().startsWith("sql_query")
                || dataSchema.toLowerCase().startsWith("sql_script")))
            || (operationType != null && operationType.toLowerCase().startsWith("sql."));
    }

    private boolean isLinuxCommandExecutionToolName(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return "linux_command_execute".equals(semantic) || semantic.endsWith("_linux_command_execute");
    }

    private boolean isLinuxExecutionResult(String toolName, Object data) {
        if (asMap(data).isEmpty()) {
            return false;
        }
        if (isLinuxCommandExecutionToolName(toolName)) {
            return true;
        }
        Map<String, Object> root = unwrapStructuredRoot(data);
        String kind = stringValue(root.get("kind"));
        String dataSchema = stringValue(root.get("dataSchema"));
        String operationType = stringValue(asMap(root.get("operation")).get("type"));
        return "ssh_command".equalsIgnoreCase(kind)
            || (dataSchema != null && dataSchema.toLowerCase().startsWith("ssh_"))
            || (operationType != null && operationType.toLowerCase().startsWith("ssh."));
    }

    private boolean isStandardExecutionResult(Object data) {
        Map<String, Object> root = unwrapStructuredRoot(data);
        return "tool_execution_result.v1".equals(stringValue(root.get("schemaVersion")));
    }

    private String toolSemanticKey(String toolName) {
        if (toolName == null) {
            return "";
        }
        String normalized = toolName.trim().toLowerCase().replace('-', '_');
        while (normalized.startsWith("mcp_")) {
            normalized = normalized.substring(4);
        }
        String[] prefixes = {
            "chatchat_mcp_server_",
            "chatchat_",
            "xxx_"
        };
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String prefix : prefixes) {
                if (normalized.startsWith(prefix)) {
                    normalized = normalized.substring(prefix.length());
                    changed = true;
                }
            }
        }
        return normalized;
    }

    private Map<String, Object> asMap(Object data) {
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> values = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null) {
                    values.put(String.valueOf(key), value);
                }
            });
            return values;
        }
        return Map.of();
    }

    private List<Map<String, Object>> mapList(Object value) {
        List<Map<String, Object>> values = new ArrayList<>();
        if (!(value instanceof Collection<?> collection)) {
            return values;
        }
        for (Object item : collection) {
            Map<String, Object> map = asMap(item);
            if (!map.isEmpty()) {
                values.add(map);
            }
        }
        return values;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String shortText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 220 ? normalized : normalized.substring(0, 220);
    }

    private String shortObservationText(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        int limit = Math.max(80, maxChars);
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private String observationText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private String normalizeWebCitationLabels(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.replace("[缃戦〉", "[网页");
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    private record WebCitation(
        String url,
        String title,
        String snippet
    ) {
    }

    private record DocumentEvidence(
        String docId,
        String title,
        String snippet
    ) {
    }
}
