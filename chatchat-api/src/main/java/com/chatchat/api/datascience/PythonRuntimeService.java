package com.chatchat.api.datascience;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
public class PythonRuntimeService {
    private final PythonDataScienceProperties properties;
    private final ObjectMapper objectMapper;

    public ProvisionResult provision(PythonAssetEntity asset) {
        try {
            Path workspace = workspace(asset);
            Files.createDirectories(workspace.resolve("scripts"));
            String container = containerName(asset.getId());
            ExecResult inspect = run(List.of(properties.getDockerCommand(), "inspect", container), Duration.ofSeconds(15));
            if (inspect.exitCode() != 0) {
                List<String> command = new ArrayList<>(List.of(properties.getDockerCommand(), "create", "--name", container,
                        "--cpus", asset.getCpuLimit(), "--memory", asset.getMemoryLimit(), "--pids-limit", "256",
                        "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true"));
                if (!asset.isNetworkEnabled()) command.addAll(List.of("--network", "none"));
                command.addAll(List.of("-v", workspace.toString() + ":/workspace", "-w", "/workspace", asset.getDockerImage(),
                        "sh", "-c", "while true; do sleep 3600; done"));
                ExecResult created = run(command, Duration.ofMinutes(5));
                if (created.exitCode() != 0)
                    return new ProvisionResult(false, container, workspace.toString(), created.stderr());
            }
            ExecResult start = run(List.of(properties.getDockerCommand(), "start", container), Duration.ofSeconds(30));
            return new ProvisionResult(start.exitCode() == 0, container, workspace.toString(), start.exitCode() == 0 ? "" : start.stderr());
        } catch (Exception ex) {
            return new ProvisionResult(false, containerName(asset.getId()), "", ex.getMessage());
        }
    }

    public ExecResult execute(PythonAssetEntity asset, String fileName, String source, Map<String, Object> parameters) {
        requireSafeFileName(fileName);
        try {
            ProvisionResult provision = provision(asset);
            if (!provision.ready()) return new ExecResult(-1, "", provision.message(), 0, false);
            Path script = workspace(asset).resolve("scripts").resolve(fileName).normalize();
            if (!script.startsWith(workspace(asset).resolve("scripts")))
                throw new IllegalArgumentException("非法脚本文件名");
            Files.writeString(script, source, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            String json = objectMapper.writeValueAsString(parameters == null ? Map.of() : parameters);
            return run(List.of(properties.getDockerCommand(), "exec", "-e", "CHATCHAT_INPUT_JSON=" + json,
                    provision.containerName(), "python", "/workspace/scripts/" + fileName), Duration.ofSeconds(properties.getTimeoutSeconds()));
        } catch (Exception ex) {
            return new ExecResult(-1, "", ex.getMessage(), 0, false);
        }
    }

    private Path workspace(PythonAssetEntity asset) throws IOException {
        Path root = Paths.get(properties.getWorkspaceRoot()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path path = root.resolve(safe(asset.getTenantId())).resolve(safe(asset.getOwnerId())).resolve(asset.getId()).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("非法 workspace 路径");
        return path;
    }

    private String containerName(String assetId) {
        return "chatchat-py-" + assetId.replaceAll("[^A-Za-z0-9_.-]", "").toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "default" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private void requireSafeFileName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,170}\\.py"))
            throw new IllegalArgumentException("脚本文件名必须是安全的 .py 文件名");
    }

    private ExecResult run(List<String> command, Duration timeout) throws IOException, InterruptedException {
        long started = System.nanoTime();
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        ExecutorService readers = Executors.newFixedThreadPool(2);
        try {
            Future<String> out = readers.submit(() -> readLimited(process.getInputStream()));
            Future<String> err = readers.submit(() -> readLimited(process.getErrorStream()));
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            return new ExecResult(finished ? process.exitValue() : 124, get(out), get(err), elapsed(started), !finished);
        } finally {
            readers.shutdownNow();
        }
    }

    private String readLimited(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(properties.getOutputLimitBytes() + 1);
        String text = new String(bytes, 0, Math.min(bytes.length, properties.getOutputLimitBytes()), StandardCharsets.UTF_8);
        return bytes.length > properties.getOutputLimitBytes() ? text + "\n[输出已截断]" : text;
    }

    private String get(Future<String> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            return ex.getMessage();
        }
    }

    private long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    public record ProvisionResult(boolean ready, String containerName, String workspacePath, String message) {
    }

    public record ExecResult(int exitCode, String stdout, String stderr, long durationMs, boolean timedOut) {
    }
}
