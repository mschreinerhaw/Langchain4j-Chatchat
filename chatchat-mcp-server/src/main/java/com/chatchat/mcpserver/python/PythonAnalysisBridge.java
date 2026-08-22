package com.chatchat.mcpserver.python;

import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class PythonAnalysisBridge {
    private final PythonTemplateAssetRepository templates;
    private final ObjectProvider<PythonCapabilityService> serviceProvider;
    private final PythonTemplateArgumentResolver argumentResolver;
    private final PythonDataFileService dataFiles;
    private final ObjectMapper objectMapper;

    public Result run(Map<String, Object> rawArguments) {
        Map<String, Object> arguments = rawArguments == null ? Map.of() : rawArguments;
        String tenantId = tenantId(arguments);
        String explicitTemplateId = text(arguments.get("templateId"));
        if (explicitTemplateId == null) {
            return candidates(tenantId, arguments);
        }
        String ownerId = ownerId(arguments);
        Selection selection = selectTemplate(tenantId, arguments);
        if (selection.result() != null) return selection.result();
        PythonTemplate template = selection.template();
        FileBinding binding = bindFiles(template, tenantId, ownerId, arguments);
        if (binding.result() != null) return binding.result();
        Map<String, Object> body = base("READY_FOR_EXECUTION", false);
        body.put("requiresModelReview", false);
        body.put("executionTool", PythonMcpToolPublisher.TEMPLATE_EXECUTE_TOOL);
        body.put("template", templateChoice(new ScoredTemplate(template, 0D)));
        body.put("executionArguments", Map.of(
            "templateId", template.getId(),
            "parameters", binding.parameters()));
        return new Result(Map.copyOf(body), false);
    }

    public Result execute(Map<String, Object> rawArguments) {
        Map<String, Object> arguments = rawArguments == null ? Map.of() : rawArguments;
        String tenantId = tenantId(arguments);
        String ownerId = ownerId(arguments);
        String templateId = text(arguments.get("templateId"));
        if (templateId == null) throw new IllegalArgumentException("templateId is required");
        PythonTemplate template = templates.findByIdAndTenantId(templateId, tenantId)
            .filter(value -> "PUBLISHED".equals(value.getStatus()))
            .orElseThrow(() -> new IllegalArgumentException(
                "Python template not found in the current tenant or disabled"));
        FileBinding binding = bindFiles(template, tenantId, ownerId, arguments);
        if (binding.result() != null) return binding.result();
        Map<String, Object> parameters = argumentResolver.resolve(
            template.getInputSchemaJson(), binding.parameters());
        PythonExecution execution = serviceProvider.getObject()
                .executeTemplateForUser(template.getId(), tenantId, ownerId, parameters);
        Map<String, Object> body = base("EXECUTED", false);
        body.put("templateId", template.getId());
        body.put("templateName", nullable(template.getTemplateName()));
        body.put("scriptFileName", nullable(template.getScriptFileName()));
        body.put("assetId", template.getAssetId());
        body.put("environmentId", template.getEnvironmentId());
        body.put("executionId", execution.getId());
        body.put("status", execution.getStatus());
        body.put("stdout", nullable(execution.getStdout()));
        body.put("stderr", nullable(execution.getStderr()));
        body.put("exitCode", execution.getExitCode());
        body.put("durationMs", execution.getDurationMs());
        boolean failed = execution.getExitCode() == null || execution.getExitCode() != 0;
        body.put("success", !failed);
        return new Result(body, failed);
    }

    private Result candidates(String tenantId, Map<String, Object> arguments) {
        String assetId = text(arguments.get("assetId"));
        String environmentId = text(arguments.get("environmentId"));
        String script = firstText(text(arguments.get("script")), text(arguments.get("scriptFileName")));
        String query = firstText(text(arguments.get("query")), text(arguments.get("intent")));
        List<ScoredTemplate> ranked = templates.findByTenantIdAndStatus(tenantId, "PUBLISHED").stream()
            .filter(value -> assetId == null || assetId.equals(value.getAssetId()))
            .filter(value -> environmentId == null || environmentId.equals(value.getEnvironmentId()))
            .map(value -> new ScoredTemplate(value, score(value, script, query)))
            .filter(value -> script == null && query == null || value.score() > 0)
            .sorted(Comparator.comparingDouble(ScoredTemplate::score).reversed()
                .thenComparing(value -> nullable(value.template().getTemplateName())))
            .limit(20)
            .toList();
        Map<String, Object> body = base(ranked.isEmpty() ? "NOT_FOUND" : "CANDIDATES_FOUND", false);
        body.put("requiresModelReview", !ranked.isEmpty());
        body.put("candidateCount", ranked.size());
        body.put("candidates", ranked.stream().map(this::templateChoice).toList());
        body.put("executionTool", PythonMcpToolPublisher.TEMPLATE_EXECUTE_TOOL);
        body.put("selectionPolicy", "Review every candidate; invoke the Runtime executor once per accepted template");
        return new Result(Map.copyOf(body), ranked.isEmpty());
    }

    private Selection selectTemplate(String tenantId, Map<String, Object> arguments) {
        String templateId = text(arguments.get("templateId"));
        String assetId = text(arguments.get("assetId"));
        String environmentId = text(arguments.get("environmentId"));
        String script = firstText(text(arguments.get("script")), text(arguments.get("scriptFileName")));
        String query = firstText(text(arguments.get("query")), text(arguments.get("intent")));
        List<PythonTemplate> candidates = templates.findByTenantIdAndStatus(tenantId, "PUBLISHED").stream()
                .filter(value -> templateId == null || templateId.equals(value.getId()))
                .filter(value -> assetId == null || assetId.equals(value.getAssetId()))
                .filter(value -> environmentId == null || environmentId.equals(value.getEnvironmentId()))
                .toList();
        if (script != null) {
            List<PythonTemplate> exact = candidates.stream().filter(value ->
                    equalsIgnoreCase(script, value.getScriptFileName()) || equalsIgnoreCase(script, value.getTemplateName())).toList();
            if (!exact.isEmpty()) candidates = exact;
        }
        String requestedScript = script;
        String requestedQuery = query;
        List<ScoredTemplate> ranked = candidates.stream()
                .map(value -> new ScoredTemplate(value, score(value, requestedScript, requestedQuery)))
                .filter(value -> templateId != null || requestedScript == null && requestedQuery == null || value.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredTemplate::score).reversed()
                        .thenComparing(value -> nullable(value.template().getTemplateName())))
                .toList();
        if (ranked.isEmpty()) return new Selection(null, notFound("没有找到匹配的已发布 Python 脚本模板"));
        boolean uniqueTop = ranked.size() == 1 || ranked.get(0).score() > ranked.get(1).score();
        if (templateId == null && !uniqueTop) {
            List<Map<String, Object>> choices = ranked.stream().limit(10).map(this::templateChoice).toList();
            return new Selection(null, clarification("template", "存在多个匹配的 Python 脚本或执行环境，请选择一个", choices));
        }
        return new Selection(ranked.get(0).template(), null);
    }

    private FileBinding bindFiles(PythonTemplate template, String tenantId, String ownerId,
                                  Map<String, Object> arguments) {
        Map<String, Object> parameters = new LinkedHashMap<>(map(arguments.get("parameters")));
        Map<String, Object> schema = argumentResolver.schema(template.getInputSchemaJson());
        Map<String, Object> properties = map(schema.get("properties"));
        List<String> fileFields = properties.entrySet().stream()
                .filter(entry -> "FILE".equalsIgnoreCase(String.valueOf(map(entry.getValue()).get("type"))))
                .map(Map.Entry::getKey).toList();
        String genericFile = firstText(text(arguments.get("file")), text(arguments.get("fileName")));
        if (genericFile != null && fileFields.size() > 1
                && fileFields.stream().noneMatch(parameters::containsKey)) {
            List<Map<String, Object>> choices = fileFields.stream()
                    .map(field -> Map.<String, Object>of("parameter", field)).toList();
            return new FileBinding(Map.of(), clarification("file_parameter",
                    "模板包含多个 FILE 参数，请明确文件绑定到哪个参数", choices));
        }
        if (genericFile != null && fileFields.size() == 1 && !parameters.containsKey(fileFields.get(0)))
            parameters.put(fileFields.get(0), genericFile);
        String query = text(arguments.get("query"));
        if (genericFile == null && fileFields.size() == 1 && !parameters.containsKey(fileFields.get(0)) && query != null) {
            FileResolution resolution = resolveFile(query, tenantId, ownerId, false);
            if (resolution.result() != null) return new FileBinding(Map.of(), resolution.result());
            if (resolution.fileId() != null) parameters.put(fileFields.get(0), resolution.fileId());
        }
        for (String field : fileFields) {
            Object value = parameters.get(field);
            if (value == null) continue;
            FileResolution resolution = resolveFile(String.valueOf(value), tenantId, ownerId, true);
            if (resolution.result() != null) return new FileBinding(Map.of(), resolution.result());
            if (resolution.fileId() != null) parameters.put(field, resolution.fileId());
        }
        return new FileBinding(Map.copyOf(parameters), null);
    }

    private FileResolution resolveFile(String reference, String tenantId, String ownerId, boolean explicit) {
        if (reference.startsWith("/data/input/")) return new FileResolution(reference, null);
        List<PythonDataFileService.DataFileView> files = dataFiles.discover(tenantId, ownerId, reference, 20);
        List<PythonDataFileService.DataFileView> exact = files.stream()
                .filter(file -> file.fileName().equalsIgnoreCase(reference)).toList();
        PythonDataFileService.DataFileView selected = exact.size() == 1 ? exact.get(0)
                : exact.isEmpty() && files.size() == 1 ? files.get(0) : null;
        if (selected != null) return new FileResolution(selected.fileId(), null);
        if (files.size() > 1) {
            List<Map<String, Object>> choices = files.stream().map(file -> Map.<String, Object>of(
                    "fileId", file.fileId(), "fileName", file.fileName(), "fileSize", file.fileSize())).toList();
            return new FileResolution(null, clarification("file", "存在多个匹配的数据文件，请选择一个", choices));
        }
        return new FileResolution(explicit ? reference : null, null);
    }

    private double score(PythonTemplate template, String script, String query) {
        double score = 0;
        if (script != null) {
            if (equalsIgnoreCase(script, template.getScriptFileName())) score += 1000;
            if (equalsIgnoreCase(script, template.getTemplateName())) score += 900;
            if (contains(template.getScriptFileName(), script)) score += 100;
            if (contains(template.getTemplateName(), script)) score += 80;
        }
        if (query != null) {
            if (contains(query, template.getScriptFileName())) score += 500;
            if (contains(query, template.getTemplateName())) score += 200;
            for (String term : semanticTerms(query)) {
                score += contains(template.getTemplateName(), term) ? 10 : 0;
                score += contains(template.getScenario(), term) ? 6 : 0;
                score += contains(template.getDescription(), term) ? 4 : 0;
                score += contains(template.getKeywords(), term) ? 3 : 0;
                score += contains(template.getDomain(), term) ? 2 : 0;
            }
        }
        return score;
    }

    /**
     * Produces language-neutral lookup terms without relying on a business vocabulary.
     * Latin/numeric runs remain whole while consecutive Han text is represented by
     * overlapping bigrams, so natural phrases can match differently worded metadata.
     */
    private Set<String> semanticTerms(String value) {
        Set<String> terms = new LinkedHashSet<>();
        if (value == null || value.isBlank()) return terms;
        StringBuilder latin = new StringBuilder();
        StringBuilder han = new StringBuilder();
        value.toLowerCase(Locale.ROOT).codePoints().forEach(codePoint -> {
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                flushLatinTerm(latin, terms);
                han.appendCodePoint(codePoint);
            } else {
                flushHanTerms(han, terms);
                if (Character.isLetterOrDigit(codePoint)
                    || codePoint == '_' || codePoint == '-' || codePoint == '.') {
                    latin.appendCodePoint(codePoint);
                } else {
                    flushLatinTerm(latin, terms);
                }
            }
        });
        flushLatinTerm(latin, terms);
        flushHanTerms(han, terms);
        return terms;
    }

    private void flushLatinTerm(StringBuilder value, Set<String> terms) {
        if (value.length() >= 2) terms.add(value.toString());
        value.setLength(0);
    }

    private void flushHanTerms(StringBuilder value, Set<String> terms) {
        int[] codePoints = value.codePoints().toArray();
        if (codePoints.length == 1) terms.add(new String(codePoints, 0, 1));
        for (int index = 0; index + 1 < codePoints.length; index++) {
            terms.add(new String(codePoints, index, 2));
        }
        value.setLength(0);
    }

    private Map<String, Object> templateChoice(ScoredTemplate candidate) {
        PythonTemplate template = candidate.template();
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("templateId", template.getId());
        choice.put("templateName", nullable(template.getTemplateName()));
        choice.put("scriptFileName", nullable(template.getScriptFileName()));
        choice.put("assetId", nullable(template.getAssetId()));
        choice.put("assetName", nullable(template.getAssetName()));
        choice.put("environmentId", nullable(template.getEnvironmentId()));
        choice.put("version", nullable(template.getVersion()));
        choice.put("parameterSchema", argumentResolver.schema(template.getInputSchemaJson()));
        choice.put("outputSchema", argumentResolver.schema(template.getOutputSchemaJson()));
        choice.put("score", candidate.score());
        return Map.copyOf(choice);
    }

    private Result clarification(String type, String message, List<Map<String, Object>> choices) {
        Map<String, Object> body = base("NEEDS_CLARIFICATION", true);
        body.put("clarificationType", type);
        body.put("message", message);
        body.put("choices", choices);
        return new Result(Map.copyOf(body), false);
    }

    private Result notFound(String message) {
        Map<String, Object> body = base("NOT_FOUND", false);
        body.put("message", message);
        return new Result(Map.copyOf(body), true);
    }

    private Map<String, Object> base(String status, boolean clarification) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "python_analysis_bridge_result.v1");
        body.put("success", "EXECUTED".equals(status) || "CANDIDATES_FOUND".equals(status)
            || "READY_FOR_EXECUTION".equals(status));
        body.put("status", status);
        body.put("requiresClarification", clarification);
        body.put("bridgeManaged", true);
        return body;
    }

    private String tenantId(Map<String, Object> arguments) {
        McpInvocationContext.Context invocation = McpInvocationContext.current();
        Map<String, Object> context = map(arguments.get("mcpContext"));
        Map<String, Object> tenant = map(context.get("tenant"));
        String value = firstText(invocation == null ? null : text(invocation.tenantId()),
                text(arguments.get("tenantId")), text(context.get("tenantId")), text(tenant.get("tenantId")));
        if (value == null) throw new IllegalArgumentException("tenantId is required for Python asset governance");
        return value;
    }

    private String ownerId(Map<String, Object> arguments) {
        McpInvocationContext.Context invocation = McpInvocationContext.current();
        Map<String, Object> context = map(arguments.get("mcpContext"));
        Map<String, Object> user = map(context.get("user"));
        String value = firstText(invocation == null ? null : text(invocation.username()),
                invocation == null ? null : text(invocation.userId()), text(arguments.get("username")),
                text(context.get("username")), text(user.get("username")), text(arguments.get("userId")),
                text(context.get("userId")), text(user.get("userId")));
        if (value == null || "anonymous".equalsIgnoreCase(value))
            throw new IllegalArgumentException("Authenticated user identity is required for Python data access");
        return value;
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private boolean contains(String value, String needle) {
        return value != null && needle != null && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private String firstText(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private String nullable(String value) {
        return value == null ? "" : value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? objectMapper.convertValue(value, new TypeReference<>() {
        }) : Map.of();
    }

    public record Result(Map<String, Object> body, boolean error) {
    }

    private record Selection(PythonTemplate template, Result result) {
    }

    private record FileBinding(Map<String, Object> parameters, Result result) {
    }

    private record FileResolution(String fileId, Result result) {
    }

    private record ScoredTemplate(PythonTemplate template, double score) {
    }
}
