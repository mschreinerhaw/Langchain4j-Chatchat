package com.chatchat.common.runtime.coding;

import com.chatchat.common.kernel.KernelDataScope;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CodingTaskContract(String schemaVersion, String taskId, KernelDataScope scope,
    Objective objective, String inputSnapshotRef, List<String> metadataRefs, String workspaceRef,
    Authorization authorization, Budget budget, String environmentRef, String validationPlanRef,
    OutputContract outputContract) {
    public CodingTaskContract {
        CodingProtocol.version(schemaVersion); taskId = CodingProtocol.ref(taskId, "taskId");
        scope = CodingProtocol.scope(scope); CodingProtocol.required(objective, "objective");
        inputSnapshotRef = CodingProtocol.ref(inputSnapshotRef, "inputSnapshotRef");
        metadataRefs = CodingProtocol.strings(metadataRefs, "metadataRefs", true);
        workspaceRef = CodingProtocol.ref(workspaceRef, "workspaceRef");
        CodingProtocol.required(authorization, "authorization"); CodingProtocol.required(budget, "budget");
        environmentRef = CodingProtocol.ref(environmentRef, "environmentRef");
        validationPlanRef = CodingProtocol.ref(validationPlanRef, "validationPlanRef");
        CodingProtocol.required(outputContract, "outputContract");
        CodingProtocol.require(!CodingProtocol.WORKSPACE_VERSION.equals(schemaVersion)
                ? outputContract.resultFormat() == ResultFormat.JSON_LINES
                : outputContract.resultFormat() == ResultFormat.WORKSPACE_FILES,
            "Task version and output profile do not match");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Objective(String instruction, List<String> deliverables, List<String> acceptanceCriteriaRefs) {
        public Objective {
            instruction = CodingProtocol.text(instruction, "instruction", 32768);
            deliverables = CodingProtocol.strings(deliverables, "deliverables", false);
            CodingProtocol.require(!deliverables.isEmpty(), "deliverables cannot be empty");
            acceptanceCriteriaRefs = CodingProtocol.strings(acceptanceCriteriaRefs, "acceptanceCriteriaRefs", true);
            CodingProtocol.require(!acceptanceCriteriaRefs.isEmpty(), "acceptance criteria are required");
        }
    }
    public enum NetworkPolicy { NONE, ALLOWLIST }
    public enum SideEffect { WORKSPACE_WRITE, CODE_EXECUTION, DEPENDENCY_INSTALL }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Authorization(String decisionRef, List<String> allowedTools, List<String> allowedDataRefs,
        List<String> readablePathRefs, List<String> writablePathRefs, NetworkPolicy networkPolicy,
        List<String> networkAllowlist, List<SideEffect> allowedSideEffects) {
        public Authorization {
            decisionRef = CodingProtocol.ref(decisionRef, "decisionRef");
            allowedTools = CodingProtocol.strings(allowedTools, "allowedTools", true);
            allowedDataRefs = CodingProtocol.strings(allowedDataRefs, "allowedDataRefs", true);
            readablePathRefs = CodingProtocol.strings(readablePathRefs, "readablePathRefs", true);
            writablePathRefs = CodingProtocol.strings(writablePathRefs, "writablePathRefs", true);
            CodingProtocol.required(networkPolicy, "networkPolicy");
            networkAllowlist = CodingProtocol.strings(networkAllowlist, "networkAllowlist", true);
            CodingProtocol.require((networkPolicy == NetworkPolicy.NONE) == networkAllowlist.isEmpty(),
                "NONE requires an empty allowlist; ALLOWLIST requires explicit entries");
            CodingProtocol.required(allowedSideEffects, "allowedSideEffects");
            CodingProtocol.require(allowedSideEffects.size() <= SideEffect.values().length
                && allowedSideEffects.stream().distinct().count() == allowedSideEffects.size()
                && allowedSideEffects.stream().noneMatch(java.util.Objects::isNull), "invalid side effects");
            allowedSideEffects = allowedSideEffects.stream().sorted(java.util.Comparator.comparing(Enum::name)).toList();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Budget(String budgetId, Instant deadlineAt, long maxModelCalls, long maxModelTokens,
        long maxToolCalls, long maxCodeExecutions, String maxCostAmount, String costCurrency,
        long maxCpuMillis, long maxMemoryBytes, long maxOutputBytes) {
        public Budget {
            budgetId = CodingProtocol.ref(budgetId, "budgetId"); deadlineAt = CodingProtocol.time(deadlineAt, "deadlineAt");
            CodingProtocol.count(maxModelCalls, "maxModelCalls"); CodingProtocol.count(maxModelTokens, "maxModelTokens");
            CodingProtocol.count(maxToolCalls, "maxToolCalls"); CodingProtocol.count(maxCodeExecutions, "maxCodeExecutions");
            maxCostAmount = CodingProtocol.decimal(maxCostAmount, "maxCostAmount");
            CodingProtocol.require(costCurrency != null && costCurrency.matches("[A-Z]{3}"), "currency must be a three-letter uppercase code");
            CodingProtocol.count(maxCpuMillis, "maxCpuMillis"); CodingProtocol.count(maxMemoryBytes, "maxMemoryBytes");
            CodingProtocol.count(maxOutputBytes, "maxOutputBytes");
        }
    }

    public enum ResultFormat { JSON_LINES, WORKSPACE_FILES }
    public enum RequiredEvidence { INPUT_SNAPSHOT, CODE_ARTIFACT, EXECUTION_RECEIPT, RESULT_DATASET, VALIDATION_RECORD, EVIDENCE_BUNDLE }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OutputContract(ResultFormat resultFormat, String resultSchemaRef, String validationSchemaRef,
        List<String> requiredFiles, List<String> acceptedMediaTypes, long maxResultRows,
        long maxResultBytes, List<RequiredEvidence> requiredEvidence) {
        public OutputContract {
            CodingProtocol.required(resultFormat, "resultFormat");
            resultSchemaRef = CodingProtocol.ref(resultSchemaRef, "resultSchemaRef");
            validationSchemaRef = CodingProtocol.ref(validationSchemaRef, "validationSchemaRef");
            requiredFiles = CodingProtocol.strings(requiredFiles, "requiredFiles", true);
            CodingProtocol.require(!requiredFiles.isEmpty(), "requiredFiles cannot be empty");
            for (String file : requiredFiles) CodingProtocol.require(file.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*\\.[A-Za-z0-9]+"),
                "requiredFiles must be safe relative file paths");
            acceptedMediaTypes = CodingProtocol.strings(acceptedMediaTypes, "acceptedMediaTypes", true);
            CodingProtocol.require(!acceptedMediaTypes.isEmpty(), "acceptedMediaTypes cannot be empty");
            CodingProtocol.count(maxResultRows, "maxResultRows"); CodingProtocol.count(maxResultBytes, "maxResultBytes");
            CodingProtocol.required(requiredEvidence, "requiredEvidence");
            CodingProtocol.require(requiredEvidence.size() <= RequiredEvidence.values().length
                && requiredEvidence.stream().noneMatch(java.util.Objects::isNull)
                && requiredEvidence.stream().distinct().count() == requiredEvidence.size(), "invalid requiredEvidence");
            requiredEvidence = requiredEvidence.stream().sorted(java.util.Comparator.comparing(Enum::name)).toList();
        }
    }
}
