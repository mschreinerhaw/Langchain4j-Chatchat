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
            .filter(row -> "RUNNING".equals(row.path("state").asText()) && row.path("verifiedReadOnlySql").asBoolean()
                && ("sql_datasource:" + id).equals(row.path("templateKey").asText())
                && grant.assetName().equals(row.path("assetName").asText())).toList();
        if (rows.size() != 1) throw new SecurityException("Expected one authorized running SQL publication");
        var row = rows.get(0);
        String tool = row.path("toolName").asText();
        if (!templateTools.allowed(scope.tenantId(), scope.userId(), skill, Set.of(tool)).contains(tool))
            throw new SecurityException("Published tool denied by the current Agent resource policy");
        var route = tools.resolveBoundPublishedTool(skill, tool)
            .orElseThrow(() -> new SecurityException("No matching Skill-bound published SQL template route"));
        var schema = row.path("parameterSchema");
        if (!schema.isObject() || schema.toString().length() > 7000)
            throw new IllegalStateException("Invalid published template descriptor");
        return Map.of("skillId", skill, "grantHash", supply.hash(grant), "route", route,
            "releaseId", row.path("id").asText(), "revision", row.path("revision").asText(), "template",
            Map.of("templateId", id, "description", row.path("title").asText(), "parameterSchema",
                json.convertValue(schema, new TypeReference<Map<String, Object>>() {})));
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
        return Map.of("name", TOOL, "description", "Query a task-authorized published read-only template. "
            + "Extract and normalize the requested values using the template parameter schema. Cite the exact "
            + "source phrase from the original user request in evidence.quote. Set evidence.interpretation=model "
            + "when the submitted value had to be interpreted or normalized. Omit missing values and list their "
            + "fields in unresolved_parameters. Leave dates unresolved when the user provides no time expression. "
            + "Runtime applies the published template defaults."
            + temporalContext + " Correct invalid fields once. Stop on authorization, quota or query errors. "
            + "After success inspect defaultedFields and disclose any default scope in the report. "
            + "When complete is false, the delivered rows cover only part of the source. "
            + templates, "inputSchema", Map.of("type", "object", "additionalProperties", false,
                "required", List.copyOf(fields.keySet()), "properties", fields));
    }

    public Map<String, Object> execute(CodingTaskContract task, JsonNode input) {
        var taskContext = json.valueToTree(task.runtimeContext().get(CONTEXT));
        boolean optional = taskContext.path("optionalBindings").isObject();
        String requestedId = input.path("template_id").asText();
        var binding = optional ? taskContext.path("optionalBindings").path(requestedId) : taskContext;
        var schema = binding.path("template");
        if (!schema.isObject()) return status("AUTHORIZATION_DENIED");
        String operation = optional ? optionalOperation(requestedId) : OPERATION;
        String stateKey = optional ? STATE + ":" + operation : STATE;
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
        if (!json.valueToTree(current).equals(binding)) return status("AUTHORIZATION_DENIED");
        if (!admitAttempt(task, stateKey + ":attempts", input)) return status("QUOTA_DENIED");
        if (!input.isObject() || input.size() != (optional ? 6 : 4)
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

        String digest = supply.hash(optional ? input : parameters);
        var context = context(task.scope(), grant, question, parameters, binding.path("route").asText())
            .withAttribute(RegisteredToolAnalysisOperator.CODING_DATA_OPERATION, "data-" + supply.hash(
                optional ? List.of(task.taskId(), operation) : task.taskId()));
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
        var result = tools.executePublishedReadOnlySql(context, analysisScope(context));
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
        receipt.put("status", "QUERY_FAILED");
        if (tasks.get(task.scope(), task.taskId()).executionControl().stopReason() != null)
            receipt.put("status", "STOPPING");
        else if (result.evidence().size() == 1) {
            try {
                var fact = result.evidence().get(0);
                var data = sqlData(json, fact.content());
                receipt.putAll(publish(task, grant, data, digest));
                receipt.put("sourceEvidenceId", fact.evidenceId());
            } catch (IOException | IllegalArgumentException failed) {
                log.warn("Published template could not deliver governed SQL rows task={} errorType={} error={}",
                    task.taskId(), failed.getClass().getSimpleName(),
                    failed instanceof IllegalArgumentException ? failed.getMessage() : "SQL result or Parquet I/O failed");
                receipt.put("status", "DATA_DELIVERY_FAILED");
            }
        }
        receipt.put("taskId", task.taskId());
        receipt.put("operationId", operation);
        // A successful response proves the remote call returned. Transport failure cannot prove it stopped.
        receipt.put("executorStopped", result.evidence().size() == 1);
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
        if (tasks.privateState(scope, id, stateKey) != null) return Admission.EXISTING;
        var state = tasks.get(scope, id);
        if (state.status().terminal() || state.executionControl().stopReason() != null) return Admission.STOPPING;
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
        var ids = binding.fieldNames();
        while (ids.hasNext()) {
            String operation = optionalOperation(ids.next());
            var receipt = evidence.payload(task, evidence.reference(task, "CODING_DATA_RECEIPT", operation));
            if (receipt.isEmpty() || !"OK".equals(receipt.get().get("status"))) continue;
            successes++;
            if (receipt.get().get("rowCount") instanceof Number rows) producedRows += rows.longValue();
            if (receipt.get().get("size") instanceof Number bytes) snapshotBytes += bytes.longValue();
        }
        long requests = tasks.startedDataRounds(task.scope(), task.taskId());
        return Map.of("supplementRequestCount", requests, "optionalToolInvocationCount", requests,
            "supplementSuccessCount", successes, "supplementSnapshotRows", producedRows,
            "supplementSnapshotBytes", snapshotBytes);
    }

    private String optionalOperation(String templateId) {
        return OPERATION + "-" + supply.hash(templateId);
    }

    static JsonNode sqlData(ObjectMapper json, String content) throws IOException {
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
        if (!data.path("rows").isArray() || data.path("rows").size() > 100
                || data.path("rowCount").asInt(-1) != data.path("rows").size())
            throw new IllegalArgumentException("Published query returned no bounded SQL rows");
        if (incomplete || incomplete(data)) {
            var limited = ((com.fasterxml.jackson.databind.node.ObjectNode) data).deepCopy();
            limited.put("complete", false);
            return limited;
        }
        return data;
    }

    private static boolean incomplete(JsonNode data) {
        var declared = data.path("complete");
        var completeness = data.path("completeness").path("complete");
        return Set.of("PARTIAL", "PREVIEW").contains(data.path("status").asText())
            || data.path("preview").asBoolean() || data.path("possiblyTruncated").asBoolean()
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
            JsonNode data, String digest) throws IOException {
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
        var columns = new ArrayList<CodingInputManifest.Column>();
        fields.forEach((name, field) -> columns.add(field.column(name, "c" + columns.size(), rows.size())));
        var directory = Files.createTempDirectory("native-template-");
        var path = directory.resolve("result.parquet");
        try {
            CodingTableCodec.write(path, CodingInputManifest.Format.PARQUET, columns, rows, json);
            if (Files.size(path) > 1024 * 1024) return status("QUOTA_DENIED");
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

    private Map<String, Object> replay(CodingTaskContract task, CodingDataSupplyProperties.Grant grant,
            JsonNode binding, Map<String, Object> receipt) {
        if ("OK".equals(receipt.get("status"))) {
            // A cached receipt cannot rely on the authorization applied by the
            // original SQL execution. The caller's live catalog applies the
            // same template and datasource ACL used by the MCP executor.
            var current = callerCatalog("templates", task.scope());
            boolean allowed = java.util.stream.StreamSupport.stream(current.spliterator(), false)
                .anyMatch(row -> ("sql_datasource:" + grant.templateId()).equals(row.path("templateKey").asText())
                    && grant.assetName().equals(row.path("assetName").asText())
                    && binding.path("revision").equals(row.path("revision"))
                    && "read".equals(row.path("operationLevel").asText()));
            if (!allowed) return status("AUTHORIZATION_DENIED");
        }
        return deliver(task, receipt);
    }

    private JsonNode callerCatalog(String catalog, KernelDataScope scope) {
        return publications.request("GET", "/api/v1/template-capabilities/" + catalog + "?tenantId="
            + encode(scope.tenantId()) + "&actorId=" + encode(scope.userId()), null);
    }

    private Map<String, Object> deliver(CodingTaskContract task, Map<String, Object> receipt) {
        if (!"OK".equals(receipt.get("status"))) return receipt;
        var result = new LinkedHashMap<>(receipt);
        result.remove("parameterBindings");
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
            String query, Map<String, Object> parameters, String route) {
        var roles = scopes.resolve(scope.tenantId(), scope.userId(), grant.skillId(), List.of(), List.of());
        if (!roles.skillAllowed()) throw new SecurityException("Data Skill permission revoked");
        var traceScope = new KernelDataScope(scope.tenantId(), scope.userId(),
            STATE + "-" + supply.hash(List.of(scope.runId(),grant.templateId())),
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
