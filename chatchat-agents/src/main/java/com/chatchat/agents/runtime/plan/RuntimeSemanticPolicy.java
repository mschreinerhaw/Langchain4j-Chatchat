package com.chatchat.agents.runtime.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable, database-published business vocabulary for Runtime OS. */
public final class RuntimeSemanticPolicy {
    private static final RuntimeSemanticPolicy EMPTY = new RuntimeSemanticPolicy(
        List.of(), Map.of(), List.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of(),
        List.of(), List.of(), List.of());

    private final List<ToolRule> toolRules;
    private final Map<String, String> dialectAliases;
    private final List<DialectRule> dialectContains;
    private final Map<String, String> templateDialectPrefixes;
    private final Map<String, String> environmentAliases;
    private final List<Pattern> explicitEnvironmentPatterns;
    private final Map<String, String> discoveryRoles;
    private final Map<String, String> discoveryTargetKinds;
    private final List<String> tableScopedTemplateSuffixes;
    private final List<String> toolNamePrefixes;
    private final Set<String> protocolStopWords;

    private RuntimeSemanticPolicy(List<ToolRule> toolRules,
                                  Map<String, String> dialectAliases,
                                  List<DialectRule> dialectContains,
                                  Map<String, String> templateDialectPrefixes,
                                  Map<String, String> environmentAliases,
                                  List<Pattern> explicitEnvironmentPatterns,
                                  Map<String, String> discoveryRoles,
                                  Map<String, String> discoveryTargetKinds,
                                  List<String> tableScopedTemplateSuffixes,
                                  List<String> toolNamePrefixes,
                                  List<String> protocolStopWords) {
        this.toolRules = List.copyOf(toolRules);
        this.dialectAliases = Map.copyOf(dialectAliases);
        this.dialectContains = List.copyOf(dialectContains);
        this.templateDialectPrefixes = Map.copyOf(templateDialectPrefixes);
        this.environmentAliases = Map.copyOf(environmentAliases);
        this.explicitEnvironmentPatterns = List.copyOf(explicitEnvironmentPatterns);
        this.discoveryRoles = Map.copyOf(discoveryRoles);
        this.discoveryTargetKinds = Map.copyOf(discoveryTargetKinds);
        this.tableScopedTemplateSuffixes = List.copyOf(tableScopedTemplateSuffixes);
        this.toolNamePrefixes = List.copyOf(toolNamePrefixes);
        this.protocolStopWords = Set.copyOf(protocolStopWords);
    }

    public static RuntimeSemanticPolicy empty() { return EMPTY; }

    public static RuntimeSemanticPolicy from(Map<String, Object> values) {
        if (values == null || values.isEmpty()) return EMPTY;
        List<ToolRule> rules = new ArrayList<>();
        if (values.get("toolRules") instanceof List<?> configured) {
            for (Object item : configured) {
                if (!(item instanceof Map<?, ?> row)) throw new IllegalArgumentException("Invalid tool rule");
                String role = required(row, "role");
                String mode = required(row, "mode");
                String value = required(row, "value").toLowerCase(Locale.ROOT);
                if (!List.of("EXACT", "SUFFIX", "CONTAINS", "REGEX").contains(mode)) {
                    throw new IllegalArgumentException("Unsupported tool rule mode: " + mode);
                }
                String exclude = row.get("exclude") instanceof String text
                    ? text.trim().toLowerCase(Locale.ROOT) : "";
                rules.add(new ToolRule(role, mode, value, exclude,
                    "REGEX".equals(mode) ? Pattern.compile(value) : null));
            }
        }
        List<Pattern> environmentPatterns = new ArrayList<>();
        if (values.get("explicitEnvironmentPatterns") instanceof List<?> patterns) {
            for (Object pattern : patterns) {
                if (!(pattern instanceof String text) || text.isBlank()) {
                    throw new IllegalArgumentException("Invalid environment pattern");
                }
                Pattern compiled = Pattern.compile(text, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                if (compiled.matcher("").groupCount() < 1) {
                    throw new IllegalArgumentException("Environment pattern requires a capture group");
                }
                environmentPatterns.add(compiled);
            }
        }
        List<DialectRule> contains = new ArrayList<>();
        if (values.get("dialectContains") instanceof List<?> rawContains) {
            for (Object item : rawContains) {
                if (!(item instanceof Map<?, ?> row)) throw new IllegalArgumentException("Invalid dialect rule");
                contains.add(new DialectRule(required(row, "needle").toLowerCase(Locale.ROOT),
                    required(row, "canonical")));
            }
        }
        return new RuntimeSemanticPolicy(rules,
            strings(values, "dialectAliases", true), contains, strings(values, "templateDialectPrefixes", true),
            strings(values, "environmentAliases", true), environmentPatterns,
            strings(values, "discoveryRoles", false), strings(values, "discoveryTargetKinds", false),
            list(values, "tableScopedTemplateSuffixes"), lowercaseList(values, "toolNamePrefixes"),
            lowercaseList(values, "protocolStopWords"));
    }

    public boolean hasRole(String toolName, String role) {
        if (toolName == null || role == null) return false;
        String name = toolName.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return toolRules.stream().anyMatch(rule -> rule.role.equals(role) && rule.matches(name));
    }

    public String discoveryTargetKind(String executorTool) {
        for (Map.Entry<String, String> entry : discoveryTargetKinds.entrySet()) {
            if (hasRole(executorTool, entry.getKey())) return entry.getValue();
        }
        return discoveryTargetKinds.get("*");
    }

    public String relatedDiscoveryTool(String executorTool, List<String> allowedTools) {
        if (executorTool == null || allowedTools == null) return null;
        for (Map.Entry<String, String> relation : discoveryRoles.entrySet()) {
            if (!hasRole(executorTool, relation.getKey())) continue;
            for (String candidate : allowedTools) {
                if (candidate != null && !candidate.equalsIgnoreCase(executorTool)
                    && hasRole(candidate, relation.getValue())) return candidate;
            }
        }
        return null;
    }

    public String normalizeDialect(String value) {
        if (value == null || value.isBlank()) return null;
        String key = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        String exact = dialectAliases.get(key.toUpperCase(Locale.ROOT));
        if (exact != null) return exact;
        return dialectContains.stream().filter(rule -> key.contains(rule.needle()))
            .map(DialectRule::canonical).findFirst().orElse(key);
    }

    public String dialectFromTemplateId(String templateId) {
        if (templateId == null || templateId.isBlank()) return null;
        String normalized = templateId.trim().toUpperCase(Locale.ROOT);
        return templateDialectPrefixes.entrySet().stream()
            .filter(entry -> normalized.startsWith(entry.getKey()))
            .max(Map.Entry.comparingByKey((left, right) -> Integer.compare(left.length(), right.length())))
            .map(Map.Entry::getValue).orElse(null);
    }

    public boolean isTableScopedTemplate(String templateId) {
        if (templateId == null || templateId.isBlank()) return false;
        String normalized = templateId.trim().toUpperCase(Locale.ROOT);
        return tableScopedTemplateSuffixes.stream().anyMatch(normalized::endsWith);
    }

    public String canonicalToolName(String toolName) {
        if (toolName == null) return "";
        String name = toolName.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        boolean changed;
        do {
            changed = false;
            for (String prefix : toolNamePrefixes) {
                if (name.startsWith(prefix)) {
                    name = name.substring(prefix.length());
                    changed = true;
                    break;
                }
            }
        } while (changed);
        return name;
    }

    public Set<String> protocolTokens(String toolName) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : canonicalToolName(toolName).split("_")) {
            if (!token.isBlank() && !protocolStopWords.contains(token)) tokens.add(token);
        }
        return tokens;
    }

    public String canonicalEnvironment(String value) {
        if (value == null || value.isBlank()) return null;
        return environmentAliases.get(value.trim().toUpperCase(Locale.ROOT));
    }

    public String explicitEnvironment(String query) {
        if (query == null || query.isBlank()) return null;
        for (Pattern pattern : explicitEnvironmentPatterns) {
            Matcher matcher = pattern.matcher(query);
            if (matcher.find()) {
                String canonical = canonicalEnvironment(matcher.group(1));
                if (canonical != null) return canonical;
            }
        }
        return null;
    }

    private static Map<String, String> strings(Map<String, Object> values, String field, boolean normalizeKeys) {
        Object raw = values.get(field);
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (!(key instanceof String name) || name.isBlank()
                || !(value instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("Invalid " + field + " entry");
            }
            result.put(normalizeKeys ? name.toUpperCase(Locale.ROOT) : name, text.trim());
        });
        return result;
    }

    private static List<String> list(Map<String, Object> values, String field) {
        if (!(values.get(field) instanceof List<?> raw)) return List.of();
        if (raw.stream().anyMatch(value -> !(value instanceof String text) || text.isBlank())) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return raw.stream().map(String::valueOf).map(value -> value.toUpperCase(Locale.ROOT)).distinct().toList();
    }

    private static List<String> lowercaseList(Map<String, Object> values, String field) {
        if (!(values.get(field) instanceof List<?> raw)) return List.of();
        if (raw.stream().anyMatch(value -> !(value instanceof String text) || text.isBlank())) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return raw.stream().map(String::valueOf).map(value -> value.toLowerCase(Locale.ROOT)).distinct().toList();
    }

    private static String required(Map<?, ?> row, String key) {
        Object value = row.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return text.trim();
    }

    private record ToolRule(String role, String mode, String value, String exclude, Pattern compiled) {
        private boolean matches(String name) {
            if (!exclude.isEmpty() && name.contains(exclude)) return false;
            return switch (mode) {
                case "EXACT" -> name.equals(value);
                case "SUFFIX" -> name.endsWith(value);
                case "CONTAINS" -> name.contains(value);
                case "REGEX" -> compiled.matcher(name).matches();
                default -> false;
            };
        }
    }

    private record DialectRule(String needle, String canonical) { }
}
