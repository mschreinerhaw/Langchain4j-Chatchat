package com.chatchat.api.runtime;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.api.ChatChatApplication;
import com.chatchat.common.config.ModelResourceRegistry;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.CodingAgentSPI;
import com.chatchat.common.runtime.coding.CodingCanonicalHash;
import com.chatchat.common.runtime.coding.CodingProtocol;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.chatchat.common.runtime.coding.CodingTaskOutcome;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import javax.sql.DataSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manually launched, test-classpath-only D1 smoke driver. It uses an isolated fixture
 * and the real Spring beans; it never exposes an HTTP task-submission endpoint.
 */
public final class D1StagingSmokeDriver {
    private D1StagingSmokeDriver() { }

    public static void main(String[] args) throws Exception {
        try (ConfigurableApplicationContext app = SpringApplication.run(ChatChatApplication.class, args)) {
            String recoverTask = System.getenv("D1_SMOKE_RECOVER_TASK_ID");
            if (recoverTask != null && !recoverTask.isBlank()) {
                recover(app, recoverTask);
                return;
            }
            var resources = app.getBean(ModelResourceRegistry.class);
            String model = resources.defaultChatModel();
            if (model == null || model.isBlank())
                throw new IllegalStateException("No published server-side chat model in the isolated catalog");
            try (var connection = app.getBean(DataSource.class).getConnection()) {
                System.out.println("D1_SMOKE datasource=" + connection.getMetaData().getURL());
            }
            var configured = resources.require(model).config();
            System.out.println("D1_SMOKE model=" + model + " protocol=" + configured.getProtocol()
                + " modelKeyPresent=" + (configured.getApiKey() != null
                    && !configured.getApiKey().isBlank()));
            var python = app.getBean(McpPythonControlPlanePort.class);
            String environmentId = System.getenv("D1_SMOKE_PYTHON_ENVIRONMENT_ID");
            if (environmentId == null || environmentId.isBlank())
                environmentId = python.environments().stream()
                    .filter(item -> "PUBLISHED".equals(item.status())
                        && "NONE".equals(item.networkPolicy()))
                    .map(McpPythonControlPlanePort.EnvironmentView::id).sorted().findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                        "No published, network-disabled Python environment is available"));
            var environment = python.environment(environmentId);
            if (environment == null || !"PUBLISHED".equals(environment.status())
                    || !"NONE".equals(environment.networkPolicy()))
                throw new IllegalStateException("Smoke Python environment must be published and network-disabled");

            String suffix = UUID.randomUUID().toString().replace("-", "");
            boolean cancelMode = "cancel".equals(System.getenv("D1_SMOKE_MODE"));
            boolean budgetMode = "budget".equals(System.getenv("D1_SMOKE_MODE"));
            boolean restartMode = "restart".equals(System.getenv("D1_SMOKE_MODE"));
            boolean repairMode = "repair".equals(System.getenv("D1_SMOKE_MODE"));
            String taskId = "d1-smoke-" + suffix;
            String runId = "d1-smoke-run-" + suffix;
            var scope = new KernelDataScope("d1-smoke", "d1-smoke", "fixture", null, runId, null, Map.of());
            String source = "fixture:authorized-financial-" + suffix;
            String decision = "fixture:authorized-decision-" + suffix;
            String workspace = "fixture:workspace-" + suffix;
            var dataset = new Dataset(source, Map.of(), List.of(
                Map.of("tradeId", "t1", "amount", "0.10"),
                Map.of("tradeId", "t2", "amount", "0.20")));
            var identity = app.getBean(CodingInvocationContext.class);
            var inputs = app.getBean(CodingInputSnapshotService.class);
            var plans = app.getBean(CodingValidationPlanService.class);
            var agent = app.getBean(CodingAgentSPI.class);
            var tasks = app.getBean(DurableCodingTaskService.class);
            var collector = app.getBean(CodingEvidenceCollector.class);
            var grants = EnumSet.allOf(CodingTaskAccess.Action.class);
            var input = identity.as(scope, grants, () -> inputs.capture(scope, taskId,
                List.of(dataset), source, decision, workspace, 10, 4096));
            String validation = identity.as(scope, grants, () -> plans.register(scope, taskId,
                input, new CodingValidationPlanService.Plan("amount", "total", "tradeId", false)));
            Instant deadline = Instant.now().plusSeconds(900).truncatedTo(ChronoUnit.MILLIS);
            var contract = new CodingTaskContract(CodingProtocol.VERSION, taskId, scope,
                new CodingTaskContract.Objective(
                    (cancelMode || restartMode ? "For this recovery drill, write main.py that first sleeps 90 seconds, "
                        + "then uses only the authorized input snapshot to sum amount with Decimal. "
                        : repairMode ? "For this repair drill, first execute main.py with a deliberate "
                        + "RuntimeError('D1_FAULT_INJECTION'). Inspect the returned stderr, then rewrite "
                        + "main.py to use only the authorized input snapshot and sum amount with Decimal. "
                        : "Using only the authorized input snapshot, write main.py to sum amount with Decimal. ")
                        + "Write one JSON object with a total field to rows.jsonl. Execute in python_execute "
                        + "and repair technical errors if needed.",
                    List.of("rows.jsonl"), List.of(validation)),
                input.reference(), List.of(), workspace,
                new CodingTaskContract.Authorization(decision, List.of("python_execute"),
                    List.of(input.fileId()), List.of(), List.of(),
                    CodingTaskContract.NetworkPolicy.NONE, List.of(),
                    List.of(CodingTaskContract.SideEffect.CODE_EXECUTION)),
                new CodingTaskContract.Budget("d1-smoke-budget-" + suffix, deadline,
                    8, budgetMode ? 2048 : 16384, 8, 8, "40", "USD", 300_000,
                    256L * 1024 * 1024, 100_000),
                environmentId, validation,
                new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.JSON_LINES,
                    "schema:total", "schema:validation", List.of("rows.jsonl"),
                    List.of("application/x-ndjson"), 10, 100_000,
                    List.of(CodingTaskContract.RequiredEvidence.values())));
            CodingTaskOutcome outcome = identity.as(scope, grants, () -> agent.submit(contract));
            long fence = identity.as(scope, grants,
                () -> tasks.ownership(scope, taskId).fencingToken());
            System.out.println("D1_SMOKE taskId=" + taskId + " contractHash="
                + CodingCanonicalHash.sha256(contract) + " fencingToken=" + fence
                + " accepted=" + outcome.status());
            int polls = 0;
            boolean stopSent = false;
            String stoppedOperation = null;
            while (Instant.now().isBefore(deadline) && !outcome.status().terminal()) {
                Thread.sleep(500);
                outcome = identity.as(scope, grants, () -> agent.get(scope, taskId));
                if ((cancelMode || restartMode) && !stopSent) {
                    for (String operationId : identity.as(scope, grants,
                            () -> tasks.unresolvedOperations(scope, taskId))) {
                        if (!operationId.matches("cline-[0-9a-f]{64}")) continue;
                        McpPythonControlPlanePort.OperationView operation;
                        try {
                            operation = python.get(scope.tenantId(), scope.userId(), scope.runId(), taskId, operationId);
                        } catch (RuntimeException notYetAccepted) {
                            continue;
                        }
                        if ("RUNNING".equals(operation.status())) {
                            if (restartMode) {
                                System.out.println("D1_SMOKE restartReady=true operationId=" + operationId);
                                System.out.flush();
                                Runtime.getRuntime().halt(0);
                            }
                            stoppedOperation = operationId;
                            outcome = identity.as(scope, grants,
                                () -> agent.cancel(scope, taskId, "Isolated D1 cancellation drill"));
                            stopSent = true;
                            System.out.println("D1_SMOKE cancellationSent=true operationId=" + operationId);
                            break;
                        }
                    }
                }
                if (cancelMode && stopSent && !outcome.status().terminal())
                    outcome = identity.as(scope, grants, () -> agent.resume(scope, taskId));
                if (++polls % 20 == 0) {
                    CodingTaskOutcome observed = outcome;
                    int unknown = identity.as(scope, grants,
                        () -> tasks.unresolvedOperations(scope, taskId).size());
                    System.out.println("D1_SMOKE progress=" + observed.status()
                        + " revision=" + observed.revision() + " unresolved=" + unknown);
                }
            }
            System.out.println("D1_SMOKE status=" + outcome.status()
                + " validation=" + outcome.validationStatus()
                + " revision=" + outcome.revision()
                + " modelCalls=" + outcome.usage().consumed().modelCalls()
                + " codeExecutions=" + outcome.usage().consumed().codeExecutions());
            if (cancelMode) {
                if (!stopSent || outcome.status() != CodingTaskOutcome.Status.CANCELLED || stoppedOperation == null)
                    throw new IllegalStateException("D1 cancel drill did not reach a confirmed cancelled state");
                var operation = python.get(scope.tenantId(), scope.userId(), scope.runId(), taskId, stoppedOperation);
                if (!operation.executorStopped() || !"CANCELLED".equals(operation.status()))
                    throw new IllegalStateException("D1 cancel drill did not stop the real Python container");
                System.out.println("D1_SMOKE cancelledDocker=true container=" + operation.containerId());
                return;
            }
            if (budgetMode) {
                if (outcome.status() != CodingTaskOutcome.Status.BUDGET_EXCEEDED
                        || outcome.usage().consumed().modelCalls() > 1)
                    throw new IllegalStateException("D1 budget drill did not enforce the model call limit");
                System.out.println("D1_SMOKE budgetEnforced=true");
                return;
            }
            if (outcome.status() != CodingTaskOutcome.Status.COMPLETED)
                throw new IllegalStateException("D1 smoke did not complete; last summary: " + outcome.summary());
            if (repairMode) {
                var attempts = identity.as(scope, grants, () -> tasks.startedCodeOperations(scope, taskId));
                boolean failed = false, succeeded = false;
                for (String operationId : attempts) {
                    var operation = python.get(scope.tenantId(), scope.userId(), scope.runId(), taskId, operationId);
                    failed |= "FAILED".equals(operation.status());
                    succeeded |= "SUCCEEDED".equals(operation.status());
                }
                if (!failed || !succeeded || outcome.usage().consumed().codeExecutions() < 2)
                    throw new IllegalStateException("D1 repair drill did not execute and fix a failed script");
                System.out.println("D1_SMOKE technicalRepair=true attempts=" + attempts.size());
            }
            if (outcome.inputSnapshotRef() == null || outcome.codeArtifactRef() == null
                    || outcome.executionReceiptRef() == null || outcome.resultDatasetRef() == null
                    || outcome.validationRecordRef() == null || outcome.evidenceBundleRef() == null)
                throw new IllegalStateException("D1 smoke is missing a required evidence reference");
            String resultRef = outcome.resultDatasetRef();
            long rows = identity.as(scope, grants, () -> {
                try (var result = collector.openResult(scope, taskId, resultRef)) {
                    return result.recordCount();
                }
            });
            if (rows != 1 || outcome.validationStatus() != CodingTaskOutcome.ValidationStatus.PASSED)
                throw new IllegalStateException("D1 smoke result or independent validation failed");
            System.out.println("D1_SMOKE sixRefs=true resultRows=" + rows
                + " evidenceBundleRef=" + outcome.evidenceBundleRef());
        }
    }

    private static void recover(ConfigurableApplicationContext app, String taskId) {
        if (!taskId.matches("d1-smoke-[0-9a-f]{32}"))
            throw new IllegalArgumentException("Only an isolated D1 smoke task can be recovered");
        String runId = "d1-smoke-run-" + taskId.substring("d1-smoke-".length());
        var scope = new KernelDataScope("d1-smoke", "d1-smoke", "fixture", null, runId, null, Map.of());
        var identity = app.getBean(CodingInvocationContext.class);
        var tasks = app.getBean(DurableCodingTaskService.class);
        var python = app.getBean(McpPythonControlPlanePort.class);
        var agent = app.getBean(CodingAgentSPI.class);
        var grants = EnumSet.allOf(CodingTaskAccess.Action.class);
        var before = identity.as(scope, grants, () -> tasks.get(scope, taskId));
        var unresolved = identity.as(scope, grants, () -> tasks.unresolvedOperations(scope, taskId));
        System.out.println("D1_SMOKE_RECOVER before=" + before.status() + " revision=" + before.revision()
            + " unresolved=" + unresolved.size());
        String explicitOperation = System.getenv("D1_SMOKE_OPERATION_ID");
        List<String> inspect = explicitOperation != null && explicitOperation.matches("cline-[0-9a-f]{64}")
            ? List.of(explicitOperation) : unresolved;
        for (String operationId : inspect) {
            if (!operationId.matches("cline-[0-9a-f]{64}")) continue;
            try {
                var operation = python.get(scope.tenantId(), scope.userId(), scope.runId(), taskId, operationId);
                System.out.println("D1_SMOKE_RECOVER pythonStatus=" + operation.status()
                    + " stopped=" + operation.executorStopped()
                    + " exitCode=" + operation.exitCode()
                    + " manifest=" + (operation.manifestHash() != null)
                    + " failure=" + String.valueOf(operation.failure()).substring(0,
                        Math.min(160, String.valueOf(operation.failure()).length())));
                if (operation.executorStopped()) {
                    try {
                        byte[] stderr = python.artifact(scope.tenantId(), scope.userId(),
                            scope.runId(), taskId, operationId, "logs", "stderr.txt");
                        String excerpt = new String(stderr, java.nio.charset.StandardCharsets.UTF_8);
                        System.out.println("D1_SMOKE_RECOVER stderr=" + excerpt.substring(0,
                            Math.min(1200, excerpt.length())));
                    } catch (RuntimeException artifactFailure) {
                        System.out.println("D1_SMOKE_RECOVER stderrLookupFailure="
                            + artifactFailure.getClass().getSimpleName());
                    }
                }
                if ("true".equals(System.getenv("D1_SMOKE_COLLECT"))
                        && "SUCCEEDED".equals(operation.status())) {
                    try {
                        var contract = identity.as(scope, grants, () -> tasks.contract(scope, taskId));
                        var input = identity.as(scope, grants,
                            () -> app.getBean(CodingInputSnapshotService.class).requireAuthorized(contract));
                        var collected = identity.as(scope, grants, () -> app.getBean(CodingEvidenceCollector.class)
                            .collect(scope, contract, operationId, "main.py", "rows.jsonl",
                                List.of(new CodingEvidenceCollector.InputFile(
                                    input.fileId(), input.fileName(), input.sha256())),
                                app.getBean(CodingValidationPlanService.class).validator(contract)));
                        System.out.println("D1_SMOKE_RECOVER collected=" + collected.evidenceBundleRef());
                    } catch (RuntimeException failure) {
                        System.out.println("D1_SMOKE_RECOVER collectFailure=" + failure);
                        if (failure.getCause() != null)
                            System.out.println("D1_SMOKE_RECOVER collectCause=" + failure.getCause());
                        for (StackTraceElement frame : failure.getStackTrace()) {
                            System.out.println("D1_SMOKE_RECOVER collectFrame=" + frame);
                            if (frame.getClassName().contains("CodingEvidenceCollector")) break;
                        }
                    }
                }
            } catch (RuntimeException unavailable) {
                System.out.println("D1_SMOKE_RECOVER pythonLookupFailure="
                    + unavailable.getClass().getSimpleName() + " detail="
                    + String.valueOf(unavailable.getMessage()).substring(0,
                        Math.min(200, String.valueOf(unavailable.getMessage()).length())));
            }
        }
        if ("true".equals(System.getenv("D1_SMOKE_CANCEL")))
            identity.as(scope, grants, () -> agent.cancel(scope, taskId, "Isolated D1 smoke cleanup"));
        var after = identity.as(scope, grants, () -> agent.resume(scope, taskId));
        System.out.println("D1_SMOKE_RECOVER after=" + after.status() + " revision=" + after.revision()
            + " reconciliationRequired=" + after.executionControl().reconciliationRequired());
        if (after.status() == CodingTaskOutcome.Status.COMPLETED) {
            boolean sixRefs = after.inputSnapshotRef() != null && after.codeArtifactRef() != null
                && after.executionReceiptRef() != null && after.resultDatasetRef() != null
                && after.validationRecordRef() != null && after.evidenceBundleRef() != null;
            long rows = identity.as(scope, grants, () -> {
                try (var result = app.getBean(CodingEvidenceCollector.class)
                        .openResult(scope, taskId, after.resultDatasetRef())) {
                    return result.recordCount();
                }
            });
            var repeated = identity.as(scope, grants, () -> agent.resume(scope, taskId));
            System.out.println("D1_SMOKE_RECOVER sixRefs=" + sixRefs + " resultRows=" + rows
                + " modelCalls=" + after.usage().consumed().modelCalls()
                + " codeExecutions=" + after.usage().consumed().codeExecutions()
                + " validation=" + after.validationStatus()
                + " idempotentRevision=" + (after.revision() == repeated.revision()));
            System.out.println("D1_SMOKE_RECOVER refs=" + String.join(" | ",
                after.inputSnapshotRef(), after.codeArtifactRef(), after.executionReceiptRef(),
                after.resultDatasetRef(), after.validationRecordRef(), after.evidenceBundleRef()));
        }
    }
}
