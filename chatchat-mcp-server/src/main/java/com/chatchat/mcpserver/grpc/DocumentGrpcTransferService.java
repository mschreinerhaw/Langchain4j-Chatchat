package com.chatchat.mcpserver.grpc;

import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import com.chatchat.knowledgebase.search.service.SearchService;
import com.chatchat.mcp.grpc.v1.DocumentTransferChunk;
import com.chatchat.mcp.grpc.v1.DocumentTransferReply;
import com.chatchat.mcp.grpc.v1.DocumentTransferServiceGrpc;
import com.chatchat.mcp.grpc.v1.DocumentTransferStart;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** MCP-owned upload and legacy migration over authenticated, chunked gRPC. */
public final class DocumentGrpcTransferService extends DocumentTransferServiceGrpc.DocumentTransferServiceImplBase {
    private static final long MAX_BYTES = 55L * 1024 * 1024;
    private final SearchService search;
    private final SearchProperties properties;
    private final ObjectMapper mapper;

    public DocumentGrpcTransferService(SearchService search, SearchProperties properties, ObjectMapper mapper) {
        this.search = search;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override public StreamObserver<DocumentTransferChunk> transfer(StreamObserver<DocumentTransferReply> response) {
        return new StreamObserver<>() {
            private DocumentTransferStart start;
            private Path temporary;
            private OutputStream output;
            private long bytes;
            private boolean finished;

            @Override public void onNext(DocumentTransferChunk chunk) {
                if (finished) return;
                try {
                    if (chunk.hasStart()) {
                        if (start != null) throw new IllegalArgumentException("duplicate transfer header");
                        start = chunk.getStart();
                        if (start.getTenantId().isBlank() || start.getUserId().isBlank())
                            throw new IllegalArgumentException("document identity is required");
                        temporary = Files.createTempFile("mcp-document-transfer-", ".bin");
                        output = Files.newOutputStream(temporary);
                    } else if (chunk.hasData()) {
                        if (start == null) throw new IllegalArgumentException("transfer header is required");
                        bytes += chunk.getData().size();
                        if (bytes > MAX_BYTES || bytes > properties.getMaxUploadBytes())
                            throw new IllegalArgumentException("file exceeds 55MB limit");
                        chunk.getData().writeTo(output);
                    } else throw new IllegalArgumentException("empty transfer chunk");
                } catch (Exception failure) {
                    fail(failure);
                }
            }

            @Override public void onError(Throwable failure) { cleanup(); }

            @Override public void onCompleted() {
                if (finished) return;
                try {
                    if (start == null) throw new IllegalArgumentException("transfer header is required");
                    output.close();
                    output = null;
                    SearchDocument saved = process(start, temporary, bytes);
                    response.onNext(DocumentTransferReply.newBuilder()
                        .setDocumentJson(ByteString.copyFrom(mapper.writeValueAsBytes(saved))).build());
                    finished = true;
                    response.onCompleted();
                } catch (Exception failure) {
                    fail(failure);
                } finally {
                    cleanup();
                }
            }

            private void fail(Exception failure) {
                if (finished) return;
                finished = true;
                cleanup();
                Status status = failure instanceof IllegalArgumentException ? Status.INVALID_ARGUMENT : Status.INTERNAL;
                response.onError(status.withDescription(failure.getMessage()).withCause(failure).asRuntimeException());
            }

            private void cleanup() {
                try { if (output != null) output.close(); } catch (IOException ignored) { }
                try { if (temporary != null) Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        };
    }

    private SearchDocument process(DocumentTransferStart start, Path temporary, long bytes) throws IOException {
        SearchPermissionContext caller = SearchPermissionContext.of(start.getTenantId(), start.getUserId(),
            csv(start.getRoles()));
        if ("UPLOAD".equals(start.getOperation())) {
            MultipartFile file = new PathMultipartFile(temporary, safeName(start.getFileName()),
                start.getContentType(), bytes);
            return search.upload(file,
                field(start, "title"), field(start, "source"), field(start, "date"), field(start, "tags"),
                field(start, "companies"), field(start, "industries"), field(start, "keywords"),
                field(start, "documentType"), field(start, "content"), caller,
                field(start, "visibility"), csv(field(start, "permissionRoles")));
        }
        if (!"MIGRATE".equals(start.getOperation())) throw new IllegalArgumentException("unknown transfer operation");
        SearchDocument document = mapper.readValue(start.getDocumentJson().toByteArray(), SearchDocument.class);
        boolean admin = "admin".equalsIgnoreCase(start.getUsername());
        boolean owner = caller.tenantId().equals(document.getTenantId())
            && caller.userId().equals(document.getUserId());
        if (!owner && !admin)
            throw new IllegalArgumentException("only document owner or admin can transfer documents");
        if (document.getDocId() == null || !document.getDocId().matches("[A-Za-z0-9._:-]{1,128}"))
            throw new IllegalArgumentException("invalid document ID");
        if (document.getContent() == null || document.getContent().isBlank())
            throw new IllegalArgumentException("document content is required");
        search.get(document.getDocId()).ifPresent(existing -> {
            boolean ownerMismatch = !Objects.equals(existing.getTenantId(), document.getTenantId())
                || !Objects.equals(existing.getUserId(), document.getUserId());
            if (ownerMismatch && !admin)
                throw new IllegalArgumentException("document ID belongs to another owner");
        });
        Path savedFile = null;
        boolean existingFile = false;
        if (bytes > 0) {
            Path root = Path.of(properties.getFilePath()).toAbsolutePath().normalize();
            Files.createDirectories(root);
            String fileName = safeName(start.getFileName());
            savedFile = root.resolve(document.getDocId() + "_" + fileName).normalize();
            if (!savedFile.startsWith(root)) throw new IllegalArgumentException("invalid file name");
            existingFile = Files.exists(savedFile);
            Files.copy(temporary, savedFile, StandardCopyOption.REPLACE_EXISTING);
            document.setFilePath(savedFile.toString());
            document.setFileName(fileName);
            document.setFileSize(bytes);
        } else document.setFilePath(null);
        try {
            return search.createOrUpdate(document);
        } catch (RuntimeException failure) {
            if (savedFile != null && !existingFile) Files.deleteIfExists(savedFile);
            throw failure;
        }
    }

    private String field(DocumentTransferStart start, String key) { return start.getFieldsOrDefault(key, ""); }
    private List<String> csv(String value) {
        return value == null || value.isBlank() ? List.of()
            : Arrays.stream(value.split(",")).map(String::trim).filter(part -> !part.isBlank()).toList();
    }
    private String safeName(String value) {
        if (value == null || value.isBlank()) return "document";
        String normalized = value.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private record PathMultipartFile(Path path, String filename, String type, long length) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return filename; }
        @Override public String getContentType() { return type; }
        @Override public boolean isEmpty() { return length == 0; }
        @Override public long getSize() { return length; }
        @Override public byte[] getBytes() throws IOException { return Files.readAllBytes(path); }
        @Override public InputStream getInputStream() throws IOException { return Files.newInputStream(path); }
        @Override public void transferTo(java.io.File destination) throws IOException {
            Files.copy(path, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
