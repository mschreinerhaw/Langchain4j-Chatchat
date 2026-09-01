package com.chatchat.agents.runtime.plan.contract;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.protocol.ToolProtocolPayloadNavigator;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

/** Validates data contracts between completed plan nodes. */
@Slf4j
public final class PlanEdgeContractValidator {

    private final ToolProtocolPayloadNavigator payloads;

    public PlanEdgeContractValidator(ToolProtocolPayloadNavigator payloads) {
        this.payloads = Objects.requireNonNull(payloads, "payloads");
    }

    public ValidationFailure validate(InterpretationPlan plan,
                                      List<InterpretationPlanRuntime.StepExecution> waveResults,
                                      Map<Integer, InterpretationPlanRuntime.StepExecution> completed,
                                      InterpretationPlanRuntime.ExecutionRequest request,
                                      RuntimeSemantics semantics,
                                      BiPredicate<InterpretationPlan.EdgeContract,
                                          Map<Integer, InterpretationPlanRuntime.StepExecution>> runtimeOwnedEdge) {
        if (plan == null || plan.plan() == null || plan.plan().edgeContracts() == null
            || plan.plan().edgeContracts().isEmpty()) {
            return null;
        }
        Set<Integer> completedNow = waveResults.stream()
            .filter(InterpretationPlanRuntime.StepExecution::success)
            .map(InterpretationPlanRuntime.StepExecution::stepId)
            .collect(Collectors.toSet());
        for (InterpretationPlan.EdgeContract contract : plan.plan().edgeContracts()) {
            if (contract == null || !completedNow.contains(contract.from())) {
                continue;
            }
            if (indexedEdgeTargetsFinalAnswer(plan, contract)) {
                log.info("InterpretationPlan ignored model data edge into final_answer; final synthesis reads cumulative Runtime evidence directly: fromStep={}, toStep={}, field={}",
                    contract.from(), contract.to(), contract.field());
                continue;
            }
            if (runtimeOwnedEdge != null && runtimeOwnedEdge.test(contract, completed)) {
                log.info("InterpretationPlan ignored model template transport edge contract because Runtime will compile the authorized template batch: fromStep={}, toStep={}, field={}",
                    contract.from(), contract.to(), contract.field());
                continue;
            }
            ContractCheck check = check(contract, completed.get(contract.from()), request, semantics);
            if (!check.success()) {
                return new ValidationFailure(contract.to(), check.message());
            }
        }
        return null;
    }

    public ContractCheck check(InterpretationPlan.EdgeContract contract,
                               InterpretationPlanRuntime.StepExecution source,
                               InterpretationPlanRuntime.ExecutionRequest request,
                               RuntimeSemantics semantics) {
        Object value = contractValue(source, contract.field(), semantics);
        if (value == null && environmentContractField(contract.field())) {
            value = environmentContractValue(source, request, contract.from(), semantics);
            if (value != null) {
                log.info("InterpretationPlan satisfied environment edge contract from deterministic Agent context: fromStep={}, toStep={}, field={}, env={}",
                    contract.from(), contract.to(), contract.field(), value);
            }
        }
        boolean required = contract.required() == null || contract.required();
        if (value == null) {
            return required
                ? new ContractCheck(false, "EDGE_CONTRACT_FAILED: missing required field " + contract.field()
                    + " from step " + contract.from() + " for step " + contract.to())
                : new ContractCheck(true, null);
        }
        String declaredType = contract.type() == null ? "any" : contract.type().trim().toLowerCase();
        String type = canonicalType(contract.field(), declaredType);
        if (!type.equals(declaredType)) {
            log.warn("InterpretationPlan edge contract type normalized field={} declaredType={} canonicalType={} fromStep={} toStep={}",
                contract.field(), declaredType, type, contract.from(), contract.to());
        }
        boolean matches = switch (type) {
            case "any" -> true;
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            default -> false;
        };
        return matches
            ? new ContractCheck(true, null)
            : new ContractCheck(false, "EDGE_CONTRACT_FAILED: field " + contract.field()
                + " expected " + type + " but was " + value.getClass().getSimpleName());
    }

    public Object contractValue(InterpretationPlanRuntime.StepExecution source,
                                String field,
                                RuntimeSemantics semantics) {
        if (source == null) {
            return null;
        }
        if (isWholeStepOutputField(field)) {
            return source.output();
        }
        if (semantics.isWebSearchTool(source.toolName()) && "data".equalsIgnoreCase(String.valueOf(field).trim())
            && source.output() != null) {
            return source.output();
        }
        Object value = contractValue(source.output(), field);
        if (value != null || !semantics.isTemplateDiscoveryTool(source.toolName())) {
            return value;
        }
        String key = payloads.fieldKey(field);
        if ("templateid".equals(key) || "id".equals(key) || "template".equals(key)) {
            return payloads.firstValue(source.output(),
                "$.templates[0].templateId", "$.templates[0].id", "$.templates[0].code",
                "$.candidates[0].templateId", "$.candidates[0].id", "$.candidates[0].code",
                "$.results[0].associatedTemplates[0].templateId",
                "$.results[0].associatedTemplates[0].id", "$.results[0].associatedTemplates[0].code",
                "$.templateId", "$.id", "$.code");
        }
        return null;
    }

    public Object contractValue(Object output, String field) {
        if (isWholeStepOutputField(field)) {
            return output;
        }
        Object value = payloads.valueAtPath(output, field);
        if (value != null || field == null || field.isBlank()) {
            return value;
        }
        value = canonicalProtocolValue(output, field);
        if (value != null) {
            return value;
        }
        String key = payloads.fieldKey(field);
        if ("assettype".equals(key) || "asset.type".equals(key)) {
            return payloads.firstValue(output, "assetType", "data.assetType", "asset.type", "data.asset.type",
                "assets[0].assetType", "data.assets[0].assetType", "assets[0].asset.type",
                "data.assets[0].asset.type");
        }
        if ("allowedcommandtemplates".equals(key)) {
            return payloads.firstValue(output, "capabilities.allowedCommandTemplates",
                "assets[0].capabilities.allowedCommandTemplates",
                "data.assets[0].capabilities.allowedCommandTemplates");
        }
        if ("allowedcommandtemplateids".equals(key)) {
            return payloads.firstValue(output, "capabilities.allowedCommandTemplateIds",
                "assets[0].capabilities.allowedCommandTemplateIds",
                "data.assets[0].capabilities.allowedCommandTemplateIds");
        }
        return null;
    }

    public boolean indexedEdgeTargetsFinalAnswer(InterpretationPlan plan, InterpretationPlan.EdgeContract contract) {
        if (plan == null || contract == null || contract.to() == null || contract.field() == null
            || !contract.field().matches(".*\\[\\d+\\].*")) {
            return false;
        }
        return plan.steps().stream().filter(Objects::nonNull)
            .anyMatch(step -> Objects.equals(step.id(), contract.to()) && step.finalAnswerAction());
    }

    public boolean environmentContractField(String field) {
        String key = payloads.fieldKey(field);
        return "env".equals(key) || "environment".equals(key);
    }

    public String environmentContractValue(InterpretationPlanRuntime.StepExecution source,
                                           InterpretationPlanRuntime.ExecutionRequest request,
                                           Integer sourceStepId,
                                           RuntimeSemantics semantics) {
        if (source == null || !semantics.isAssetDiscoveryTool(source.toolName())) {
            return null;
        }
        Object value = payloads.firstValue(source.metadata(),
            "$.resolvedInput.filters.env", "$.resolvedInput.filters.environment",
            "$.resolvedInput.executionContext.env", "$.resolvedInput.executionContext.environment",
            "$.resolvedInput.env", "$.resolvedInput.environment");
        String canonical = semantics.canonicalEnvironment(value == null ? null : String.valueOf(value));
        if (canonical != null) {
            return canonical;
        }
        canonical = semantics.runtimeEnvironment(request);
        if (canonical != null) {
            return canonical;
        }
        if (request == null || request.plan() == null || sourceStepId == null) {
            return null;
        }
        InterpretationPlan.Step sourceStep = request.plan().steps().stream().filter(Objects::nonNull)
            .filter(step -> sourceStepId.equals(step.id())).findFirst().orElse(null);
        value = payloads.firstValue(sourceStep == null ? null : sourceStep.input(),
            "$.filters.env", "$.filters.environment", "$.env", "$.environment");
        return semantics.canonicalEnvironment(value == null ? null : String.valueOf(value));
    }

    private String canonicalType(String field, String declaredType) {
        String normalized = field == null ? "" : field.replace("_", "").toLowerCase(java.util.Locale.ROOT);
        if ((normalized.contains("parameterschema.") || normalized.contains("inputschema.")
            || normalized.contains("schema.")) && normalized.endsWith(".required")) {
            return "array";
        }
        if ((normalized.contains("parameterschema.") || normalized.contains("inputschema.")
            || normalized.contains("schema.")) && normalized.endsWith(".properties")) {
            return "object";
        }
        return declaredType;
    }

    private Object canonicalProtocolValue(Object output, String requestedField) {
        if (output == null || requestedField == null || requestedField.isBlank()) {
            return null;
        }
        output = routingCapableOutput(output);
        if (!(payloads.firstValue(output, "$.assets[0].asset") instanceof Map<?, ?>)) {
            return null;
        }
        return switch (payloads.fieldKey(requestedField)) {
            case "assetname", "name", "displayname" -> payloads.firstValue(output,
                "$.assets[0].asset.name", "$.assets[0].asset.displayName");
            case "env", "environment" -> payloads.firstValue(output,
                "$.assets[0].asset.environment", "$.assets[0].asset.env");
            case "databaserole" -> payloads.firstValue(output,
                "$.assets[0].asset.databaseRole", "$.assets[0].asset.database_role");
            case "assettype", "asset.type" -> payloads.firstValue(output,
                "$.assets[0].asset.type", "$.assets[0].asset.assetType");
            case "toolname" -> payloads.firstValue(output,
                "$.assets[0].asset.toolName", "$.assets[0].asset.tool_name");
            default -> null;
        };
    }

    private Object routingCapableOutput(Object output) {
        Object assets = payloads.firstValue(output, "$.assets");
        if (assets instanceof Iterable<?>) {
            return output;
        }
        Object projection = payloads.firstValue(output, "$.routingProjection");
        return projection instanceof Map<?, ?> ? projection : output;
    }

    private boolean isWholeStepOutputField(String field) {
        String normalized = field == null ? "" : field.trim();
        if ("output".equalsIgnoreCase(normalized) || "$".equals(normalized) || "$.".equals(normalized)) {
            return true;
        }
        String semanticKey = normalized.replace("_", "").replace("-", "").replace(" ", "")
            .toLowerCase(java.util.Locale.ROOT);
        return Set.of("searchresult", "searchresults", "queryresult", "queryresults", "retrievalresult",
            "retrievalresults", "toolresult", "toolresults", "evidence", "evidenceresult", "evidenceresults",
            "\u641c\u7d22\u7ed3\u679c", "\u68c0\u7d22\u7ed3\u679c", "\u67e5\u8be2\u7ed3\u679c",
            "\u5de5\u5177\u7ed3\u679c", "\u8bc1\u636e\u7ed3\u679c")
            .contains(semanticKey);
    }

    public interface RuntimeSemantics {
        boolean isWebSearchTool(String toolName);
        boolean isTemplateDiscoveryTool(String toolName);
        boolean isAssetDiscoveryTool(String toolName);
        String runtimeEnvironment(InterpretationPlanRuntime.ExecutionRequest request);
        String canonicalEnvironment(String value);
    }

    public record ContractCheck(boolean success, String message) { }

    public record ValidationFailure(Integer targetStepId, String message) { }
}
