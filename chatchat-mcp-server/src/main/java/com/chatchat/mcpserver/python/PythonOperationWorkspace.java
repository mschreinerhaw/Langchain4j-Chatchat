package com.chatchat.mcpserver.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.*;

/** Immutable inputs and a persistent, operation-scoped output archive. */
@Component
@RequiredArgsConstructor
public class PythonOperationWorkspace {
    private final PythonRuntimeProperties properties;
    private final PythonDataFileService dataFiles;
    private final ObjectMapper mapper;

    public Path path(String tenant, String owner, String run, String task, String operation, int attempt) {
        Path root = Path.of(properties.getWorkspaceRoot()).toAbsolutePath().normalize().resolve("operations");
        Path result = root.resolve(key(tenant)).resolve(key(owner)).resolve(key(run))
                .resolve(key(task)).resolve(key(operation)).resolve(Integer.toString(attempt)).normalize();
        if (!result.startsWith(root)) throw new IllegalArgumentException("Invalid operation path");
        return result;
    }

    public Snapshot prepare(Path target, String fileName, String source, Map<String,Object> parameters,
                            String tenant, String owner, List<String> authorizedFileIds) {
        return prepare(target,fileName,source,parameters,tenant,owner,authorizedFileIds,null);
    }
    public Snapshot prepare(Path target,String fileName,String source,Map<String,Object> parameters,
                            String tenant,String owner,List<String> authorizedFileIds,com.chatchat.common.runtime.coding.CodingScript script) {
        Path scopedRoot=Path.of(properties.getWorkspaceRoot()).toAbsolutePath().normalize().resolve("operations").resolve(key(tenant)).resolve(key(owner));
        if(!target.toAbsolutePath().normalize().startsWith(scopedRoot))throw new SecurityException("Operation workspace is outside owner boundary");
        if(script!=null && !script.fileName().equals(fileName))throw new SecurityException("Script file differs from its version");
        if (!com.chatchat.common.runtime.coding.CodingScript.safeFileName(fileName))
            throw new IllegalArgumentException("Invalid Python file name");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Python source is empty");
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(target)) throw new IllegalArgumentException("Operation path is a symlink");
                return matchingSnapshot(target,fileName,source,parameters,script);
            }
            Path parent = target.getParent();
            rejectLinks(parent);
            Files.createDirectories(parent);
            rejectLinks(parent);
            Path temporary = Files.createTempDirectory(parent, ".snapshot-");
            try {
                Path scripts = Files.createDirectories(temporary.resolve("scripts"));
                Path inputs = Files.createDirectories(temporary.resolve("input"));
                Files.createDirectories(temporary.resolve("output").resolve("results"));
                Files.createDirectories(temporary.resolve("output").resolve("validation"));
                Files.createDirectories(temporary.resolve("output").resolve("logs"));
                byte[] code = source.getBytes(StandardCharsets.UTF_8);
                Files.write(scripts.resolve(fileName), code, StandardOpenOption.CREATE_NEW);
                List<PythonDataFileService.SnapshotFile> files = new ArrayList<>();
                for (String id : new LinkedHashSet<>(authorizedFileIds == null ? List.<String>of() : authorizedFileIds)) {
                    if (id == null || !id.matches("[A-Za-z0-9_.-]{1,64}")) throw new IllegalArgumentException("Invalid authorized file ID");
                    files.add(dataFiles.snapshotAuthorized(tenant, owner, id, inputs.resolve(id)));
                }
                byte[] args = mapper.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                        .writeValueAsBytes(parameters == null ? Map.of() : parameters);
                Files.write(temporary.resolve("parameters.json"), args, StandardOpenOption.CREATE_NEW);
                Snapshot snapshot = new Snapshot(fileName, hash(code), hash(args), List.copyOf(files),script);
                Files.write(temporary.resolve("snapshot.json"), mapper.writeValueAsBytes(snapshot), StandardOpenOption.CREATE_NEW);
                try {
                    Files.move(temporary, target);
                } catch (FileAlreadyExistsException ex) {
                    return matchingSnapshot(target,fileName,source,parameters,script);
                } catch (DirectoryNotEmptyException ex) {
                    return matchingSnapshot(target,fileName,source,parameters,script);
                }
                return snapshot;
            } finally {
                if (Files.exists(temporary)) removeTree(temporary);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot prepare operation snapshot", ex);
        }
    }

    public Snapshot readSnapshot(Path target) throws IOException {
        rejectLinks(target);
        for (Path path : List.of(target.resolve("snapshot.json"), target.resolve("parameters.json"), target.resolve("scripts"), target.resolve("input")))
            if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("Symlink in operation snapshot");
        Snapshot snapshot = mapper.readValue(Files.readAllBytes(target.resolve("snapshot.json")), Snapshot.class);
        if (!com.chatchat.common.runtime.coding.CodingScript.safeFileName(snapshot.fileName()))
            throw new IllegalArgumentException("Invalid stored Python file name");
        if(snapshot.script()!=null&&!snapshot.fileName().equals(snapshot.script().fileName()))
            throw new SecurityException("Stored script version binding differs");
        if (Files.isSymbolicLink(target.resolve("scripts").resolve(snapshot.fileName()))
                || !hash(Files.readAllBytes(target.resolve("scripts").resolve(snapshot.fileName()))).equals(snapshot.codeHash())
                || !hash(Files.readAllBytes(target.resolve("parameters.json"))).equals(snapshot.parametersHash()))
            throw new IllegalStateException("Operation snapshot checksum mismatch");
        for (var file : snapshot.inputs()) {
            if (file.fileId() == null || !file.fileId().matches("[A-Za-z0-9_.-]{1,64}")
                    || file.fileName() == null || file.fileName().isBlank() || file.fileName().length() > 255
                    || file.fileName().contains("/") || file.fileName().contains("\\")
                    || file.fileName().chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid stored input reference");
            Path source = target.resolve("input").resolve(file.fileId()).resolve(file.fileName());
            if (Files.isSymbolicLink(source.getParent()) || Files.isSymbolicLink(source)
                    || !hash(Files.readAllBytes(source)).equals(file.sha256()))
                throw new IllegalStateException("Input snapshot checksum mismatch");
        }
        return snapshot;
    }
    private Snapshot matchingSnapshot(Path target,String name,String source,Map<String,Object> parameters,
            com.chatchat.common.runtime.coding.CodingScript script) throws IOException {
        var stored=readSnapshot(target);
        byte[] args=mapper.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .writeValueAsBytes(parameters==null?Map.of():parameters);
        if(!stored.fileName().equals(name)||!stored.codeHash().equals(hash(source.getBytes(StandardCharsets.UTF_8)))
                ||!stored.parametersHash().equals(hash(args))||!Objects.equals(stored.script(),script))
            throw new SecurityException("Operation snapshot is immutable");
        return stored;
    }

    /** Give only the configured sandbox UID access to the operation's writable mount. */
    public static void assignOutputOwner(Path workspace, String runtimeUser) throws IOException {
        if (runtimeUser == null || !runtimeUser.matches("[0-9]{1,10}(:[0-9]{1,10})?"))
            throw new IllegalArgumentException("Invalid sandbox user");
        String[] ids = runtimeUser.split(":", 2);
        int uid = Math.toIntExact(Long.parseLong(ids[0]));
        int gid = Math.toIntExact(Long.parseLong(ids.length == 2 ? ids[1] : ids[0]));
        rejectLinks(workspace);
        Path output = workspace.resolve("output");
        for (Path path : List.of(output, output.resolve("results"), output.resolve("validation"), output.resolve("logs"))) {
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("Invalid operation output directory");
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.setAttribute(path, "unix:uid", uid, LinkOption.NOFOLLOW_LINKS);
                Files.setAttribute(path, "unix:gid", gid, LinkOption.NOFOLLOW_LINKS);
                Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
            } else {
                // Docker Desktop binds NTFS using host ACLs, not persistent Unix ownership.
                // Keep the host mount private to its service owner; the container still runs
                // with the fixed non-root UID, no capabilities and no host filesystem access.
                var acl=Files.getFileAttributeView(path,java.nio.file.attribute.AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
                if(acl==null)throw new IOException("Sandbox output filesystem provides neither POSIX ownership nor ACLs");
                var owner=Files.getOwner(path,LinkOption.NOFOLLOW_LINKS);
                var entry=java.nio.file.attribute.AclEntry.newBuilder().setType(java.nio.file.attribute.AclEntryType.ALLOW)
                    .setPrincipal(owner).setPermissions(EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class))
                    .setFlags(java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT,java.nio.file.attribute.AclEntryFlag.FILE_INHERIT).build();
                acl.setAcl(List.of(entry));
            }
        }
    }

    public Manifest archive(Path workspace) throws IOException {
        rejectLinks(workspace);
        Path existing = workspace.resolve("manifest.json");
        if (Files.isSymbolicLink(existing)) throw new IllegalArgumentException("Manifest is a symlink");
        if (Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) return mapper.readValue(Files.readAllBytes(existing), Manifest.class);
        List<Artifact> artifacts = new ArrayList<>();
        Path archive = Files.createDirectories(workspace.resolve("archive"));
        rejectLinks(archive);
        for (String channel : List.of("results", "validation", "logs")) {
            Path directory = workspace.resolve("output").resolve(channel);
            rejectLinks(directory);
            Path preserved = Files.createDirectories(archive.resolve(channel));
            try (var stream = Files.list(directory)) {
                List<Path> outputFiles = stream.sorted().limit(257).toList();
                if (artifacts.size() + outputFiles.size() > 256) throw new IllegalStateException("Too many output artifacts");
                for (Path file : outputFiles) {
                    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                            || !file.getParent().equals(directory)
                            || !file.getFileName().toString().matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,254}"))
                        throw new IllegalStateException("Invalid output artifact");
                    String name = file.getFileName().toString();
                    Path saved = preserved.resolve(name);
                    if (!Files.exists(saved, LinkOption.NOFOLLOW_LINKS)) {
                        Path part = preserved.resolve(".part-" + UUID.randomUUID());
                        try {
                            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                                Files.copy(input, part);
                            }
                            if (Files.size(part) != Files.size(file) || !hash(part).equals(hash(file)))
                                throw new IllegalStateException("Output changed during archival");
                            try { Files.move(part, saved, StandardCopyOption.ATOMIC_MOVE); }
                            catch (AtomicMoveNotSupportedException ex) { Files.move(part, saved); }
                        } finally { Files.deleteIfExists(part); }
                    }
                    if (Files.isSymbolicLink(saved) || Files.size(saved) != Files.size(file) || !hash(saved).equals(hash(file)))
                        throw new IllegalStateException("Archived artifact differs from stopped output");
                    long size = Files.size(saved);
                    long rows = -1;
                    String schemaJson = null;
                    String completeness = size == 0 ? "EMPTY" : "PRESENT";
                    if (name.endsWith(".jsonl")) {
                        rows = 0;
                        var observedSchema = new com.chatchat.common.mcp.python.JsonlStructuralSchema();
                        var jsonlReader = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
                        try (var reader = Files.newBufferedReader(saved, StandardCharsets.UTF_8)) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.isBlank()) continue;
                                try {
                                    com.fasterxml.jackson.databind.JsonNode parsed = jsonlReader.readTree(line);
                                    if (!parsed.isObject()) throw new IllegalArgumentException("JSONL row must be an object");
                                    var types = new TreeMap<String,String>();
                                    parsed.properties().forEach(e -> types.put(e.getKey(),e.getValue().getNodeType().name()));
                                    observedSchema.accept(types);
                                    rows++;
                                } catch (Exception ex) { completeness = "PARTIAL"; break; }
                            }
                        }
                        if (rows > 0) schemaJson = mapper.writeValueAsString(observedSchema.fieldTypes());
                        if (rows == 0 && !"PARTIAL".equals(completeness)) completeness = "EMPTY";
                    } else if (size > 0) {
                        completeness = "UNVERIFIED";
                    }
                    artifacts.add(new Artifact(channel, name, size, hash(saved), rows, completeness, schemaJson));
                }
            }
        }
        List<Artifact> results = artifacts.stream().filter(a -> a.channel().equals("results")).toList();
        String overall = results.isEmpty() ? "MISSING" : results.stream().anyMatch(a -> "PARTIAL".equals(a.completeness())) ? "PARTIAL"
                : results.stream().anyMatch(a -> "UNVERIFIED".equals(a.completeness())) ? "UNVERIFIED"
                : results.stream().allMatch(a -> "EMPTY".equals(a.completeness())) ? "EMPTY" : "PRESENT";
        Manifest manifest = new Manifest(List.copyOf(artifacts), overall);
        Path part = workspace.resolve(".manifest-" + UUID.randomUUID() + ".part");
        byte[] proposed = mapper.writeValueAsBytes(manifest);
        try {
            Files.write(part, proposed, StandardOpenOption.CREATE_NEW);
            try { Files.move(part, existing); }
            catch (FileAlreadyExistsException ex) {
                byte[] committed = Files.readAllBytes(existing);
                if (!Arrays.equals(proposed, committed)) throw new IllegalStateException("Committed manifest differs from retry");
            }
            return mapper.readValue(Files.readAllBytes(existing), Manifest.class);
        } finally { Files.deleteIfExists(part); }
    }

    public byte[] artifact(Path workspace, String channel, String fileName) throws IOException {
        return Files.readAllBytes(artifactPath(workspace, channel, fileName));
    }

    public Path artifactPath(Path workspace, String channel, String fileName) throws IOException {
        if (fileName == null || fileName.isBlank() || fileName.length() > 255
                || fileName.contains("/") || fileName.contains("\\")
                || fileName.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid artifact path");
        if ("code".equals(channel)) {
            Snapshot snapshot = readSnapshot(workspace);
            if (!snapshot.fileName().equals(fileName)) throw new IllegalArgumentException("Code file was not snapshotted");
            return workspace.resolve("scripts").resolve(fileName);
        }
        if ("manifest".equals(channel) && "manifest.json".equals(fileName)) {
            Path manifest = workspace.resolve("manifest.json");
            if (Files.isSymbolicLink(manifest)) throw new IllegalArgumentException("Manifest is a symlink");
            return manifest;
        }
        if (channel != null && channel.startsWith("input-") && channel.substring(6).matches("[A-Za-z0-9_.-]{1,64}")) {
            Snapshot snapshot = readSnapshot(workspace);
            String fileId = channel.substring(6);
            if (snapshot.inputs().stream().noneMatch(file -> file.fileId().equals(fileId) && file.fileName().equals(fileName)))
                throw new IllegalArgumentException("Input file was not authorized for this operation");
            return workspace.resolve("input").resolve(fileId).resolve(fileName);
        }
        if (!Set.of("results", "validation", "logs").contains(channel)) throw new IllegalArgumentException("Invalid artifact channel");
        if (Files.isSymbolicLink(workspace.resolve("manifest.json"))) throw new IllegalArgumentException("Manifest is a symlink");
        Manifest manifest = mapper.readValue(Files.readAllBytes(workspace.resolve("manifest.json")), Manifest.class);
        Artifact entry = manifest.artifacts().stream().filter(a -> a.channel().equals(channel) && a.fileName().equals(fileName))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Artifact was not archived"));
        Path file = workspace.resolve("archive").resolve(channel).resolve(fileName);
        if (Files.isSymbolicLink(file)) throw new IllegalStateException("Artifact is a symlink");
        if (Files.size(file) != entry.size() || !hash(file).equals(entry.sha256())) throw new IllegalStateException("Artifact checksum mismatch");
        return file;
    }

    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private String hash(Path path) throws IOException {
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private String key(String text) {
        if (text == null || text.isBlank() || text.length() > 128 || text.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid operation identity");
        return hash(text.getBytes(StandardCharsets.UTF_8));
    }
    private static void rejectLinks(Path path) throws IOException {
        for (Path current = path.toAbsolutePath(); current != null; current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("Symbolic link in operation path");
    }
    private void removeTree(Path path) throws IOException {
        try (var stream = Files.walk(path)) {
            for (Path child : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(child);
        }
    }
    public record Snapshot(String fileName, String codeHash, String parametersHash,
                           List<PythonDataFileService.SnapshotFile> inputs, com.chatchat.common.runtime.coding.CodingScript script) {}
    public record Artifact(String channel, String fileName, long size, String sha256, long rows,
                           String completeness, String schemaJson) {}
    public record Manifest(List<Artifact> artifacts, String resultCompleteness) {}
}
