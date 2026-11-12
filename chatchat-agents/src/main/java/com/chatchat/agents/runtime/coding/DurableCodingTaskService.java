package com.chatchat.agents.runtime.coding;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.coding.CodingTaskOutcome.*;
import com.chatchat.common.runtime.evidence.RuntimeExecutionCheckpointPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.scheduling.TaskScheduler;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static com.chatchat.common.runtime.coding.CodingErrorCode.*;
import static com.chatchat.common.runtime.coding.CodingExecutionEvent.Type.*;
import static com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.*;

/** Coding-specific state machine over the existing Runtime checkpoint transaction. Never executes code. */
public final class DurableCodingTaskService implements CodingAgentSPI {
    public static final String CHECKPOINT_KEY = "coding.tasks.v1";
    public static final String ENFORCEMENT_LIMITATION = "A2 enforces cumulative admission reservations only; "
        + "model/process hard limits, instantaneous memory and disk enforcement require execution-node adapters.";
    private static final int MAX_TASKS = 64, MAX_OPERATIONS = 256, EVENT_HISTORY = 8192;
    private final RuntimeExecutionCheckpointPort store;
    private final ObjectMapper json;
    private final Clock clock;
    private final CodingTaskAccess access;
    private final CodingEvidenceVerifier evidence;
    private final CodingExecutionProofVerifier executionProof;
    private final Executor executor;
    private final TaskScheduler scheduler;
    /** Optional read projection. It has no task authority and must never block execution. */
    @FunctionalInterface public interface ProjectionListener {
        void committed(CodingTaskContract contract,CodingTaskOutcome outcome,List<CodingExecutionEvent> events);
    }
    private volatile ProjectionListener projectionListener;
    public void setProjectionListener(ProjectionListener listener){projectionListener=listener;}
    private void project(CodingJournal.Task task,List<CodingExecutionEvent> events){
        var listener=projectionListener;if(listener==null)return;
        try{listener.committed(task.contract,task.outcome,List.copyOf(events));}
        catch(RuntimeException unavailable){/* A failed read projection cannot change a committed task. */}
    }

    public DurableCodingTaskService(RuntimeExecutionCheckpointPort store, ObjectMapper json, Clock clock,
        CodingTaskAccess access, CodingEvidenceVerifier evidence, CodingExecutionProofVerifier executionProof,
        Executor executor, TaskScheduler scheduler) {
        this.store = Objects.requireNonNull(store); this.json = Objects.requireNonNull(json).copy()
            .findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.clock = Objects.requireNonNull(clock); this.access = Objects.requireNonNull(access);
        this.evidence = Objects.requireNonNull(evidence); this.executionProof = executionProof;
        this.executor = Objects.requireNonNull(executor); this.scheduler = Objects.requireNonNull(scheduler);
    }

    @Override public CodingTaskOutcome submit(CodingTaskContract contract) {
        Objects.requireNonNull(contract, "contract"); access.require(contract.scope(), SUBMIT);
        var committed=mutate(contract.scope(), SUBMIT, journal -> {
            CodingJournal.Task existing = journal.tasks.get(contract.taskId());
            String hash = CodingCanonicalHash.sha256(contract);
            if (existing != null) {
                require(hash.equals(existing.outcome.contractSha256()), TASK_ID_CONFLICT, "Task ID has another contract");
                return;
            }
            require(journal.tasks.size() < MAX_TASKS, ADAPTER_UNAVAILABLE, "Coding owner journal is at task capacity");
            require(now().isBefore(contract.budget().deadlineAt()), DEADLINE_EXCEEDED, "Task deadline has passed");
            var priorBudget = journal.budgets.putIfAbsent(contract.budget().budgetId(), contract.budget());
            require(priorBudget == null || priorBudget.equals(contract.budget()), TASK_ID_CONFLICT, "Shared budget limits are immutable");
            CodingJournal.Task task = new CodingJournal.Task(); task.contract = contract;
            Instant at = now();
            task.outcome = new CodingTaskOutcome(contract.schemaVersion(), contract.taskId(), contract.scope(), hash,
                Status.CREATED, 0, 1, at, at, null, null, false, null, "Coding task accepted durably", List.of(ENFORCEMENT_LIMITATION),
                contract.inputSnapshotRef(), null,null,null,null,null, ValidationStatus.NOT_RUN,
                new Reproducibility(ReproducibilityLevel.CONDITIONAL, List.of("Execution and reproducibility have not been verified"), List.of(contract.environmentRef())),
                usage(task), new ExecutionControl(null,null,false,false), null,null,Map.of());
            task.events.add(event(task, TASK_CREATED, null, List.of())); journal.tasks.put(contract.taskId(), task);
        }).tasks.get(contract.taskId());
        return committed.outcome;
    }

    @Override public CodingTaskOutcome get(KernelDataScope scope, String taskId) {
        return task(read(scope, READ), taskId).outcome;
    }

    public CodingTaskContract contract(KernelDataScope scope, String taskId) {
        return task(read(scope, READ), taskId).contract;
    }

    public com.fasterxml.jackson.databind.JsonNode privateState(KernelDataScope scope, String taskId, String key) {
        var value = task(read(scope, READ), taskId).privateState.get(key);
        return value == null ? null : value.deepCopy();
    }
    /** Read only the explicitly published Native telemetry groups; never the
     * private grant, control token or continuation binding. */
    public List<com.fasterxml.jackson.databind.JsonNode> nativeTelemetry(KernelDataScope scope,String taskId,String group){
        require(Set.of("model","execution","repair").contains(group),CONTRACT_INVALID,"Unknown Native telemetry group");
        String prefix="native-s3:"+group+":";
        return task(read(scope,READ),taskId).privateState.entrySet().stream().filter(e->e.getKey().startsWith(prefix))
            .<com.fasterxml.jackson.databind.JsonNode>map(e->e.getValue().deepCopy()).toList();
    }
    public record NativeUsage(String operationId,CodingJournal.OperationKind kind,Counters actual,boolean stopped,boolean meteringComplete){}
    public record NativeObservation(String channel,long sourceSequence,String sourceEventId,String sourceSha256,
        String evidenceRef,String normalizedType,String summary,Map<String,Object> payload,NativeUsage usage,
        String telemetryGroup,String telemetryId,com.fasterxml.jackson.databind.JsonNode telemetry){}
    /** Ingest an authenticated Host receipt into the existing authoritative
     * journal. Cursor, actual counters and read telemetry commit atomically.
     * This grants no execution and generates/edits no source. */
    public CodingTaskOutcome recordNativeObservation(KernelDataScope scope,String taskId,CodingJournal.Lease lease,long expectedRevision,NativeObservation observation){
        access.require(scope,WORKER);Objects.requireNonNull(observation);
        require(Set.of("core","provider").contains(observation.channel())&&observation.sourceSequence()>0&&observation.sourceSequence()<=CodingProtocol.MAX_SAFE_INTEGER,
            CONTRACT_INVALID,"Invalid Native event channel/cursor");
        require(observation.sourceSha256()!=null&&observation.sourceSha256().matches("[0-9a-f]{64}")&&evidence.isCommitted(scope,observation.evidenceRef()),OUTPUT_CONTRACT_VIOLATION,"Committed original Native receipt required");
        return mutateTask(scope,taskId,WORKER,task->{
            fence(task,lease);active(task);
            var nativeBinding=task.privateState.get("native-s3:binding");
            if(nativeBinding!=null&&nativeBinding.hasNonNull("executionEpoch"))
                require(Objects.equals(nativeBinding.path("sessionId").asText(),observation.payload().get("sessionId"))
                    &&Objects.equals(nativeBinding.path("executionEpoch").asText(),observation.payload().get("executionEpoch")),
                    AUTHORIZATION_DENIED,"Native observation belongs to a superseded execution");
            require(task.contract.metadataRefs().contains("runtime-coding:native-python-silo:v1")
                &&CodingLimit.unlimited(task.contract.budget().maxModelCalls())&&CodingLimit.unlimited(task.contract.budget().maxToolCalls())&&CodingLimit.unlimited(task.contract.budget().maxCodeExecutions()),AUTHORIZATION_DENIED,"Frozen Native execution profile required");
            String cursorKey="native-s3:cursor:"+observation.channel();var old=task.privateState.get(cursorKey);
            if(old!=null&&observation.sourceSequence()<=old.path("sequence").asLong()){
                if(observation.sourceSequence()==old.path("sequence").asLong())require(observation.sourceSha256().equals(old.path("sha256").asText()),TASK_ID_CONFLICT,"Native event identity changed");return;
            }
            revision(task,expectedRevision);
            var nativeUsage=observation.usage();
            if(nativeUsage!=null){
                require(task.privateState.containsKey("native-s3:binding"),AUTHORIZATION_DENIED,"Native session binding required");
                bounded(nativeUsage.operationId(),"Native operation");require(nativeUsage.kind()!=CodingJournal.OperationKind.ACCOUNTING,CONTRACT_INVALID,"Native observation is an actual invocation");
                var operation=task.operations.get(nativeUsage.operationId());
                if(operation==null){operation=new CodingJournal.Operation();operation.kind=nativeUsage.kind();operation.intentSha256=observation.sourceSha256();operation.reservation=nativeUsage.actual();
                    operation.startIntent=true;operation.executionHandle="native-s3:"+task.privateState.get("native-s3:binding").path("sessionId").asText()+":"+nativeUsage.operationId();
                    operation.startingFencingToken=lease.fencingToken();operation.startSequence=task.outcome.lastEventSequence()+1;task.operations.put(nativeUsage.operationId(),operation);}
                require(operation.kind==nativeUsage.kind()&&CodingBudgetMath.covers(nativeUsage.actual(),operation.actual),CONTRACT_INVALID,"Native actual counters cannot decrease/change kind");
                if(nativeUsage.stopped())require(executionProof!=null&&executionProof.confirmsStopped(scope,taskId,nativeUsage.operationId(),operation.executionHandle,observation.evidenceRef()),EXECUTION_OUTCOME_UNKNOWN,"Actual Native completion proof required");
                operation.actual=nativeUsage.actual();operation.meteringComplete=nativeUsage.meteringComplete();
                if(nativeUsage.stopped())operation.stopProofRef=observation.evidenceRef();
            }
            task.privateState.put(cursorKey,json.valueToTree(Map.of("sequence",observation.sourceSequence(),"sha256",observation.sourceSha256())));
            if(observation.telemetry()!=null){
                require(Set.of("model","execution","repair").contains(observation.telemetryGroup())&&observation.telemetryId()!=null&&observation.telemetryId().matches("[A-Za-z0-9_-]{1,96}"),CONTRACT_INVALID,"Invalid Native read telemetry");
                task.privateState.put("native-s3:"+observation.telemetryGroup()+":"+observation.telemetryId(),observation.telemetry().deepCopy());
            }
            advance(task,RECOVERY_RECONCILED,null,observation.summary(),null,List.of(observation.evidenceRef()),copy(task,task.outcome.status(),task.outcome.reasonCode(),task.outcome.executionControl(),null));
            int last=task.events.size()-1;var payload=new LinkedHashMap<String,Object>(observation.payload());
            payload.put("normalizedType",observation.normalizedType());payload.put("nativeEventId",observation.sourceEventId());payload.put("nativeEvidenceRef",observation.evidenceRef());
            payload.put("summary",observation.summary());payload.put("attemptId",taskId+":fence-"+lease.fencingToken());
            task.events.set(last,task.events.get(last).withPayload(payload));
        }).outcome;
    }

    /** Allocation and version increments share the existing owner journal lock and lease fencing. */
    public CodingScript allocateScript(KernelDataScope scope,String taskId,CodingJournal.Lease lease,String operationId) {
        require(operationId!=null&&operationId.matches("cline-[0-9a-f]{64}"),CONTRACT_INVALID,"Invalid script operation");
        final CodingScript[] result=new CodingScript[1];
        mutateTask(scope,taskId,WORKER,task->{
            fence(task,lease);active(task);deadline(task);
            require(task.outcome.executionControl().stopReason()==null,INVALID_STATE_TRANSITION,"Task is stopping");
            String key="script:"+operationId;
            if(task.privateState.containsKey(key)){result[0]=json.convertValue(task.privateState.get(key).path("script"),CodingScript.class);return;}
            require(unlimitedCounts(task)||task.privateState.size()<128,ADAPTER_UNAVAILABLE,"Private state capacity");
            int next=task.privateState.entrySet().stream().filter(e->e.getKey().startsWith("script:"))
                .mapToInt(e->e.getValue().path("script").path("scriptVersion").asInt()).max().orElse(0)+1;
            require(com.chatchat.common.runtime.coding.CodingLimit.permits(task.contract.budget().maxCodeExecutions(),next),BUDGET_EXCEEDED,"Script version budget exhausted");
            result[0]=CodingScript.allocate(task.contract,clock.instant(),next);
            task.privateState.put(key,json.valueToTree(Map.of("script",result[0])));
        });
        return result[0];
    }
    public CodingScript script(KernelDataScope scope,String taskId,String operationId) {
        var value=privateState(scope,taskId,"script:"+operationId);
        return value==null?null:json.convertValue(value.path("script"),CodingScript.class);
    }
    public void bindScriptHash(KernelDataScope scope,String taskId,CodingJournal.Lease lease,String operationId,String hash) {
        require(hash!=null&&hash.matches("[0-9a-f]{64}"),CONTRACT_INVALID,"Script hash required");
        mutateTask(scope,taskId,WORKER,task->{
            fence(task,lease);active(task);deadline(task);
            require(task.outcome.executionControl().stopReason()==null,INVALID_STATE_TRANSITION,"Task is stopping");
            require(task.privateState.containsKey("script:"+operationId),CONTRACT_INVALID,"Script must be allocated first");
            var allocation=(com.fasterxml.jackson.databind.node.ObjectNode)task.privateState.get("script:"+operationId);
            var previous=allocation.get("codeHash");
            require(previous==null||hash.equals(previous.asText()),TASK_ID_CONFLICT,"Script version is immutable");
            allocation.put("codeHash",hash);
        });
    }
    public String scriptFile(KernelDataScope scope,String taskId,String operationId) {
        var value=script(scope,taskId,operationId);
        return value==null?CodingScript.LEGACY_FILE:value.fileName();
    }
    public List<ExecutionFeedback> executionFeedback(KernelDataScope scope,String taskId){
        var task=task(read(scope,READ),taskId);
        return task.operations.entrySet().stream().filter(e->e.getValue().kind==CodingJournal.OperationKind.CODE)
            .sorted(Comparator.comparingLong(e->e.getValue().startSequence))
            .map(e->task.privateState.get("feedback:"+e.getKey())).filter(Objects::nonNull)
            .map(value->json.convertValue(value,ExecutionFeedback.class)).toList();
    }

    /** Internal extensions use the existing task journal and fencing transaction. No external I/O. */
    public void privateState(KernelDataScope scope, String taskId, CodingJournal.Lease lease, String key,
            com.fasterxml.jackson.databind.JsonNode expected, com.fasterxml.jackson.databind.JsonNode next) {
        require(key != null && key.length() <= 128 && next != null && next.toString().length() <= 262144,
            CONTRACT_INVALID, "Bounded private state required");
        mutateTask(scope, taskId, WORKER, task -> {
            fence(task, lease); active(task); deadline(task);
            require(task.outcome.executionControl().stopReason() == null, INVALID_STATE_TRANSITION, "Task is stopping");
            require(unlimitedCounts(task)||task.privateState.size() < 128 || task.privateState.containsKey(key), ADAPTER_UNAVAILABLE, "Private state capacity");
            require(Objects.equals(task.privateState.get(key), expected), TASK_ID_CONFLICT, "Private state CAS conflict");
            task.privateState.put(key, next.deepCopy());
        });
    }

    public com.fasterxml.jackson.databind.JsonNode sdkCheckpoint(KernelDataScope scope, String taskId) {
        var task = task(read(scope, READ), taskId);
        var saved = task.sdkCheckpoint;
        if (saved == null || unresolved(task) || saved.path("operationCount").asInt(-1) != task.operations.size())
            return null; // A crash beyond this boundary requires receipt reconciliation, never replay.
        return saved.deepCopy();
    }

    /** Bind a transport model attempt to the existing SDK boundary, retaining no second transcript. */
    public void bindModelCheckpoint(KernelDataScope scope,String taskId,CodingJournal.Lease lease,
            String operationId,long checkpointOrdinal) {
        require(operationId!=null&&operationId.matches("model-[1-9][0-9]*"),CONTRACT_INVALID,"Model operation required");
        mutateTask(scope,taskId,WORKER,task->{
            fence(task,lease);active(task);deadline(task);
            require(task.outcome.executionControl().stopReason()==null,INVALID_STATE_TRANSITION,"Task is stopping");
            var saved=task.sdkCheckpoint;
            require(saved!=null&&saved.path("checkpointOrdinal").asLong(-1)==checkpointOrdinal
                && saved.path("modelOrdinal").asLong(-1)+1==Long.parseLong(operationId.substring(6)),
                CONTRACT_INVALID,"Model attempt differs from its SDK boundary");
            var binding=json.createObjectNode().put("checkpointOrdinal",checkpointOrdinal)
                .put("sdkSessionId",saved.path("sdkSessionId").asText()).put("checkpointSha256",checkpointHash(saved));
            String key="model-checkpoint:"+operationId;var prior=task.privateState.get(key);
            require(prior==null||prior.equals(binding),TASK_ID_CONFLICT,"Model checkpoint binding changed");
            require(unlimitedCounts(task)||prior!=null||task.privateState.size()<128,ADAPTER_UNAVAILABLE,"Private state capacity");
            task.privateState.put(key,binding);
        });
    }

    /** Only the next stopped MODEL receipt may be consumed by the restored SDK transport. */
    public com.fasterxml.jackson.databind.JsonNode sdkCheckpointForModelReceipt(KernelDataScope scope,String taskId,
            Set<String> verifiedResponses) {
        var task=task(read(scope,READ),taskId);var saved=task.sdkCheckpoint;
        if(saved==null||unresolved(task)||!saved.path("operationIds").isArray())return null;
        var covered=new HashSet<String>();saved.path("operationIds").forEach(v->covered.add(v.asText()));
        if(covered.size()!=saved.path("operationCount").asInt(-1)||!task.operations.keySet().containsAll(covered))return null;
        var extra=task.operations.keySet().stream().filter(id->!covered.contains(id)).toList();
        if(extra.size()!=1)return null;
        String id=extra.get(0);var op=task.operations.get(id);var binding=task.privateState.get("model-checkpoint:"+id);
        if(!verifiedResponses.contains(id)||op.kind!=CodingJournal.OperationKind.MODEL||!op.startIntent||op.stopProofRef==null
            ||!id.equals("model-"+(saved.path("modelOrdinal").asLong(-1)+1))||binding==null
            ||!checkpointHash(saved).equals(binding.path("checkpointSha256").asText()))return null;
        var next=(com.fasterxml.jackson.databind.node.ObjectNode)saved.deepCopy();
        next.put("receiptReplayOperationId",id);
        return next;
    }

    public boolean modelCheckpointMatches(KernelDataScope scope,String taskId,String operationId,long ordinal) {
        var task=task(read(scope,READ),taskId);var binding=task.privateState.get("model-checkpoint:"+operationId);
        return binding!=null&&task.sdkCheckpoint!=null&&binding.path("checkpointOrdinal").asLong(-1)==ordinal
            && checkpointHash(task.sdkCheckpoint).equals(binding.path("checkpointSha256").asText());
    }

    private String checkpointHash(com.fasterxml.jackson.databind.JsonNode value) {
        try {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(json.writeValueAsBytes(json.convertValue(value,Object.class))));}
        catch(java.io.IOException|java.security.NoSuchAlgorithmException invalid){throw new IllegalStateException(invalid);}
    }

    /** Resume a closed response-less MODEL request only. Never rebase unknown code/tool side effects. */
    public com.fasterxml.jackson.databind.JsonNode sdkCheckpointAfterLocalModelStops(KernelDataScope scope,String taskId,
            Set<String> stoppedWithoutResponse) {
        var task=task(read(scope,READ),taskId);var saved=task.sdkCheckpoint;
        if(saved==null || unresolved(task) || !saved.path("operationIds").isArray())return null;
        var covered=new HashSet<String>();saved.path("operationIds").forEach(v->covered.add(v.asText()));
        if(covered.size()!=saved.path("operationCount").asInt(-1) || !task.operations.keySet().containsAll(covered))return null;
        var additional=task.operations.keySet().stream().filter(id->!covered.contains(id)).toList();
        if(additional.isEmpty() || !stoppedWithoutResponse.containsAll(additional))return null;
        for(String id:additional){var op=task.operations.get(id);
            if(op.kind!=CodingJournal.OperationKind.MODEL || !op.startIntent || op.stopProofRef==null
                || !id.matches("model-[1-9][0-9]*"))return null;
        }
        var next=(com.fasterxml.jackson.databind.node.ObjectNode)saved.deepCopy();
        long ordinal=task.operations.keySet().stream().filter(id->id.matches("model-[1-9][0-9]*"))
            .mapToLong(id->Long.parseLong(id.substring(6))).max().orElse(0);
        next.put("modelOrdinal",ordinal);next.put("operationCount",task.operations.size());
        next.set("operationIds",json.valueToTree(task.operations.keySet()));
        var messages=(com.fasterxml.jackson.databind.node.ArrayNode)next.path("messages");
        int limit=task.contract.metadataRefs().contains("runtime-coding:sql-metadata-tools:v1")?192:64;
        if(messages.size()>=limit)return null;
        messages.add(json.valueToTree(Map.of("role","user","content",List.of(Map.of("type","text","text",
            "Runtime recovery fact: prior model request(s) "+additional+" ended locally without a delivered response. "
            +"No tools from those responses were dispatched. Their unknown provider usage remains reserved. "
            +"Continue this SAME task from its existing code/evidence under the remaining original budget; do not replay operations.")))));
        return next;
    }

    /** Server-verified stopped feedback shares the existing journal transaction and idempotency. */
    public boolean recordExecutionFeedback(KernelDataScope scope, String taskId, CodingJournal.Lease lease,
            ExecutionFeedback feedback) {
        require(taskId.equals(feedback.taskId()) && Objects.equals(scope.runId(), feedback.traceId()),
            CONTRACT_INVALID, "Execution feedback identity mismatch");
        boolean[] inserted = {false};
        mutateTask(scope, taskId, WORKER, task -> {
            fence(task, lease);
            var operation = task.operations.get(feedback.executionId());
            require(operation != null && operation.kind == CodingJournal.OperationKind.CODE && operation.stopProofRef != null,
                INVALID_STATE_TRANSITION, "Execution feedback requires independently confirmed stop");
            String key = "feedback:" + feedback.executionId();
            if (task.privateState.containsKey(key)) return;
            require(unlimitedCounts(task)||task.privateState.size() < 128, ADAPTER_UNAVAILABLE, "Private state capacity");
            com.fasterxml.jackson.databind.JsonNode value = json.valueToTree(feedback);
            task.privateState.put(key, value);
            advance(task, CodingExecutionEvent.Type.RECOVERY_RECONCILED, null,
                "Python feedback: " + feedback.stage(), feedback.executionId(), List.of(operation.stopProofRef),
                copy(task, task.outcome.status(), task.outcome.reasonCode(), task.outcome.executionControl(), null));
            int last = task.events.size() - 1;
            var event = task.events.get(last);
            task.events.set(last, new CodingExecutionEvent(event.schemaVersion(), event.eventId(), event.taskId(),
                event.scope(), event.sequence(), event.revision(), event.occurredAt(), event.type(), event.operationId(),
                event.attempt(), event.status(), event.reasonCode(), event.evidenceRefs(), event.usage(), event.summary(),
                Map.of("type", "EXECUTION_FEEDBACK", "stage", feedback.stage(), "feedback", json.convertValue(feedback, Map.class))));
            inserted[0] = true;
        });
        return inserted[0];
    }

    /** Same owner lock/transaction as leases, budget reservations and stop requests. */
    public void saveSdkCheckpoint(KernelDataScope scope, String taskId, CodingJournal.Lease lease,
            com.fasterxml.jackson.databind.JsonNode checkpoint) {
        var contract=task(read(scope,WORKER),taskId).contract;
        boolean metadata=contract.metadataRefs().contains("runtime-coding:sql-metadata-tools:v1")
            && contract.outputContract().resultFormat()==CodingTaskContract.ResultFormat.WORKSPACE_FILES;
        // Versioned analysis carries the complete input dictionary and reviewed reports
        // in the native SDK transcript. Reuse the existing bounded metadata envelope.
        boolean fileAnalysis=CodingProtocol.ANALYSIS_VERSION.equals(contract.schemaVersion())
            && contract.runtimeContext().containsKey("inputFiles");
        require(checkpoint != null && checkpoint.isObject() && checkpoint.toString().length() <= (metadata||fileAnalysis?750000:262144),
            CONTRACT_INVALID, "Bounded SDK checkpoint required");
        var value = checkpoint.deepCopy();
        mutateTask(scope, taskId, WORKER, task -> {
            fence(task, lease); active(task); deadline(task);
            require(task.outcome.executionControl().stopReason() == null && !unresolved(task),
                EXECUTION_OUTCOME_UNKNOWN, "Checkpoint requires a settled active boundary");
            require(value.path("inputSnapshotRef").asText().equals(task.contract.inputSnapshotRef())
                && value.path("workspaceRef").asText().equals(task.contract.workspaceRef())
                && value.path("contractHash").asText().equals(task.outcome.contractSha256()),
                CONTRACT_INVALID, "Checkpoint input/workspace binding differs");
            long ordinal = task.operations.entrySet().stream()
                .filter(e -> e.getValue().kind == CodingJournal.OperationKind.MODEL && e.getKey().matches("model-[1-9][0-9]*"))
                .mapToLong(e -> Long.parseLong(e.getKey().substring(6))).max().orElse(0);
            require(value.path("modelOrdinal").asLong(-1) == ordinal && value.path("eventOrdinal").asLong(-1) >= 0,
                CONTRACT_INVALID, "Checkpoint ordinals differ from durable operations");
            require(value.path("messages").isArray() && value.path("messages").size() > 0
                && value.path("messages").size() <= (metadata?192:64) && value.path("sdkSessionId").asText().matches("[a-zA-Z0-9-]{1,64}"),
                CONTRACT_INVALID, "SDK messages/session identity required");
            // Complete call/result pairs only; restore() cannot resume pending SDK calls.
            Set<String> pending = new HashSet<>();
            for (var message : value.path("messages")) {
                require(message.path("content").isArray(), CONTRACT_INVALID, "SDK content must be an array");
                for (var part : message.path("content")) {
                    String type = part.path("type").asText();
                    require(Set.of("text", "tool-call", "tool-result").contains(type), CONTRACT_INVALID, "Unsupported SDK message part");
                    if (type.equals("tool-call")) require(pending.add(part.path("toolCallId").asText()), CONTRACT_INVALID, "Duplicate pending call");
                    if (type.equals("tool-result")) require(pending.remove(part.path("toolCallId").asText()), CONTRACT_INVALID, "Unmatched tool result");
                }
            }
            require(pending.isEmpty(), EXECUTION_OUTCOME_UNKNOWN, "Pending SDK tool calls cannot be restored");
            long expected = task.sdkCheckpoint == null ? 0 : task.sdkCheckpoint.path("checkpointOrdinal").asLong();
            if (task.sdkCheckpoint != null && value.path("checkpointOrdinal").asLong() == expected) {
                var previous = task.sdkCheckpoint.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) previous).remove(List.of("operationCount","operationIds"));
                ((com.fasterxml.jackson.databind.node.ObjectNode) value).remove(List.of("operationCount","operationIds"));
                require(previous.equals((left,right) -> left.isNumber() && right.isNumber()
                    ? left.decimalValue().compareTo(right.decimalValue()) : left.equals(right) ? 0 : 1, value),
                    TASK_ID_CONFLICT, "Checkpoint identity conflict");
                return;
            }
            require(value.path("checkpointOrdinal").asLong(-1) == expected + 1, TASK_ID_CONFLICT, "Checkpoint CAS conflict");
            ((com.fasterxml.jackson.databind.node.ObjectNode) value).put("operationCount", task.operations.size());
            ((com.fasterxml.jackson.databind.node.ObjectNode) value).set("operationIds",json.valueToTree(task.operations.keySet()));
            task.sdkCheckpoint = value;
        });
    }

    /** A stopped operation is still an external call and must not be replayed by a fresh SDK session. */
    public boolean hasStartedOperations(KernelDataScope scope, String taskId) {
        return task(read(scope, READ), taskId).operations.values().stream().anyMatch(op -> op.startIntent);
    }
    public long startedDataRounds(KernelDataScope scope, String taskId) {
        return task(read(scope, READ), taskId).operations.values().stream().filter(op -> op.startIntent
            && op.executionHandle != null && op.executionHandle.startsWith("runtime-data:")).count();
    }

    @Override public CodingTaskOutcome cancel(KernelDataScope scope, String taskId, String reason) {
        bounded(reason, "cancellation reason");
        return mutateTask(scope, taskId, STOP, task -> {
            if (!task.outcome.status().terminal() && !task.outcome.cancellationRequested())
                requestStop(task, CANCEL_REQUESTED, "Cancellation requested: " + reason);
        }).outcome;
    }

    @Override public CodingTaskOutcome resume(KernelDataScope scope, String taskId) {
        return mutateTask(scope, taskId, RECOVER, task -> {
            if (task.outcome.status().terminal()) return;
            deadline(task);
            boolean unknown = unresolved(task);
            if (unknown != task.outcome.executionControl().reconciliationRequired())
                advance(task, RECOVERY_RECONCILED, reasonAfterReconciliation(task,unknown),
                    unknown ? "Existing execution must be reconciled before new work" : "No unresolved start intent", null, List.of(),
                    copy(task, task.outcome.status(), reasonAfterReconciliation(task,unknown),
                        control(task, false, unknown), null));
        }).outcome;
    }

    public CodingJournal.Lease claim(KernelDataScope scope, String taskId, String owner, Duration duration, long expectedRevision) {
        bounded(owner, "owner"); leaseDuration(duration);
        return mutateTask(scope, taskId, WORKER, task -> {
            revision(task, expectedRevision);
            Instant at = now();
            require(!at.isBefore(task.lease.leaseUntil()), INVALID_STATE_TRANSITION, "Task lease is already held");
            require(task.lease.fencingToken() < CodingProtocol.MAX_SAFE_INTEGER, CONTRACT_INVALID, "Fencing token exhausted");
            task.lease = new CodingJournal.Lease(owner, at.plus(duration), task.lease.fencingToken()+1);
            if (!task.outcome.status().terminal()) deadline(task);
            boolean unknown = unresolved(task);
            Status status = task.outcome.status() == Status.CREATED ? Status.RUNNING : task.outcome.status();
            var candidate = copy(task, status, reasonAfterReconciliation(task,unknown),
                control(task, task.outcome.status().terminal(), unknown), null);
            advance(task, task.outcome.status()==Status.CREATED ? TASK_STARTED : RECOVERY_RECONCILED,
                unknown ? EXECUTION_OUTCOME_UNKNOWN : null, "Execution ownership acquired; no operation was started",
                null, List.of(), candidate);
        }).lease;
    }

    public CodingJournal.Lease renew(KernelDataScope scope, String taskId, CodingJournal.Lease lease,
        Duration duration, long expectedRevision) {
        leaseDuration(duration);
        return mutateTask(scope, taskId, WORKER, task -> {
            revision(task,expectedRevision); fence(task,lease);
            task.lease = new CodingJournal.Lease(lease.owner(),now().plus(duration),lease.fencingToken());
            advance(task,RECOVERY_RECONCILED,null,"Lease renewed",null,List.of(),copy(task,task.outcome.status(),task.outcome.reasonCode(),task.outcome.executionControl(),null));
        }).lease;
    }
    public CodingTaskOutcome enforceDeadline(KernelDataScope scope,String taskId,CodingJournal.Lease lease,long expectedRevision){
        return mutateTask(scope,taskId,WORKER,task->{revision(task,expectedRevision);fence(task,lease);if(!task.outcome.status().terminal())deadline(task);}).outcome;
    }
    /** Orchestrator terminal failure revokes delivery immediately, but cannot
     * become terminal until actual execution stop proofs are reconciled. */
    public CodingTaskOutcome requestFailureStop(KernelDataScope scope,String taskId,CodingJournal.Lease lease,long expectedRevision,String reason){
        bounded(reason,"failure reason");
        return mutateTask(scope,taskId,WORKER,task->{revision(task,expectedRevision);fence(task,lease);requestStop(task,EXECUTION_FAILED,"Workflow execution failed: "+reason);}).outcome;
    }

    /** Same-task recovery after all starts are proven stopped; fence out the ended SDK instance. */
    public CodingJournal.Lease recoveryFence(KernelDataScope scope,String taskId,CodingJournal.Lease lease,long expectedRevision) {
        return mutateTask(scope,taskId,WORKER,task->{
            revision(task,expectedRevision);fence(task,lease);active(task);deadline(task);
            require(task.outcome.executionControl().stopReason()==null && !unresolved(task),EXECUTION_OUTCOME_UNKNOWN,"Recovery requires all operations stopped");
            require(task.lease.fencingToken()<CodingProtocol.MAX_SAFE_INTEGER,CONTRACT_INVALID,"Fencing token exhausted");
            task.lease=new CodingJournal.Lease(lease.owner(),now().plus(Duration.ofMinutes(5)),lease.fencingToken()+1);
            advance(task,RECOVERY_RECONCILED,null,"Ended SDK fenced for same-task checkpoint recovery",null,List.of(),
                copy(task,task.outcome.status(),task.outcome.reasonCode(),control(task,false,false),null));
        }).lease;
    }

    /** Reconciliation-only CAS for already admitted native metadata; no dispatch, journal or fence bypass. */
    public void reconcileNativeState(KernelDataScope scope, String taskId, CodingJournal.Lease lease, String key,
            com.fasterxml.jackson.databind.JsonNode expected, com.fasterxml.jackson.databind.JsonNode next) {
        require(key != null && key.startsWith("native:") && key.length() <= 128 && expected != null
            && next != null && next.toString().length() <= 262144, CONTRACT_INVALID, "Bounded existing native state required");
        mutateTask(scope, taskId, WORKER, task -> {
            fence(task, lease);
            require(task.privateState.containsKey(key) && Objects.equals(task.privateState.get(key), expected),
                TASK_ID_CONFLICT, "Native reconciliation CAS conflict");
            task.privateState.put(key, next.deepCopy());
        });
    }

    /** Read-only projection of committed native bindings; no secondary index can hide a dispatch. */
    public Map<String, com.fasterxml.jackson.databind.JsonNode> nativeOperationStates(KernelDataScope scope, String taskId) {
        var result = new LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        task(read(scope, READ), taskId).privateState.forEach((key, value) -> {
            if (key.matches("native:cline-[0-9a-f]{64}")) result.put(key.substring(7), value.deepCopy());
        });
        return Collections.unmodifiableMap(result);
    }

    public record ReservationResult(boolean admitted, CodingTaskOutcome outcome) { }

    public ReservationResult reserve(KernelDataScope scope, String taskId, CodingJournal.Lease lease,
        long expectedRevision, String operationId, String intentSha256, Counters reservation) {
        Objects.requireNonNull(reservation);
        var kind=reservation.codeExecutions()>0 ? CodingJournal.OperationKind.CODE
            : reservation.modelCalls()>0 ? CodingJournal.OperationKind.MODEL
            : reservation.toolCalls()>0 ? CodingJournal.OperationKind.TOOL : CodingJournal.OperationKind.ACCOUNTING;
        return reserve(scope,taskId,lease,expectedRevision,operationId,intentSha256,kind,reservation);
    }

    public ReservationResult reserve(KernelDataScope scope, String taskId, CodingJournal.Lease lease,
        long expectedRevision, String operationId, String intentSha256, CodingJournal.OperationKind kind, Counters reservation) {
        bounded(operationId,"operationId");
        require(intentSha256 != null && intentSha256.matches("[0-9a-f]{64}"),CONTRACT_INVALID,"Operation intent hash is required");
        Objects.requireNonNull(reservation);
        Objects.requireNonNull(kind);
        require(switch(kind) {
            case CODE -> reservation.codeExecutions()>0;
            case MODEL -> reservation.modelCalls()>0 && reservation.modelTokens()>0;
            case TOOL -> reservation.toolCalls()>0;
            case ACCOUNTING -> reservation.modelCalls()==0 && reservation.toolCalls()==0 && reservation.codeExecutions()==0;
        },CONTRACT_INVALID,"Operation kind requires its own call reservation");
        require(reservation.peakMemoryBytes()==0,CONTRACT_INVALID,"Instantaneous memory is not a cumulative reservation");
        AtomicBoolean admitted = new AtomicBoolean();
        var journal = mutate(scope,WORKER, state -> {
            var task = task(state,taskId); fence(task,lease);
            var existing = task.operations.get(operationId);
            if (existing != null) {
                require(existing.intentSha256.equals(intentSha256) && existing.reservation.equals(reservation) && existing.kind==kind,TASK_ID_CONFLICT,"Operation identity conflict");
                admitted.set(true); return;
            }
            revision(task,expectedRevision); active(task); deadline(task);
            if (task.outcome.executionControl().stopReason()!=null) return;
            if(kind==CodingJournal.OperationKind.CODE && task.privateState.containsKey("execution-repair-policy")){
                var codes=task.operations.entrySet().stream().filter(e->e.getValue().kind==CodingJournal.OperationKind.CODE).toList();
                if(!codes.isEmpty()){
                    var previous=codes.get(codes.size()-1);
                    var feedback=task.privateState.get("feedback:"+previous.getKey());
                    require(previous.getValue().stopProofRef!=null&&feedback!=null,EXECUTION_OUTCOME_UNKNOWN,
                        "Reconcile and commit previous execution feedback before new code admission");
                    int limit=task.privateState.get("execution-repair-policy").path("maxAutoRepairs").asInt(0);
                    require(feedback.path("resultAvailable").asBoolean() || CodingLimit.hasRoom(limit,feedback.path("repair").path("attemptsUsed").asInt())
                            && feedback.path("repair").path("allowed").asBoolean(),
                        BUDGET_EXCEEDED,"Automatic repair limit or policy prohibits another execution");
                }
            }
            require(task.operations.values().stream().noneMatch(op -> op.startIntent && op.stopProofRef == null
                && op.executionHandle != null && op.executionHandle.startsWith("runtime-data:")),
                EXECUTION_OUTCOME_UNKNOWN, "WAITING_FOR_DATA: new work is blocked until acquisition is reconciled");
            require(!task.outcome.executionControl().reconciliationRequired(),EXECUTION_OUTCOME_UNKNOWN,"Reconcile previous execution first");
            require(unlimitedCounts(task)||task.operations.size()<MAX_OPERATIONS,ADAPTER_UNAVAILABLE,"Task operation capacity reached");
            Counters total = CodingBudgetMath.add(account(state,task.contract.budget().budgetId()),reservation);
            if (!CodingBudgetMath.within(total,task.contract.budget())) {
                requestStop(task,BUDGET_EXCEEDED,"Cumulative budget reservation denied"); return;
            }
            var operation = new CodingJournal.Operation(); operation.kind=kind; operation.intentSha256=intentSha256; operation.reservation=reservation;
            task.operations.put(operationId,operation); admitted.set(true);
            advance(task,BUDGET_UPDATED,null,"Operation budget reserved",operationId,List.of(),copy(task,task.outcome.status(),task.outcome.reasonCode(),task.outcome.executionControl(),null));
        });
        return new ReservationResult(admitted.get(),task(journal,taskId).outcome);
    }

    /** Persists permission to attempt execution. It is not evidence that execution occurred. */
    public record StartIntentResult(boolean dispatchIntentCreated, CodingTaskOutcome outcome) { }

    public StartIntentResult startIntent(KernelDataScope scope, String taskId, CodingJournal.Lease lease,
        long expectedRevision, String operationId, String executionHandle) {
        bounded(executionHandle,"executionHandle");
        AtomicBoolean created=new AtomicBoolean();
        var journal=mutate(scope,WORKER,state -> {
            var task = task(state,taskId); fence(task,lease); active(task);
            var op = operation(task,operationId);
            require(op.kind!=CodingJournal.OperationKind.ACCOUNTING,CONTRACT_INVALID,"Accounting reservations cannot dispatch work");
            if(op.kind==CodingJournal.OperationKind.CODE)require(task.contract.authorization().allowedSideEffects()
                .contains(CodingTaskContract.SideEffect.CODE_EXECUTION),AUTHORIZATION_DENIED,"Code execution is not authorized");
            deadline(task);
            if(task.outcome.executionControl().stopReason()!=null)return;
            if (op.startIntent) {
                require(Objects.equals(op.executionHandle,executionHandle) && op.startingFencingToken==lease.fencingToken(),
                    EXECUTION_OUTCOME_UNKNOWN,"A previous start intent cannot be replayed"); return;
            }
            require(!op.meteringComplete,INVALID_STATE_TRANSITION,"Settled reservations cannot grant a new dispatch");
            revision(task,expectedRevision); active(task); deadline(task);
            if (task.outcome.executionControl().stopReason()!=null) return;
            require(!task.outcome.executionControl().reconciliationRequired(),EXECUTION_OUTCOME_UNKNOWN,"Reconciliation is required");
            if (!CodingBudgetMath.within(account(state,task.contract.budget().budgetId()),task.contract.budget())) {
                requestStop(task,BUDGET_EXCEEDED,"Shared cumulative budget exceeded"); return;
            }
            op.startIntent=true; op.executionHandle=executionHandle; op.startingFencingToken=lease.fencingToken();
            op.startSequence=task.outcome.lastEventSequence()+1;
            created.set(true);
            // No EXECUTION_STARTED event: A2 has not received execution-node confirmation.
            advance(task,RECOVERY_RECONCILED,null,"Start intent committed; execution result is not yet known",operationId,List.of(),
                copy(task,task.outcome.status(),task.outcome.reasonCode(),control(task,false,false),null));
        });
        return new StartIntentResult(created.get(),journal.tasks.get(taskId).outcome);
    }

    /** Actual counters are cumulative lower bounds for this operation, never deltas. */
    public CodingTaskOutcome settle(KernelDataScope scope, String taskId, CodingJournal.Lease lease,
        long expectedRevision, String operationId, Counters actual, boolean meteringComplete) {
        Objects.requireNonNull(actual);
        return mutate(scope,WORKER,journal -> {
            var task=task(journal,taskId); fence(task,lease); var op=operation(task,operationId);
            if (op.actual.equals(actual) && op.meteringComplete==meteringComplete) return;
            revision(task,expectedRevision);
            require(op.startIntent || (actual.modelCalls()==0 && actual.toolCalls()==0 && actual.codeExecutions()==0
                && actual.cpuMillis()==0 && actual.peakMemoryBytes()==0 && actual.outputBytes()==0),
                EXECUTION_OUTCOME_UNKNOWN,"Execution usage without a durable start intent requires investigation");
            require(CodingBudgetMath.covers(actual,op.actual),CONTRACT_INVALID,"Actual usage cannot decrease");
            require(!op.meteringComplete || meteringComplete,CONTRACT_INVALID,"Final metering cannot become unknown");
            op.actual=actual; op.meteringComplete=meteringComplete;
            advance(task,BUDGET_UPDATED,null,meteringComplete ? "Actual usage settled" : "Unknown usage retains unspent reservation",operationId,List.of(),
                copy(task,task.outcome.status(),task.outcome.reasonCode(),task.outcome.executionControl(),null));
            if (!CodingBudgetMath.within(account(journal,task.contract.budget().budgetId()),task.contract.budget())) {
                for (var other:journal.tasks.values()) if (other.contract.budget().budgetId().equals(task.contract.budget().budgetId())
                    && !other.outcome.status().terminal()) requestStop(other,BUDGET_EXCEEDED,"Shared budget exceeded by actual usage");
            }
        }).tasks.get(taskId).outcome;
    }

    public CodingTaskOutcome reconcileStopped(KernelDataScope scope, String taskId, CodingJournal.Lease lease,
        long expectedRevision, String operationId, String proofRef) {
        access.require(scope,WORKER); bounded(proofRef,"proofRef");
        var prior=task(read(scope,WORKER),taskId); var op=operation(prior,operationId);
        require(op.startIntent,CONTRACT_INVALID,"No start intent to reconcile");
        require(executionProof!=null && executionProof.confirmsStopped(scope,taskId,operationId,op.executionHandle,proofRef),
            EXECUTION_OUTCOME_UNKNOWN,"No verified execution-node stop confirmation is available");
        return mutateTask(scope,taskId,WORKER,task -> {
            revision(task,expectedRevision); fence(task,lease);
            var current=operation(task,operationId);
            require(Objects.equals(current.executionHandle,op.executionHandle),TASK_ID_CONFLICT,"Execution identity changed");
            if(current.stopProofRef!=null) {
                require(current.stopProofRef.equals(proofRef),TASK_ID_CONFLICT,"Stop evidence is immutable"); return;
            }
            current.stopProofRef=proofRef;
            advance(task,RECOVERY_RECONCILED,null,"Verified stop evidence recorded",operationId,List.of(proofRef),
                copy(task,task.outcome.status(),reasonAfterReconciliation(task,unresolved(task)),control(task,false,unresolved(task)),null));
        }).outcome;
    }

    /** Durable absence of a start intent is proof of no dispatch, not fabricated Sandbox confirmation. */
    public CodingTaskOutcome finishStoppedWithoutExecution(KernelDataScope scope,String taskId,long expectedRevision) {
        return mutateTask(scope,taskId,STOP,task -> {
            if (task.outcome.status().terminal()) return;
            revision(task,expectedRevision);
            require(task.outcome.executionControl().stopReason()!=null,INVALID_STATE_TRANSITION,"Stop request is required");
            require(task.operations.values().stream().noneMatch(op->op.startIntent),EXECUTION_OUTCOME_UNKNOWN,"A start intent requires execution-node reconciliation");
            Status target=stopStatus(task.outcome.executionControl().stopReason());
            advance(task,TASK_FINISHED,task.outcome.executionControl().stopReason(),"Stopped before any external dispatch",null,List.of(),
                copy(task,target,task.outcome.executionControl().stopReason(),control(task,true,false),null));
        }).outcome;
    }

    /** A signed worker completion may close a stop only after every started operation has a verified stop proof. */
    public CodingTaskOutcome finishStoppedAfterReconciliation(KernelDataScope scope,String taskId,
        CodingJournal.Lease lease,long expectedRevision) {
        return mutateTask(scope,taskId,WORKER,task -> {
            if (task.outcome.status().terminal()) return;
            revision(task,expectedRevision); fence(task,lease);
            var stop=task.outcome.executionControl().stopReason();
            require(stop!=null,INVALID_STATE_TRANSITION,"Stop request is required");
            require(!unresolved(task),EXECUTION_OUTCOME_UNKNOWN,"Started operation still needs stop proof");
            Status target=stopStatus(stop);
            advance(task,TASK_FINISHED,stop,"Worker stopped; all started operations reconciled",null,List.of(),
                copy(task,target,stop,control(task,true,false),null));
        }).outcome;
    }

    public CodingTaskOutcome commitOutcome(KernelDataScope scope,String taskId,CodingJournal.Lease lease,
        long expectedRevision,CodingTaskOutcome proposal) {
        access.require(scope,EVIDENCE); Objects.requireNonNull(proposal);
        require(proposal.status().terminal(),CONTRACT_INVALID,"Only a terminal outcome may be committed");
        var refs=references(proposal);
        for (String ref:refs) require(evidence.isCommitted(scope,ref),OUTPUT_CONTRACT_VIOLATION,"Uncommitted evidence reference: "+ref);
        return mutateTask(scope,taskId,EVIDENCE,task -> {
            revision(task,expectedRevision); fence(task,lease);
            require(!task.outcome.status().terminal(),INVALID_STATE_TRANSITION,"Terminal results are immutable");
            proposal.validateAgainst(task.contract);
            require(!unresolved(task),EXECUTION_OUTCOME_UNKNOWN,"Active or unknown execution prevents a terminal result");
            deadline(task);
            var stop=task.outcome.executionControl().stopReason();
            // Return the persisted stop snapshot, including a deadline discovered by this call.
            // Throwing here would roll back that newly accepted stop request.
            if(stop!=null && proposal.status()!=(stopStatus(stop)))return;
            // All control fields, revisions and usage are built from the authoritative journal, not the proposal.
            advance(task,TASK_FINISHED,proposal.reasonCode(),proposal.summary(),null,refs,
                copy(task,proposal.status(),proposal.reasonCode(),control(task,true,false),proposal));
        }).outcome;
    }

    public CodingTaskOutcome reconcileTerminalEvidence(KernelDataScope scope,String taskId,CodingJournal.Lease lease,
        long expectedRevision,List<String> committedRefs) {
        access.require(scope,EVIDENCE); Objects.requireNonNull(committedRefs);
        require(!committedRefs.isEmpty() && committedRefs.size()<=64,CONTRACT_INVALID,"Bounded evidence references required");
        for(String ref:committedRefs)require(evidence.isCommitted(scope,ref),OUTPUT_CONTRACT_VIOLATION,"Evidence is not committed");
        return mutateTask(scope,taskId,EVIDENCE,task->{
            revision(task,expectedRevision); fence(task,lease);
            require(task.outcome.status().terminal(),INVALID_STATE_TRANSITION,"Task must already be terminal");
            advance(task,RECOVERY_RECONCILED,null,"Additional immutable evidence reconciled after completion",null,List.copyOf(committedRefs),
                copy(task,task.outcome.status(),task.outcome.reasonCode(),task.outcome.executionControl(),null));
        }).outcome;
    }

    public List<CodingExecutionEvent> events(KernelDataScope scope,String taskId,long afterSequence,int limit) {
        return events(scope, taskId, afterSequence, limit, access);
    }

    private List<CodingExecutionEvent> events(KernelDataScope scope,String taskId,long afterSequence,int limit,
                                              CodingTaskAccess reader) {
        require(afterSequence>=0 && limit>0 && limit<=256,CONTRACT_INVALID,"Invalid event cursor or limit");
        var task=task(read(scope,READ,reader),taskId);
        long first=task.events.isEmpty() ? task.outcome.lastEventSequence()+1 : task.events.get(0).sequence();
        require(afterSequence>=first-1,EVENT_CURSOR_EXPIRED,"Event history was compacted; earliestSequence="+first+"; read authoritative snapshot");
        require(afterSequence<=task.outcome.lastEventSequence(),CONTRACT_INVALID,"Event cursor is ahead of task");
        return task.events.stream().filter(event->event.sequence()>afterSequence).limit(limit).toList();
    }

    public CodingJournal.Lease ownership(KernelDataScope scope,String taskId) { return task(read(scope,READ),taskId).lease; }

    /** Worker reports are durable observations; they never establish execution, usage or validation facts. */
    public CodingTaskOutcome recordWorkerProgress(KernelDataScope scope, String taskId,
            CodingJournal.Lease lease, long expectedRevision, long sequence, String type,
            String operationId, String payloadHash) {
        return recordWorkerProgress(scope, taskId, lease, expectedRevision, sequence, type, operationId,
            payloadHash, Map.of());
    }

    public CodingTaskOutcome recordWorkerProgress(KernelDataScope scope, String taskId,
            CodingJournal.Lease lease, long expectedRevision, long sequence, String type,
            String operationId, String payloadHash, Map<String, Object> payload) {
        require(sequence > 0 && sequence <= CodingProtocol.MAX_SAFE_INTEGER, CONTRACT_INVALID, "Worker event sequence is out of bounds");
        require(Set.of("MODEL_STARTED", "MODEL_USAGE", "MODEL_RESOLVED", "MODEL_FAILED", "MODEL_RESPONSE_REJECTED", "MODEL_TIMING", "EXECUTION_STRATEGY", "TOOL_STARTED", "TOOL_FINISHED",
            "CODE_READY", "CODE_PROPOSAL_REJECTED", "TOOL_TIMING", "SESSION_TIMING", "PLAN_READY", "CONTEXT_READY", "SKILLS_AVAILABLE", "SKILLS_LOADED", "SKILLS_APPLIED").contains(type),
            CONTRACT_INVALID, "Unknown worker progress type");
        require(payloadHash != null && payloadHash.matches("[0-9a-f]{64}"), CONTRACT_INVALID,
            "Worker event content hash is required");
        return mutateTask(scope, taskId, WORKER, task -> {
            fence(task, lease);
            String key = lease.fencingToken() + ":" + sequence;
            String old = task.workerEvents.get(key);
            if (old != null) {
                require(old.equals(payloadHash), TASK_ID_CONFLICT, "Worker event identity conflict");
                return;
            }
            revision(task, expectedRevision); active(task);
            require(unlimitedCounts(task)||task.workerEvents.size() < 1024, ADAPTER_UNAVAILABLE, "Worker event capacity reached");
            if (operationId != null) require(task.operations.containsKey(operationId), NOT_FOUND,
                "Worker progress references an unregistered operation");
            task.workerEvents.put(key, payloadHash);
            advance(task, RECOVERY_RECONCILED, null, "Worker observation: " + type,
                operationId, List.of(), copy(task, task.outcome.status(), task.outcome.reasonCode(),
                    task.outcome.executionControl(), null));
            int last = task.events.size() - 1;
            var telemetry = new java.util.LinkedHashMap<String,Object>(task.events.get(last).payload());
            telemetry.putAll(payload);
            telemetry.put("attemptId",taskId+":fence-"+lease.fencingToken());
            telemetry.put("source",payload.getOrDefault("source","CODING_WORKER"));
            task.events.set(last, task.events.get(last).withPayload(telemetry));
        }).outcome;
    }

    /** Read-only A2 binding: C may publish only operations whose stop proof was accepted by A2. */
    public Optional<VerifiedOperation> verifiedOperation(KernelDataScope scope,String taskId,String operationId) {
        var task=task(read(scope,READ),taskId);
        var op=task.operations.get(operationId);
        return op==null || !op.startIntent || op.stopProofRef==null ? Optional.empty()
            : Optional.of(new VerifiedOperation(operationId,op.startingFencingToken,op.executionHandle,op.stopProofRef));
    }
    public record VerifiedOperation(String operationId,long fencingToken,String executionHandle,String stopProofRef) { }

    /** Actual started model identities, including their original fence, for receipt projections. */
    public List<StartedModelOperation> startedModelOperations(KernelDataScope scope,String taskId) {
        return task(read(scope,READ),taskId).operations.entrySet().stream()
            .filter(entry->entry.getValue().kind==CodingJournal.OperationKind.MODEL && entry.getValue().startIntent
                && entry.getKey().matches("model-[1-9][0-9]*"))
            .map(entry->new StartedModelOperation(entry.getKey(),entry.getValue().startingFencingToken))
            .sorted(java.util.Comparator.comparing(operation->new java.math.BigInteger(operation.operationId().substring(6)))).toList();
    }
    public record StartedModelOperation(String operationId,long fencingToken) { }

    /** Read-only unknown starts for cancellation and reconciliation; never authorizes replay. */
    public List<String> unresolvedOperations(KernelDataScope scope,String taskId) {
        var task=task(read(scope,READ),taskId);
        return task.operations.entrySet().stream()
            .filter(entry->entry.getValue().startIntent && entry.getValue().stopProofRef==null)
            .map(Map.Entry::getKey).toList();
    }
    /** Read-only operation identities for evidence-only recovery; never authorizes a new dispatch. */
    public List<String> startedCodeOperations(KernelDataScope scope,String taskId) {
        var task=task(read(scope,READ),taskId);
        var starts = new HashMap<String, Long>();
        for (var entry : task.operations.entrySet()) {
            var operation = entry.getValue();
            if (operation.kind != CodingJournal.OperationKind.CODE || !operation.startIntent) continue;
            long sequence = operation.startSequence;
            if (sequence == 0) sequence = task.events.stream()
                .filter(event -> entry.getKey().equals(event.operationId())
                    && "Start intent committed; execution result is not yet known".equals(event.summary()))
                .mapToLong(CodingExecutionEvent::sequence).findFirst().orElse(0);
            // A legacy journal with trimmed dispatch history cannot establish the latest execution.
            if (sequence == 0) return List.of();
            starts.put(entry.getKey(), sequence);
        }
        return starts.entrySet().stream().sorted(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey).toList();
    }
    public Counters budgetUsage(KernelDataScope scope,String budgetId) { return account(read(scope,READ),budgetId); }

    /** Archive a collector-verified asset index; a later failed execution never removes earlier results. */
    public void preserveDeliverable(KernelDataScope scope,String taskId,CodingJournal.Lease lease,
            String operationId,Map<String,Object> references) {
        mutateTask(scope,taskId,WORKER,task->{
            fence(task,lease);
            require(operation(task,operationId).kind==CodingJournal.OperationKind.CODE
                && operation(task,operationId).stopProofRef!=null,EXECUTION_OUTCOME_UNKNOWN,"Stopped code operation proof required");
            for(String key:List.of("codeArtifactRef","executionReceiptRef","resultDatasetRef","validationRecordRef","evidenceBundleRef"))
                require(references.get(key) instanceof String value&&!value.isBlank(),CONTRACT_INVALID,"Verified asset references required");
            String key="deliverable:"+operationId;
            if(task.privateState.containsKey(key))return;
            require(unlimitedCounts(task)||task.privateState.size()<128,ADAPTER_UNAVAILABLE,"Private state capacity");
            var asset=new LinkedHashMap<String,Object>(references);
            asset.put("operationId",operationId);asset.put("verifiedAt",now().toString());
            asset.put("businessCorrectnessCertified",false);
            asset.put("integrityVerified",true);asset.put("structureVerified",true);
            require(json.valueToTree(asset).toString().length()<=8192,CONTRACT_INVALID,"Bounded asset index required");
            task.privateState.put(key,json.valueToTree(asset));
        });
    }

    /** One journal read: consumed usage plus outstanding reservations is the admission authority. */
    public Map<String,Object> budgetSnapshot(KernelDataScope scope,String taskId,long contextTokens,
            CodingCompletionBudget policy) {
        require(contextTokens>=0,CONTRACT_INVALID,"Context estimate must be nonnegative");
        var journal=read(scope,READ);var task=task(journal,taskId);var budget=task.contract.budget();
        var charged=account(journal,budget.budgetId());
        long tokens=com.chatchat.common.runtime.coding.CodingLimit.remaining(budget.maxModelTokens(),charged.modelTokens());
        long calls=com.chatchat.common.runtime.coding.CodingLimit.remaining(budget.maxModelCalls(),charged.modelCalls());
        long millis=Math.max(0,Duration.between(now(),budget.deadlineAt()).toMillis());
        var pending=task.operations.entrySet().stream().filter(e->e.getValue().startIntent&&e.getValue().stopProofRef==null)
            .map(e->Map.of("operationId",e.getKey(),"kind",e.getValue().kind.name())).toList();
        var deliverables=task.privateState.entrySet().stream().filter(e->e.getKey().startsWith("deliverable:"))
            .map(e->e.getValue().deepCopy()).toList();
        int failures=(int)task.privateState.entrySet().stream().filter(e->e.getKey().startsWith("feedback:") && !e.getValue().path("resultAvailable").asBoolean())
            .count();
        var view=new LinkedHashMap<String,Object>();
        view.put("schemaVersion","runtime_os.coding.budget.v1");view.put("taskId",taskId);
        view.put("revision",task.outcome.revision());view.put("asOf",now().toString());
        view.put("remainingTokens",com.chatchat.common.runtime.coding.CodingLimit.wire(tokens));view.put("remainingModelCalls",com.chatchat.common.runtime.coding.CodingLimit.wire(calls));view.put("remainingMillis",millis);
        view.put("contextTokensEstimate",contextTokens);view.put("contextEstimateIsMetered",false);
        view.put("chargedUsage",charged);view.put("status",task.outcome.status().name());
        view.put("pendingOperations",pending);view.put("verifiedDeliverables",deliverables);
        view.put("unknownUsageOperations",task.operations.entrySet().stream()
            .filter(e->e.getValue().startIntent&&!e.getValue().meteringComplete).map(Map.Entry::getKey).toList());
        view.put("unresolvedReason",task.outcome.executionControl().stopReason()==null?"":task.outcome.executionControl().stopReason().name());
        view.put("completionReservation",policy.allocate(budget.maxModelTokens(),budget.maxModelCalls(),
            Math.max(0,Duration.between(task.outcome.createdAt(),budget.deadlineAt()).toMillis()),
            tokens,calls,millis,contextTokens,task.contract.outputContract().requiredEvidence().size(),failures,!deliverables.isEmpty()));
        return Collections.unmodifiableMap(view);
    }

    @Override public Subscription subscribe(KernelDataScope scope,String taskId,long afterSequence,Observer observer) {
        Objects.requireNonNull(observer); events(scope,taskId,afterSequence,1);
        CodingTaskAccess reader = access.subscriptionAccess(scope);
        var closed=new AtomicBoolean(); var busy=new AtomicBoolean(); var cursor=new AtomicLong(afterSequence);
        Object deliveryLock=new Object();
        var handle=new AtomicReference<java.util.concurrent.ScheduledFuture<?>>();
        Runnable close=()->{ synchronized(deliveryLock) { if (closed.compareAndSet(false,true)) {
            var future=handle.get(); if(future!=null)future.cancel(false); observer.onClosed();
        } } };
        Runnable tick=()->{
            if (closed.get() || !busy.compareAndSet(false,true)) return;
            try { executor.execute(()->{
                try {
                    for(var event:events(scope,taskId,cursor.get(),64,reader)) {
                        synchronized(deliveryLock) {
                            if(closed.get())break; observer.onEvent(event); cursor.set(event.sequence());
                        }
                    }
                } catch(Exception failure) {
                    synchronized(deliveryLock) { if(!closed.get())try { observer.onError(failure instanceof CodingContractException contract ? contract.error()
                        : new CodingAgentSPI.Error(ADAPTER_UNAVAILABLE,true,taskId,null,null,null,"Coding event subscription failed")); }
                    finally { close.run(); } }
                } finally { busy.set(false); }
            }); } catch(RuntimeException rejected) {
                busy.set(false);
                synchronized(deliveryLock) { if(!closed.get())try {
                    observer.onError(new CodingAgentSPI.Error(SUBSCRIPTION_OVERFLOW,true,taskId,null,null,null,"Shared event executor rejected delivery"));
                } finally { close.run(); } }
            }
        };
        handle.set(scheduler.scheduleWithFixedDelay(tick,Duration.ofMillis(250)));
        if(closed.get() && handle.get()!=null)handle.get().cancel(false);
        return close::run;
    }

    private CodingJournal mutate(KernelDataScope scope,CodingTaskAccess.Action action,Consumer<CodingJournal> mutation) {
        access.require(scope,action);
        stableScope(scope);
        var priorRevisions=new HashMap<String,Long>();var priorSequences=new HashMap<String,Long>();
        String result=store.mutateExecutionCheckpoint(scope,CHECKPOINT_KEY,current->{
            access.require(scope,action); var journal=decode(current);
            priorRevisions.clear();priorSequences.clear();
            for(var entry:journal.tasks.entrySet()){priorRevisions.put(entry.getKey(),entry.getValue().outcome.revision());priorSequences.put(entry.getKey(),entry.getValue().outcome.lastEventSequence());}
            mutation.accept(journal); return encode(journal);
        });
        var journal=decode(result);
        if(projectionListener!=null)for(var entry:journal.tasks.entrySet())if(!Objects.equals(priorRevisions.get(entry.getKey()),entry.getValue().outcome.revision())){
            long after=priorSequences.getOrDefault(entry.getKey(),0L);project(entry.getValue(),entry.getValue().events.stream().filter(event->event.sequence()>after).toList());
        }
        return journal;
    }
    private CodingJournal.Task mutateTask(KernelDataScope scope,String taskId,CodingTaskAccess.Action action,Consumer<CodingJournal.Task> mutation) {
        long[] before={0};
        var committed=task(mutate(scope,action,journal->{
            var current=task(journal,taskId);before[0]=current.outcome.lastEventSequence();mutation.accept(current);
        }),taskId);
        for(var event:committed.events)if(event.sequence()>before[0]){
            var fields=new LinkedHashMap<String,Object>(event.payload());fields.put("status",event.status().name());
            fields.put("reasonCode",event.reasonCode()==null?"NONE":event.reasonCode().name());
            if(event.type()==CodingExecutionEvent.Type.TASK_FINISHED&&committed.outcome.startedAt()!=null&&committed.outcome.endedAt()!=null)
                fields.put("durationMs",Math.max(0,Duration.between(committed.outcome.startedAt(),committed.outcome.endedAt()).toMillis()));
            String execution=event.operationId()!=null?event.operationId():fields.get("proposedOperationId") instanceof String id?id:null;
            com.chatchat.common.runtime.event.RuntimeEventLog.execution(event.type().name(),scope,taskId,execution,fields);
        }
        return committed;
    }
    private CodingJournal read(KernelDataScope scope,CodingTaskAccess.Action action) {
        return read(scope, action, access);
    }
    private CodingJournal read(KernelDataScope scope,CodingTaskAccess.Action action,CodingTaskAccess reader) {
        reader.require(scope,action); stableScope(scope);
        return decode(store.readExecutionCheckpoint(scope,CHECKPOINT_KEY).orElse(null));
    }
    private CodingJournal decode(String value) {
        if(value==null)return new CodingJournal();
        try {
            var tree=json.readTree(value);
            require(tree.has("storageVersion") && tree.get("storageVersion").isIntegralNumber()
                && tree.get("storageVersion").intValue()==1,VERSION_UNSUPPORTED,"Missing or unknown Coding journal version");
            return json.treeToValue(tree,CodingJournal.class);
        }
        catch(CodingContractException error){throw error;}
        catch(Exception error){throw new IllegalStateException("Coding journal cannot be decoded",error);}
    }
    private String encode(CodingJournal journal) {
        try { String result=json.writeValueAsString(journal); require(result.getBytes(StandardCharsets.UTF_8).length<=64*1024*1024,
            ADAPTER_UNAVAILABLE,"Coding owner journal capacity exceeded"); return result; }
        catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException("Coding journal cannot be encoded",error);}
    }
    private static CodingJournal.Task task(CodingJournal journal,String taskId) {
        var task=journal.tasks.get(taskId); require(task!=null,NOT_FOUND,"Coding task not found in authorized scope"); return task;
    }
    private static CodingJournal.Operation operation(CodingJournal.Task task,String id) {
        var operation=task.operations.get(id); require(operation!=null,NOT_FOUND,"Coding operation not found"); return operation;
    }
    private static void revision(CodingJournal.Task task,long expected) { require(task.outcome.revision()==expected,INVALID_STATE_TRANSITION,"Coding task revision conflict"); }
    private void fence(CodingJournal.Task task,CodingJournal.Lease lease) {
        require(lease!=null && Objects.equals(task.lease.owner(),lease.owner()) && task.lease.fencingToken()==lease.fencingToken()
            && task.lease.owner()!=null && now().isBefore(task.lease.leaseUntil()),INVALID_STATE_TRANSITION,"Expired or fenced execution owner");
    }
    private static void active(CodingJournal.Task task) { require(task.outcome.status()==Status.RUNNING,INVALID_STATE_TRANSITION,"Task must be running"); }
    private static boolean unresolved(CodingJournal.Task task) { return task.operations.values().stream().anyMatch(op->op.startIntent && op.stopProofRef==null); }
    private static CodingErrorCode reasonAfterReconciliation(CodingJournal.Task task,boolean unknown) {
        if(task.outcome.status().terminal())return task.outcome.reasonCode();
        var stop=task.outcome.executionControl().stopReason();
        return stop!=null ? stop : unknown ? EXECUTION_OUTCOME_UNKNOWN : null;
    }
    private static void leaseDuration(Duration duration) { require(duration!=null && !duration.isNegative() && !duration.isZero()
        && duration.compareTo(Duration.ofMinutes(5))<=0 && duration.toMillis()>0,CONTRACT_INVALID,"Lease duration must be 1ms..5min"); }
    private static void bounded(String text,String field) { require(text!=null && !text.isBlank() && text.length()<=512,CONTRACT_INVALID,field+" is invalid"); }
    private static void stableScope(KernelDataScope scope) {
        require(scope!=null && scope.tenantId()!=null && scope.userId()!=null && scope.runId()!=null,
            CONTRACT_INVALID,"Coding requires explicit tenant/user/run identity; requestId fallback is forbidden");
    }
    private static void require(boolean condition,CodingErrorCode code,String message) { if(!condition)throw new CodingContractException(code,message); }
    private Instant now() { return Instant.ofEpochMilli(clock.millis()); }
    private static boolean unlimitedCounts(CodingJournal.Task task) {
        var budget=task.contract.budget();
        return CodingLimit.unlimited(budget.maxModelCalls())||CodingLimit.unlimited(budget.maxToolCalls())||CodingLimit.unlimited(budget.maxCodeExecutions());
    }
    private static Status stopStatus(CodingErrorCode reason){return reason==CANCEL_REQUESTED?Status.CANCELLED:reason==EXECUTION_FAILED?Status.FAILED:Status.BUDGET_EXCEEDED;}
    private void deadline(CodingJournal.Task task) { if(!now().isBefore(task.contract.budget().deadlineAt()))requestStop(task,DEADLINE_EXCEEDED,"Task deadline reached"); }
    private void requestStop(CodingJournal.Task task,CodingErrorCode reason,String summary) {
        if(task.outcome.status().terminal() || task.outcome.executionControl().stopReason()!=null)return;
        var control=new ExecutionControl(reason,at(task),false,unresolved(task));
        var next=copy(task,task.outcome.status(),reason,control,null);
        advance(task,reason==CANCEL_REQUESTED ? CANCELLATION_REQUESTED : BUDGET_UPDATED,reason,summary,null,List.of(),next);
    }
    private ExecutionControl control(CodingJournal.Task task,boolean stopped,boolean unknown) {
        var old=task.outcome.executionControl(); return new ExecutionControl(old.stopReason(),old.stopRequestedAt(),stopped,unknown);
    }
    private Instant at(CodingJournal.Task task) { Instant current=now(); return current.isBefore(task.outcome.updatedAt()) ? task.outcome.updatedAt() : current; }
    private CodingTaskOutcome copy(CodingJournal.Task task,Status status,CodingErrorCode reason,ExecutionControl control,CodingTaskOutcome content) {
        var old=task.outcome; var body=content==null ? old : content; Instant at=at(task);
        var limitations=new ArrayList<>(body.limitations()); if(!limitations.contains(ENFORCEMENT_LIMITATION))limitations.add(ENFORCEMENT_LIMITATION);
        if(status.terminal() && (body.codeArtifactRef()==null || body.executionReceiptRef()==null || body.resultDatasetRef()==null
            || body.validationRecordRef()==null || body.evidenceBundleRef()==null))limitations.add("Required execution evidence was not produced");
        return new CodingTaskOutcome(old.schemaVersion(),old.taskId(),old.scope(),old.contractSha256(),status,old.revision()+1,old.lastEventSequence()+1,
            old.createdAt(),at,old.startedAt()==null && status==Status.RUNNING ? at : old.startedAt(),
            old.status().terminal() ? old.endedAt() : status.terminal() ? at : null,
            old.cancellationRequested() || control.stopReason()==CANCEL_REQUESTED,reason,body.summary(),limitations.stream().distinct().toList(),
            old.inputSnapshotRef(),body.codeArtifactRef(),body.executionReceiptRef(),body.resultDatasetRef(),body.validationRecordRef(),body.evidenceBundleRef(),
            body.validationStatus(),body.reproducibility(),usage(task),control,old.parentTaskId(),old.supersedesTaskId(),body.perArtifactCompleteness());
    }
    private void advance(CodingJournal.Task task,CodingExecutionEvent.Type type,CodingErrorCode reason,String summary,
        String operationId,List<String> refs,CodingTaskOutcome next) {
        // Summary supplied by the controller, without changing task identity or accepting provider control fields.
        next=new CodingTaskOutcome(next.schemaVersion(),next.taskId(),next.scope(),next.contractSha256(),next.status(),next.revision(),next.lastEventSequence(),
            next.createdAt(),next.updatedAt(),next.startedAt(),next.endedAt(),next.cancellationRequested(),next.reasonCode(),summary,next.limitations(),
            next.inputSnapshotRef(),next.codeArtifactRef(),next.executionReceiptRef(),next.resultDatasetRef(),next.validationRecordRef(),next.evidenceBundleRef(),
            next.validationStatus(),next.reproducibility(),next.usage(),next.executionControl(),next.parentTaskId(),next.supersedesTaskId(),next.perArtifactCompleteness());
        task.outcome.validateSuccessor(next); next.validateAgainst(task.contract); task.outcome=next;
        task.events.add(event(task,type,operationId,refs));
        if(task.events.size()>EVENT_HISTORY)task.events.remove(0);
    }
    private static CodingExecutionEvent event(CodingJournal.Task task,CodingExecutionEvent.Type type,String operation,List<String> refs) {
        var out=task.outcome;
        String phase = task.events.isEmpty() ? "QUEUED" : String.valueOf(
            task.events.get(task.events.size()-1).payload().getOrDefault("stage","CONTEXT_READY"));
        if(out.status().terminal()) phase = out.reasonCode()==CodingErrorCode.DEADLINE_EXCEEDED ? "TIMED_OUT" : out.status().name();
        else if(type==CodingExecutionEvent.Type.TASK_STARTED) phase="CONTEXT_READY";
        else if(type==CodingExecutionEvent.Type.EXECUTION_STARTED) phase=operation!=null&&operation.startsWith("model-")?"CODING":"EXECUTING";
        else if(type==CodingExecutionEvent.Type.EXECUTION_FINISHED) phase=operation!=null&&operation.startsWith("model-")?"CODING":"RESULT_VALIDATING";
        else if(type==CodingExecutionEvent.Type.CODE_COMMITTED||type==CodingExecutionEvent.Type.VALIDATION_RECORDED) phase="RESULT_VALIDATING";
        else if(type==CodingExecutionEvent.Type.CANCELLATION_REQUESTED) phase="CANCELLING";
        else if(operation!=null && task.operations.containsKey(operation)) {
            var intent=task.operations.get(operation);
            if(intent.startIntent && intent.kind==CodingJournal.OperationKind.MODEL) phase="CODING";
            else if(intent.startIntent && intent.kind==CodingJournal.OperationKind.CODE)
                phase=intent.stopProofRef==null ? "EXECUTING" : "RESULT_VALIDATING";
        }
        return new CodingExecutionEvent(out.schemaVersion(),"coding:"+UUID.randomUUID(),out.taskId(),out.scope(),out.lastEventSequence(),out.revision(),
            out.updatedAt(),type,operation,operation==null ? null : 1L,out.status(),out.reasonCode(),refs,out.usage(),out.summary())
            .withPayload(Map.of("attemptId",out.taskId()+":fence-"+task.lease.fencingToken(),"stage",phase,"source","JAVA_RUNTIME"));
    }
    private static Usage usage(CodingJournal.Task task) {
        Counters actual=CodingBudgetMath.zero(),reserved=CodingBudgetMath.zero(); boolean complete=true;
        for(var op:task.operations.values()) {
            actual=CodingBudgetMath.add(actual,op.actual);
            if(!op.meteringComplete) { reserved=CodingBudgetMath.add(reserved,CodingBudgetMath.remaining(op.reservation,op.actual)); complete=false; }
        }
        return new Usage(task.contract.budget().budgetId(),task.contract.budget().costCurrency(),actual,reserved,complete);
    }
    private static Counters account(CodingJournal journal,String budgetId) {
        require(journal.budgets.containsKey(budgetId),NOT_FOUND,"Budget not found"); Counters total=CodingBudgetMath.zero();
        for(var task:journal.tasks.values())if(task.contract.budget().budgetId().equals(budgetId)) {
            var usage=usage(task); total=CodingBudgetMath.add(total,CodingBudgetMath.add(usage.consumed(),usage.reserved()));
        }
        return total;
    }
    private static List<String> references(CodingTaskOutcome outcome) {
        return java.util.stream.Stream.of(outcome.inputSnapshotRef(),outcome.codeArtifactRef(),outcome.executionReceiptRef(),outcome.resultDatasetRef(),
            outcome.validationRecordRef(),outcome.evidenceBundleRef()).filter(Objects::nonNull).distinct().toList();
    }
}
