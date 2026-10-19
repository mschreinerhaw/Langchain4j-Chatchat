package com.chatchat.common.mcp.python;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

import java.util.List;
import java.util.Map;

/** Stable Python runtime control-plane protocol independent of its remote client. */
public interface McpPythonControlPlanePort extends RuntimeProtocolPort {
    String PROTOCOL_VERSION = "runtime_os.mcp.python_control.v1";
    String MANAGED_PROTOCOL_VERSION = "runtime_os.mcp.python_managed.v1";
    /** Durable cancellation before MCP admission; never an execution/result receipt. */
    String NO_START_RECEIPT_VERSION = "runtime_os.mcp.python_no_start.v1";

    List<EnvironmentView> environments();
    /** Returns the MCP-published state of an environment, including non-active states. */
    EnvironmentView environment(String id);
    ProvisionResult provision(String environmentId, String tenantId, String ownerId, String assetId);
    ExecutionResult preview(String environmentId, String tenantId, String ownerId, String assetId,
                            String fileName, String source, Map<String, Object> parameters, String inputSchemaJson);
    SyncResult synchronizeTemplate(String id, TemplatePayload payload);
    ExecutionResult executeTemplate(String id, String tenantId, String ownerId, Map<String, Object> parameters);
    void setTemplateEnabled(String id, boolean enabled);
    DataFileResult uploadDataFile(String tenantId, String ownerId, String fileId, String fileName,
                                  String fileHash, byte[] content);
    byte[] downloadDataFile(String tenantId, String ownerId, String fileId);
    /** Small authenticated receipt only: data bytes are already published on the configured shared volume. */
    default DataFileResult registerSharedDataFile(String tenantId,String ownerId,String fileId,String fileName,
                                                 String fileHash,long fileSize) {
        throw new UnsupportedOperationException("Shared immutable input delivery is unavailable");
    }
    default List<String> inputCapabilities() { return List.of("JSON_LINES", "REMOTE_UPLOAD"); }
    void deleteDataFile(String tenantId, String ownerId, String fileId);

    /** An accepted operation is durable before this call returns. Source is encrypted by the client. */
    default OperationView start(StartRequest request) {
        throw new UnsupportedOperationException("Async Python execution is unavailable");
    }

    default OperationView get(String tenantId, String ownerId, String runId, String taskId, String operationId) {
        throw new UnsupportedOperationException("Async Python execution is unavailable");
    }

    /** Cancellation before admission must durably fence late starts for the exact scoped operation.
     * Its CANCELLED view has null executor/code/image fields and only receipt.json/manifest.json audit artifacts.
     * The receipt uses NO_START_RECEIPT_VERSION, binds tenantId/ownerId/runId/taskId/operationId/fencingToken,
     * and confirms stopConfirmed + dispatchPrevented. A missing get response alone is never stop proof. */
    default OperationView cancel(String tenantId, String ownerId, String runId, String taskId,
                                 String operationId, long fencingToken) {
        throw new UnsupportedOperationException("Async Python execution is unavailable");
    }

    default byte[] artifact(String tenantId, String ownerId, String runId, String taskId,
                            String operationId, String channel, String fileName) {
        throw new UnsupportedOperationException("Python artifacts are unavailable");
    }

    default void downloadArtifact(String tenantId, String ownerId, String runId, String taskId,
                                  String operationId, String channel, String fileName, java.io.OutputStream target)
            throws java.io.IOException {
        target.write(artifact(tenantId, ownerId, runId, taskId, operationId, channel, fileName));
    }

    record StartRequest(String tenantId, String ownerId, String runId, String taskId, String operationId,
                        int attempt, long fencingToken, String environmentId, String assetId, String fileName,
                        String source, Map<String, Object> parameters, String inputSchemaJson,
                        List<String> authorizedFileIds, Map<String, String> authorizedFileHashes,
                        String sourceSha256, java.time.Instant deadlineAt, String budgetReservationRef) {
    }

    record OperationView(String operationId, String status, String containerId, long fencingToken,
                         boolean cancelRequested, boolean executorStopped, Integer exitCode,
                         String codeHash, String imageDigest, String manifestHash, String failure) {
    }

    record EnvironmentView(String id, String name, String description, String dockerImage, String pythonVersion,
                           String cpuLimit, String memoryLimit, String diskLimit, String tmpfsLimit,
                           String runtimeUser, String networkPolicy, String networkName, String requirementsJson,
                           int timeoutSeconds, boolean networkEnabled, int versionNumber, String status) {
    }
    record ProvisionResult(boolean ready, String containerName, String workspacePath, String message) {
    }
    record ExecutionResult(String id, String containerId, String status, String stdout, String stderr,
                           Integer exitCode, Long durationMs) {
    }
    record SyncResult(String id, String status, String toolName, String environmentId, String sourceHash) {
    }
    record DataFileResult(String id, String storagePath, String pythonPath, long fileSize, String fileHash,
                          String status) {
    }
    record TemplatePayload(String tenantId, String ownerId, String assetId, String assetName,
                           String assetDescription, String environmentId, String scriptFileName,
                           String templateName, String toolName, String version, String scenario,
                           String description, String keywords, String domain, String inputSchemaJson,
                           String outputSchemaJson, String source) {
    }
}
