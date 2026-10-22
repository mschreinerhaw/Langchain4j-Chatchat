package com.chatchat.mcpserver.python;

import com.chatchat.common.mcp.python.McpPythonControlPlanePort.OperationView;
import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalSecretCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** MCP-node operation journal. A2 remains the task/budget authority; this table only owns Docker dispatch. */
@Service
public class PythonManagedExecutionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PythonCapabilityService capability;
    private final PythonDockerRuntime docker;
    private final PythonOperationWorkspace workspaces;
    private final InternalCredentialProperties credentials;
    private final ObjectMapper mapper;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.AbortPolicy());

    public PythonManagedExecutionService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                         PythonCapabilityService capability, PythonDockerRuntime docker,
                                         PythonOperationWorkspace workspaces, InternalCredentialProperties credentials,
                                         ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.capability = capability;
        this.docker = docker;
        this.workspaces = workspaces;
        this.credentials = credentials;
        this.mapper = mapper;
    }

    public OperationView start(StartCommand request, String tenant, String owner) {
        requireSame(request.tenantId(), tenant);
        requireSame(request.ownerId(), owner);
        if (request.fencingToken() < 1 || request.attempt() < 1) throw new IllegalArgumentException("Invalid fencing token or attempt");
        String id = identity(tenant, owner, request.runId(), request.taskId(), request.operationId());
        var cancelled = noStart(id);
        if (cancelled != null) return noStartView(cancelled);
        Operation prior = find(id);
        if (request.deadlineAt() == null || prior == null && !request.deadlineAt().isAfter(Instant.now()))
            throw new IllegalArgumentException("Execution deadline has expired");
        if (request.budgetReservationRef() == null || request.budgetReservationRef().isBlank()
                || request.budgetReservationRef().length() > 256)
            throw new IllegalArgumentException("Durable budget reservation reference is required");
        String imageId;
        int timeoutSeconds;
        int environmentVersion;
        if (prior != null) { imageId = prior.imageDigest; timeoutSeconds = prior.timeoutSeconds; environmentVersion = prior.environmentVersion; }
        else {
            PythonEnvironment environment = capability.managedEnvironmentState(request.environmentId());
            environmentVersion = environment.getVersionNumber();
            if (environmentVersion < 1) throw new IllegalStateException("Published Python environment has no durable version");
            timeoutSeconds = environment.getTimeoutSeconds();
            try { imageId = docker.resolveImageId(environment); }
            catch (Exception ex) { throw new IllegalStateException("Python image cannot be pinned", ex); }
        }
        if (!InternalSecretCipher.isEncrypted(request.sourceCiphertext())) throw new IllegalArgumentException("Encrypted Python source is required");
        if (request.expectedEnvironmentVersion() != null && request.expectedEnvironmentVersion() != environmentVersion
                || request.expectedImageDigest() != null && !request.expectedImageDigest().equals(imageId))
            throw new IllegalStateException("Python 环境版本或镜像已更新，当前任务保留原环境快照，请重新提交任务以使用最新默认环境。");
        String source = InternalSecretCipher.decryptIfNecessary(request.sourceCiphertext(), credentials.resolvedSecret());
        if (request.sourceSha256() == null || !request.sourceSha256().equals(PythonOperationWorkspace.hash(source.getBytes(StandardCharsets.UTF_8))))
            throw new IllegalArgumentException("Python source checksum mismatch");
        Map<String,Object> parameters = request.parameters() == null ? Map.of() : request.parameters();
        List<String> allowed = request.authorizedFileIds() == null ? List.of() : request.authorizedFileIds();
        Path workspace = workspaces.path(tenant, owner, request.runId(), request.taskId(), request.operationId(), request.attempt());
        if(request.script()!=null && !request.script().workingDirectory().equals(
                com.chatchat.common.runtime.coding.CodingScript.workspace(tenant,owner,request.runId(),request.taskId())))
            throw new SecurityException("Script workspace belongs to another task or owner");
        PythonOperationWorkspace.Snapshot snapshot = workspaces.prepare(workspace, request.fileName(), source, parameters, tenant, owner, allowed,request.script());
        if (!java.util.Objects.equals(request.script(),snapshot.script()) || !request.fileName().equals(snapshot.fileName()) || !snapshot.codeHash().equals(PythonOperationWorkspace.hash(source.getBytes(StandardCharsets.UTF_8)))
                || !snapshot.parametersHash().equals(PythonOperationWorkspace.hash(json(parameters))))
            throw new IllegalArgumentException("Operation ID is bound to another code or input snapshot");
        if (!new HashSet<>(allowed).equals(snapshot.inputs().stream().map(PythonDataFileService.SnapshotFile::fileId).collect(java.util.stream.Collectors.toSet())))
            throw new IllegalArgumentException("Operation ID is bound to another input set");
        Map<String,String> expectedInputs = request.authorizedFileHashes() == null ? Map.of() : request.authorizedFileHashes();
        if (expectedInputs.size() != snapshot.inputs().size()
                || snapshot.inputs().stream().anyMatch(file -> !file.sha256().equals(expectedInputs.get(file.fileId()))))
            throw new IllegalArgumentException("Authorized input checksum mismatch");
        String fenceId = identity(tenant, owner, request.runId(), request.taskId(), "fence");
        String hash = requestHash(request, snapshot, imageId, environmentVersion);
        ensureFence(fenceId, tenant, owner, request.runId(), request.taskId());
        Operation row = tx.execute(status -> {
            long current = lockFence(fenceId);
            if (request.fencingToken() < current) throw new IllegalStateException("Fencing token is stale");
            if (noStart(id) != null) return null; // Cancellation won the same fence lock; never dispatch.
            Operation existing = find(id);
            if (existing != null) {
                if (!existing.requestHash.equals(hash) || existing.fencingToken != request.fencingToken())
                    throw new IllegalStateException("Operation ID already has a different immutable request");
                return existing;
            }
            if (request.fencingToken() > current)
                jdbc.update("update mcp_python_managed_fence set fencing_token=? where id=?", request.fencingToken(), fenceId);
            String container = docker.managedContainerName(tenant, owner, request.runId(), request.taskId(), request.operationId(), request.attempt());
            Instant now = Instant.now();
            jdbc.update("insert into mcp_python_managed_operation (id,tenant_id,owner_id,run_id,task_id,operation_id,attempt_number,fencing_token,request_hash,environment_id,environment_version,image_digest,container_name,workspace_path,code_hash,deadline_at,timeout_seconds,budget_reservation_ref,status,cancel_requested,executor_stopped,cleanup_failed,revision,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ACCEPTED',false,false,false,1,?,?)",
                    id, tenant, owner, request.runId(), request.taskId(), request.operationId(), request.attempt(), request.fencingToken(), hash,
                    request.environmentId(), environmentVersion, imageId, container, workspace.toString(), snapshot.codeHash(), Timestamp.from(request.deadlineAt()),
                    timeoutSeconds, request.budgetReservationRef(), Timestamp.from(now), Timestamp.from(now));
            return Objects.requireNonNull(find(id));
        });
        if (row == null) return noStartView(Objects.requireNonNull(noStart(id)));
        if ("ACCEPTED".equals(row.status)) dispatch(id);
        return view(row);
    }

    public OperationView get(String tenant, String owner, String run, String task, String operation) {
        var cancelled = noStart(identity(tenant, owner, run, task, operation));
        if (cancelled != null) return noStartView(cancelled);
        Operation row = authorized(tenant, owner, run, task, operation);
        if ("ACCEPTED".equals(row.status)) dispatch(row.id);
        else if (Set.of("STARTING", "RUNNING", "UNKNOWN").contains(row.status)) reconcile(row.id);
        else if (row.cleanupFailed) retryCleanup(row.id);
        preserveUndispatchedStop(row.id);
        return view(authorized(tenant, owner, run, task, operation));
    }

    public OperationView cancel(String tenant, String owner, String run, String task, String operation, long token) {
        String id = identity(tenant, owner, run, task, operation);
        String fenceId = identity(tenant, owner, run, task, "fence");
        if (token < 1) throw new IllegalArgumentException("Positive cancellation fence required");
        ensureFence(fenceId, tenant, owner, run, task);
        Operation row = tx.execute(status -> {
            long current = lockFence(fenceId);
            if (token < current) throw new IllegalStateException("Fencing token is stale");
            Operation found = find(id);
            if (found == null) {
                if (noStart(id) == null) {
                    byte[] receipt = json(Map.of("schemaVersion", com.chatchat.common.mcp.python.McpPythonControlPlanePort.NO_START_RECEIPT_VERSION,
                        "tenantId", tenant, "ownerId", owner, "runId", run, "taskId", task, "operationId", operation,
                        "fencingToken", token, "stopConfirmed", true, "dispatchPrevented", true, "timestamp", Instant.now().toString()));
                    byte[] manifest = json(Map.of("schemaVersion", "runtime_os.mcp.python_no_start_manifest.v1", "artifacts",
                        List.of(Map.of("channel", "logs", "fileName", "receipt.json", "size", receipt.length,
                            "sha256", PythonOperationWorkspace.hash(receipt)))));
                    jdbc.update("insert into mcp_python_managed_cancellation(id,operation_id,fencing_token,receipt_json,manifest_json) values (?,?,?,?,?)",
                        id, operation, token, new String(receipt, StandardCharsets.UTF_8), new String(manifest, StandardCharsets.UTF_8));
                }
                if (token > current) jdbc.update("update mcp_python_managed_fence set fencing_token=? where id=?", token, fenceId);
                return null;
            }
            checkScope(found, tenant, owner, run, task, operation);
            if (token > current) {
                jdbc.update("update mcp_python_managed_fence set fencing_token=? where id=?", token, fenceId);
                jdbc.update("update mcp_python_managed_operation set fencing_token=?,revision=revision+1,updated_at=? where id=?",
                        token, Timestamp.from(Instant.now()), id);
            }
            if (terminal(found.status)) return found;
            if ("ACCEPTED".equals(found.status)) {
                update(found.id, "CANCELLED", true, true, null, null, null);
            } else {
                jdbc.update("update mcp_python_managed_operation set cancel_requested=true,revision=revision+1,updated_at=? where id=?", Timestamp.from(Instant.now()), id);
            }
            return Objects.requireNonNull(find(id));
        });
        if (row == null) return noStartView(Objects.requireNonNull(noStart(id)));
        if (!row.executorStopped && row.cancelRequested) {
            PythonDockerRuntime.StopCheck stopped = docker.stopManaged(row.containerName);
            if (stopped.confirmed()) finalizeStopped(id);
        }
        preserveUndispatchedStop(id);
        return view(authorized(tenant, owner, run, task, operation));
    }

    /** A durable terminal row prevents startCreated under the task fence lock. Preserve that
     * non-dispatch proof as well as code/inputs, so Runtime can reconcile cancellation before create. */
    private void preserveUndispatchedStop(String id) {
        Operation row=find(id);
        if(row==null||!row.executorStopped||row.containerStartedAt!=null||row.manifestHash!=null
                ||!Set.of("CANCELLED","TIMED_OUT").contains(row.status))return;
        var state=docker.inspectManaged(row.containerName);
        if(state.running()||state.startedAt()!=null)return;
        try {
            Path workspace=Path.of(row.workspacePath);
            writeOnce(workspace.resolve("output/logs/stdout.txt"),"");writeOnce(workspace.resolve("output/logs/stderr.txt"),"");
            writeReceipt(row,state,false,true);workspaces.archive(workspace);
            String hash=PythonOperationWorkspace.hash(Files.readAllBytes(workspace.resolve("manifest.json")));
            jdbc.update("update mcp_python_managed_operation set manifest_hash=?,revision=revision+1,updated_at=? where id=? and executor_stopped=true and container_started_at is null and manifest_hash is null",hash,Timestamp.from(Instant.now()),id);
            if(state.exists()&&!docker.removeManaged(row.containerName))jdbc.update("update mcp_python_managed_operation set cleanup_failed=true where id=?",id);
        } catch(Exception incomplete) { /* Stop is durable; no A2 proof is accepted until archival succeeds. */ }
    }

    public byte[] artifact(String tenant, String owner, String run, String task, String operation,
                           String channel, String fileName) {
        byte[] stopped = noStartArtifact(tenant, owner, run, task, operation, channel, fileName);
        if (stopped != null) return stopped;
        try { return Files.readAllBytes(artifactPath(tenant, owner, run, task, operation, channel, fileName)); }
        catch (java.io.IOException ex) { throw new IllegalStateException("Artifact cannot be read", ex); }
    }

    /** Small audit artifacts only; a tombstone has no source, stdout, dataset or execution output. */
    byte[] noStartArtifact(String tenant, String owner, String run, String task, String operation, String channel, String file) {
        var stopped = noStart(identity(tenant, owner, run, task, operation));
        if (stopped == null) return null;
        if ("logs".equals(channel) && "receipt.json".equals(file)) return stopped.receipt.getBytes(StandardCharsets.UTF_8);
        if ("manifest".equals(channel) && "manifest.json".equals(file)) return stopped.manifest.getBytes(StandardCharsets.UTF_8);
        throw new IllegalArgumentException("No execution artifacts exist for an unadmitted cancellation");
    }

    private NoStart noStart(String id) {
        return jdbc.query("select operation_id,fencing_token,receipt_json,manifest_json from mcp_python_managed_cancellation where id=?",
            rs -> rs.next() ? new NoStart(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4)) : null, id);
    }
    private OperationView noStartView(NoStart stopped) {
        return new OperationView(stopped.operation, "CANCELLED", null, stopped.fence, true, true, null, null, null,
            PythonOperationWorkspace.hash(stopped.manifest.getBytes(StandardCharsets.UTF_8)), "CANCELLED_BEFORE_ACCEPTANCE");
    }
    private record NoStart(String operation, long fence, String receipt, String manifest) { }

    public Path artifactPath(String tenant, String owner, String run, String task, String operation,
                             String channel, String fileName) {
        Operation row = authorized(tenant, owner, run, task, operation);
        if (row.manifestHash == null || !terminal(row.status)) throw new IllegalStateException("Artifact is not committed");
        try {
            Path workspace = Path.of(row.workspacePath);
            byte[] manifest = Files.readAllBytes(workspace.resolve("manifest.json"));
            if (!PythonOperationWorkspace.hash(manifest).equals(row.manifestHash)) throw new IllegalStateException("Manifest checksum mismatch");
            return workspaces.artifactPath(workspace, channel, fileName);
        } catch (java.io.IOException ex) { throw new IllegalStateException("Artifact cannot be read", ex); }
    }

    @Scheduled(fixedDelay = 10000)
    public void recoverPending() {
        List<String> ids = jdbc.query("select id from mcp_python_managed_operation where status in ('ACCEPTED','STARTING','RUNNING','UNKNOWN') order by updated_at limit 64",
                (rs, rowNum) -> rs.getString(1));
        for (String id : ids) {
            try {
                Operation row = find(id);
                if (row == null) continue;
                if ("ACCEPTED".equals(row.status)) dispatch(id);
                else reconcile(id);
            } catch (Exception ignored) { /* The durable row remains queryable; retry next scan. */ }
        }
        List<String> cleanup = jdbc.query("select id from mcp_python_managed_operation where cleanup_failed=true order by updated_at limit 16",
                (rs, rowNum) -> rs.getString(1));
        for (String id : cleanup) retryCleanup(id);
    }

    private void dispatch(String id) {
        try { workers.execute(() -> execute(id)); }
        catch (RejectedExecutionException ignored) { /* Accepted state is durable; scanner retries. */ }
    }

    private void execute(String id) {
        Operation row = tx.execute(status -> {
            Operation reference = Objects.requireNonNull(find(id));
            long fence = lockFence(fenceId(reference));
            Operation current = Objects.requireNonNull(findForUpdate(id));
            if (!"ACCEPTED".equals(current.status)) return null;
            if (fence != current.fencingToken) {
                update(id, "FENCED", false, true, null, null, "Execution fence was superseded before dispatch");
                return null;
            }
            update(id, "STARTING", false, false, null, null, null);
            return find(id);
        });
        if (row == null) return;
        try {
            if (cancelledOrFenced(id)) return;
            if (!row.deadlineAt.isAfter(Instant.now())) {
                tx.execute(status -> {
                    Operation locked = findForUpdate(id);
                    if (locked != null && "STARTING".equals(locked.status))
                        update(id, "TIMED_OUT", false, true, null, null, "Deadline expired before Docker was created");
                    return null;
                });
                return;
            }
            Path workspace = Path.of(row.workspacePath);
            PythonOperationWorkspace.Snapshot snapshot = workspaces.readSnapshot(workspace);
            PythonDockerRuntime.ContainerState state = docker.inspectManaged(row.containerName);
            if (!state.exists()) {
                if (cancelledOrFenced(id)) return;
                PythonEnvironment environment = capability.managedEnvironmentState(row.environmentId);
                if (environment.getVersionNumber() != row.environmentVersion)
                    throw new IllegalStateException("Published Python environment version changed before dispatch");
                docker.createManaged(environment, workspace, row.containerName, snapshot.fileName(), row.imageDigest,snapshot.script()==null?"/workspace/scripts/"+snapshot.fileName():snapshot.script().scriptPath());
            } else if (state.finished() || state.running()) {
                reconcile(id);
                return;
            }
            if (cancelledOrFenced(id) || !startCreated(id)) return;
            long remainingSeconds = Math.max(1, java.time.Duration.between(Instant.now(), row.deadlineAt).toSeconds());
            docker.waitManaged(row.containerName, (int) Math.min(row.timeoutSeconds, remainingSeconds));
            reconcile(id);
        } catch (Exception ex) {
            reconcile(id);
            Operation latest = find(id);
            if (latest != null && !terminal(latest.status)) markUnknown(id, ex.getMessage());
        }
    }

    /** The task fence row remains locked only for the bounded Docker start command. */
    private boolean startCreated(String id) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            Operation reference = find(id);
            if (reference == null) return false;
            long fence = lockFence(fenceId(reference));
            Operation current = findForUpdate(id);
            if (current == null || !"STARTING".equals(current.status) || current.cancelRequested
                    || !current.deadlineAt.isAfter(Instant.now())) return false;
            if (fence != current.fencingToken) return false;
            try { docker.startManagedDetached(current.containerName); }
            catch (Exception ex) { throw new IllegalStateException("Docker start cannot be confirmed", ex); }
            update(id, "RUNNING", false, false, null, null, null);
            jdbc.update("update mcp_python_managed_operation set container_started_at=? where id=?",
                    Timestamp.from(Instant.now()), id);
            return true;
        }));
    }

    private boolean cancelledOrFenced(String id) {
        Operation row = find(id);
        if (row == null) return true;
        long fence = currentFence(fenceId(row));
        if (fence != row.fencingToken) {
            fenceSuperseded(row);
            return true;
        }
        if (!row.cancelRequested) return false;
        PythonDockerRuntime.ContainerState state = docker.inspectManaged(row.containerName);
        if (state.exists()) {
            if (docker.stopManaged(row.containerName).confirmed()) finalizeStopped(id);
        } else if ("STARTING".equals(row.status)) {
            tx.execute(status -> { Operation locked = findForUpdate(id); if (locked != null && locked.cancelRequested && "STARTING".equals(locked.status)) update(id, "CANCELLED", true, true, null, null, null); return null; });
        }
        return true;
    }

    private void reconcile(String id) {
        Operation row = find(id);
        if (row == null || terminal(row.status)) return;
        if (currentFence(fenceId(row)) != row.fencingToken) { fenceSuperseded(row); return; }
        PythonDockerRuntime.ContainerState state = docker.inspectManaged(row.containerName);
        if (!state.exists()) {
            if (!"ACCEPTED".equals(row.status)
                    && !("STARTING".equals(row.status) && row.updatedAt.isAfter(Instant.now().minusSeconds(30))))
                markUnknown(id, "Container identity cannot be located; execution outcome is unknown");
            return;
        }
        if (state.running()) {
            boolean expired = !row.deadlineAt.isAfter(Instant.now()) || row.containerStartedAt != null
                    && !row.containerStartedAt.plusSeconds(row.timeoutSeconds).isAfter(Instant.now());
            if (row.cancelRequested || expired) {
                if (docker.stopManaged(row.containerName).confirmed()) finalizeStopped(id);
            } else if (Set.of("STARTING", "UNKNOWN").contains(row.status)) {
                tx.execute(status -> { Operation locked = findForUpdate(id); if (locked != null && Set.of("STARTING", "UNKNOWN").contains(locked.status)) update(id, "RUNNING", false, false, null, null, null); return null; });
            }
            return;
        }
        if (state.finished() || row.cancelRequested || !row.deadlineAt.isAfter(Instant.now())) finalizeStopped(id);
        else if ("STARTING".equals(row.status)) {
            try { startCreated(id); } catch (Exception ex) { markUnknown(id, "Container start remains uncertain: " + ex.getMessage()); }
        } else markUnknown(id, "Container exists but has no confirmed execution result");
    }

    private void fenceSuperseded(Operation row) {
        PythonDockerRuntime.ContainerState state = docker.inspectManaged(row.containerName);
        boolean stopped = state.exists() && docker.stopManaged(row.containerName).confirmed();
        if (stopped) {
            try {
                Path workspace = Path.of(row.workspacePath);
                PythonExecutionLogBoundary.persist(workspace, mapper);
                writeReceipt(row, docker.inspectManaged(row.containerName), true);
                workspaces.archive(workspace);
            } catch (Exception ex) {
                stopped = false;
            }
        }
        final boolean preservedAndStopped = stopped;
        tx.execute(status -> {
            Operation locked = findForUpdate(row.id);
            if (locked != null && !terminal(locked.status))
                update(row.id, preservedAndStopped ? "FENCED" : "UNKNOWN", locked.cancelRequested, preservedAndStopped,
                        null, null, preservedAndStopped ? "Superseded execution stopped" : "Superseded execution stop or archive is unconfirmed");
            return null;
        });
        if (preservedAndStopped && !docker.removeManaged(row.containerName))
            jdbc.update("update mcp_python_managed_operation set cleanup_failed=true,revision=revision+1,updated_at=? where id=?",
                    Timestamp.from(Instant.now()), row.id);
    }

    private void finalizeStopped(String id) {
        Operation row = find(id);
        if (row == null || terminal(row.status)) return;
        PythonDockerRuntime.ContainerState state = docker.inspectManaged(row.containerName);
        if (!state.exists() || state.running()) return;
        try {
            Path workspace = Path.of(row.workspacePath);
            PythonExecutionLogBoundary.persist(workspace, mapper);
            writeReceipt(row, state, false);
            workspaces.archive(workspace);
            String manifestHash = PythonOperationWorkspace.hash(Files.readAllBytes(workspace.resolve("manifest.json")));
            Instant cutoff = row.containerStartedAt == null ? row.deadlineAt
                    : row.deadlineAt.isBefore(row.containerStartedAt.plusSeconds(row.timeoutSeconds))
                    ? row.deadlineAt : row.containerStartedAt.plusSeconds(row.timeoutSeconds);
            boolean expired = state.startedAt() == null && !row.deadlineAt.isAfter(Instant.now())
                    || state.finishedAt() != null && state.finishedAt().isAfter(cutoff);
            String target = row.cancelRequested ? "CANCELLED" : expired ? "TIMED_OUT"
                    : state.exitCode() != null && state.exitCode() == 0 && state.startedAt() != null ? "SUCCEEDED" : "FAILED";
            boolean committed = Boolean.TRUE.equals(tx.execute(status -> {
                Operation reference = find(id);
                if (reference == null) return false;
                long fence = lockFence(fenceId(reference));
                Operation locked = findForUpdate(id);
                if (locked == null || terminal(locked.status) || fence != locked.fencingToken) return false;
                update(id, target, locked.cancelRequested, true, state.exitCode(), manifestHash, state.message());
                return true;
            }));
            if (committed && !docker.removeManaged(row.containerName))
                jdbc.update("update mcp_python_managed_operation set cleanup_failed=true,revision=revision+1,updated_at=? where id=?", Timestamp.from(Instant.now()), id);
        } catch (Exception ex) { markUnknown(id, "Artifact preservation failed: " + ex.getMessage()); }
    }

    private void retryCleanup(String id) {
        Operation row = find(id);
        if (row != null && row.cleanupFailed && docker.removeManaged(row.containerName))
            jdbc.update("update mcp_python_managed_operation set cleanup_failed=false,revision=revision+1,updated_at=? where id=? and cleanup_failed=true",
                    Timestamp.from(Instant.now()), id);
    }

    private void writeOnce(Path path, String value) throws java.io.IOException {
        try { Files.writeString(path, value == null ? "" : value, StandardOpenOption.CREATE_NEW); }
        catch (FileAlreadyExistsException ignored) { /* Preserve first captured log. */ }
    }

    private void writeReceipt(Operation row, PythonDockerRuntime.ContainerState state, boolean fenceSuperseded) throws java.io.IOException {
        writeReceipt(row,state,fenceSuperseded,false);
    }
    private void writeReceipt(Operation row, PythonDockerRuntime.ContainerState state, boolean fenceSuperseded,boolean dispatchPrevented) throws java.io.IOException {
        Map<String,Object> receipt = new LinkedHashMap<>();
        receipt.put("operationId", row.operation);
        receipt.put("containerName", row.containerName);
        receipt.put("environmentId", row.environmentId);
        receipt.put("imageDigest", row.imageDigest);
        receipt.put("deadlineAt", row.deadlineAt.toString());
        receipt.put("timeoutSeconds", row.timeoutSeconds);
        receipt.put("fencingToken", row.fencingToken);
        receipt.put("budgetReservationRef", row.budgetReservationRef);
        receipt.put("codeHash", row.codeHash);
        var script=workspaces.readSnapshot(Path.of(row.workspacePath)).script();
        if(script!=null)receipt.put("script",script);
        receipt.put("inputSnapshotHash", PythonOperationWorkspace.hash(Files.readAllBytes(Path.of(row.workspacePath).resolve("snapshot.json"))));
        receipt.put("startedAt", state.startedAt() == null ? null : state.startedAt().toString());
        receipt.put("finishedAt", state.finishedAt() == null ? null : state.finishedAt().toString());
        receipt.put("exitCode", state.exitCode());
        receipt.put("oomKilled", state.oomKilled());
        receipt.put("stopConfirmed", (state.exists() || dispatchPrevented) && !state.running());
        if(dispatchPrevented)receipt.put("dispatchPrevented",true);
        receipt.put("cancelRequested", row.cancelRequested);
        receipt.put("fenceSuperseded", fenceSuperseded);
        receipt.put("cpuMillisMetered", false);
        receipt.put("diskHardLimitEnforced", false);
        receipt.put("outputBytesHardLimitEnforced", false);
        writeOnce(Path.of(row.workspacePath).resolve("output/logs/receipt.json"), mapper.writeValueAsString(receipt));
    }

    private void markUnknown(String id, String failure) {
        tx.execute(status -> { Operation row = findForUpdate(id); if (row != null && !terminal(row.status)
                && (!"UNKNOWN".equals(row.status) || !Objects.equals(row.failure, failure)))
            update(id, "UNKNOWN", row.cancelRequested, false, null, null, failure); return null; });
    }

    private void ensureFence(String id, String tenant, String owner, String run, String task) {
        try {
            tx.execute(status -> { jdbc.update("insert into mcp_python_managed_fence(id,tenant_id,owner_id,run_id,task_id,fencing_token) values (?,?,?,?,?,0)", id, tenant, owner, run, task); return null; });
        } catch (DuplicateKeyException ignored) { /* Another owner established the same task row. */ }
    }

    private long lockFence(String id) {
        Long value = jdbc.queryForObject("select fencing_token from mcp_python_managed_fence where id=? for update", Long.class, id);
        if (value == null) throw new IllegalStateException("Task fence does not exist");
        return value;
    }
    private long currentFence(String id) { Long value = jdbc.queryForObject("select fencing_token from mcp_python_managed_fence where id=?", Long.class, id); return Objects.requireNonNull(value); }
    private String fenceId(Operation row) { return identity(row.tenant, row.owner, row.run, row.task, "fence"); }
    private Operation authorized(String tenant, String owner, String run, String task, String operation) {
        Operation row = Objects.requireNonNull(find(identity(tenant, owner, run, task, operation)), "Operation does not exist");
        checkScope(row, tenant, owner, run, task, operation);
        return row;
    }
    private void checkScope(Operation row, String tenant, String owner, String run, String task, String operation) {
        if (!row.tenant.equals(tenant) || !row.owner.equals(owner) || !row.run.equals(run)
                || !row.task.equals(task) || !row.operation.equals(operation)) throw new SecurityException("Operation scope does not match");
    }
    private Operation findForUpdate(String id) { return query("select * from mcp_python_managed_operation where id=? for update", id); }
    private Operation find(String id) { return query("select * from mcp_python_managed_operation where id=?", id); }
    private Operation query(String sql, String id) {
        return jdbc.query(sql, rs -> rs.next() ? new Operation(rs.getString("id"), rs.getString("tenant_id"), rs.getString("owner_id"),
                rs.getString("run_id"), rs.getString("task_id"), rs.getString("operation_id"), rs.getInt("attempt_number"),
                rs.getLong("fencing_token"), rs.getString("request_hash"), rs.getString("environment_id"), rs.getInt("environment_version"), rs.getString("image_digest"), rs.getString("container_name"),
                rs.getString("workspace_path"), rs.getString("code_hash"), rs.getString("manifest_hash"), rs.getString("status"),
                rs.getBoolean("cancel_requested"), rs.getBoolean("executor_stopped"), (Integer) rs.getObject("exit_code"), rs.getString("failure"), rs.getBoolean("cleanup_failed"),
                rs.getTimestamp("deadline_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getInt("timeout_seconds"), rs.getTimestamp("container_started_at") == null ? null : rs.getTimestamp("container_started_at").toInstant(),
                rs.getString("budget_reservation_ref")) : null, id);
    }
    private void update(String id, String status, boolean cancel, boolean stopped, Integer exit, String manifest, String failure) {
        jdbc.update("update mcp_python_managed_operation set status=?,cancel_requested=?,executor_stopped=?,exit_code=?,manifest_hash=?,failure=?,revision=revision+1,updated_at=? where id=?",
                status, cancel, stopped, exit, manifest, failure, Timestamp.from(Instant.now()), id);
    }
    private OperationView view(Operation row) { return new OperationView(row.operation, row.status, row.containerName, row.fencingToken, row.cancelRequested, row.executorStopped, row.exitCode, row.codeHash, row.imageDigest, row.manifestHash, row.failure); }
    private boolean terminal(String status) { return Set.of("SUCCEEDED", "FAILED", "CANCELLED", "FENCED", "TIMED_OUT").contains(status); }
    private void requireSame(String actual, String expected) { if (!Objects.equals(actual, expected)) throw new SecurityException("Execution scope mismatch"); }
    private String identity(String... values) { return PythonOperationWorkspace.hash(String.join("\u0000", values).getBytes(StandardCharsets.UTF_8)); }
    private byte[] json(Object value) { try { return mapper.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value); } catch (Exception ex) { throw new IllegalArgumentException("Invalid execution parameters", ex); } }
    private String requestHash(StartCommand request, PythonOperationWorkspace.Snapshot snapshot, String imageId, int environmentVersion) {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("tenant",request.tenantId());value.put("owner",request.ownerId());value.put("run",request.runId());
        value.put("task",request.taskId());value.put("operation",request.operationId());value.put("attempt",request.attempt());
        value.put("fence",request.fencingToken());value.put("environment",request.environmentId());value.put("environmentVersion",environmentVersion);
        value.put("imageDigest",imageId);value.put("asset",request.assetId());
        if(snapshot.script()!=null)value.put("script",snapshot.script());value.put("fileName",snapshot.fileName());value.put("code",snapshot.codeHash());value.put("parameters",snapshot.parametersHash());
        value.put("inputSchema",request.inputSchemaJson());value.put("inputs",snapshot.inputs());
        value.put("deadline",request.deadlineAt().toString());value.put("budgetReservationRef",request.budgetReservationRef());
        return PythonOperationWorkspace.hash(json(value));
    }
    @PreDestroy public void shutdown() { workers.shutdownNow(); }

    public record StartCommand(String tenantId, String ownerId, String runId, String taskId, String operationId,
                               int attempt, long fencingToken, String environmentId, String assetId,
                               String fileName, String sourceCiphertext, Map<String,Object> parameters,
                               String inputSchemaJson, List<String> authorizedFileIds,
                               Map<String,String> authorizedFileHashes, String sourceSha256,
                               Instant deadlineAt, String budgetReservationRef,
                               Integer expectedEnvironmentVersion, String expectedImageDigest, com.chatchat.common.runtime.coding.CodingScript script) {
        public StartCommand(String tenantId, String ownerId, String runId, String taskId, String operationId,
                               int attempt, long fencingToken, String environmentId, String assetId,
                               String fileName, String sourceCiphertext, Map<String,Object> parameters,
                               String inputSchemaJson, List<String> authorizedFileIds,
                               Map<String,String> authorizedFileHashes, String sourceSha256,
                               Instant deadlineAt, String budgetReservationRef,
                               Integer expectedEnvironmentVersion, String expectedImageDigest) {
            this(tenantId, ownerId, runId, taskId, operationId, attempt, fencingToken, environmentId, assetId, fileName, sourceCiphertext, parameters, inputSchemaJson, authorizedFileIds, authorizedFileHashes, sourceSha256, deadlineAt, budgetReservationRef, expectedEnvironmentVersion, expectedImageDigest, null);
        }
        public StartCommand(String tenantId, String ownerId, String runId, String taskId, String operationId,
                int attempt, long fencingToken, String environmentId, String assetId, String fileName,
                String sourceCiphertext, Map<String,Object> parameters, String inputSchemaJson,
                List<String> authorizedFileIds, Map<String,String> authorizedFileHashes, String sourceSha256,
                Instant deadlineAt, String budgetReservationRef) {
            this(tenantId, ownerId, runId, taskId, operationId, attempt, fencingToken, environmentId, assetId,
                fileName, sourceCiphertext, parameters, inputSchemaJson, authorizedFileIds, authorizedFileHashes,
                sourceSha256, deadlineAt, budgetReservationRef, null, null);
        }
    }
    private record Operation(String id,String tenant,String owner,String run,String task,String operation,int attempt,
                             long fencingToken,String requestHash,String environmentId,int environmentVersion,String imageDigest,String containerName,
                             String workspacePath,String codeHash,String manifestHash,String status,boolean cancelRequested,
                             boolean executorStopped,Integer exitCode,String failure,boolean cleanupFailed,Instant deadlineAt,Instant updatedAt,
                             int timeoutSeconds,Instant containerStartedAt,String budgetReservationRef) {}
}
