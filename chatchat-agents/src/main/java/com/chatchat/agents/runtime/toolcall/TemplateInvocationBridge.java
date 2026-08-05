package com.chatchat.agents.runtime.toolcall;

import com.chatchat.agents.protocol.AgentProtocolCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical bridge between model-produced template arguments and Runtime-owned execution.
 *
 * <p>The model may analyze the user request and propose semantic argument values with evidence.
 * This bridge never lets it select execution metadata: Runtime supplies the selected template and
 * schema, then this class audits provenance, applies schema defaults/type conversion and produces
 * the concrete executor input.</p>
 */
public final class TemplateInvocationBridge {

    public static final String PROTOCOL_VERSION = AgentProtocolCatalog.TEMPLATE_PARAMETER;
    public static final String APPLIED_MARKER = "runtimeParameterProtocolApplied";
    public static final String USER_QUERY_SOURCE = "user_query";
    public static final String TOOL_RESULT_SOURCE = "tool_result";
    public static final String TEMPLATE_DEFAULT_SOURCE = "template_default";
    private static final Set<String> TOOL_RESULT_SOURCE_ALIASES = Set.of(
        TOOL_RESULT_SOURCE, "completed_step", "dependency_output"
    );

    private final ToolArgumentCompiler argumentCompiler;

    public TemplateInvocationBridge() {
        this(new ToolArgumentCompiler());
    }

    TemplateInvocationBridge(ToolArgumentCompiler argumentCompiler) {
        this.argumentCompiler = argumentCompiler == null ? new ToolArgumentCompiler() : argumentCompiler;
    }

    public BridgeResult prepare(BridgeRequest request) {
        if (request == null) {
            throw failure("TEMPLATE_ARGUMENT_CONTRACT_FAILED", "bridge request is required");
        }
        Map<String, Object> input = new LinkedHashMap<>(
            request.input() == null ? Map.of() : request.input());
        Map<String, Object> template = request.templateMetadata() == null
            ? Map.of() : request.templateMetadata();

        String runtimeTemplateId = canonicalTemplateId(request.runtimeTemplateId());
        if (runtimeTemplateId == null) {
            runtimeTemplateId = canonicalTemplateId(firstPresent(input,
                "templateId", "template", "template_id"));
        }
        String metadataTemplateId = canonicalTemplateId(firstPresent(template,
            "templateId", "template_id", "id", "code", "template"));
        if (runtimeTemplateId == null) {
            runtimeTemplateId = metadataTemplateId;
        }
        if (runtimeTemplateId == null) {
            throw failure("TEMPLATE_REQUIRED", "Runtime must bind a selected template before execution");
        }
        if (metadataTemplateId != null && !metadataTemplateId.equals(runtimeTemplateId)) {
            throw failure("TEMPLATE_ARGUMENT_CONTRACT_FAILED",
                "selected template metadata does not match Runtime template " + runtimeTemplateId);
        }

        input.put("templateId", runtimeTemplateId);
        input.put("template", runtimeTemplateId);
        removeReadOnlyTemplateMetadata(input);

        Map<String, Object> schema = objectMap(firstPresent(template,
            "parameterSchema", "parameter_schema", "inputSchema", "schema"));
        Map<String, Object> parameters = objectMap(input.get("parameters"));
        boolean protocolApplied = false;
        boolean runtimeEvidenceRecovered = false;
        ParameterAudit parameterAudit = ParameterAudit.empty();
        if (request.parameterProtocol() != null) {
            parameterAudit = auditModelProtocol(
                request.parameterProtocol(),
                request.stepId(),
                runtimeTemplateId,
                request.runtimeTemplateAuthoritative(),
                request.evidenceContext());
            // The audited protocol is authoritative. Never merge unreviewed model fields from
            // input.parameters with evidence-backed values.
            parameters.clear();
            parameters.putAll(parameterAudit.parameters());
            protocolApplied = true;
        } else if (!parameters.isEmpty()) {
            parameterAudit = auditRuntimeRecoverableParameters(
                runtimeTemplateId, parameters, schema, request.evidenceContext());
            parameters.clear();
            parameters.putAll(parameterAudit.parameters());
            runtimeEvidenceRecovered = !parameterAudit.evidence().isEmpty();
        }

        List<String> required = requiredParameters(template, schema);

        ToolArgumentCompiler.CompilationResult compilation = argumentCompiler.compile(parameters, schema);
        List<String> unresolvedWithoutDefault = parameterAudit.unresolved().stream()
            .filter(required::contains)
            .filter(name -> !hasValue(compilation.parameters().get(name)))
            .toList();
        if (!unresolvedWithoutDefault.isEmpty()) {
            throw failure("TEMPLATE_PARAMETER_PROTOCOL_INCOMPLETE",
                "unresolved parameters without template defaults " + unresolvedWithoutDefault
                    + "; rewrite the plan or request missing values instead of executing");
        }
        List<String> requiredWithoutValuesOrDefaults = required.stream()
            .filter(name -> !hasValue(compilation.parameters().get(name)))
            .toList();
        if (!protocolApplied && !requiredWithoutValuesOrDefaults.isEmpty()) {
            throw failure("TEMPLATE_PARAMETER_PROTOCOL_REQUIRED",
                "template " + runtimeTemplateId + " has required parameters without defaults "
                    + requiredWithoutValuesOrDefaults + "; the model must emit " + PROTOCOL_VERSION
                    + " with evidence-backed overrides");
        }
        if (!compilation.valid()) {
            throw new TemplateBridgeException(
                "INVALID_TOOL_ARGUMENTS",
                compilation.structuredError(request.executorTool(), runtimeTemplateId),
                compilation.validationErrors()
            );
        }
        List<String> missing = required.stream()
            .filter(name -> !hasValue(compilation.parameters().get(name)))
            .toList();
        if (!missing.isEmpty()) {
            List<ToolArgumentCompiler.ValidationError> errors = missing.stream()
                .map(name -> new ToolArgumentCompiler.ValidationError(
                    name,
                    "REQUIRED_PARAMETER_MISSING",
                    "Missing required parameter " + name,
                    parameters.get(name),
                    "value"
                ))
                .toList();
            throw new TemplateBridgeException(
                "INVALID_TOOL_ARGUMENTS",
                "INVALID_TOOL_ARGUMENTS: template " + runtimeTemplateId
                    + " is missing required parameters " + missing,
                errors
            );
        }
        input.put("parameters", new LinkedHashMap<>(compilation.parameters()));
        if (protocolApplied) {
            input.put(APPLIED_MARKER, true);
        } else {
            input.remove(APPLIED_MARKER);
        }
        Map<String, ParameterEvidence> compiledEvidence = new LinkedHashMap<>(parameterAudit.evidence());
        for (ToolArgumentCompiler.Repair repair : compilation.repairs()) {
            if ("DEFAULT_VALUE_APPLIED".equals(repair.repairCode())) {
                compiledEvidence.putIfAbsent(
                    repair.field(),
                    new ParameterEvidence(
                        TEMPLATE_DEFAULT_SOURCE,
                        Map.of("templateId", runtimeTemplateId, "schemaField", repair.field()),
                        repair.repairedValue()
                    )
                );
            }
        }
        Map<String, Object> protocolTrace = new LinkedHashMap<>(AgentProtocolCatalog.trace(
            request.stepId() == null ? "legacy_agent_template_bridge" : "interpretation_plan_template_bridge",
            runtimeTemplateId,
            request.executorTool(),
            protocolApplied
        ));
        protocolTrace.put("reviewedParameterCount", parameterAudit.evidence().size());
        protocolTrace.put("runtimeParameterEvidenceRecovered", runtimeEvidenceRecovered);
        protocolTrace.put("templateDefaultParameterCount",
            Math.max(0, compiledEvidence.size() - parameterAudit.evidence().size()));
        protocolTrace.put("parameterEvidenceSources", compiledEvidence.values().stream()
            .map(ParameterEvidence::source)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        return new BridgeResult(
            new LinkedHashMap<>(input),
            runtimeTemplateId,
            Map.copyOf(compilation.parameters()),
            protocolApplied,
            compilation.repairs(),
            Map.copyOf(compiledEvidence),
            Map.copyOf(protocolTrace)
        );
    }

    /**
     * Recovers provenance deterministically when the controller omitted the model protocol.
     * Values are accepted only when they are declared by the selected template and occur
     * verbatim in the Runtime-owned user query. A value equal to the declared schema default is
     * removed so the compiler reapplies the authoritative default instead of trusting the model.
     */
    private ParameterAudit auditRuntimeRecoverableParameters(String templateId,
                                                              Map<String, Object> proposed,
                                                              Map<String, Object> schema,
                                                              EvidenceContext context) {
        Map<String, Object> properties = objectMap(schema.get("properties"));
        Map<String, Object> recovered = new LinkedHashMap<>();
        Map<String, ParameterEvidence> evidence = new LinkedHashMap<>();
        List<String> denied = new ArrayList<>();
        String userQuery = context == null ? null : context.userQuery();
        for (Map.Entry<String, Object> entry : proposed.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            Map<String, Object> property = objectMap(properties.get(name));
            if (property.isEmpty() || !hasValue(value)) {
                denied.add(name);
                continue;
            }
            Object declaredDefault = firstPresent(property, "default", "defaultValue", "default_value");
            if (hasValue(declaredDefault) && equivalent(value, declaredDefault)) {
                continue;
            }
            String quote = userQueryEvidenceQuote(userQuery, value);
            if (quote == null) {
                denied.add(name);
                continue;
            }
            recovered.put(name, value);
            evidence.put(name, new ParameterEvidence(
                USER_QUERY_SOURCE,
                Map.of("quote", quote, "recoveredBy", "runtime_exact_match"),
                value
            ));
        }
        if (!denied.isEmpty()) {
            throw failure("TEMPLATE_PARAMETER_PROTOCOL_REQUIRED",
                "template " + templateId + " contains parameters " + denied
                    + " without per-parameter evidence; values are neither schema defaults "
                    + "nor exact Runtime user-query evidence");
        }
        return new ParameterAudit(Map.copyOf(recovered), Map.copyOf(evidence), List.of());
    }

    private String userQueryEvidenceQuote(String userQuery, Object value) {
        if (!hasText(userQuery) || !hasValue(value)) {
            return null;
        }
        String needle = String.valueOf(value).trim();
        if (compact(needle).length() < 2) {
            return null;
        }
        String lowerQuery = userQuery.toLowerCase(Locale.ROOT);
        int index = lowerQuery.indexOf(needle.toLowerCase(Locale.ROOT));
        if (index < 0) {
            return null;
        }
        int start = Math.max(0, index - 32);
        int end = Math.min(userQuery.length(), index + needle.length() + 32);
        return userQuery.substring(start, end);
    }

    @SuppressWarnings("unchecked")
    private ParameterAudit auditModelProtocol(Map<String, Object> rawProtocol,
                                              Integer expectedStepId,
                                              String runtimeTemplateId,
                                              boolean runtimeTemplateAuthoritative,
                                              EvidenceContext evidenceContext) {
        Map<String, Object> protocol = new LinkedHashMap<>(rawProtocol);
        String version = text(firstPresent(protocol, "protocol_version", "protocolVersion"));
        if (!AgentProtocolCatalog.ACCEPTED_TEMPLATE_PARAMETER_PROTOCOLS.contains(version)) {
            throw failure("TEMPLATE_PARAMETER_PROTOCOL_INVALID",
                "unsupported protocol version " + version);
        }
        if (expectedStepId != null) {
            Integer protocolStepId = integer(firstPresent(protocol, "step_id", "stepId"));
            if (!expectedStepId.equals(protocolStepId)) {
                throw failure("TEMPLATE_PARAMETER_PROTOCOL_INVALID",
                    "protocol step_id must match selected execution step " + expectedStepId);
            }
        }
        String proposedTemplateId = canonicalTemplateId(firstPresent(protocol,
            "template_id", "templateId"));
        if (proposedTemplateId == null
            || (!runtimeTemplateAuthoritative && !runtimeTemplateId.equals(proposedTemplateId))) {
            throw failure("TEMPLATE_PARAMETER_PROTOCOL_INVALID",
                "protocol template_id " + proposedTemplateId
                    + " does not match the Runtime-bound template " + runtimeTemplateId);
        }
        List<String> unresolved = strings(firstPresent(protocol,
            "unresolved_parameters", "unresolvedParameters"));
        Object argumentsValue = firstPresent(protocol, "arguments", "parameters");
        if (!(argumentsValue instanceof Map<?, ?> rawArguments)) {
            throw failure("TEMPLATE_PARAMETER_PROTOCOL_INVALID", "arguments must be an object");
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        Map<String, ParameterEvidence> evidenceByParameter = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawArguments.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> rawArgument)) {
                throw failure("TEMPLATE_PARAMETER_PROTOCOL_INVALID",
                    "argument " + name + " must contain value, source and evidence");
            }
            Map<String, Object> argument = new LinkedHashMap<>((Map<String, Object>) rawArgument);
            Object value = argument.get("value");
            String source = normalizeSource(argument.get("source"));
            Object evidence = argument.get("evidence");
            if (!hasValue(value) || source == null || evidence == null) {
                throw failure("TEMPLATE_PARAMETER_PROTOCOL_INVALID",
                    "argument " + name
                        + " must have a non-empty value, a supported source and evidence");
            }
            ParameterEvidence verifiedEvidence = verifyEvidence(name, value, source, evidence, evidenceContext);
            parameters.put(name, value);
            evidenceByParameter.put(name, verifiedEvidence);
        }
        return new ParameterAudit(Map.copyOf(parameters), Map.copyOf(evidenceByParameter), unresolved);
    }

    private ParameterEvidence verifyEvidence(String parameter,
                                             Object proposedValue,
                                             String source,
                                             Object rawEvidence,
                                             EvidenceContext context) {
        EvidenceContext evidenceContext = context == null ? EvidenceContext.empty() : context;
        if (USER_QUERY_SOURCE.equals(source)) {
            String quote = rawEvidence instanceof Map<?, ?> map
                ? text(firstPresent(objectMap(map), "quote", "text", "excerpt"))
                : text(rawEvidence);
            if (quote == null) {
                throw failure("TEMPLATE_PARAMETER_EVIDENCE_INVALID",
                    "argument " + parameter + " requires a user-query evidence quote");
            }
            if (!hasText(evidenceContext.userQuery())) {
                throw failure("TEMPLATE_PARAMETER_EVIDENCE_UNAVAILABLE",
                    "argument " + parameter + " cannot be verified without the Runtime user query");
            }
            if (!compact(evidenceContext.userQuery()).contains(compact(quote))) {
                throw failure("TEMPLATE_PARAMETER_EVIDENCE_MISMATCH",
                    "argument " + parameter + " evidence quote is absent from the Runtime user query");
            }
            if (!compact(quote).contains(compact(String.valueOf(proposedValue)))) {
                throw failure("TEMPLATE_PARAMETER_EVIDENCE_MISMATCH",
                    "argument " + parameter + " value is not present in its user-query evidence quote");
            }
            return new ParameterEvidence(USER_QUERY_SOURCE, Map.of("quote", quote), proposedValue);
        }
        if (TOOL_RESULT_SOURCE.equals(source)) {
            if (!(rawEvidence instanceof Map<?, ?> rawMap)) {
                throw failure("TEMPLATE_PARAMETER_EVIDENCE_INVALID",
                    "argument " + parameter + " tool-result evidence must contain step_id and output_path");
            }
            Map<String, Object> evidence = objectMap(rawMap);
            Integer stepId = integer(firstPresent(evidence, "step_id", "stepId"));
            String outputPath = text(firstPresent(evidence, "output_path", "outputPath", "path"));
            if (stepId == null || outputPath == null) {
                throw failure("TEMPLATE_PARAMETER_EVIDENCE_INVALID",
                    "argument " + parameter + " tool-result evidence must contain step_id and output_path");
            }
            if (!evidenceContext.completedStepOutputs().containsKey(stepId)) {
                throw failure("TEMPLATE_PARAMETER_EVIDENCE_UNAVAILABLE",
                    "argument " + parameter + " references unavailable completed step " + stepId);
            }
            Object verifiedValue = valueAtPath(evidenceContext.completedStepOutputs().get(stepId), outputPath);
            if (verifiedValue == null || !equivalent(proposedValue, verifiedValue)) {
                throw failure("TEMPLATE_PARAMETER_EVIDENCE_MISMATCH",
                    "argument " + parameter + " does not match completed step " + stepId
                        + " at " + outputPath);
            }
            return new ParameterEvidence(TOOL_RESULT_SOURCE, Map.of(
                "step_id", stepId,
                "output_path", outputPath
            ), verifiedValue);
        }
        throw failure("TEMPLATE_PARAMETER_SOURCE_DENIED",
            "argument " + parameter + " uses unsupported source " + source);
    }

    private Object valueAtPath(Object root, String rawPath) {
        if (root == null || rawPath == null || rawPath.isBlank()
            || "$".equals(rawPath.trim())) {
            return root;
        }
        String path = rawPath.trim();
        if (path.startsWith("$.")) {
            path = path.substring(2);
        } else if (path.startsWith("$")) {
            path = path.substring(1);
        }
        Object current = root;
        for (String token : path.replace("[", ".").replace("]", "").split("\\.")) {
            if (token.isBlank()) {
                continue;
            }
            if (current instanceof Map<?, ?> map) {
                current = map.get(token);
            } else if (current instanceof List<?> list) {
                try {
                    int index = Integer.parseInt(token);
                    current = index >= 0 && index < list.size() ? list.get(index) : null;
                } catch (NumberFormatException ignored) {
                    return null;
                }
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private boolean equivalent(Object proposed, Object verified) {
        if (Objects.equals(proposed, verified)) {
            return true;
        }
        if (proposed == null || verified == null) {
            return false;
        }
        return compact(String.valueOf(proposed)).equals(compact(String.valueOf(verified)));
    }

    private String normalizeSource(Object value) {
        String source = text(value);
        if (source == null) {
            return null;
        }
        String normalized = source.toLowerCase(Locale.ROOT);
        return TOOL_RESULT_SOURCE_ALIASES.contains(normalized) ? TOOL_RESULT_SOURCE : normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim().toLowerCase(Locale.ROOT);
    }

    private List<String> requiredParameters(Map<String, Object> template, Map<String, Object> schema) {
        Object value = schema.get("required");
        if (!(value instanceof Iterable<?>)) {
            value = firstPresent(template, "requiredParameters", "required_parameters");
        }
        return strings(value);
    }

    private void removeReadOnlyTemplateMetadata(Map<String, Object> input) {
        for (String key : List.of(
            "template_id", "selectedTemplate", "selected_template",
            "parameterSchema", "parameter_schema", "parameterContract", "parameter_contract",
            "requiredParameters", "required_parameters", "invocationExample", "invocation_example",
            "parameterProtocol", "parameter_protocol")) {
            input.remove(key);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
    }

    private Object firstPresent(Map<String, Object> source, String... keys) {
        if (source == null) {
            return null;
        }
        for (String key : keys) {
            if (source.containsKey(key)) {
                return source.get(key);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String canonicalTemplateId(Object value) {
        if (value instanceof Map<?, ?> map) {
            return canonicalTemplateId(firstPresent(
                new LinkedHashMap<>((Map<String, Object>) map),
                "templateId", "template_id", "id", "code", "template"));
        }
        if (value == null || value instanceof Iterable<?> || value.getClass().isArray()) {
            return null;
        }
        return text(value);
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : iterable) {
            String text = text(item);
            if (text != null) {
                result.add(text);
            }
        }
        return List.copyOf(result);
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String text(Object value) {
        if (value == null || value instanceof Map<?, ?> || value instanceof Iterable<?>) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private boolean hasValue(Object value) {
        return value != null && (!(value instanceof String text) || !text.isBlank());
    }

    private TemplateBridgeException failure(String code, String message) {
        return new TemplateBridgeException(code, code + ": " + message, List.of());
    }

    public record BridgeRequest(
        String executorTool,
        Integer stepId,
        String runtimeTemplateId,
        Map<String, Object> templateMetadata,
        Map<String, Object> input,
        Map<String, Object> parameterProtocol,
        boolean requireModelProtocol,
        boolean runtimeTemplateAuthoritative,
        EvidenceContext evidenceContext
    ) {
        public BridgeRequest(String executorTool,
                             Integer stepId,
                             String runtimeTemplateId,
                             Map<String, Object> templateMetadata,
                             Map<String, Object> input,
                             Map<String, Object> parameterProtocol,
                             boolean requireModelProtocol,
                             boolean runtimeTemplateAuthoritative) {
            this(executorTool, stepId, runtimeTemplateId, templateMetadata, input, parameterProtocol,
                requireModelProtocol, runtimeTemplateAuthoritative, EvidenceContext.empty());
        }
    }

    public record BridgeResult(
        Map<String, Object> executorInput,
        String templateId,
        Map<String, Object> parameters,
        boolean modelProtocolApplied,
        List<ToolArgumentCompiler.Repair> repairs,
        Map<String, ParameterEvidence> parameterEvidence,
        Map<String, Object> protocolTrace
    ) {
    }

    public record EvidenceContext(
        String userQuery,
        Map<Integer, Object> completedStepOutputs
    ) {
        public EvidenceContext {
            completedStepOutputs = completedStepOutputs == null
                ? Map.of() : Map.copyOf(completedStepOutputs);
        }

        public static EvidenceContext empty() {
            return new EvidenceContext(null, Map.of());
        }
    }

    public record ParameterEvidence(
        String source,
        Map<String, Object> reference,
        Object verifiedValue
    ) {
    }

    private record ParameterAudit(
        Map<String, Object> parameters,
        Map<String, ParameterEvidence> evidence,
        List<String> unresolved
    ) {
        private static ParameterAudit empty() {
            return new ParameterAudit(Map.of(), Map.of(), List.of());
        }
    }

    public static final class TemplateBridgeException extends IllegalStateException {
        private final String code;
        private final List<ToolArgumentCompiler.ValidationError> validationErrors;

        TemplateBridgeException(String code,
                                String message,
                                List<ToolArgumentCompiler.ValidationError> validationErrors) {
            super(message);
            this.code = code;
            this.validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
        }

        public String code() {
            return code;
        }

        public List<ToolArgumentCompiler.ValidationError> validationErrors() {
            return validationErrors;
        }
    }
}
