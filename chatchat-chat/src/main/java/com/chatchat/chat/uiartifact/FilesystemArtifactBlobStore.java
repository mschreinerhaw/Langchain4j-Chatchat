package com.chatchat.chat.uiartifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

@Component
@ConditionalOnExpression(
    "'${chatchat.ui-artifact.store-type:local}' == 'local' || "
        + "'${chatchat.ui-artifact.store-type:local}' == 'shared' || "
        + "'${chatchat.ui-artifact.store-type:local}' == 'filesystem'"
)
public class FilesystemArtifactBlobStore implements ArtifactBlobStore {

    private final ObjectMapper objectMapper;
    private final Path storageRoot;
    private final String configuredStoreType;

    public FilesystemArtifactBlobStore(ObjectMapper objectMapper, UiArtifactProperties properties) {
        this.objectMapper = objectMapper;
        String type = properties.getStoreType() == null ? "local" : properties.getStoreType().trim().toLowerCase();
        this.configuredStoreType = "shared".equals(type) ? "shared" : "local";
        Path configuredPath = Path.of(properties.getStoragePath()).normalize();
        if ("shared".equals(configuredStoreType) && !configuredPath.isAbsolute()) {
            throw new IllegalArgumentException("Shared artifact storage path must be absolute");
        }
        this.storageRoot = configuredPath.toAbsolutePath().normalize();
        if ("shared".equals(configuredStoreType)) {
            requireSharedStorageMarker(properties.getSharedMarkerFile());
        }
    }

    @Override
    public void put(ArtifactLocation location, InputStream content, ArtifactObjectMetadata metadata) {
        Path target = resolve(location);
        Path metadataTarget = metadataPath(target);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
            try {
                long copied = Files.copy(content, temporary, StandardCopyOption.REPLACE_EXISTING);
                if (copied != metadata.contentLength()) {
                    throw new IllegalStateException("Artifact content length mismatch");
                }
                forceFile(temporary);
                moveAtomically(temporary, target);
                forceDirectory(target.getParent());
            } finally {
                Files.deleteIfExists(temporary);
            }

            byte[] metadataBytes = objectMapper.writeValueAsBytes(metadata);
            Path metadataTemporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".meta.tmp");
            try {
                try (FileChannel channel = FileChannel.open(
                    metadataTemporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    channel.write(ByteBuffer.wrap(metadataBytes));
                    channel.force(true);
                }
                moveAtomically(metadataTemporary, metadataTarget);
                forceDirectory(target.getParent());
            } finally {
                Files.deleteIfExists(metadataTemporary);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to persist artifact object " + location.objectKey(), ex);
        }
    }

    @Override
    public Optional<ArtifactContent> get(ArtifactLocation location) {
        Path target = resolve(location);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            ArtifactObjectMetadata metadata = Files.isRegularFile(metadataPath(target))
                ? objectMapper.readValue(Files.readAllBytes(metadataPath(target)), ArtifactObjectMetadata.class)
                : new ArtifactObjectMetadata(
                    Files.probeContentType(target), Files.size(target), "", Map.of());
            return Optional.of(new ArtifactContent(Files.newInputStream(target), metadata));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read artifact object " + location.objectKey(), ex);
        }
    }

    @Override
    public boolean exists(ArtifactLocation location) {
        return Files.isRegularFile(resolve(location));
    }

    @Override
    public void delete(ArtifactLocation location) {
        Path target = resolve(location);
        try {
            Files.deleteIfExists(target);
            Files.deleteIfExists(metadataPath(target));
            forceDirectory(target.getParent());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to delete artifact object " + location.objectKey(), ex);
        }
    }

    @Override
    public String storeType() {
        return configuredStoreType;
    }

    private Path resolve(ArtifactLocation location) {
        Path target = storageRoot
            .resolve(tenantHash(location.tenantId()))
            .resolve(location.artifactId())
            .resolve(location.objectKey())
            .normalize();
        if (!target.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Invalid artifact object path");
        }
        return target;
    }

    private static Path metadataPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".metadata.json");
    }

    private static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Some Windows and network providers cannot fsync a directory.
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String tenantHash(String tenantId) {
        String normalized = tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private void requireSharedStorageMarker(String markerFile) {
        String marker = markerFile == null || markerFile.isBlank()
            ? ".chatchat-artifact-store" : markerFile.trim();
        Path markerName = Path.of(marker);
        if (markerName.getNameCount() != 1 || !marker.equals(markerName.getFileName().toString())) {
            throw new IllegalArgumentException("Shared artifact marker file name is invalid");
        }
        Path markerPath = storageRoot.resolve(marker).normalize();
        if (!markerPath.startsWith(storageRoot) || !Files.isRegularFile(markerPath)) {
            throw new IllegalStateException(
                "Shared artifact storage marker is missing: " + markerPath
                    + ". Refusing to write because the shared volume may not be mounted.");
        }
    }
}
