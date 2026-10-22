package com.chatchat.mcpserver.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** No user stream content crosses the managed-execution persistence boundary. */
final class PythonExecutionLogBoundary {
    private static final Map<String,String> HINTS = Map.of(
        "VALUE_NOT_SUBSCRIPTABLE", "The value at this line cannot be indexed. Inspect its definition and distinguish a scalar from the intended row/dictionary; repair the program without changing source data.",
        "NUMERIC_TYPE_MISMATCH", "Operands have incompatible types. Use the declared input schema to normalize compatible numeric types explicitly and preserve decimal precision.",
        "JSON_VALUE_NOT_SERIALIZABLE", "A result value is not JSON serializable. Encode exact decimal/date values explicitly according to the output schema before writing JSONL.",
        "PACKAGE_NOT_INSTALLED", "The requested package is absent. Use the captured installed-package versions and available libraries; do not install dependencies or access the network.",
        "PACKAGE_API_UNAVAILABLE", "The attribute/API is unavailable. Check the captured package version and use a supported API at the reported source line.");
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
        safe.put("policy", "METADATA_ONLY_V1");
        if (Files.isRegularFile(diagnostic, LinkOption.NOFOLLOW_LINKS) && Files.size(diagnostic) <= 8192) {
            try {
                var value = mapper.readTree(Files.readAllBytes(diagnostic));
                for (String key : List.of("stdoutBytes", "stderrBytes")) {
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
                if (HINTS.containsKey(code)) safe.put("diagnosticCode", code);
                if (value.path("line").isIntegralNumber() && value.path("line").asLong() > 0
                        && value.path("line").asLong() < 100_000_000)
                    safe.put("line", value.path("line").asLong());
            } catch (RuntimeException | IOException malformed) {
                // Missing diagnostics never authorize persisting arbitrary process output.
            }
        }
        replace(diagnostic, mapper.writeValueAsString(safe));
        replace(directory.resolve("stdout.txt"), "[Runtime: raw stdout omitted; observedBytes="
            + safe.getOrDefault("stdoutBytes", "unavailable") + "]\n");
        String stderr = "[Runtime: raw stderr omitted; observedBytes=" + safe.getOrDefault("stderrBytes", "unavailable") + "]\n";
        if (safe.containsKey("line")) stderr += "  File \"/workspace/main.py\", line " + safe.get("line") + "\n";
        if (safe.containsKey("exceptionType")) stderr += safe.get("exceptionType") + ":\n";
        if (safe.containsKey("diagnosticCode")) stderr += "Runtime diagnostic: " + safe.get("diagnosticCode")
            + "\nRepair guidance: " + HINTS.get(safe.get("diagnosticCode")) + "\n";
        replace(directory.resolve("stderr.txt"), stderr);
    }

    private static void replace(Path path, String value) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Log artifact is a symlink");
        Files.writeString(path, value, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS);
    }
}
