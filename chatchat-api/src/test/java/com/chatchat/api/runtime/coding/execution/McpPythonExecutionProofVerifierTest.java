package com.chatchat.api.runtime.coding.execution;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpPythonExecutionProofVerifierTest {
    @Test void noStartProofRequiresDurableScopedTombstoneAndNeverPretendsAnExecutorRan() throws Exception {
        var port = mock(McpPythonControlPlanePort.class);
        var json = new ObjectMapper(); var verifier = new McpPythonExecutionProofVerifier(port, json);
        var scope = new KernelDataScope("tenant", "owner", null, null, "run", null, Map.of());
        byte[] receipt = json.writeValueAsBytes(Map.of("schemaVersion", McpPythonControlPlanePort.NO_START_RECEIPT_VERSION,
            "tenantId", "tenant", "ownerId", "owner", "runId", "run", "taskId", "task", "operationId", "op",
            "fencingToken", 2, "stopConfirmed", true, "dispatchPrevented", true));
        when(port.get("tenant", "owner", "run", "task", "op")).thenReturn(new McpPythonControlPlanePort.OperationView(
            "op", "CANCELLED", null, 2, true, true, null, null, null, "a".repeat(64), "CANCELLED_BEFORE_ACCEPTANCE"));
        when(port.artifact("tenant", "owner", "run", "task", "op", "logs", "receipt.json")).thenReturn(receipt);
        String handle = "mcp-python:" + McpPythonExecutionProofVerifier.sha256("tenant\u0000owner\u0000run\u0000task\u0000op".getBytes(StandardCharsets.UTF_8));
        String proof = "mcp-python-receipt:" + McpPythonExecutionProofVerifier.sha256(receipt);
        assertTrue(verifier.confirmsStopped(scope, "task", "op", handle, proof));
        assertFalse(verifier.confirmsStopped(scope, "task", "op", handle, proof + "corrupt"));
        var other = new KernelDataScope("other", "owner", null, null, "run", null, Map.of());
        assertFalse(verifier.confirmsStopped(other, "task", "op", handle, proof));
        var changed = json.readTree(receipt); ((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("dispatchPrevented", false);
        byte[] forged = json.writeValueAsBytes(changed);
        when(port.artifact("tenant", "owner", "run", "task", "op", "logs", "receipt.json")).thenReturn(forged);
        assertFalse(verifier.confirmsStopped(scope, "task", "op", handle, "mcp-python-receipt:" + McpPythonExecutionProofVerifier.sha256(forged)));
    }
    @Test void onlyScopedHashMatchedStoppedReceiptIsAccepted() {
        var port = mock(McpPythonControlPlanePort.class);
        var verifier = new McpPythonExecutionProofVerifier(port, new ObjectMapper());
        var scope = new KernelDataScope("tenant", "owner", "request", "conversation", "run", "test", Map.of());
        String container = "chatchat-py-" + "a".repeat(40);
        String image = "sha256:" + "b".repeat(64);
        byte[] receipt = ("{\"operationId\":\"op\",\"containerName\":\"" + container
                + "\",\"imageDigest\":\"" + image + "\",\"stopConfirmed\":true}").getBytes(StandardCharsets.UTF_8);
        when(port.get("tenant", "owner", "run", "task", "op")).thenReturn(new McpPythonControlPlanePort.OperationView(
                "op", "SUCCEEDED", container, 1, false, true, 0, "c".repeat(64), image, "d".repeat(64), null));
        when(port.artifact("tenant", "owner", "run", "task", "op", "logs", "receipt.json")).thenReturn(receipt);
        String handle = "mcp-python:" + McpPythonExecutionProofVerifier.sha256("tenant\u0000owner\u0000run\u0000task\u0000op".getBytes(StandardCharsets.UTF_8));
        String proof = "mcp-python-receipt:" + McpPythonExecutionProofVerifier.sha256(receipt);
        assertTrue(verifier.confirmsStopped(scope, "task", "op", handle, proof));
        assertFalse(verifier.confirmsStopped(scope, "task", "op", handle, proof + "corrupt"));
        assertFalse(verifier.confirmsStopped(scope, "task", "op", "mcp-python:wrong", proof));
    }
}
