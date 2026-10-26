package com.chatchat.mcpserver.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import com.chatchat.common.runtime.coding.ExecutionFailureClassifier;

/** No user stream content crosses the managed-execution persistence boundary. */
final class PythonExecutionLogBoundary {
    private static final Set<String> EXCEPTIONS = Set.of("AssertionError", "AttributeError", "EOFError",
        "ImportError", "ModuleNotFoundError", "IndexError", "KeyError", "NameError", "UnboundLocalError",
        "OSError", "FileNotFoundError", "PermissionError", "RuntimeError", "NotImplementedError",
        "RecursionError", "StopIteration", "SyntaxError", "IndentationError", "TabError", "TypeError",
        "ValueError", "UnicodeError", "UnicodeDecodeError", "UnicodeEncodeError", "ZeroDivisionError",
        "OverflowError", "MemoryError", "TimeoutError", "SystemExit", "KeyboardInterrupt");

    static void persist(Path workspace, ObjectMapper mapper) throws IOException {
        Path directory = workspace.resolve("output/logs");
        if (Files.isSymbolicLink(directory)) throw new IOException("Log directory is a symlink");
        Path diagnostic = directory.resolve("runtime-diagnostic.json");
        Map<String,Object> safe = new TreeMap<>();
        var snapshot=mapper.readTree(Files.readAllBytes(workspace.resolve("snapshot.json")));
        String scriptFile=snapshot.path("fileName").asText();
        if(!com.chatchat.common.runtime.coding.CodingScript.safeFileName(scriptFile))throw new IOException("Invalid diagnostic script binding");
        safe.put("policy", "METADATA_ONLY_V1");
        if (Files.isRegularFile(diagnostic, LinkOption.NOFOLLOW_LINKS) && Files.size(diagnostic) <= 8192) {
            try {
                var value = mapper.readTree(Files.readAllBytes(diagnostic));
                for (String key : List.of("stdoutBytes", "stderrBytes", "cpuTimeMs", "maxRssBytes")) {
                    var count = value.path(key);
                    if (count.isIntegralNumber() && count.canConvertToLong() && count.asLong() >= 0)
                        safe.put(key, count.asLong());
                }
                for (String key : List.of("stdoutSha256", "stderrSha256")) {
                    String hash = value.path(key).asText("");
                    if (hash.matches("[0-9a-f]{64}")) safe.put(key, hash);
                }
                if (EXCEPTIONS.contains(value.path("exceptionType").asText("")))
                    safe.put("exceptionType", value.path("exceptionType").asText());
                String code = value.path("diagnosticCode").asText("");
                if (ExecutionFailureClassifier.known(code)) safe.put("diagnosticCode", code);
                var stack = new ArrayList<Map<String,Object>>();
                for (var frame : value.path("stack")) {
                    if (stack.size() >= 16) break;
                    if (scriptFile.equals(frame.path("file").asText()) && frame.path("line").canConvertToInt()
                            && frame.path("line").asInt() > 0 && frame.path("line").asInt() < 100_000_000)
                        stack.add(Map.of("file", scriptFile, "line", frame.path("line").asInt()));
                }
                if (!stack.isEmpty()) safe.put("stack", stack);
                if (value.path("line").isIntegralNumber() && value.path("line").asLong() > 0
                        && value.path("line").asLong() < 100_000_000)
                    safe.put("line", value.path("line").asLong());
            } catch (RuntimeException | IOException malformed) {
                // Missing diagnostics never authorize persisting arbitrary process output.
            }
        }
        safe.put("schemaVersion", "runtime_os.python_diagnostic.v1");
        if (!safe.containsKey("diagnosticCode") && safe.get("exceptionType") instanceof String exception) {
            String code = ExecutionFailureClassifier.code(exception);
            if (ExecutionFailureClassifier.known(code)) safe.put("diagnosticCode", code);
        }
        replace(diagnostic, mapper.writeValueAsString(safe));
        replace(directory.resolve("stdout.txt"), "[Runtime: raw stdout omitted; observedBytes="
            + safe.getOrDefault("stdoutBytes", "unavailable") + "]\n");
        String stderr = "[Runtime: raw stderr omitted; observedBytes=" + safe.getOrDefault("stderrBytes", "unavailable") + "]\n";
        if (safe.containsKey("line")) stderr += "  File \"/workspace/scripts/"+scriptFile+"\", line " + safe.get("line") + "\n";
        if (safe.containsKey("exceptionType")) stderr += safe.get("exceptionType") + ":\n";
        if (safe.containsKey("diagnosticCode")) stderr += "Runtime diagnostic: " + safe.get("diagnosticCode")
            + "\nRepair guidance: " + ExecutionFailureClassifier.guidance((String)safe.get("diagnosticCode")) + "\n";
        replace(directory.resolve("stderr.txt"), stderr);
    }

    private static void replace(Path path, String value) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Log artifact is a symlink");
        Files.writeString(path, value, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS);
    }
}
