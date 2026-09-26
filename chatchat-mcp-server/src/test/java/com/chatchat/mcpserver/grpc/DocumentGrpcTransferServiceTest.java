package com.chatchat.mcpserver.grpc;

import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.service.SearchService;
import com.chatchat.mcp.grpc.v1.DocumentTransferChunk;
import com.chatchat.mcp.grpc.v1.DocumentTransferReply;
import com.chatchat.mcp.grpc.v1.DocumentTransferStart;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentGrpcTransferServiceTest {
    @TempDir Path storage;

    @Test
    void uploadsChunkedFileAndIndexesItOnMcp() throws Exception {
        SearchService search = mock(SearchService.class);
        AtomicReference<String> uploaded = new AtomicReference<>();
        when(search.upload(any(MultipartFile.class), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any())).thenAnswer(call -> {
                MultipartFile file = call.getArgument(0);
                uploaded.set(new String(file.getBytes()));
                return SearchDocument.builder().docId("new-1").build();
            });
        SearchProperties properties = new SearchProperties();
        properties.setFilePath(storage.toString());
        DocumentGrpcTransferService service = new DocumentGrpcTransferService(search, properties, new ObjectMapper());
        AtomicReference<DocumentTransferReply> result = new AtomicReference<>();
        StreamObserver<DocumentTransferChunk> sender = service.transfer(observer(result));
        sender.onNext(DocumentTransferChunk.newBuilder().setStart(DocumentTransferStart.newBuilder()
            .setOperation("UPLOAD").setTenantId("tenant-1").setUserId("user-1")
            .setFileName("guide.txt").putFields("title", "Guide").build()).build());
        sender.onNext(DocumentTransferChunk.newBuilder().setData(ByteString.copyFromUtf8("hello ")).build());
        sender.onNext(DocumentTransferChunk.newBuilder().setData(ByteString.copyFromUtf8("world")).build());
        sender.onCompleted();

        assertThat(uploaded.get()).isEqualTo("hello world");
        assertThat(result.get().getDocumentJson().toStringUtf8()).contains("new-1");
    }

    @Test
    void migratesOriginalFileAndPreservesId() throws Exception {
        SearchService search = mock(SearchService.class);
        when(search.get("legacy-1")).thenReturn(Optional.empty());
        when(search.createOrUpdate(any(SearchDocument.class))).thenAnswer(call -> call.getArgument(0));
        SearchProperties properties = new SearchProperties();
        properties.setFilePath(storage.toString());
        DocumentGrpcTransferService service = new DocumentGrpcTransferService(search, properties, new ObjectMapper());
        AtomicReference<DocumentTransferReply> result = new AtomicReference<>();
        StreamObserver<DocumentTransferChunk> sender = service.transfer(observer(result));
        sender.onNext(DocumentTransferChunk.newBuilder().setStart(DocumentTransferStart.newBuilder()
            .setOperation("MIGRATE").setTenantId("tenant-1").setUserId("user-1").setUsername("admin")
            .setFileName("guide.txt").setDocumentJson(ByteString.copyFromUtf8(
                "{\"docId\":\"legacy-1\",\"title\":\"Guide\",\"content\":\"searchable\","
                    + "\"tenantId\":\"tenant-1\",\"userId\":\"user-1\"}"))
            .build()).build());
        sender.onNext(DocumentTransferChunk.newBuilder().setData(ByteString.copyFromUtf8("original"))
            .build());
        sender.onCompleted();

        SearchDocument saved = new ObjectMapper().readValue(result.get().getDocumentJson().toByteArray(),
            SearchDocument.class);
        assertThat(saved.getDocId()).isEqualTo("legacy-1");
        assertThat(Files.readString(Path.of(saved.getFilePath()))).isEqualTo("original");
    }

    @Test
    void rejectsMigrationWhenDocumentIdBelongsToAnotherOwner() {
        SearchService search = mock(SearchService.class);
        when(search.get("legacy-1")).thenReturn(Optional.of(SearchDocument.builder()
            .docId("legacy-1").tenantId("tenant-2").userId("user-2").build()));
        SearchProperties properties = new SearchProperties();
        properties.setFilePath(storage.toString());
        DocumentGrpcTransferService service = new DocumentGrpcTransferService(search, properties, new ObjectMapper());
        AtomicReference<Throwable> error = new AtomicReference<>();
        StreamObserver<DocumentTransferChunk> sender = service.transfer(new StreamObserver<>() {
            @Override public void onNext(DocumentTransferReply value) { }
            @Override public void onError(Throwable failure) { error.set(failure); }
            @Override public void onCompleted() { }
        });
        sender.onNext(DocumentTransferChunk.newBuilder().setStart(DocumentTransferStart.newBuilder()
            .setOperation("MIGRATE").setTenantId("tenant-1").setUserId("user-1")
            .setDocumentJson(ByteString.copyFromUtf8(
                "{\"docId\":\"legacy-1\",\"title\":\"Guide\",\"content\":\"searchable\","
                    + "\"tenantId\":\"tenant-1\",\"userId\":\"user-1\"}"))
            .build()).build());
        sender.onCompleted();

        assertThat(error.get()).hasMessageContaining("document ID belongs to another owner");
    }

    @Test
    void allowsAuthorizedCallerToMigrateDocumentWhenExistingOwnerIsLegacy() throws Exception {
        SearchService search = mock(SearchService.class);
        when(search.get("legacy-1")).thenReturn(Optional.of(SearchDocument.builder()
            .docId("legacy-1").tenantId("default").userId("admin").build()));
        when(search.createOrUpdate(any(SearchDocument.class))).thenAnswer(call -> call.getArgument(0));
        SearchProperties properties = new SearchProperties();
        properties.setFilePath(storage.toString());
        DocumentGrpcTransferService service = new DocumentGrpcTransferService(search, properties, new ObjectMapper());
        AtomicReference<DocumentTransferReply> result = new AtomicReference<>();
        StreamObserver<DocumentTransferChunk> sender = service.transfer(observer(result));
        sender.onNext(DocumentTransferChunk.newBuilder().setStart(DocumentTransferStart.newBuilder()
            .setOperation("MIGRATE").setTenantId("tenant-1").setUserId("admin-uuid").setUsername("admin")
            .setPermissions("workspace:search:delete")
            .setDocumentJson(ByteString.copyFromUtf8(
                "{\"docId\":\"legacy-1\",\"title\":\"Guide\",\"content\":\"searchable\","
                    + "\"tenantId\":\"tenant-1\",\"userId\":\"admin-uuid\"}"))
            .build()).build());
        sender.onCompleted();

        SearchDocument saved = new ObjectMapper().readValue(result.get().getDocumentJson().toByteArray(),
            SearchDocument.class);
        assertThat(saved.getTenantId()).isEqualTo("tenant-1");
        assertThat(saved.getUserId()).isEqualTo("admin-uuid");
    }

    private StreamObserver<DocumentTransferReply> observer(AtomicReference<DocumentTransferReply> result) {
        return new StreamObserver<>() {
            @Override public void onNext(DocumentTransferReply value) { result.set(value); }
            @Override public void onError(Throwable failure) { throw new AssertionError(failure); }
            @Override public void onCompleted() { }
        };
    }
}
