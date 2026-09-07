package com.chatchat.agents.orchestration.analysis.report;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Deterministic row binding plus advisory explicit prose-value checks. General semantics remain uncertified. */
public final class ReportFactBindingAudit {
    public record Result(String markdown, List<Map<String, Object>> checks) { }

    public Result audit(String markdown, VerifiedReportDataCatalog catalog) {
        if (markdown == null) return new Result("", List.of());
        String[] lines = markdown.split("\n", -1);
        List<Map<String, Object>> checks = new ArrayList<>();
        List<ReturnedReportDataset> sources = catalog.datasetPromptView().stream()
            .map(view -> catalog.dataset(String.valueOf(view.get("sourceRef")))).filter(Objects::nonNull).toList();
        List<String> headers = List.of();
        String fence = "";
        Set<Integer> warnings = new HashSet<>();
        Set<Integer> proseWarnings = new HashSet<>();
        for (int i = 0; i < lines.length && checks.size() < 120; i++) {
            String line = lines[i].strip();
            var marker = Pattern.compile("^(`{3,}|~{3,})(.*)$").matcher(line);
            if (marker.matches()) {
                if (fence.isEmpty()) fence = marker.group(1);
                else if (marker.group(1).charAt(0) == fence.charAt(0)
                    && marker.group(1).length() >= fence.length() && marker.group(2).isBlank()) fence = "";
                headers = List.of(); continue;
            }
            if (!fence.isEmpty()) continue;
            if (!line.startsWith("|") || !line.endsWith("|") || line.contains("\\|")) {
                headers = List.of();
                int before = checks.size();
                if (!line.startsWith("#")) inspectProse(line, i + 1, sources, checks);
                if (checks.subList(before, checks.size()).stream().anyMatch(check ->
                    "EXPLICIT_VALUE_CONFLICT".equals(check.get("status")))) proseWarnings.add(i);
                continue;
            }
            List<String> cells = cells(line);
            if (i + 1 < lines.length && separator(lines[i + 1], cells.size())) { headers = cells; i++; continue; }
            if (headers.isEmpty() || cells.size() != headers.size() || new HashSet<>(headers).size() != headers.size()) continue;
            List<String> candidates = new ArrayList<>();
            Set<String> headerSet = new HashSet<>(headers);
            boolean matched = false;
            for (var source : sources) {
                if (source.rows().isEmpty()) continue;
                // Every source field must be represented: omitted entity, time, unit or scope is ambiguous.
                if (source.rows().stream().anyMatch(row -> !row.keySet().equals(headerSet))) continue;
                candidates.add(source.reference());
                for (var row : source.rows()) {
                    boolean same = true;
                    for (int c = 0; c < headers.size(); c++) same &= equal(row.get(headers.get(c)), cells.get(c));
                    if (same) { matched = true; break; }
                }
            }
            if (candidates.isEmpty()) continue;
            String status = matched ? "BOUND_SOURCE_ROW" : "UNRESOLVED";
            // Absence from an incomplete projection is not a contradiction.
            if (!matched && candidates.size() == 1 && catalog.dataset(candidates.get(0)).complete()) {
                status = "ROW_BINDING_MISMATCH";
                // Place the qualification after the table, preserving Markdown table structure.
                int end = i;
                while (end + 1 < lines.length && lines[end + 1].strip().startsWith("|")) end++;
                warnings.add(end);
            }
            checks.add(Map.of("line", i + 1, "status", status, "sourceRefs", List.copyOf(candidates),
                "verificationScope", "EXACT_RETURNED_ROW_ONLY"));
        }
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            output.append(lines[i]);
            if (warnings.contains(i)) output.append("\n\n> 核验提示：上表存在无法与原始记录逐行对应的数据，请核对对象、指标、期间及数值后使用。\n");
            if (proseWarnings.contains(i)) output.append("\n\n> 核验提示：该段的明确数值陈述与可定位的原始记录存在差异，需结合口径复核。\n");
            if (i < lines.length - 1) output.append('\n');
        }
        return new Result(output.toString(), List.copyOf(checks));
    }

    private List<String> cells(String line) {
        if (line.length() < 2) return List.of();
        return Arrays.stream(line.substring(1, line.length() - 1).split("\\|", -1)).map(String::strip).toList();
    }
    /** Only explicit field/value assertions with all other row dimensions present are compared. */
    private void inspectProse(String line, int lineNumber, List<ReturnedReportDataset> sources,
                              List<Map<String, Object>> checks) {
        if (line.length() > 4000) return;
        for (String sentence : line.split("[。！？!?；;]")) {
            List<Map<String, Object>> pending = new ArrayList<>();
            Set<String> ambiguous = new HashSet<>();
            for (var source : sources) {
                Set<String> fields = new LinkedHashSet<>();
                source.rows().forEach(row -> row.forEach((key, value) -> { if (value instanceof Number) fields.add(key); }));
                for (String field : fields) {
                    if (checks.size() >= 120) return;
                    var assertion = Pattern.compile(Pattern.quote(field)
                        + "\\s*(?:为|是|[:：=])\\s*([-+]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?)(?![\\d.eE])").matcher(sentence);
                    if (!assertion.find()) continue;
                    String value = assertion.group(1);
                    List<Map<String, Object>> candidates = source.rows().stream().filter(row ->
                        row.containsKey(field) && row.entrySet().stream().filter(cell -> !cell.getKey().equals(field))
                            .allMatch(cell -> cell.getValue() != null && mentions(sentence, cell.getValue().toString()))) .toList();
                    // Never resolve a metric against a row lacking identifying dimensions.
                    if (candidates.size() > 1) ambiguous.add(field);
                    if (candidates.size() != 1 || candidates.get(0).size() < 2) continue;
                    boolean same = equal(candidates.get(0).get(field), value);
                    pending.add(Map.of("line", lineNumber, "status", same ? "EXPLICIT_VALUE_MATCH" : "EXPLICIT_VALUE_CONFLICT",
                        "sourceRefs", List.of(source.reference()), "field", field, "reportedValue", value,
                        "sourceValue", String.valueOf(candidates.get(0).get(field)),
                        "verificationScope", "ADVISORY_EXPLICIT_VALUE_ONLY"));
                }
            }
            for (var check : pending) {
                if (checks.size() >= 120) return;
                Object field = check.get("field");
                if (!ambiguous.contains(field) && pending.stream().filter(item -> item.get("field").equals(field)).count() == 1)
                    checks.add(check);
            }
        }
    }
    private boolean mentions(String sentence, String value) {
        if (value.isBlank()) return false;
        String start = value.substring(0, 1).matches("[A-Za-z0-9_]") ? "(?<![A-Za-z0-9_./-])" : "";
        String end = value.substring(value.length() - 1).matches("[A-Za-z0-9_]") ? "(?![A-Za-z0-9_./-])" : "";
        return Pattern.compile(start + Pattern.quote(value) + end).matcher(sentence).find();
    }
    private boolean separator(String line, int size) {
        String text = line.strip();
        if (!text.startsWith("|") || !text.endsWith("|")) return false;
        var cells = cells(text);
        return cells.size() == size && cells.stream().allMatch(cell -> cell.matches(":?-{3,}:?"));
    }
    private boolean equal(Object expected, String actual) {
        if (expected == null) return actual.isEmpty() || "null".equalsIgnoreCase(actual);
        if (expected instanceof Number) {
            try { return new BigDecimal(expected.toString()).compareTo(new BigDecimal(actual.replace(",", ""))) == 0; }
            catch (NumberFormatException invalid) { return false; }
        }
        // Strings stay exact, including leading-zero identifiers and unit-bearing values.
        return expected.toString().equals(actual);
    }
}
