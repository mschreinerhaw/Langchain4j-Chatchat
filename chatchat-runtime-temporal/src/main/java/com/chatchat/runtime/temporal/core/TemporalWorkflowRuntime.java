package com.chatchat.runtime.temporal.core;

import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.workflow.WorkflowDefinition;
import com.chatchat.agents.runtime.workflow.WorkflowExecutionSnapshot;
import com.chatchat.agents.runtime.workflow.WorkflowExecutionStatus;
import com.chatchat.agents.runtime.workflow.WorkflowHandle;
import com.chatchat.agents.runtime.workflow.WorkflowRegistration;
import com.chatchat.agents.runtime.workflow.WorkflowRuntime;
import com.chatchat.agents.runtime.workflow.WorkflowStartRequest;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.runtime.plan.execution.PlanExecutionPhaseHandler;
import com.chatchat.agents.runtime.plan.execution.ResumableAgentRunExecutor;
import com.chatchat.agents.orchestration.analysis.dispatch.AnalysisDatasetExecutionPort;
import com.chatchat.runtime.temporal.activity.RuntimeOsAnalysisDatasetActivityImpl;
import com.chatchat.runtime.temporal.activity.RuntimeOsPlanStageActivityImpl;
import com.chatchat.runtime.temporal.activity.RuntimeOsToolActivityImpl;
import com.chatchat.runtime.temporal.activity.RuntimeOsWorkflowActivityImpl;
import com.chatchat.runtime.temporal.config.TemporalWorkflowProperties;
import com.chatchat.runtime.temporal.contract.TemporalWorkflowCommand;
import com.chatchat.runtime.temporal.contract.TemporalWorkflowResult;
import com.chatchat.runtime.temporal.workflow.RuntimeOsAgentExecutionWorkflowImpl;
import com.chatchat.runtime.temporal.workflow.RuntimeOsAnalysisBatchWorkflowImpl;
import com.chatchat.runtime.temporal.workflow.RuntimeOsPlanDagControlWorkflowImpl;
import com.chatchat.runtime.temporal.workflow.RuntimeOsPlanExecutionWorkflowImpl;
import com.chatchat.runtime.temporal.workflow.RuntimeOsTemporalWorkflow;
import com.chatchat.runtime.temporal.workflow.RuntimeOsTemporalWorkflowImpl;
import com.chatchat.runtime.temporal.workflow.RuntimeOsToolExecutionWorkflowImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Temporal-backed durable implementation of the Runtime OS workflow port. */
public final class TemporalWorkflowRuntime implements WorkflowRuntime, AutoCloseable {

    private static final String MEMO_WORKFLOW_TYPE = "runtimeWorkflowType";
    private static final String MEMO_TENANT_ID = "runtimeTenantId";
    private static final String MEMO_IDEMPOTENCY_KEY = "runtimeIdempotencyKey";

    private final WorkflowClient client;
    private final WorkerFactory workerFactory;
    private final ObjectMapper objectMapper;
    private final TemporalWorkflowProperties properties;
    private final TemporalWorkflowDefinitionRegistry registry = new TemporalWorkflowDefinitionRegistry();
    private final RuntimeOsWorkflowActivityImpl activity;
    private final RuntimeOsToolActivityImpl toolActivity;
    private final RuntimeOsPlanStageActivityImpl planStageActivity;
    private final RuntimeOsAnalysisDatasetActivityImpl analysisDatasetActivity;
    private final ConcurrentMap<String, ActiveExecution<?>> activeExecutions = new ConcurrentHashMap<>();
    private final AtomicBoolean workerStarted = new AtomicBoolean(false);

    public TemporalWorkflowRuntime(WorkflowClient client, WorkerFactory workerFactory,
                                   ObjectMapper objectMapper, TemporalWorkflowProperties properties) {
        this(client, workerFactory, objectMapper, properties, null);
    }

    public TemporalWorkflowRuntime(WorkflowClient client, WorkerFactory workerFactory,
                                   ObjectMapper objectMapper, TemporalWorkflowProperties properties,
                                   ToolRuntimeService toolRuntimeService) {
        this(client, workerFactory, objectMapper, properties, toolRuntimeService, null);
    }

    public TemporalWorkflowRuntime(WorkflowClient client, WorkerFactory workerFactory,
                                   ObjectMapper objectMapper, TemporalWorkflowProperties properties,
                                   ToolRuntimeService toolRuntimeService,
                                   PlanExecutionPhaseHandler planExecutionPhaseHandler) {
        this(client, workerFactory, objectMapper, properties, toolRuntimeService,
            planExecutionPhaseHandler, null);
    }

    public TemporalWorkflowRuntime(WorkflowClient client, WorkerFactory workerFactory,
                                   ObjectMapper objectMapper, TemporalWorkflowProperties properties,
                                   ToolRuntimeService toolRuntimeService,
                                   PlanExecutionPhaseHandler planExecutionPhaseHandler,
                                   Supplier<AnalysisDatasetExecutionPort> analysisExecutionPort) {
        this.client = client;
        this.workerFactory = workerFactory;
        this.objectMapper = objectMapper.copy();
        this.properties = properties;
        this.activity = new RuntimeOsWorkflowActivityImpl(
            registry, this.objectMapper,
            planExecutionPhaseHandler instanceof ResumableAgentRunExecutor resumable
                ? resumable : null);
        this.toolActivity = toolRuntimeService == null ? null
            : new RuntimeOsToolActivityImpl(toolRuntimeService);
        this.planStageActivity = new RuntimeOsPlanStageActivityImpl(planExecutionPhaseHandler);
        this.analysisDatasetActivity = analysisExecutionPort == null ? null
            : new RuntimeOsAnalysisDatasetActivityImpl(analysisExecutionPort);
    }

    @Override
    public <I, O> void register(String workflowType, Class<I> inputType, Class<O> outputType,
                                WorkflowDefinition<I, O> definition) {
        registry.register(workflowType, inputType, outputType, definition);
    }

    @Override
    public <I, O> WorkflowHandle<O> start(WorkflowStartRequest<I> request) {
        if (request == null) {
            throw new IllegalArgumentException("Workflow start request is required");
        }
        WorkflowRegistration<?, ?> registration = registry.required(request.workflowType());
        validateInput(request, registration);

        ActiveExecution<O> created = new ActiveExecution<>(request);
        ActiveExecution<?> existing = activeExecutions.putIfAbsent(request.workflowId(), created);
        if (existing != null) {
            validateDuplicate(existing, request);
            return new WorkflowHandle<>(request.workflowId(), false, castCompletion(existing));
        }

        try {
            ensureWorkerStarted();
            TemporalWorkflowCommand command = command(request);
            RuntimeOsTemporalWorkflow workflow = client.newWorkflowStub(
                RuntimeOsTemporalWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId(request.workflowId())
                    .setTaskQueue(properties.taskQueue())
                    .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                    .setMemo(Map.of(
                        MEMO_WORKFLOW_TYPE, request.workflowType(),
                        MEMO_TENANT_ID, request.tenantId(),
                        MEMO_IDEMPOTENCY_KEY, request.idempotencyKey()
                    ))
                    .build()
            );
            boolean newlyStarted = true;
            try {
                WorkflowClient.start(workflow::execute, command);
            } catch (WorkflowExecutionAlreadyStarted duplicate) {
                validatePersistedIdentity(request);
                newlyStarted = false;
            }
            WorkflowStub stub = client.newUntypedWorkflowStub(request.workflowId());
            created.status = WorkflowExecutionStatus.RUNNING;
            stub.getResultAsync(TemporalWorkflowResult.class)
                .whenComplete((result, failure) -> finish(created, registration, result, failure));
            return new WorkflowHandle<>(request.workflowId(), newlyStarted, created.completion);
        } catch (Throwable failure) {
            activeExecutions.remove(request.workflowId(), created);
            created.status = WorkflowExecutionStatus.FAILED;
            created.finishedAt = System.currentTimeMillis();
            created.errorMessage = message(failure);
            created.completion.completeExceptionally(failure);
            return new WorkflowHandle<>(request.workflowId(), false, created.completion);
        }
    }

    @Override
    public boolean cancel(String workflowId, String reason) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("Workflow id is required");
        }
        String id = workflowId.trim();
        ActiveExecution<?> active = activeExecutions.get(id);
        if (active != null) {
            active.cancellationReason = text(reason, "Workflow cancellation requested");
            active.status = WorkflowExecutionStatus.CANCELLED;
        }
        try {
            client.newUntypedWorkflowStub(id).cancel();
            return true;
        } catch (WorkflowNotFoundException notFound) {
            return false;
        }
    }

    @Override
    public Optional<WorkflowExecutionSnapshot> find(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return Optional.empty();
        }
        String id = workflowId.trim();
        ActiveExecution<?> active = activeExecutions.get(id);
        if (active != null) {
            return Optional.of(active.snapshot());
        }
        try {
            DescribeWorkflowExecutionResponse response = client.getWorkflowServiceStubs().blockingStub()
                .describeWorkflowExecution(DescribeWorkflowExecutionRequest.newBuilder()
                    .setNamespace(client.getOptions().getNamespace())
                    .setExecution(WorkflowExecution.newBuilder().setWorkflowId(id).build())
                    .build());
            var info = response.getWorkflowExecutionInfo();
            String workflowType = memo(info, MEMO_WORKFLOW_TYPE, info.getType().getName());
            String tenantId = memo(info, MEMO_TENANT_ID, "default");
            String idempotencyKey = memo(info, MEMO_IDEMPOTENCY_KEY, id);
            return Optional.of(new WorkflowExecutionSnapshot(
                id,
                workflowType,
                tenantId,
                idempotencyKey,
                status(info.getStatus()),
                1,
                toEpochMillis(info.getStartTime()),
                info.hasCloseTime() ? toEpochMillis(info.getCloseTime()) : null,
                null,
                null
            ));
        } catch (StatusRuntimeException notFound) {
            if (notFound.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            throw notFound;
        }
    }

    @Override
    public int activeExecutionCount() {
        return activeExecutions.size();
    }

    /** Starts the shared Runtime OS worker exactly once for runtime and adapter entry points. */
    public void startWorker() {
        ensureWorkerStarted();
    }

    private synchronized void ensureWorkerStarted() {
        if (workerStarted.get()) {
            return;
        }
        Worker worker = workerFactory.newWorker(properties.taskQueue());
        worker.registerWorkflowImplementationTypes(
            RuntimeOsTemporalWorkflowImpl.class,
            RuntimeOsAgentExecutionWorkflowImpl.class,
            RuntimeOsAnalysisBatchWorkflowImpl.class,
            RuntimeOsToolExecutionWorkflowImpl.class,
            RuntimeOsPlanDagControlWorkflowImpl.class,
            RuntimeOsPlanExecutionWorkflowImpl.class);
        worker.registerActivitiesImplementations(activity);
        if (toolActivity != null) {
            worker.registerActivitiesImplementations(toolActivity);
        }
        worker.registerActivitiesImplementations(planStageActivity);
        if (analysisDatasetActivity != null) {
            worker.registerActivitiesImplementations(analysisDatasetActivity);
        }
        workerFactory.start();
        workerStarted.set(true);
    }

    private <I> TemporalWorkflowCommand command(WorkflowStartRequest<I> request)
        throws JsonProcessingException {
        Object input = serializableInput(request.input());
        return new TemporalWorkflowCommand(
            request.workflowType(),
            objectMapper.writeValueAsString(input),
            properties.activityStartToCloseSeconds(),
            properties.activityHeartbeatSeconds(),
            properties.activityMaximumAttempts()
        );
    }

    private Object serializableInput(Object input) {
        if (!(input instanceof AgentRunRequest request)) {
            return input;
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (request.getAttributes() != null) {
            request.getAttributes().forEach((key, value) -> {
                if (key != null && !key.startsWith("__")
                    && !(value instanceof BooleanSupplier)) {
                    attributes.put(key, value);
                }
            });
        }
        return AgentRunRequest.builder()
            .runId(request.getRunId())
            .query(request.getQuery())
            .tenantId(request.getTenantId())
            .availableTools(request.getAvailableTools())
            .systemPrompt(request.getSystemPrompt())
            .modelName(request.getModelName())
            .boundDocumentIds(request.getBoundDocumentIds())
            .boundDocumentTags(request.getBoundDocumentTags())
            .skillId(request.getSkillId())
            .requestId(request.getRequestId())
            .conversationId(request.getConversationId())
            .userId(request.getUserId())
            .webSearchResultLimit(request.getWebSearchResultLimit())
            .requiredToolNames(request.getRequiredToolNames())
            .requireBoundToolCall(request.isRequireBoundToolCall())
            .maxSteps(request.getMaxSteps())
            .maxToolCalls(request.getMaxToolCalls())
            .timeoutMs(request.getTimeoutMs())
            .attributes(attributes)
            .build();
    }

    private <O> void finish(ActiveExecution<O> active, WorkflowRegistration<?, ?> registration,
                            TemporalWorkflowResult result, Throwable failure) {
        try {
            if (failure != null) {
                if (isCancellation(failure)) {
                    active.status = WorkflowExecutionStatus.CANCELLED;
                    java.util.concurrent.CancellationException cancellation =
                        new java.util.concurrent.CancellationException("Workflow was cancelled");
                    cancellation.initCause(failure);
                    active.errorMessage = message(cancellation);
                    active.completion.completeExceptionally(cancellation);
                    return;
                }
                active.status = WorkflowExecutionStatus.FAILED;
                active.errorMessage = message(failure);
                active.completion.completeExceptionally(failure);
                return;
            }
            O output = decodeResult(registration, result);
            active.status = WorkflowExecutionStatus.COMPLETED;
            active.completion.complete(output);
        } catch (Throwable conversionFailure) {
            active.status = WorkflowExecutionStatus.FAILED;
            active.errorMessage = message(conversionFailure);
            active.completion.completeExceptionally(conversionFailure);
        } finally {
            active.finishedAt = System.currentTimeMillis();
            activeExecutions.remove(active.workflowId, active);
        }
    }

    @SuppressWarnings("unchecked")
    private <O> O decodeResult(WorkflowRegistration<?, ?> registration,
                               TemporalWorkflowResult result) throws JsonProcessingException {
        return (O) objectMapper.readValue(result.outputJson(), registration.outputType());
    }

    private void validateInput(WorkflowStartRequest<?> request, WorkflowRegistration<?, ?> registration) {
        if (request.input() != null && !registration.inputType().isInstance(request.input())) {
            throw new IllegalArgumentException("Workflow input does not match registered type: "
                + request.workflowType());
        }
    }

    private void validateDuplicate(ActiveExecution<?> existing, WorkflowStartRequest<?> request) {
        if (!existing.workflowType.equals(request.workflowType())
            || !existing.tenantId.equals(request.tenantId())
            || !existing.idempotencyKey.equals(request.idempotencyKey())) {
            throw new IllegalStateException(
                "Workflow id is already active with a different type, tenant or idempotency key: "
                    + request.workflowId());
        }
    }

    private void validatePersistedIdentity(WorkflowStartRequest<?> request) {
        DescribeWorkflowExecutionResponse response = describe(request.workflowId());
        var info = response.getWorkflowExecutionInfo();
        String workflowType = memo(info, MEMO_WORKFLOW_TYPE, null);
        String tenantId = memo(info, MEMO_TENANT_ID, null);
        String idempotencyKey = memo(info, MEMO_IDEMPOTENCY_KEY, null);
        if (!request.workflowType().equals(workflowType)
            || !request.tenantId().equals(tenantId)
            || !request.idempotencyKey().equals(idempotencyKey)) {
            throw new IllegalStateException(
                "Workflow id already belongs to a different type, tenant or idempotency key: "
                    + request.workflowId());
        }
    }

    private DescribeWorkflowExecutionResponse describe(String workflowId) {
        return client.getWorkflowServiceStubs().blockingStub()
            .describeWorkflowExecution(DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(client.getOptions().getNamespace())
                .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId).build())
                .build());
    }

    private String memo(io.temporal.api.workflow.v1.WorkflowExecutionInfo info,
                        String key, String fallback) {
        var payload = info.getMemo().getFieldsMap().get(key);
        if (payload == null) {
            return fallback;
        }
        String decoded = client.getOptions().getDataConverter()
            .fromPayload(payload, String.class, String.class);
        return decoded == null || decoded.isBlank() ? fallback : decoded;
    }

    @SuppressWarnings("unchecked")
    private <O> CompletableFuture<O> castCompletion(ActiveExecution<?> execution) {
        return (CompletableFuture<O>) execution.completion;
    }

    private WorkflowExecutionStatus status(io.temporal.api.enums.v1.WorkflowExecutionStatus status) {
        return switch (status) {
            case WORKFLOW_EXECUTION_STATUS_RUNNING -> WorkflowExecutionStatus.RUNNING;
            case WORKFLOW_EXECUTION_STATUS_COMPLETED -> WorkflowExecutionStatus.COMPLETED;
            case WORKFLOW_EXECUTION_STATUS_CANCELED,
                 WORKFLOW_EXECUTION_STATUS_TERMINATED -> WorkflowExecutionStatus.CANCELLED;
            case WORKFLOW_EXECUTION_STATUS_FAILED,
                 WORKFLOW_EXECUTION_STATUS_TIMED_OUT -> WorkflowExecutionStatus.FAILED;
            default -> WorkflowExecutionStatus.PENDING;
        };
    }

    private long toEpochMillis(com.google.protobuf.Timestamp timestamp) {
        return timestamp.getSeconds() * 1_000L + timestamp.getNanos() / 1_000_000L;
    }

    private String message(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return text(current.getMessage(), current.getClass().getSimpleName());
    }

    private boolean isCancellation(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof java.util.concurrent.CancellationException
                || current instanceof CanceledFailure) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @Override
    public void close() {
        activity.close();
    }

    private static final class ActiveExecution<O> {
        private final String workflowId;
        private final String workflowType;
        private final String tenantId;
        private final String idempotencyKey;
        private final long startedAt = System.currentTimeMillis();
        private final CompletableFuture<O> completion = new CompletableFuture<>();
        private volatile WorkflowExecutionStatus status = WorkflowExecutionStatus.PENDING;
        private volatile Long finishedAt;
        private volatile String cancellationReason;
        private volatile String errorMessage;

        private ActiveExecution(WorkflowStartRequest<?> request) {
            this.workflowId = request.workflowId();
            this.workflowType = request.workflowType();
            this.tenantId = request.tenantId();
            this.idempotencyKey = request.idempotencyKey();
        }

        private WorkflowExecutionSnapshot snapshot() {
            return new WorkflowExecutionSnapshot(
                workflowId, workflowType, tenantId, idempotencyKey, status, 1,
                startedAt, finishedAt, cancellationReason, errorMessage);
        }
    }
}
