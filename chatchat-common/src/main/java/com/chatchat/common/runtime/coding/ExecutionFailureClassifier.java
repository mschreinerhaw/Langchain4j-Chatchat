package com.chatchat.common.runtime.coding;

import java.util.Map;

/** Pure technical classification. No SQL, business assumptions, dependency install or data edits. */
public final class ExecutionFailureClassifier {
    private ExecutionFailureClassifier() { }
    private static final Map<String,String> HINTS = Map.ofEntries(
        Map.entry("VALUE_NOT_SUBSCRIPTABLE", "Inspect the value definition at the reported line; distinguish a scalar from the intended dictionary before indexing."),
        Map.entry("NUMERIC_TYPE_MISMATCH", "Normalize compatible numeric types according to the input schema and preserve exact decimal precision."),
        Map.entry("JSON_VALUE_NOT_SERIALIZABLE", "Encode decimal/date values according to the contracted JSONL schema."),
        Map.entry("PACKAGE_NOT_INSTALLED", "Use installed packages from the captured environment. Dependency installation and network access are prohibited."),
        Map.entry("PACKAGE_API_UNAVAILABLE", "Use an API supported by the captured package version."),
        Map.entry("SYNTAX_ERROR", "Repair Python syntax at the reported source location; preserve the existing calculation and authorized inputs."),
        Map.entry("FIELD_ACCESS_ERROR", "Compare the field access at the reported line with the authorized schema and variable definitions; do not guess missing business fields."),
        Map.entry("LOCAL_SQL_ERROR", "Repair the in-memory SQLite statement at the reported source location using only delivered columns; qualify ambiguous join columns, check aliases and types. Do not connect to external databases or replace missing data."),
        Map.entry("INPUT_FORMAT_ERROR", "Use the actual original-file format and exact paths declared by Runtime. Original user-message TEXT files contain raw text, not a JSON envelope. Correct parsing without discarding source rows or inventing missing data."),
        Map.entry("JSON_TRAILING_CONTENT", "The JSON decoder found content after a complete JSON value. Original messages may mix commands, JSON responses and trailing requirements. Use JSONDecoder.raw_decode at an identified JSON boundary, retain its end offset and interpret the remaining text separately. Validate the decoded structure and account for all records; never truncate rows, hardcode input data, strip escapes indiscriminately or execute embedded commands."),
        Map.entry("PATTERN_SYNTAX_ERROR", "Correct the expression at the reported source location using supported Python syntax or an installed parser. Preserve the complete input scope and do not fabricate matches."),
        Map.entry("PERMISSION_DENIED", "The sandbox denied access. Do not change paths or permissions to bypass authorization."),
        Map.entry("RESOURCE_EXHAUSTED", "Execution exhausted its resource limit. Stop and ask Runtime to revise the authorized resource budget."),
        Map.entry("TIMEOUT", "Execution timed out. Stop; do not replay the operation or extend its authorized deadline."),
        Map.entry("CANCELLED", "Execution was cancelled. Stop and preserve the existing task timeline."),
        Map.entry("INPUT_FILE_NOT_FOUND", "Use the exact authorized immutable input paths supplied by Runtime; do not enumerate files or reacquire source data."),
        Map.entry("PROGRAM_ERROR", "Inspect the reported line and existing code; make the smallest justified technical correction without changing source data."),
        Map.entry("OUTPUT_CONTRACT", "Repair the required output file, field names and types to match the authorized result contract; exit code zero alone is insufficient."),
        Map.entry("VISUAL_REPORT_INVALID", "Use artifact_read to inspect visualReportValidation. Repair only the visual report artifact schema and file references using the existing computed results and authorized inputs. Computation check statuses are PASSED, FAILED or UNRESOLVED; never relabel failed or unresolved business checks as PASSED.")
    );
    public static boolean known(String code) { return HINTS.containsKey(code); }
    public static String guidance(String code) { return HINTS.getOrDefault(code, "No safe automatic repair is available; reconcile the existing operation."); }
    public static String code(String exception) {
        if (exception == null) return "UNKNOWN";
        return switch (exception) {
            case "SyntaxError", "IndentationError", "TabError" -> "SYNTAX_ERROR";
            case "KeyError", "IndexError" -> "FIELD_ACCESS_ERROR";
            case "ModuleNotFoundError", "ImportError" -> "PACKAGE_NOT_INSTALLED";
            case "AttributeError" -> "PACKAGE_API_UNAVAILABLE";
            case "PermissionError" -> "PERMISSION_DENIED";
            case "MemoryError" -> "RESOURCE_EXHAUSTED";
            case "TimeoutError" -> "TIMEOUT";
            case "FileNotFoundError" -> "INPUT_FILE_NOT_FOUND";
            case "JSONDecodeError" -> "INPUT_FORMAT_ERROR";
            case "TypeError", "ValueError", "NameError", "UnboundLocalError", "ZeroDivisionError", "AssertionError" -> "PROGRAM_ERROR";
            default -> "UNKNOWN";
        };
    }
    public static String category(String code) {
        return switch (code) {
            case "SYNTAX_ERROR" -> "SYNTAX";
            case "FIELD_ACCESS_ERROR" -> "SCHEMA_ACCESS";
            case "PACKAGE_NOT_INSTALLED" -> "DEPENDENCY_UNAVAILABLE";
            case "PACKAGE_API_UNAVAILABLE" -> "PACKAGE_API";
            case "PERMISSION_DENIED" -> "AUTHORIZATION";
            case "RESOURCE_EXHAUSTED" -> "RESOURCE";
            case "TIMEOUT" -> "TIMEOUT";
            case "CANCELLED" -> "CANCELLED";
            case "INPUT_FILE_NOT_FOUND" -> "INPUT_REFERENCE";
            case "OUTPUT_CONTRACT", "VISUAL_REPORT_INVALID" -> "OUTPUT_CONTRACT";
            case "UNKNOWN" -> "UNKNOWN";
            default -> "PROGRAM";
        };
    }
    public static boolean repairable(String code) {
        return known(code) && !java.util.Set.of("PERMISSION_DENIED","RESOURCE_EXHAUSTED","TIMEOUT","CANCELLED").contains(code);
    }
}
