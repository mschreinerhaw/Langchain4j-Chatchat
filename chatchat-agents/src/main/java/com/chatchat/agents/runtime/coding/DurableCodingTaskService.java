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
    private static final int MAX_TASKS = 64, MAX_OPERATIONS = 256, EVENT_HISTORY = 128;
    private final RuntimeExecutionCheckpointPort store;
    private final ObjectMapper json;
    private final Clock clock;
    private final CodingTaskAccess access;
    private final CodingEvidenceVerifier evidence;
    private final CodingExecutionProofVerifier executionProof;
    private final Executor executor;
    private final TaskScheduler scheduler;

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
        return mutate(contract.scope(), SUBMIT, journal -> {
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
            task.outcome = new CodingTaskOutcome(CodingProtocol.VERSION, contract.taskId(), contract.scope(), hash,
                Status.CREATED, 0, 1, at, at, null, null, false, null, "Coding task accepted durably", List.of(ENFORCEMENT_LIMITATION),
                contract.inputSnapshotRef(), null,null,null,null,null, ValidationStatus.NOT_RUN,
                new Reproducibility(ReproducibilityLevel.CONDITIONAL, List.of("Execution and reproducibility have not been verified"), List.of(contract.environmentRef())),
                usage(task), new ExecutionControl(null,null,false,false), null,null,Map.of());
            task.events.add(event(task, TASK_CREATED, null, List.of())); journal.tasks.put(contract.taskId(), task);
        }).tasks.get(contract.taskId()).outcome;
    }

    @Override public CodingTaskOutcome get(KernelDataScope scope, String taskId) {
        return task(read(scope, READ), taskId).outcome;
    }

    public CodingTaskContract contract(KernelDataScope scope, String taskId) {
        return task(read(scope, READ), taskId).contract;
    }

    /** A stopped operation is still an external call and must not be replayed by a fresh SDK session. */
    public boolean hasStartedOperations(KernelDataScope scope, String taskId) {
        return task(read(scope, READ), taskId).operations.values().stream().anyMatch(op -> op.startIntent);
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
            require(!task.outcome.executionControl().reconciliationRequired(),EXECUTION_OUTCOME_UNKNOWN,"Reconcile previous execution first");
            require(task.operations.size()<MAX_OPERATIONS,ADAPTER_UNAVAILABLE,"Task operation capacity reached");
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
            Status target=task.outcome.executionControl().stopReason()==CANCEL_REQUESTED ? Status.CANCELLED : Status.BUDGET_EXCEEDED;
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
            Status target=stop==CANCEL_REQUESTED ? Status.CANCELLED : Status.BUDGET_EXCEEDED;
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
            if(stop!=null && proposal.status()!=(stop==CANCEL_REQUESTED ? Status.CANCELLED : Status.BUDGET_EXCEEDED))return;
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
        require(afterSequence>=0 && limit>0 && limit<=256,CONTRACT_INVALID,"Invalid event cursor or limit");
        var task=task(read(scope,READ),taskId);
        long first=task.events.isEmpty() ? task.outcome.lastEventSequence()+1 : task.events.get(0).sequence();
        require(afterSequence>=first-1,EVENT_CURSOR_EXPIRED,"Event history was compacted; earliestSequence="+first+"; read authoritative snapshot");
        require(afterSequence<=task.outcome.lastEventSequence(),CONTRACT_INVALID,"Event cursor is ahead of task");
        return task.events.stream().filter(event->event.sequence()>afterSequence).limit(limit).toList();
    }

    public CodingJournal.Lease ownership(KernelDataScope scope,String taskId) { return task(read(scope,READ),taskId).lease; }

    /** Read-only A2 binding: C may publish only operations whose stop proof was accepted by A2. */
    public Optional<VerifiedOperation> verifiedOperation(KernelDataScope scope,String taskId,String operationId) {
        var task=task(read(scope,READ),taskId);
        var op=task.operations.get(operationId);
        return op==null || !op.startIntent || op.stopProofRef==null ? Optional.empty()
            : Optional.of(new VerifiedOperation(operationId,op.startingFencingToken,op.executionHandle,op.stopProofRef));
    }
    public record VerifiedOperation(String operationId,long fencingToken,String executionHandle,String stopProofRef) { }

    /** Read-only unknown starts for cancellation and reconciliation; never authorizes replay. */
    public List<String> unresolvedOperations(KernelDataScope scope,String taskId) {
        var task=task(read(scope,READ),taskId);
        return task.operations.entrySet().stream()
            .filter(entry->entry.getValue().startIntent && entry.getValue().stopProofRef==null)
            .map(Map.Entry::getKey).toList();
    }
    public Counters budgetUsage(KernelDataScope scope,String budgetId) { return account(read(scope,READ),budgetId); }

    @Override public Subscription subscribe(KernelDataScope scope,String taskId,long afterSequence,Observer observer) {
        Objects.requireNonNull(observer); events(scope,taskId,afterSequence,1);
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
                    for(var event:events(scope,taskId,cursor.get(),64)) {
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
        String result=store.mutateExecutionCheckpoint(scope,CHECKPOINT_KEY,current->{
            access.require(scope,action); var journal=decode(current); mutation.accept(journal); return encode(journal);
        });
        return decode(result);
    }
    private CodingJournal.Task mutateTask(KernelDataScope scope,String taskId,CodingTaskAccess.Action action,Consumer<CodingJournal.Task> mutation) {
        return task(mutate(scope,action,journal->mutation.accept(task(journal,taskId))),taskId);
    }
    private CodingJournal read(KernelDataScope scope,CodingTaskAccess.Action action) {
        access.require(scope,action); stableScope(scope);
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
        return new CodingTaskOutcome(CodingProtocol.VERSION,old.taskId(),old.scope(),old.contractSha256(),status,old.revision()+1,old.lastEventSequence()+1,
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
        return new CodingExecutionEvent(CodingProtocol.VERSION,"coding:"+UUID.randomUUID(),out.taskId(),out.scope(),out.lastEventSequence(),out.revision(),
            out.updatedAt(),type,operation,operation==null ? null : 1L,out.status(),out.reasonCode(),refs,out.usage(),out.summary());
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
