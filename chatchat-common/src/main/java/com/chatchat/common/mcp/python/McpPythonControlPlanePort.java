package com.chatchat.common.mcp.python;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

import java.util.List;
import java.util.Map;

/** Stable Python runtime control-plane protocol independent of its remote client. */
public interface McpPythonControlPlanePort extends RuntimeProtocolPort {
    String PROTOCOL_VERSION = "runtime_os.mcp.python_control.v1";

    List<EnvironmentView> environments();
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
    void deleteDataFile(String tenantId, String ownerId, String fileId);

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
