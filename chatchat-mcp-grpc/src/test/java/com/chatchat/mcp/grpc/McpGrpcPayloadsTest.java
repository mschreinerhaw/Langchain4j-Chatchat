package com.chatchat.mcp.grpc;

import com.chatchat.mcp.grpc.v1.PayloadChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpGrpcPayloadsTest {

    @Test
    void roundTripsLargePayloadAsOrderedVerifiedChunks() {
        byte[] payload = new byte[5 * 1024 * 1024 + 137];
        for (int index = 0; index < payload.length; index++) payload[index] = (byte) (index % 251);
        List<PayloadChunk> chunks = new ArrayList<>();

        McpGrpcPayloads.emit("request-1", payload, 1024 * 1024, chunks::add);

        assertThat(chunks).hasSize(6);
        assertThat(chunks.get(chunks.size() - 1).getTerminal()).isTrue();
        assertThat(McpGrpcPayloads.assemble(chunks.iterator(), 8L * 1024 * 1024))
            .isEqualTo(payload);
    }

    @Test
    void rejectsCorruptedAndOversizedResponses() {
        List<PayloadChunk> chunks = new ArrayList<>();
        McpGrpcPayloads.emit("request-2", new byte[2 * 1024 * 1024], 1024 * 1024, chunks::add);
        PayloadChunk corrupted = chunks.get(0).toBuilder().setChunkSha256("invalid").build();
        chunks.set(0, corrupted);

        assertThatThrownBy(() -> McpGrpcPayloads.assemble(chunks.iterator(), 4L * 1024 * 1024))
            .hasMessageContaining("checksum mismatch");

        List<PayloadChunk> valid = new ArrayList<>();
        McpGrpcPayloads.emit("request-3", new byte[2 * 1024 * 1024], 1024 * 1024, valid::add);
        assertThatThrownBy(() -> McpGrpcPayloads.assemble(valid.iterator(), 1024 * 1024))
            .hasMessageContaining("exceeds configured maximum");
    }
}
