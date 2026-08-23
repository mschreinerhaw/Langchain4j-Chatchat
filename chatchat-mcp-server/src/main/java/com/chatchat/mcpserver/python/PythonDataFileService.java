package com.chatchat.mcpserver.python;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalSecretCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class PythonDataFileService {
    private final PythonRuntimeProperties properties;
    private final InternalCredentialProperties credentials;
    private final ObjectMapper objectMapper;

    public DataFileResult store(String tenant, String owner, String fileId, String encodedName, String expectedHash, byte[] encrypted) {
        component(tenant);
        component(owner);
        scope(fileId);
        if (encrypted == null || encrypted.length > properties.getMaxDataFileBytes() + 64)
            throw new IllegalArgumentException("数据文件超过 MCP 限制");
        String fileName = decodeName(encodedName);
        byte[] content = InternalSecretCipher.decryptBytes(encrypted, secret());
        if (content.length > properties.getMaxDataFileBytes())
            throw new IllegalArgumentException("数据文件超过 MCP 限制");
        String actualHash = sha256(content);
        if (expectedHash == null || !actualHash.equalsIgnoreCase(expectedHash))
            throw new IllegalArgumentException("数据文件 SHA-256 校验失败");
        try {
            Path directory = fileDirectory(tenant, owner, fileId);
            Files.createDirectories(directory);
            makeRuntimeTraversable(directory);
            Path target = directory.resolve(fileName).normalize();
            if (!target.getParent().equals(directory)) throw new IllegalArgumentException("非法数据文件名");
            Path temporary = Files.createTempFile(directory, "upload-", ".part");
            try {
                Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                makeRuntimeReadable(target);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return new DataFileResult(fileId, target.toString(), "/data/input/" + fileId + "/" + fileName, content.length, actualHash, "AVAILABLE");
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("MCP 数据文件落盘失败：" + ex.getMessage(), ex);
        }
    }

    public byte[] content(String tenant, String owner, String fileId) {
        try {
            Path dir = fileDirectory(tenant, owner, fileId);
            Path file = onlyFile(dir);
            return InternalSecretCipher.encryptBytes(Files.readAllBytes(file), secret());
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("数据文件不存在", ex);
        }
    }

    public void delete(String tenant, String owner, String fileId) {
        try {
            Path dir = fileDirectory(tenant, owner, fileId);
            if (!Files.exists(dir)) return;
            try (var files = Files.list(dir)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(dir);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("MCP 数据文件删除失败：" + ex.getMessage(), ex);
        }
    }

    public Path uploads(String tenant, String owner) {
        try {
            Path root = root();
            Path path = root.resolve(component(tenant)).resolve(component(owner)).resolve("uploads").normalize();
            if (!path.startsWith(root)) throw new IllegalArgumentException("非法数据目录");
            Files.createDirectories(path);
            makeRuntimeTraversable(path);
            return path;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("无法创建用户数据目录", ex);
        }
    }

    public List<DataFileView> discover(String tenant, String owner, String query, int requestedLimit) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        try {
            List<DataFileCandidate> candidates = new ArrayList<>();
            Path root = uploads(tenant, owner);
            try (var directories = Files.list(root)) {
                for (Path directory : directories.filter(Files::isDirectory).toList()) {
                    String fileId = directory.getFileName().toString();
                    if (!fileId.matches("[A-Za-z0-9_.-]{1,64}")) continue;
                    Path file;
                    try {
                        file = onlyFile(directory);
                    } catch (IllegalArgumentException ignored) {
                        continue;
                    }
                    String fileName = file.getFileName().toString();
                    String normalized = fileName.toLowerCase(Locale.ROOT);
                    String normalizedId = fileId.toLowerCase(Locale.ROOT);
                    int score = needle.isBlank() ? 1
                            : normalizedId.equals(needle) ? 5
                            : normalized.equals(needle) ? 4
                            : needle.contains(normalized) ? 3
                            : normalized.contains(needle) ? 2 : 0;
                    if (score > 0)
                        candidates.add(new DataFileCandidate(new DataFileView(fileId, fileName, Files.size(file), Files.getLastModifiedTime(file).toMillis()), score));
                }
            }
            return candidates.stream().sorted(Comparator.comparingInt(DataFileCandidate::score).reversed().thenComparing(candidate -> candidate.view().lastModifiedAt(), Comparator.reverseOrder())).limit(limit).map(DataFileCandidate::view).toList();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("无法查询当前用户的数据文件", ex);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveFileArguments(String schemaJson, Map<String, Object> values, String tenant, String owner) {
        try {
            Map<String, Object> schema = objectMapper.readValue(schemaJson, Map.class);
            Object raw = schema.get("properties");
            if (!(raw instanceof Map<?, ?> propertiesMap)) return values;
            Map<String, Object> resolved = new LinkedHashMap<>(values == null ? Map.of() : values);
            for (var entry : propertiesMap.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> definition)) continue;
                String type = String.valueOf(definition.get("type"));
                if (!"FILE".equalsIgnoreCase(type)) continue;
                String key = String.valueOf(entry.getKey());
                Object value = resolved.get(key);
                if (value == null) continue;
                resolved.put(key, resolveFileReference(String.valueOf(value), tenant, owner));
            }
            return resolved;
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("FILE 参数解析失败：" + ex.getMessage(), ex);
        }
    }

    private String resolveFileReference(String value, String tenant, String owner) throws java.io.IOException {
        String prefix = "/data/input/";
        String reference = value == null ? "" : value.trim();
        if (reference.startsWith(prefix)) {
            String relative = reference.substring(prefix.length());
            String[] parts = relative.split("/", -1);
            if (parts.length != 2)
                throw new IllegalArgumentException("FILE 路径必须是 /data/input/{fileId}/{fileName}");
            String id = scope(parts[0]);
            Path file = onlyFile(fileDirectory(tenant, owner, id));
            if (!file.getFileName().toString().equals(parts[1]))
                throw new IllegalArgumentException("FILE 路径不存在或不属于当前用户");
            return prefix + id + "/" + file.getFileName();
        }
        String id = scope(reference);
        Path file = onlyFile(fileDirectory(tenant, owner, id));
        return prefix + id + "/" + file.getFileName();
    }

    private Path fileDirectory(String tenant, String owner, String id) {
        Path uploads = uploads(tenant, owner);
        Path path = uploads.resolve(scope(id)).normalize();
        if (!path.startsWith(uploads)) throw new IllegalArgumentException("非法数据文件路径");
        return path;
    }

    private Path onlyFile(Path directory) throws java.io.IOException {
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("数据文件不存在");
        makeRuntimeTraversable(directory);
        try (var files = Files.list(directory)) {
            Path file = files.filter(Files::isRegularFile).findFirst().orElseThrow(() -> new IllegalArgumentException("数据文件不存在"));
            makeRuntimeReadable(file);
            return file;
        }
    }

    private void makeRuntimeReadable(Path file) throws java.io.IOException {
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
        } catch (UnsupportedOperationException ex) {
            java.io.File local = file.toFile();
            if (!local.setReadable(true, false)) throw new java.io.IOException("无法授予 Runtime 数据文件读取权限");
        }
    }

    private void makeRuntimeTraversable(Path directory) throws java.io.IOException {
        try {
            Files.setPosixFilePermissions(directory, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
        } catch (UnsupportedOperationException ex) {
            java.io.File local = directory.toFile();
            if (!local.setReadable(true, false) || !local.setExecutable(true, false))
                throw new java.io.IOException("无法授予 Runtime 数据目录访问权限");
        }
    }

    private Path root() throws java.io.IOException {
        Path root = Paths.get(properties.getDataRoot()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        return root;
    }

    private String decodeName(String encoded) {
        try {
            String name = new String(java.util.Base64.getUrlDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);
            if (name.isBlank() || name.length() > 255 || name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("非法数据文件名");
            return name;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("非法数据文件名", ex);
        }
    }

    private String scope(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,64}"))
            throw new IllegalArgumentException("非法数据作用域");
        return value;
    }

    private String component(String value) {
        if (value == null || value.isBlank() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("非法数据作用域");
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String secret() {
        String value = credentials.resolvedSecret();
        if (value.isBlank()) throw new IllegalStateException("内部加密凭据未配置");
        return value;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record DataFileCandidate(DataFileView view, int score) {
    }

    public record DataFileView(String fileId, String fileName, long fileSize, long lastModifiedAt) {
    }

    public record DataFileResult(String id, String storagePath, String pythonPath, long fileSize, String fileHash,
                                 String status) {
    }
}
