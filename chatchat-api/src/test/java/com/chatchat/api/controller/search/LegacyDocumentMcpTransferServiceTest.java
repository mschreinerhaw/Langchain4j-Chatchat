package com.chatchat.api.controller.search;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.knowledgebase.search.document.DocumentFileResource;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import com.chatchat.mcp.grpc.v1.DocumentTransferChunk;
import com.chatchat.mcp.grpc.v1.DocumentTransferReply;
import com.chatchat.mcp.grpc.v1.DocumentTransferServiceGrpc;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegacyDocumentMcpTransferServiceTest {
    @Test
    void streamsMultipleGrpcChunksWithCredentialAndIdentity() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        AtomicInteger chunks = new AtomicInteger();
        ServerInterceptor auth = new ServerInterceptor() {
            @Override public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                String bearer = headers.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER));
                if (!"Bearer secret".equals(bearer)) {
                    call.close(Status.UNAUTHENTICATED, new Metadata());
                    return new ServerCall.Listener<>() { };
                }
                return next.startCall(call, headers);
            }
        };
        Server server = NettyServerBuilder.forPort(0).addService(ServerInterceptors.intercept(
            new DocumentTransferServiceGrpc.DocumentTransferServiceImplBase() {
                @Override public StreamObserver<DocumentTransferChunk> transfer(StreamObserver<DocumentTransferReply> answer) {
                    return new StreamObserver<>() {
                        @Override public void onNext(DocumentTransferChunk chunk) {
                            if (chunk.hasStart()) received.set(chunk.getStart().getTenantId() + "|"
                                + chunk.getStart().getUserId() + "|" + chunk.getStart().getFileName());
                            if (chunk.hasData()) chunks.incrementAndGet();
                        }
                        @Override public void onError(Throwable failure) { }
                        @Override public void onCompleted() {
                            answer.onNext(DocumentTransferReply.newBuilder().setDocumentJson(ByteString.copyFromUtf8(
                                "{\"docId\":\"legacy-1\",\"title\":\"Guide\"}")).build());
                            answer.onCompleted();
                        }
                    };
                }
            }, auth)).build().start();
        InternalCredentialProperties credentials = mock(InternalCredentialProperties.class);
        when(credentials.resolvedSecret()).thenReturn("secret");
        DocumentGrpcTransferClient client = new DocumentGrpcTransferClient(new ObjectMapper(), credentials,
            "127.0.0.1", server.getPort(), true, "");
        try {
            LegacyDocumentMcpTransferService service = new LegacyDocumentMcpTransferService(
                client, new ObjectMapper(), credentials);
            when(credentials.isEnabled()).thenReturn(true);
            SearchDocument document = SearchDocument.builder().docId("legacy-1").title("Guide")
                .content("searchable text").tenantId("tenant-1").userId("user-1").build();
            byte[] bytes = new byte[2 * 1024 * 1024 + 17];
            DocumentFileResource file = new DocumentFileResource(new ByteArrayResource(bytes), "guide.txt", "text");
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setAttribute(ApiAuthenticationFilter.CURRENT_USERNAME, "user-1");

            SearchDocument transferred = service.transfer(document, file,
                SearchPermissionContext.of("tenant-1", "user-1", null), request);

            assertThat(transferred.getDocId()).isEqualTo("legacy-1");
            assertThat(received.get()).isEqualTo("tenant-1|user-1|guide.txt");
            assertThat(chunks.get()).isGreaterThan(1);
        } finally {
            client.close();
            server.shutdownNow();
        }
    }
}
