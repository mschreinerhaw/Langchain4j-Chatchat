package com.chatchat.common.runtime.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded diagnostic projection of execution facts; original evidence stays in the journal. */
public final class RuntimeEventLog {
    private static final Logger LOG = LoggerFactory.getLogger(RuntimeEventLog.class);
    private static final int MAX_ITEMS = 16;
    private static final Set<String> FIELDS = Set.of(
        "requestId", "taskId", "executionId", "attemptId", "runId", "tenantId", "agentId", "sessionId",
        "type", "source", "eventKind", "eventState", "status", "stage", "workflow", "lifecyclePhase",
        "planId", "planVersion", "version", "iteration", "step", "stepId", "stepIds", "action", "actionType",
        "toolName", "resolvedToolName", "operation", "datasetReference", "datasetFingerprint",
        "contextFingerprint", "receiptFingerprint", "evidenceSnapshotRef", "reportSha256",
        "turn", "maximumTurns", "modelCalls", "requestCount", "completedRequestCount", "workspaceVersion",
        "modelCompleted", "publicationState", "executionState", "executionStopReason", "success",
        "durationMs", "elapsedMs", "queueTimeMs", "executionTimeMs", "inputTokensEstimated",
        "cpuTimeMs", "maxRssBytes", "firstTokenMs", "generationTimeMs", "retryTimeMs", "toolDurationMs", "requestBytes", "messageCount", "responseMode",
        "parentCodeHash", "parentCodeVersion", "codeVersion", "changeKind", "proposalHash",
        "outputTokensEstimated", "instructionsChars", "layersChars", "fullChars", "outputCheckpointRestored",
        "record", "from", "limit", "field", "fromChar", "maxChars", "nextChar", "nextRecord", "hasMore",
        "truncated", "stdoutTruncated", "exitCode", "recordCount", "observationCount", "candidateCount",
        "selectedIds", "selectedAssetIds", "selectedTemplateIds", "coverageDecision", "retrievalOutcome",
        "errorCode", "errorType", "errorMessage", "reason", "summary", "changeReason", "rawObservationLocation", "eventMetadataCompacted",
        "traceId", "scriptPath", "scriptVersion", "scriptHash", "workingDirectory", "codeHash", "codeSha256", "httpStatus", "reasonCode", "errorCategory", "exceptionType", "diagnosticCode", "repairAttempts", "repairAllowed", "validationStatus", "resultAvailable",
        "schemaVersion", "contractVersion", "output_type", "target", "author", "declaration",
        "protocolVersion", "executionTraceId", "workflowExecutionAttempt", "planExecutionScope",
        "evidenceIteration", "evidenceId", "interpretationPlanVersion", "interpretationPlanStepId",
        "interpretationPlanActionType", "planStepDefinitionFingerprint", "toolExecutionTimeMs",
        "completedPlanStepIds", "remainingPlanStepIds", "waveStepIds", "failedStepId", "dependsOn", "id"
    );
    private static final Set<String> CONTAINERS = Set.of(
        "metadata", "reads", "receipts", "request", "modelDecision", "modelOutput", "promptSize",
        "executionPlan", "steps", "navigation", "error", "scope", "receipt", "receiptOrigin"
    );
    private static final Pattern SECRET = Pattern.compile(
        "(?i)([\"']?(?:password|passwd|pwd|token|authorization|api[_-]?key|secret|credential)[\"']?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;}]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;}]+|(?<=://)[^\\s/@]+:[^\\s/@]+@");

    private RuntimeEventLog() { }

    /** Execution feedback uses the existing Runtime log projection, with no free-text process data. */
    public static void execution(String eventType,com.chatchat.common.kernel.KernelDataScope scope,
            String taskId,String executionId,Map<String,?> metadata) {
        var facts=new LinkedHashMap<String,Object>();
        facts.put("taskId",taskId);facts.put("traceId",scope==null?null:scope.runId());facts.put("executionId",executionId);
        Set<String> allowed=Set.of("status","stage","reasonCode","errorCategory","exceptionType","diagnosticCode", "attemptId",
            "scriptPath","scriptVersion","scriptHash","codeHash","codeSha256","httpStatus","durationMs","repairAttempts","repairAllowed","modelCall","evidenceSnapshotRef","exitCode","validationStatus","resultAvailable",
            "cpuTimeMs","maxRssBytes","queueTimeMs","firstTokenMs","generationTimeMs","retryTimeMs","toolDurationMs","requestBytes","messageCount","responseMode",
            "parentCodeHash","parentCodeVersion","codeVersion","changeKind","proposalHash");
        metadata.forEach((key,value)->{
            if(!allowed.contains(key)||value==null)return;
            if(value instanceof Number||value instanceof Boolean)facts.put(key,value);
            else if("scriptPath".equals(key)&&value instanceof String path&&path.startsWith("/workspace/scripts/")&&com.chatchat.common.runtime.coding.CodingScript.safeFileName(path.substring("/workspace/scripts/".length())))facts.put(key,path);
            else if(value instanceof String token&&token.matches("[A-Za-z0-9_:.\\-]{1,256}"))facts.put(key,token);
        });
        writeFact(scope==null?null:scope.runId(),eventType,facts);
    }

    /** Facts with their own persistence reference (for example planId/version), outside the run-event sequence. */
    public static void writeFact(String streamId, String eventType, Map<String,Object> facts) {
        write(new RuntimeEvent() {
            public String eventId() { return ""; }
            public String streamId() { return streamId; }
            public String eventType() { return eventType; }
            public long occurredAt() { return System.currentTimeMillis(); }
            public Map<String,Object> payload() { return facts; }
        });
    }

    public static void write(RuntimeEvent event) {
        if (event == null || !LOG.isInfoEnabled()) return;
        try {
            String line = format(event);
            // Status is an execution fact, never a business/report quality decision.
            if (event.eventType().endsWith("FAILED")) LOG.warn("Runtime OS event {}", line);
            else LOG.info("Runtime OS event {}", line);
        } catch (RuntimeException failure) {
            // Diagnostic logging must not change an accepted execution transition.
            LOG.warn("Runtime OS event projection failed errorType={}", failure.getClass().getSimpleName());
        }
    }

    public static String format(RuntimeEvent event) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", "runtime_log_v1");
        result.put("runId", text(event.streamId()));
        result.put("eventId", text(event.eventId()));
        result.put("eventType", text(event.eventType()));
        result.put("occurredAt", event.occurredAt());
        result.put("sequence", event.sequence());
        Budget budget = new Budget();
        result.put("facts", project(event.payload(), 0, budget));
        result.put("logProjectionTruncated", budget.truncated);
        return json(result);
    }

    private static Object project(Object value, int depth, Budget budget) {
        if (value == null) return null;
        if (++budget.nodes > 96 || depth > 7) { budget.truncated = true; return "[bounded]"; }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || (!FIELDS.contains(key) && !CONTAINERS.contains(key))) continue;
                if (budget.nodes >= 96) { budget.truncated = true; break; }
                Object child = entry.getValue();
                // Known scalar names must not smuggle arbitrary input/output maps into the log.
                if (child instanceof Map<?, ?> && !CONTAINERS.contains(key)) continue;
                result.put(key, project(child, depth + 1, budget));
            }
            return result;
        }
        if (value instanceof Collection<?> values) {
            var result = new java.util.ArrayList<>();
            for (Object item : values) {
                if (result.size() >= MAX_ITEMS || budget.nodes >= 96) { budget.truncated = true; break; }
                result.add(project(item, depth + 1, budget));
            }
            return result;
        }
        if (value instanceof Boolean || value instanceof Number) return value;
        if (!(value instanceof String || value instanceof Enum<?>)) return "[unsupported]";
        String clean = text(String.valueOf(value));
        int remaining = Math.max(0, 6_000 - budget.chars);
        int length = Math.min(240, remaining);
        if (clean.length() > length) { clean = clean.substring(0, length) + "[truncated]"; budget.truncated = true; }
        budget.chars += clean.length();
        return clean;
    }

    private static String text(String value) {
        if (value == null) return "";
        String safe = SECRET.matcher(BEARER.matcher(value).replaceAll("[redacted]")).replaceAll("$1[redacted]");
        return safe.length() > 512 ? safe.substring(0, 512) + "[truncated]" : safe;
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number number) return Double.isFinite(number.doubleValue()) ? number.toString() : "null";
        if (value instanceof Map<?, ?> map) return map.entrySet().stream()
            .map(entry -> json(String.valueOf(entry.getKey())) + ":" + json(entry.getValue()))
            .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        if (value instanceof Collection<?> values) return values.stream().map(RuntimeEventLog::json)
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        StringBuilder out = new StringBuilder("\"");
        for (char ch : String.valueOf(value).toCharArray()) {
            if (ch == '"' || ch == '\\') out.append('\\').append(ch);
            else if (ch < 32 || ch == '\u2028' || ch == '\u2029') out.append(String.format("\\u%04x", (int) ch));
            else out.append(ch);
        }
        return out.append('"').toString();
    }

    private static final class Budget { int nodes; int chars; boolean truncated; }
}
