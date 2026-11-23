package com.chatchat.api.runtime.coding.data;

import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.agents.runtime.coding.CodingJournal;
import com.chatchat.agents.runtime.toolcall.TemplateInvocationBridge;
import com.chatchat.api.assets.UserAssetService;
import com.chatchat.api.controller.mcp.McpControlPlaneClient;
import com.chatchat.api.service.AgentTemplateToolPolicy;
import com.chatchat.api.runtime.analysis.RegisteredToolAnalysisOperator;
import com.chatchat.api.runtime.coding.nativecline.NativeS3Evidence;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.runtime.McpAnalysisPayload;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisScope;
import com.chatchat.common.runtime.coding.CodingInputManifest;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.chatchat.common.runtime.coding.CodingTaskOutcome;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix="chatchat.runtime.coding.unified", name="enabled", havingValue="true")
public final class NativePublishedTemplate {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(NativePublishedTemplate.class);
    public static final String TOOL = "execute_published_template";
    public static final String CONTEXT = "publishedTemplate";
    private static final String STATE = "native-published-template";
    private static final long MAX_OPTIONAL_DATA_BYTES = 8L * 1024 * 1024;
    private static final int PART_BYTES = 64 * 1024;
    private static final String PROTOCOL = "template_parameter_protocol_v2";
    public static final String OPERATION = "native-template-query";
    private static final CodingTaskOutcome.Counters QUERY_USAGE =
        new CodingTaskOutcome.Counters(0,0,1,0,0,0,0,"0");
    private enum Admission { STARTED, EXISTING, QUOTA_DENIED, STOPPING }
    private final CodingDataSupplyProperties policy;
    private final CodingDataSupplyService supply;
    private final DurableCodingTaskService tasks;
    private final RegisteredToolAnalysisOperator tools;
    private final McpControlPlaneClient publications;
    private final AgentTemplateToolPolicy templateTools;
    private final SkillExecutionScopePort scopes;
    private final NativeS3Evidence evidence;
    private final ObjectMapper json;
    private final TemplateInvocationBridge bridge = new TemplateInvocationBridge();

    public NativePublishedTemplate(CodingDataSupplyProperties policy, CodingDataSupplyService supply,
            DurableCodingTaskService tasks, RegisteredToolAnalysisOperator tools,
            McpControlPlaneClient publications, AgentTemplateToolPolicy templateTools, SkillExecutionScopePort scopes,
            NativeS3Evidence evidence, ObjectMapper json) {
        this.policy = policy;
        this.supply = supply;
        this.tasks = tasks;
        this.tools = tools;
        this.publications = publications;
        this.templateTools = templateTools;
        this.scopes = scopes;
        this.evidence = evidence;
        this.json = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    /** Freeze the intersection of this run's Optional publications and current task grants. */
    public Optional<Map<String, Object>> captureOptional(KernelDataScope scope, String skill,
                                                         Set<String> optionalIds, String originalQuestion) {
        if (!policy.isClineTemplateInvocationEnabled() || skill == null || skill.isBlank() || optionalIds.isEmpty())
            return Optional.empty();
        var ids = supply.grants(scope).stream()
            .filter(grant -> skill.equals(grant.skillId()) && optionalIds.contains(grant.templateId()))
            .map(CodingDataSupplyProperties.Grant::templateId).distinct().sorted().toList();
        var bindings = new LinkedHashMap<String, Object>();
        for (String id : ids) {
            try {
                bindings.put(id, capture(scope, skill, id));
            } catch (java.util.concurrent.CancellationException cancelled) {
                throw cancelled;
            } catch (RuntimeException unavailable) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                log.info("Optional Native publication unavailable: {}", unavailable.getClass().getSimpleName());
            }
        }
        return bindings.isEmpty() ? Optional.empty() : Optional.of(Map.of(
            "optionalBindings", Map.copyOf(bindings), "originalQuestion", originalQuestion));
    }

    public Map<String, Object> capture(KernelDataScope scope, String skill, String id) {
        var grant = grant(scope, skill, id);
        var published = callerCatalog("publications", scope);
        var rows = java.util.stream.StreamSupport.stream(published.spliterator(), false)
            .filter(row -> eligiblePublication(row, id) && grant.assetName().equals(row.path("assetName").asText()))
            .toList();
        if (rows.size() != 1) throw new SecurityException("Expected one authorized running read-only publication");
        var row = rows.get(0);
        String tool = row.path("toolName").asText();
        if (!templateTools.allowed(scope.tenantId(), scope.userId(), skill, Set.of(tool)).contains(tool))
            throw new SecurityException("Published tool denied by the current Agent resource policy");
        var route = tools.resolveBoundPublishedTool(skill, tool)
            .orElseThrow(() -> new SecurityException("No matching Skill-bound published template route"));
        var schema = row.path("parameterSchema");
        if (!schema.isObject() || schema.toString().length() > 7000)
            throw new IllegalStateException("Invalid published template descriptor");
        return Map.of("skillId", skill, "grantHash", supply.hash(grant), "route", route,
            "templateKey", row.path("templateKey").asText(), "boundKey", row.path("boundKey").asText(), "releaseId", row.path("id").asText(), "revision", row.path("revision").asText(), "template",
            Map.of("templateId", id, "description", row.path("title").asText(), "parameterSchema",
                json.convertValue(schema, new TypeReference<Map<String, Object>>() {})));
    }

    private static final Set<String> READ_ONLY_TYPES = Set.of(
        "sql_datasource", "api_service", "http_endpoint", "ssh_host", "jmx_endpoint");

    private static boolean eligiblePublication(JsonNode row, String id) {
        String key = row.path("templateKey").asText();
        int separator = key.indexOf(':');
        if (separator < 1 || !key.substring(separator + 1).equals(id)
                || !"RUNNING".equals(row.path("state").asText())) return false;
        String type = key.substring(0, separator);
        return READ_ONLY_TYPES.contains(type)
            && ("sql_datasource".equals(type) ? row.path("verifiedReadOnlySql").asBoolean()
                : row.path("verifiedReadOnly").asBoolean());
    }

    public Map<String, Object> specification(CodingTaskContract task) {
        var context = json.valueToTree(task.runtimeContext().get(CONTEXT));
        boolean optional = context.path("optionalBindings").isObject();
        var templates = new ArrayList<JsonNode>();
        if (optional) context.path("optionalBindings").forEach(binding -> templates.add(binding.path("template")));
        else templates.add(context.path("template"));
        var templateIds = templates.stream().map(value -> value.path("templateId").asText()).toList();
        String question = context.path("originalQuestion").asText(task.objective().instruction());
        ZoneId zone = ZoneId.systemDefault();
        LocalDate referenceDate = com.chatchat.agents.runtime.toolcall.RequiredTemporalArgumentGrounding
            .hasRelativeTimeExpression(question) ? referenceDate(task, zone) : null;
        String temporalContext = referenceDate == null ? "" : " Interpret only the explicit relative time using " + referenceDate
            + " in " + zone + ".";
        var fields = new LinkedHashMap<String, Object>();
        if (optional) {
            fields.put("data_gap", Map.of("type", "string", "minLength", 1, "maxLength", 1000));
            fields.put("reason", Map.of("type", "string", "minLength", 1, "maxLength", 1000));
        }
        fields.put("protocol_version", Map.of("type", "string", "enum", List.of(PROTOCOL)));
        fields.put("template_id", Map.of("type", "string", "enum", templateIds));
        fields.put("arguments", Map.of("type", "object", "additionalProperties", Map.of(
            "type", "object", "additionalProperties", false,
            "required", List.of("value", "source", "evidence"), "properties", Map.of(
                "value", Map.of(), "source", Map.of("type", "string", "enum", List.of("user_query")),
                "evidence", Map.of("type", "object", "properties", Map.of("quote", Map.of("type", "string"),
                    "interpretation", Map.of("type", "string", "enum", List.of("model"))),
                    "required", List.of("quote"), "additionalProperties", false)))));
        fields.put("unresolved_parameters", Map.of("type", "array", "items", Map.of("type", "string")));
        if (optional) {
            fields.put("arguments", Map.of("type", "object"));
            fields.put("evidence", Map.of("type", "object"));
            fields.put("operation_id", Map.of("type", "string"));
            fields.put("part_index", Map.of("type", "integer", "minimum", 0));
        }
        return Map.of("name", TOOL, "description", "Query a task-authorized published read-only template. "
            + "Extract and normalize the requested values using the template parameter schema. Cite the exact "
            + "source phrase from the original user request in evidence.quote. Set evidence.interpretation=model "
            + "when the submitted value had to be interpreted or normalized. Omit missing values and list their "
            + "fields in unresolved_parameters. Leave dates unresolved when the user provides no time expression. "
            + "Runtime applies the published template defaults."
            + temporalContext + " Correct invalid fields once. Stop on authorization, quota or query errors. "
            + "After success inspect defaultedFields and disclose any default scope in the report. "
            + "When complete is false, full source coverage is either limited or unverified. "
            + (optional ? "Submit data_gap and reason for each new query. With separate evidence, "
                + "use arguments field values and matching evidence quote objects. "
                + "Retrieve each authorized JSONL part with template_id, operation_id and part_index only. "
                : "")
            + templates, "inputSchema", Map.of("type", "object", "additionalProperties", false,
                "required", optional ? List.of("template_id") : List.copyOf(fields.keySet()), "properties", fields));
    }

    public Map<String, Object> execute(CodingTaskContract task, JsonNode input) {
        if (input == null || !input.isObject()) return status("PARAMETER_INVALID");
        var taskContext = json.valueToTree(task.runtimeContext().get(CONTEXT));
        boolean optional = taskContext.path("optionalBindings").isObject();
        String requestedId = input.path("template_id").asText();
        var binding = optional ? taskContext.path("optionalBindings").path(requestedId) : taskContext;
        var schema = binding.path("template");
        if (!schema.isObject()) return status("AUTHORIZATION_DENIED");
        String question = taskContext.path("originalQuestion").asText(task.objective().instruction());
        String id = schema.path("templateId").asText();
        if (!task.authorization().allowedTools().contains(TOOL) || !id.equals(input.path("template_id").asText()))
            return status("AUTHORIZATION_DENIED");
        CodingDataSupplyProperties.Grant grant;
        Map<String, Object> current;
        try {
            grant = grant(task.scope(), binding.path("skillId").asText(), id);
            current = capture(task.scope(), grant.skillId(), id);
        } catch (SecurityException denied) {
            return status("AUTHORIZATION_DENIED");
        }
        if (!binding.has("templateKey")) {
            // Older SQL tasks froze the same identity without the explicit type-qualified key.
            if (!("sql_datasource:" + id).equals(current.get("templateKey"))) return status("AUTHORIZATION_DENIED");
            current = new LinkedHashMap<>(current);
            current.remove("templateKey");
            current.remove("boundKey");
        }
        if (!json.valueToTree(current).equals(binding)) return status("AUTHORIZATION_DENIED");
        if (!hasLiveAsset(task, grant, binding)) return status("AUTHORIZATION_DENIED");
        if (optional && (input.has("operation_id") || input.has("part_index"))) return deliverPart(task, id, input);
        if (optional && input.has("evidence")) input = normalizeArguments(input);
        if (!optional && !admitAttempt(task, STATE + ":attempts", input)) return status("QUOTA_DENIED");
        if (input == null || !input.isObject() || input.size() != (optional ? 6 : 4)
                || optional && (!boundedText(input.path("data_gap")) || !boundedText(input.path("reason"))) || !PROTOCOL.equals(input.path("protocol_version").asText())
                || !input.path("arguments").isObject() || !input.path("unresolved_parameters").isArray())
            return status("PARAMETER_INVALID");
        var protocol = json.convertValue(input, new TypeReference<Map<String, Object>>() {});
        var template = json.convertValue(schema, new TypeReference<Map<String, Object>>() {});
        TemplateInvocationBridge.BridgeResult compiled;
        try {
            compiled = bridge.prepare(new TemplateInvocationBridge.BridgeRequest(
                binding.path("route").asText(), null, id, template, Map.of(), protocol, true, false,
                new TemplateInvocationBridge.EvidenceContext(question, Map.of())));
        } catch (TemplateInvocationBridge.TemplateBridgeException invalid) {
            return Map.of("status", "PARAMETER_INVALID", "errorCode", invalid.code(),
                "message", invalid.getMessage(), "errors", invalid.validationErrors());
        }
        var rejected = compiled.protocolTrace().get("unverifiedSubmittedFields");
        if (rejected instanceof Collection<?> fields && !fields.isEmpty())
            return Map.of("status", "PARAMETER_INVALID", "errorCode", "UNVERIFIED_PARAMETERS", "fields", fields);
        var parameters = compiled.parameters();
        if (!grant.parameterValues().keySet().equals(parameters.keySet())
                || parameters.entrySet().stream().anyMatch(entry ->
                    !grant.parameterValues().get(entry.getKey()).contains(String.valueOf(entry.getValue()))))
            return status("AUTHORIZATION_DENIED");

        String operation = optional ? optionalOperation(id, parameters) : OPERATION;
        String digest = supply.hash(parameters);
        if (optional && !binding.has("templateKey")) {
            String legacyOperation = optionalOperation(id);
            var legacy = tasks.privateState(task.scope(), task.taskId(), STATE + ":" + legacyOperation);
            if (legacy != null) {
                // A legacy task has no canonical parameter identity in its dispatch intent.
                // Reuse the exact previous request or require reconciliation of its outcome.
                if (!supply.hash(input).equals(legacy.path("requestHash").asText()))
                    return status("QUERY_OUTCOME_UNKNOWN");
                operation = legacyOperation;
                digest = legacy.path("requestHash").asText();
            }
        }
        return dispatch(task, input, optional, binding, grant, compiled, parameters, question, operation, digest);
    }

    private Map<String, Object> dispatch(CodingTaskContract task, JsonNode input, boolean optional, JsonNode binding,
            CodingDataSupplyProperties.Grant grant, TemplateInvocationBridge.BridgeResult compiled,
            Map<String, Object> parameters, String question, String operation, String digest) {
        String stateKey = optional ? STATE + ":" + operation : STATE;
        String id = grant.templateId();
        var context = context(task.scope(), grant, question, parameters, binding.path("route").asText(), operation)
            .withAttribute(RegisteredToolAnalysisOperator.CODING_DATA_OPERATION, "data-" + supply.hash(
                optional ? List.of(task.taskId(), operation) : task.taskId()));
        context = context.withAttribute(RegisteredToolAnalysisOperator.CODING_DATA_PUBLICATION,
            Map.of("releaseId", binding.path("releaseId").asText(), "revision", binding.path("revision").asText()));
        if (task.budget().hasDeadline()) {
            if (!Instant.now().isBefore(task.budget().deadlineAt())) return status("STOPPING");
            context = context.withAttribute(RegisteredToolAnalysisOperator.CODING_DATA_DEADLINE,
                task.budget().deadlineAt().toEpochMilli());
        }
        if (optional) context = context.withAttribute(RegisteredToolAnalysisOperator.CODING_DATA_EVIDENCE_LIMIT,
            Math.min(MAX_OPTIONAL_DATA_BYTES, task.budget().maxOutputBytes()));
        var lease = tasks.ownership(task.scope(), task.taskId());
        var started = json.valueToTree(Map.of("requestHash", digest));
        var admission = evidence.atomic(task, () -> admitQuery(task, lease, digest, started, operation, stateKey, optional));
        if (admission == Admission.EXISTING) {
            var previous = tasks.privateState(task.scope(), task.taskId(), stateKey);
            if (!digest.equals(previous.path("requestHash").asText())) return status("QUOTA_DENIED");
            if (previous.has("receipt")) return replay(task, grant, binding, json.convertValue(previous.path("receipt"),
                new TypeReference<Map<String,Object>>() {}));
            var reference = evidence.reference(task, "CODING_DATA_RECEIPT", operation);
            var recovered = evidence.payload(task, reference);
            if (recovered.isEmpty()) return status("QUERY_OUTCOME_UNKNOWN");
            reconcile(task, operation);
            var receipt = new LinkedHashMap<>(recovered.get());
            receipt.put("evidenceRef", reference);
            return replay(task, grant, binding, receipt);
        }
        if (admission != Admission.STARTED) return status(admission.name());
        long clock = System.nanoTime();
        var result = tools.executePublishedReadOnlyTemplate(context, analysisScope(context));
        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("templateId", id);
        receipt.put("queryMs", (System.nanoTime() - clock) / 1_000_000);
        receipt.put("parameterBindings", parameters);
        if (optional) {
            receipt.put("dataGap", input.path("data_gap").asText());
            receipt.put("reason", input.path("reason").asText());
        }
        receipt.put("executionPath", "governed_template_without_runtime_binding");
        receipt.put("defaultedFields", compiled.parameterEvidence().entrySet().stream()
            .filter(entry -> TemplateInvocationBridge.TEMPLATE_DEFAULT_SOURCE.equals(entry.getValue().source()))
            .map(Map.Entry::getKey).sorted().toList());
        receipt.put("status", "DATA_LIMIT_EXCEEDED".equals(result.outputs().get("status"))
            ? "DATA_LIMIT_EXCEEDED" : result.evidence().size() == 1 ? "QUERY_FAILED" : "QUERY_OUTCOME_UNKNOWN");
        if (tasks.get(task.scope(), task.taskId()).executionControl().stopReason() != null)
            receipt.put("status", "STOPPING");
        else if (result.evidence().size() == 1) {
            try {
                var fact = result.evidence().get(0);
                String key = binding.path("templateKey").asText("sql_datasource:" + id);
                var data = key.startsWith("sql_datasource:")
                    ? sqlData(json, fact.content(), !optional) : structuredData(json, fact.content(), grant.requiredFields());
                receipt.put("completenessStatus", data.path("completenessStatus").asText(
                    data.path("complete").asBoolean() ? "COMPLETE" : incomplete(data) ? "PARTIAL" : "UNKNOWN"));
                receipt.putAll(publish(task, grant, data, optional ? operation : digest,
                    optional, key.startsWith("sql_datasource:")));
                receipt.put("sourceEvidenceId", fact.evidenceId());
            } catch (IOException | IllegalArgumentException failed) {
                log.warn("Published template could not deliver governed rows task={} errorType={} error={}",
                    task.taskId(), failed.getClass().getSimpleName(),
                    failed instanceof IllegalArgumentException ? failed.getMessage() : "Template result or Parquet I/O failed");
                receipt.put("status", "DATA_DELIVERY_FAILED");
            }
        }
        receipt.put("taskId", task.taskId());
        receipt.put("operationId", operation);
        // A successful response proves the remote call returned. Transport failure cannot prove it stopped.
        receipt.put("executorStopped", result.evidence().size() == 1 || Boolean.TRUE.equals(result.outputs().get("executorStopped")));
        boolean stopped = Boolean.TRUE.equals(receipt.get("executorStopped"));
        var ref = evidence.register(task, stopped ? "CODING_DATA_RECEIPT" : "NATIVE_TEMPLATE_QUERY_UNCONFIRMED",
            operation, receipt,
            List.of(task.inputSnapshotRef()));
        receipt.put("evidenceRef", ref);
        if (stopped) reconcile(task, operation);
        if (tasks.get(task.scope(), task.taskId()).executionControl().stopReason() != null) return status("STOPPING");
        tasks.privateState(task.scope(), task.taskId(), lease, stateKey, started,
            json.valueToTree(Map.of("requestHash", digest, "receipt", receipt)));
        return deliver(task, receipt);
    }

    private static LocalDate referenceDate(CodingTaskContract task, ZoneId zone) {
        if (!(task.runtimeContext().get("capturedAt") instanceof String capturedAt)) return null;
        try {
            return Instant.parse(capturedAt).atZone(zone).toLocalDate();
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    private Admission admitQuery(CodingTaskContract task, CodingJournal.Lease lease, String digest, JsonNode started,
                                 String operation, String stateKey, boolean optional) {
        var scope = task.scope();
        String id = task.taskId();
        var state = tasks.get(scope, id);
        if (state.status().terminal() || state.executionControl().stopReason() != null) return Admission.STOPPING;
        if (tasks.privateState(scope, id, stateKey) != null) return Admission.EXISTING;
        if (optional && tasks.startedDataRounds(scope, id) >= policy.getMaxRounds()) return Admission.QUOTA_DENIED;
        var reservation = tasks.reserve(scope, id, lease, state.revision(), operation,
            digest, CodingJournal.OperationKind.TOOL, QUERY_USAGE);
        if (!reservation.admitted()) return Admission.QUOTA_DENIED;
        var begin = tasks.startIntent(scope, id, lease, reservation.outcome().revision(),
            operation, "runtime-data:" + operation);
        if (!begin.dispatchIntentCreated()) return Admission.STOPPING;
        tasks.privateState(scope, id, lease, stateKey, null, started);
        return Admission.STARTED;
    }

    public Map<String, Object> supplementMetrics(CodingTaskContract task) {
        var binding = json.valueToTree(task.runtimeContext().get(CONTEXT)).path("optionalBindings");
        if (!binding.isObject()) return Map.of();
        long successes = 0, producedRows = 0, snapshotBytes = 0;
        for (String operation : tasks.startedDataOperations(task.scope(), task.taskId())) {
            if (!operation.startsWith(OPERATION + "-")) continue;
            var receipt = evidence.payload(task, evidence.reference(task, "CODING_DATA_RECEIPT", operation));
            if (receipt.isEmpty() || !"OK".equals(receipt.get().get("status"))) continue;
            successes++;
            if (receipt.get().get("rowCount") instanceof Number rows) producedRows += rows.longValue();
            if (receipt.get().get("size") instanceof Number bytes) snapshotBytes += bytes.longValue();
        }
        long requests = tasks.startedDataOperations(task.scope(), task.taskId()).stream()
            .filter(operation -> operation.startsWith(OPERATION + "-")).count();
        return Map.of("supplementRequestCount", requests, "optionalToolInvocationCount", requests,
            "supplementSuccessCount", successes, "supplementSnapshotRows", producedRows,
            "supplementSnapshotBytes", snapshotBytes);
    }

    private String optionalOperation(String templateId) {
        return OPERATION + "-" + supply.hash(templateId);
    }

    private String optionalOperation(String templateId, Map<String, Object> parameters) {
        return OPERATION + "-" + supply.hash(List.of(templateId, parameters));
    }

    static JsonNode sqlData(ObjectMapper json, String content) throws IOException {
        return sqlData(json, content, true);
    }

    private static JsonNode sqlData(ObjectMapper json, String content, boolean legacyBound) throws IOException {
        var envelope = json.readTree(content);
        boolean incomplete = incomplete(envelope);
        var data = json.<JsonNode>valueToTree(McpAnalysisPayload.canonicalData(json.convertValue(envelope, Object.class)));
        // A published template keeps the ToolOutput envelope around the SQL result.
        for (int depth = 0; depth < 4 && !data.path("rows").isArray(); depth++) {
            incomplete |= incomplete(data);
            if (data.has("data")) data = data.path("data");
            else if (data.has("structuredContent")) data = data.path("structuredContent");
            else break;
        }
        if (!data.path("rows").isArray() || legacyBound && data.path("rows").size() > 100
                || data.path("rowCount").asInt(-1) != data.path("rows").size())
            throw new IllegalArgumentException("Published query returned no bounded SQL rows");
        if (incomplete || incomplete(data)) {
            var limited = ((com.fasterxml.jackson.databind.node.ObjectNode) data).deepCopy();
            limited.put("complete", false);
            return limited;
        }
        return data;
    }

    static JsonNode structuredData(ObjectMapper json, String content, List<String> grantedFields) throws IOException {
        var value = json.readTree(content);
        boolean knownPartial = false;
        boolean responseBody = false;
        // Traverse declared result envelopes only. Unknown business shapes and raw transport data stay outside.
        for (int depth = 0; depth < 8; depth++) {
            knownPartial |= incomplete(value) || value.path("errorCount").asInt() > 0
                || value.path("outputLimits").has("fullAggregateAvailable")
                    && !value.path("outputLimits").path("fullAggregateAvailable").asBoolean()
                || value.path("commandSuccess").isBoolean() && !value.path("commandSuccess").asBoolean();
            responseBody |= Set.of("api_response.v1", "http_response.v1").contains(value.path("dataSchema").asText());
            if (responseBody && value.has("body") && value.has("bodyCompleteness")) {
                knownPartial |= value.path("bodyCompleteness").path("gatewayTruncated").asBoolean();
                value = value.path("body");
                continue;
            }
            if (value.has("rows") || value.has("records") || value.has("metrics") || value.isArray()
                    || value.isObject() && !value.has("data") && !value.has("structuredContent")
                        && grantedFields.stream().anyMatch(value::has)) {
                var rows = value.isArray() ? value : value.has("rows") ? value.path("rows")
                    : value.has("records") ? value.path("records")
                    : value.has("metrics") ? value.path("metrics") : json.createArrayNode().add(value);
                if (!rows.isArray() || java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                        .anyMatch(row -> !row.isObject()))
                    throw new IllegalArgumentException("Published result is not a list of records");
                var declared = value.has("metricCount") ? value.path("metricCount") : value.path("rowCount");
                boolean countMismatch = !declared.isMissingNode() && declared.asInt(-1) != rows.size();
                boolean complete = !knownPartial && !countMismatch && (value.path("complete").asBoolean(false)
                    || value.path("outputLimits").path("fullAggregateAvailable").asBoolean(false)
                        && value.path("commandSuccess").asBoolean(false));
                return json.createObjectNode().put("rowCount", rows.size()).put("complete", complete)
                    .put("completenessStatus", complete ? "COMPLETE" : knownPartial || countMismatch ? "PARTIAL" : "UNKNOWN")
                    .set("rows", rows);
            }
            if (value.has("data")) value = value.path("data");
            else if (value.has("structuredContent")) value = value.path("structuredContent");
            else break;
        }
        throw new IllegalArgumentException("Published result contains no governed records");
    }

    private static boolean incomplete(JsonNode data) {
        var declared = data.path("complete");
        var completeness = data.path("completeness").path("complete");
        return Set.of("PARTIAL", "PREVIEW", "UNKNOWN", "INCOMPLETE").contains(data.path("status").asText())
            || data.path("preview").asBoolean() || data.path("possiblyTruncated").asBoolean()
            || data.path("truncated").asBoolean()
            || data.path("hasMore").asBoolean() || data.path("pagination").path("hasMore").asBoolean()
            || declared.isBoolean() && !declared.asBoolean()
            || completeness.isBoolean() && !completeness.asBoolean();
    }

    public static boolean isOperation(String operation) {
        return OPERATION.equals(operation) || operation.matches(OPERATION + "-[0-9a-f]{64}");
    }

    private static boolean boundedText(JsonNode field) {
        return field.isTextual() && !field.asText().isBlank() && field.asText().length() <= 1000;
    }

    public void reconcile(CodingTaskContract task, String operation) {
        var reference = evidence.reference(task,"CODING_DATA_RECEIPT",operation);
        if (!evidence.exists(task, reference)) return;
        evidence.atomic(task, () -> {
            var lease = tasks.ownership(task.scope(), task.taskId());
            var state = tasks.get(task.scope(), task.taskId());
            if (tasks.verifiedOperation(task.scope(), task.taskId(), operation).isEmpty())
                state = tasks.reconcileStopped(task.scope(), task.taskId(), lease, state.revision(), operation, reference);
            return tasks.settle(task.scope(), task.taskId(), lease, state.revision(), operation, QUERY_USAGE, true);
        });
    }

    private boolean admitAttempt(CodingTaskContract task, String key, JsonNode input) {
        return evidence.atomic(task, () -> recordAttempt(task, key, supply.hash(input)));
    }

    private boolean recordAttempt(CodingTaskContract task, String key, String hash) {
        var previous = tasks.privateState(task.scope(), task.taskId(), key);
        List<String> attempts = previous == null ? List.of() : json.convertValue(previous, new TypeReference<>() {});
        if (attempts.contains(hash)) return true;
        if (attempts.size() >= 2) return false;
        var next = new ArrayList<>(attempts);
        next.add(hash);
        tasks.privateState(task.scope(), task.taskId(), tasks.ownership(task.scope(), task.taskId()),
            key, previous, json.valueToTree(next));
        return true;
    }

    private Map<String, Object> publish(CodingTaskContract task, CodingDataSupplyProperties.Grant grant,
            JsonNode data, String digest, boolean optional, boolean sql) throws IOException {
        var rows = new ArrayList<JsonNode>();
        var fields = new TreeMap<String, CodingInputFilePublisher.Field>();
        for (String name : grant.requiredFields()) fields.put(name, new CodingInputFilePublisher.Field());
        for (var raw : data.path("rows")) {
            var row = json.createObjectNode();
            for (var entry : fields.entrySet()) if (raw.has(entry.getKey())) {
                var value = raw.get(entry.getKey());
                entry.getValue().observe(json.convertValue(value, Object.class));
                row.set(entry.getKey(), value);
            }
            rows.add(row);
        }
        if (fields.isEmpty() || !rows.isEmpty() && fields.values().stream().anyMatch(field -> field.present == 0))
            throw new IllegalArgumentException("Granted fields absent from query result");
        long optionalBudget = optional ? Math.min(MAX_OPTIONAL_DATA_BYTES, task.budget().maxOutputBytes()) : 0;
        if (optional && (!sql || rows.size() > 100
                || json.writeValueAsBytes(rows).length > Math.min(PART_BYTES, optionalBudget)))
            return publishParts(task, data, rows, digest);
        var columns = new ArrayList<CodingInputManifest.Column>();
        fields.forEach((name, field) -> columns.add(field.column(name, "c" + columns.size(), rows.size())));
        var directory = Files.createTempDirectory("native-template-");
        var path = directory.resolve("result.parquet");
        try {
            CodingTableCodec.write(path, CodingInputManifest.Format.PARQUET, columns, rows, json);
            if (Files.size(path) > 1024 * 1024 || optional && Files.size(path) > optionalBudget)
                return optional ? publishParts(task, data, rows, digest) : status("QUOTA_DENIED");
            var asset = evidence.assets.store(UserAssetService.Owner.of(task.scope()), UserAssetService.Kind.DATASET,
                task.taskId(), task.scope().runId(), STATE + ":" + digest, "result.parquet",
                "application/vnd.apache.parquet", Files.readAllBytes(path));
            var columnDefinitions = json.convertValue(columns, new TypeReference<List<Map<String,Object>>>() {});
            return Map.of("status", "OK", "assetId", asset.id(), "sha256", asset.sha256(),
                "rowCount", rows.size(), "size", Files.size(path), "columns", columnDefinitions,
                "complete", data.path("complete").asBoolean(), "content_type", "application/vnd.apache.parquet");
        } finally {
            Files.deleteIfExists(path);
            Files.deleteIfExists(directory);
        }
    }

    private Map<String, Object> publishParts(CodingTaskContract task, JsonNode data, List<JsonNode> rows,
                                             String digest) throws IOException {
        var parts = new ArrayList<byte[]>();
        var part = new java.io.ByteArrayOutputStream();
        long budget = Math.min(MAX_OPTIONAL_DATA_BYTES, task.budget().maxOutputBytes());
        long size = 0;
        for (var row : rows) {
            byte[] value = json.writeValueAsBytes(row);
            size += value.length + 1L;
            if (value.length >= PART_BYTES || size > budget) return status("DATA_LIMIT_EXCEEDED");
            if (part.size() + value.length + 1 > PART_BYTES) {
                parts.add(part.toByteArray());
                part.reset();
            }
            part.write(value);
            part.write('\n');
        }
        if (part.size() > 0) parts.add(part.toByteArray());
        var descriptors = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < parts.size(); index++) {
            if (stopping(task)) return status("STOPPING");
            String file = "part-%05d.jsonl".formatted(index);
            var asset = evidence.assets.store(UserAssetService.Owner.of(task.scope()), UserAssetService.Kind.DATASET,
                task.taskId(), task.scope().runId(), STATE + ":" + digest + ":" + index, file,
                "application/x-ndjson", parts.get(index));
            descriptors.add(Map.of("part_index", index, "assetId", asset.id(),
                "sha256", asset.sha256(), "size", parts.get(index).length));
        }
        return Map.of("status", "OK", "delivery", "PAGED_JSONL", "parts", List.copyOf(descriptors),
            "rowCount", rows.size(), "size", size, "complete", data.path("complete").asBoolean(),
            "content_type", "application/x-ndjson");
    }

    private JsonNode normalizeArguments(JsonNode input) {
        if (!input.isObject() || input.size() != 5 || !input.path("arguments").isObject()
                || !input.path("evidence").isObject()) return null;
        var arguments = input.path("arguments");
        var proofs = input.path("evidence");
        if (arguments.size() != proofs.size()) return null;
        var normalized = json.createObjectNode();
        for (var field : List.of("template_id", "data_gap", "reason")) {
            if (!input.has(field)) return null;
            normalized.set(field, input.get(field));
        }
        normalized.put("protocol_version", PROTOCOL);
        normalized.putArray("unresolved_parameters");
        var values = normalized.putObject("arguments");
        var fields = arguments.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!proofs.path(field.getKey()).isObject()) return null;
            var argument = values.putObject(field.getKey()).put("source", "user_query");
            argument.set("value", field.getValue());
            argument.set("evidence", proofs.get(field.getKey()));
        }
        return normalized;
    }

    private Map<String, Object> deliverPart(CodingTaskContract task, String id, JsonNode input) {
        String operation = input.path("operation_id").asText();
        var indexNode = input.path("part_index");
        if (input.size() != 3 || !operation.startsWith(OPERATION + "-") || !isOperation(operation)
                || !indexNode.isIntegralNumber() || !indexNode.canConvertToInt() || indexNode.asInt() < 0)
            return status("PARAMETER_INVALID");
        if (stopping(task)) return status("STOPPING");
        var ref = evidence.reference(task, "CODING_DATA_RECEIPT", operation);
        var receipt = json.valueToTree(evidence.payload(task, ref).orElse(Map.of()));
        if (!id.equals(receipt.path("templateId").asText()) || !"OK".equals(receipt.path("status").asText()))
            return status("AUTHORIZATION_DENIED");
        var part = receipt.path("parts").path(indexNode.asInt());
        if (!part.isObject()) return status("PARAMETER_INVALID");
        byte[] bytes = evidence.assets.readTask(task.scope(), task.taskId(), part.path("assetId").asText());
        if (part.path("size").asLong(-1) != bytes.length
                || !UserAssetService.hash(bytes).equals(part.path("sha256").asText()))
            return status("DATA_DELIVERY_FAILED");
        if (stopping(task)) return status("STOPPING");
        return Map.of("status", "OK", "operation_id", operation, "part_index", indexNode.asInt(),
            "sha256", part.path("sha256").asText(), "content_type", "application/x-ndjson",
            "content", Base64.getEncoder().encodeToString(bytes));
    }

    private boolean stopping(CodingTaskContract task) {
        var outcome = tasks.get(task.scope(), task.taskId());
        return outcome.status().terminal() || outcome.executionControl().stopReason() != null
            || task.budget().hasDeadline() && !Instant.now().isBefore(task.budget().deadlineAt());
    }

    private Map<String, Object> replay(CodingTaskContract task, CodingDataSupplyProperties.Grant grant,
            JsonNode binding, Map<String, Object> receipt) {
        if ("OK".equals(receipt.get("status")) && !hasLiveAsset(task, grant, binding))
            return status("AUTHORIZATION_DENIED");
        return deliver(task, receipt);
    }

    private boolean hasLiveAsset(CodingTaskContract task, CodingDataSupplyProperties.Grant grant, JsonNode binding) {
        var current = callerCatalog("templates", task.scope());
        String key = binding.path("templateKey").asText("sql_datasource:" + grant.templateId());
        return java.util.stream.StreamSupport.stream(current.spliterator(), false)
            .anyMatch(row -> key.equals(row.path("templateKey").asText())
                && grant.assetName().equals(row.path("assetName").asText())
                && binding.path("revision").equals(row.path("revision"))
                && (binding.path("boundKey").asText().isBlank() || binding.path("boundKey").equals(row.path("key")))
                && "read".equals(row.path("operationLevel").asText()));
    }

    private JsonNode callerCatalog(String catalog, KernelDataScope scope) {
        return publications.request("GET", "/api/v1/template-capabilities/" + catalog + "?tenantId="
            + encode(scope.tenantId()) + "&actorId=" + encode(scope.userId()), null);
    }

    private Map<String, Object> deliver(CodingTaskContract task, Map<String, Object> receipt) {
        if (stopping(task)) return status("STOPPING");
        if (!"OK".equals(receipt.get("status"))) return receipt;
        var result = new LinkedHashMap<>(receipt);
        result.remove("parameterBindings");
        if ("PAGED_JSONL".equals(receipt.get("delivery"))) {
            var parts = json.valueToTree(receipt.get("parts"));
            result.put("parts", java.util.stream.StreamSupport.stream(parts.spliterator(), false)
                .map(part -> Map.of("part_index", part.path("part_index").asInt(),
                    "sha256", part.path("sha256").asText(), "size", part.path("size").asLong())).toList());
            return result;
        }
        result.put("content", Base64.getEncoder().encodeToString(
            evidence.assets.readTask(task.scope(), task.taskId(), receipt.get("assetId").toString())));
        return result;
    }

    private CodingDataSupplyProperties.Grant grant(KernelDataScope scope, String skill, String id) {
        if (!policy.isClineTemplateInvocationEnabled()) throw new SecurityException("Native template pilot disabled");
        var grants = supply.grants(scope).stream()
            .filter(grant -> grant.templateId().equals(id) && grant.skillId().equals(skill)).toList();
        if (grants.size() != 1) throw new SecurityException("Published template outside the task's grant");
        return grants.get(0);
    }

    private AnalysisContext context(KernelDataScope scope, CodingDataSupplyProperties.Grant grant,
            String query, Map<String, Object> parameters, String route, String operation) {
        var roles = scopes.resolve(scope.tenantId(), scope.userId(), grant.skillId(), List.of(), List.of());
        if (!roles.skillAllowed()) throw new SecurityException("Data Skill permission revoked");
        var traceScope = new KernelDataScope(scope.tenantId(), scope.userId(),
            STATE + "-" + supply.hash(List.of(scope.runId(), grant.templateId(), operation)),
            scope.conversationId(), scope.runId(), scope.environment(), scope.attributes());
        return new AnalysisContext(query, traceScope, grant.skillId(), List.of(), List.of(), roles.roles(), null, Map.of(
            RegisteredToolAnalysisOperator.TOOL_NAME, route,
            RegisteredToolAnalysisOperator.TOOL_ARGUMENTS, parameters));
    }

    private AnalysisScope analysisScope(AnalysisContext context) {
        return new AnalysisScope(context.kernelScope().tenantId(), context.kernelScope().userId(),
            context.roles(), List.of(), Map.of());
    }

    private static Map<String, Object> status(String status) {
        return Map.of("status", status);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
