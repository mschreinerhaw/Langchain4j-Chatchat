package com.chatchat.api.controller.search;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.mcp.grpc.v1.DocumentTransferChunk;
import com.chatchat.mcp.grpc.v1.DocumentTransferReply;
import com.chatchat.mcp.grpc.v1.DocumentTransferServiceGrpc;
import com.chatchat.mcp.grpc.v1.DocumentTransferStart;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Streams document files to MCP in bounded gRPC messages. */
@Component
public class DocumentGrpcTransferClient {
    private static final int CHUNK_BYTES = 1024 * 1024;
    private static final long MAX_FILE_BYTES = 55L * 1024 * 1024;
    private final ObjectMapper mapper;
    private final ManagedChannel channel;
    private final DocumentTransferServiceGrpc.DocumentTransferServiceStub stub;

    public DocumentGrpcTransferClient(ObjectMapper mapper, InternalCredentialProperties credentials,
        @Value("${chatchat.mcp.grpc.client.host:localhost}") String host,
        @Value("${chatchat.mcp.grpc.client.port:9091}") int port,
        @Value("${chatchat.mcp.grpc.client.plaintext:true}") boolean plaintext,
        @Value("${chatchat.mcp.grpc.client.trust-certificate-path:}") String trustCertificatePath) {
        this.mapper = mapper;
        NettyChannelBuilder builder = NettyChannelBuilder.forAddress(host, port)
            .maxInboundMessageSize(4 * 1024 * 1024);
        if (plaintext) builder.usePlaintext();
        else if (trustCertificatePath != null && !trustCertificatePath.isBlank()) {
            try {
                builder.sslContext(GrpcSslContexts.forClient()
                    .trustManager(new java.io.File(trustCertificatePath)).build());
            } catch (javax.net.ssl.SSLException failure) {
                throw new IllegalStateException("Invalid MCP gRPC trust certificate", failure);
            }
        } else builder.useTransportSecurity();
        channel = builder.build();
        stub = DocumentTransferServiceGrpc.newStub(channel).withCallCredentials(new BearerCredentials(credentials));
    }

    public SearchDocument transfer(DocumentTransferStart start, InputStream file) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<DocumentTransferReply> reply = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        ClientResponseObserver<DocumentTransferChunk, DocumentTransferReply> response = new ClientResponseObserver<>() {
            @Override public void beforeStart(ClientCallStreamObserver<DocumentTransferChunk> requestStream) { }
            @Override public void onNext(DocumentTransferReply value) { reply.set(value); }
            @Override public void onError(Throwable failure) { error.set(failure); done.countDown(); }
            @Override public void onCompleted() { done.countDown(); }
        };
        StreamObserver<DocumentTransferChunk> request = stub.withDeadlineAfter(10, TimeUnit.MINUTES)
            .transfer(response);
        try {
            request.onNext(DocumentTransferChunk.newBuilder().setStart(start).build());
            if (file != null) {
                byte[] buffer = new byte[CHUNK_BYTES];
                long total = 0;
                int read;
                while ((read = file.read(buffer)) != -1) {
                    if (read == 0) continue;
                    total += read;
                    if (total > MAX_FILE_BYTES) throw new IllegalArgumentException("file exceeds 55MB limit");
                    while (request instanceof ClientCallStreamObserver<?> stream && !stream.isReady()) {
                        if (error.get() instanceof RuntimeException failure) throw failure;
                        if (error.get() != null) throw new IllegalStateException("MCP document transfer failed", error.get());
                        Thread.sleep(10);
                    }
                    request.onNext(DocumentTransferChunk.newBuilder()
                        .setData(ByteString.copyFrom(buffer, 0, read)).build());
                }
            }
            request.onCompleted();
            if (!done.await(10, TimeUnit.MINUTES)) throw new IllegalStateException("MCP document transfer timed out");
            if (error.get() instanceof RuntimeException failure) throw failure;
            if (error.get() != null) throw new IllegalStateException("MCP document transfer failed", error.get());
            if (reply.get() == null) throw new IllegalStateException("MCP document transfer returned no result");
            return mapper.readValue(reply.get().getDocumentJson().toByteArray(), SearchDocument.class);
        } catch (IOException | InterruptedException failure) {
            request.onError(failure);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("MCP document transfer failed", failure);
        } catch (RuntimeException failure) {
            request.onError(failure);
            throw failure;
        }
    }

    @PreDestroy public void close() { channel.shutdownNow(); }

    private static final class BearerCredentials extends CallCredentials {
        private final InternalCredentialProperties credentials;
        private BearerCredentials(InternalCredentialProperties credentials) { this.credentials = credentials; }
        @Override public void applyRequestMetadata(RequestInfo requestInfo, java.util.concurrent.Executor executor,
                                                   MetadataApplier applier) {
            executor.execute(() -> {
                try {
                    String secret = credentials.resolvedSecret();
                    if (secret.isBlank()) throw new IllegalStateException("MCP gRPC credential is required");
                    Metadata metadata = new Metadata();
                    metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                        "Bearer " + secret);
                    applier.apply(metadata);
                } catch (RuntimeException failure) {
                    applier.fail(Status.UNAUTHENTICATED.withCause(failure));
                }
            });
        }
    }
}
