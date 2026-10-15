package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.coding.CodingExecutionProofVerifier;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** A2 accepts only a receipt that the scoped MCP node can still resolve and checksum. */
@Component
@ConditionalOnProperty(prefix = "chatchat.coding", name = "enabled", havingValue = "true")
public class McpPythonExecutionProofVerifier implements CodingExecutionProofVerifier {
    private final McpPythonControlPlanePort python;
    private final ObjectMapper mapper;
    public McpPythonExecutionProofVerifier(McpPythonControlPlanePort python, ObjectMapper mapper) {
        this.python = python; this.mapper = mapper;
    }

    @Override public boolean confirmsStopped(KernelDataScope scope, String taskId, String operationId,
                                               String executionHandle, String proofRef) {
        String identity = scope.tenantId() + "\u0000" + scope.userId() + "\u0000" + scope.runId()
                + "\u0000" + taskId + "\u0000" + operationId;
        if (!("mcp-python:" + sha256(identity.getBytes(StandardCharsets.UTF_8))).equals(executionHandle)) return false;
        try {
            var operation = python.get(scope.tenantId(), scope.userId(), scope.runId(), taskId, operationId);
            if (!operation.executorStopped() || !java.util.Set.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED").contains(operation.status())
                    || operation.manifestHash() == null) return false;
            byte[] receipt = python.artifact(scope.tenantId(), scope.userId(), scope.runId(), taskId, operationId,
                    "logs", "receipt.json");
            var document = mapper.readTree(receipt);
            return document.path("stopConfirmed").asBoolean(false)
                    && operationId.equals(document.path("operationId").asText())
                    && operation.containerId().equals(document.path("containerName").asText())
                    && operation.imageDigest().equals(document.path("imageDigest").asText())
                    && proofRef.equals("mcp-python-receipt:" + sha256(receipt));
        } catch (Exception ignored) { return false; }
    }

    public static String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
