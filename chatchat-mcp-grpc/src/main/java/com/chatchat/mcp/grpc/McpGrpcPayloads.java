package com.chatchat.mcp.grpc;

import com.chatchat.common.mcp.runtime.McpRuntimeTransportPort;
import com.chatchat.mcp.grpc.v1.PayloadChunk;
import com.google.protobuf.ByteString;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.function.Consumer;

/** Lossless, ordered and checksum-verified gRPC payload chunking. */
public final class McpGrpcPayloads {
    public static final int DEFAULT_CHUNK_BYTES = 1024 * 1024;

    private McpGrpcPayloads() { }

    public static void emit(String requestId, byte[] payload, int chunkBytes,
                            Consumer<PayloadChunk> consumer) {
        byte[] source = payload == null ? new byte[0] : payload;
        int size = Math.max(16 * 1024, chunkBytes);
        int count = Math.max(1, (source.length + size - 1) / size);
        for (int sequence = 0; sequence < count; sequence++) {
            int from = sequence * size;
            int length = Math.min(size, Math.max(0, source.length - from));
            byte[] chunk = java.util.Arrays.copyOfRange(source, from, from + length);
            consumer.accept(PayloadChunk.newBuilder()
                .setProtocolVersion(McpRuntimeTransportPort.PROTOCOL_VERSION)
                .setRequestId(requestId == null ? "" : requestId)
                .setSequence(sequence)
                .setPayload(ByteString.copyFrom(chunk))
                .setChunkSha256(sha256(chunk))
                .setTerminal(sequence == count - 1)
                .setTotalBytes(source.length)
                .setContentType("application/json")
                .build());
        }
    }

    public static byte[] assemble(Iterator<PayloadChunk> chunks, long maximumBytes) {
        return assemble(chunks, maximumBytes, null);
    }

    public static byte[] assemble(Iterator<PayloadChunk> chunks, long maximumBytes,
                                  String expectedRequestId) {
        if (chunks == null) throw new IllegalArgumentException("chunks are required");
        long limit = Math.max(DEFAULT_CHUNK_BYTES, maximumBytes);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int expectedSequence = 0;
        boolean terminal = false;
        long declaredTotal = -1;
        while (chunks.hasNext()) {
            PayloadChunk chunk = chunks.next();
            if (!McpRuntimeTransportPort.PROTOCOL_VERSION.equals(chunk.getProtocolVersion())) {
                throw new IllegalStateException("Unsupported MCP gRPC protocol " + chunk.getProtocolVersion());
            }
            if (expectedRequestId != null && !expectedRequestId.equals(chunk.getRequestId())) {
                throw new IllegalStateException("MCP gRPC response requestId mismatch");
            }
            if (!"application/json".equals(chunk.getContentType())) {
                throw new IllegalStateException("Unsupported MCP gRPC response content type");
            }
            if (chunk.getSequence() != expectedSequence++) {
                throw new IllegalStateException("Out-of-order MCP gRPC chunk");
            }
            byte[] bytes = chunk.getPayload().toByteArray();
            if (!sha256(bytes).equals(chunk.getChunkSha256())) {
                throw new IllegalStateException("MCP gRPC chunk checksum mismatch");
            }
            if ((long) output.size() + bytes.length > limit) {
                throw new IllegalStateException("MCP gRPC response exceeds configured maximum bytes");
            }
            output.writeBytes(bytes);
            declaredTotal = chunk.getTotalBytes();
            terminal = chunk.getTerminal();
            if (terminal && chunks.hasNext()) {
                throw new IllegalStateException("MCP gRPC emitted data after terminal chunk");
            }
        }
        if (!terminal) throw new IllegalStateException("MCP gRPC response is missing terminal chunk");
        if (declaredTotal != output.size()) throw new IllegalStateException("MCP gRPC total byte count mismatch");
        return output.toByteArray();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
