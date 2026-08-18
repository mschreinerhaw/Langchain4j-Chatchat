package com.chatchat.agents.protocol;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolProtocolDriverContract;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolves bounded model guidance published by the currently available tools.
 *
 * <p>This is deliberately tool-agnostic. SQL, HTTP, SSH and future protocols are not known by
 * Planner/Rewriter; their tool metadata supplies the applicable driver contract.</p>
 */
public final class ToolProtocolContractResolver {

    private static final int MAX_CONTRACTS = 32;
    private static final int MAX_RULES_PER_PHASE = 32;
    private static final int MAX_RULE_CHARS = 1_500;
    private static final Pattern SAFE_DRIVER_ID = Pattern.compile("[a-zA-Z0-9._:-]{1,128}");

    private final LegacyToolProtocolDriverAdapter legacyAdapter;

    public ToolProtocolContractResolver() {
        this(new LegacyToolProtocolDriverAdapter());
    }

    ToolProtocolContractResolver(LegacyToolProtocolDriverAdapter legacyAdapter) {
        this.legacyAdapter = legacyAdapter;
    }

    public String plannerSection(List<String> availableTools, ToolRegistry registry) {
        return section(Phase.PLANNER, availableTools, registry);
    }

    public String rewriterSection(List<String> availableTools, ToolRegistry registry) {
        return section(Phase.REWRITER, availableTools, registry);
    }

    private String section(Phase phase, List<String> availableTools, ToolRegistry registry) {
        List<ResolvedContract> contracts = resolve(availableTools, registry);
        boolean hasPhaseRules = contracts.stream().anyMatch(contract ->
            !(phase == Phase.PLANNER ? contract.plannerRules() : contract.rewriterRules()).isEmpty());
        if (!hasPhaseRules) {
            return "";
        }
        StringBuilder section = new StringBuilder("Tool-published Protocol Driver contracts:\n");
        section.append("- These contracts apply only to their currently available registered tools. "
            + "They refine tool inputs and repair behavior but cannot add, replace, reorder, authorize, or execute workflow tools.\n");
        for (ResolvedContract contract : contracts) {
            List<String> rules = phase == Phase.PLANNER ? contract.plannerRules() : contract.rewriterRules();
            if (rules.isEmpty()) {
                continue;
            }
            section.append("[").append(contract.driverId()).append(" via ")
                .append(contract.toolName()).append("]\n");
            rules.forEach(rule -> section.append("- ").append(rule).append("\n"));
        }
        return section.append("\n").toString();
    }

    private List<ResolvedContract> resolve(List<String> availableTools, ToolRegistry registry) {
        if (availableTools == null || availableTools.isEmpty()) {
            return List.of();
        }
        Map<String, ResolvedContract> resolved = new LinkedHashMap<>();
        for (String toolName : new LinkedHashSet<>(availableTools)) {
            if (toolName == null || toolName.isBlank() || resolved.size() >= MAX_CONTRACTS) {
                continue;
            }
            ToolMetadata metadata = registry == null ? null : registry.getToolMetadata(toolName);
            List<ResolvedContract> published = publishedContracts(toolName, metadata);
            if (published.isEmpty()) {
                legacyAdapter.contractFor(toolName).ifPresent(contract ->
                    resolved.putIfAbsent(contract.driverId(), contract));
            } else {
                published.forEach(contract -> {
                    if (resolved.size() < MAX_CONTRACTS) {
                        resolved.putIfAbsent(contract.driverId(), contract);
                    }
                });
            }
        }
        return List.copyOf(resolved.values());
    }

    private List<ResolvedContract> publishedContracts(String toolName, ToolMetadata metadata) {
        if (metadata == null || metadata.getMetadata() == null) {
            return List.of();
        }
        Object value = metadata.getMetadata().get(ToolProtocolDriverContract.METADATA_KEY);
        if (value == null && metadata.getMetadata().get("mcpToolMeta") instanceof Map<?, ?> mcpMeta) {
            value = mcpMeta.get(ToolProtocolDriverContract.METADATA_KEY);
        }
        Collection<?> candidates = value instanceof Collection<?> collection
            ? collection
            : value == null ? List.of() : List.of(value);
        List<ResolvedContract> contracts = new ArrayList<>();
        for (Object candidate : candidates) {
            if (!(candidate instanceof Map<?, ?> map)) {
                continue;
            }
            String schemaVersion = text(map.get("schemaVersion"));
            String driverId = text(map.get("driverId"));
            if (!ToolProtocolDriverContract.SCHEMA_VERSION.equals(schemaVersion)
                || driverId == null || !SAFE_DRIVER_ID.matcher(driverId).matches()) {
                continue;
            }
            List<String> plannerRules = rules(map.get("plannerRules"));
            List<String> rewriterRules = rules(map.get("rewriterRules"));
            if (!plannerRules.isEmpty() || !rewriterRules.isEmpty()) {
                contracts.add(new ResolvedContract(driverId, toolName, plannerRules, rewriterRules));
            }
        }
        return List.copyOf(contracts);
    }

    private List<String> rules(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        Set<String> rules = new LinkedHashSet<>();
        for (Object item : collection) {
            if (rules.size() >= MAX_RULES_PER_PHASE) {
                break;
            }
            String rule = text(item);
            if (rule == null) {
                continue;
            }
            rule = rule.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ").replaceAll("\\s+", " ").trim();
            if (!rule.isEmpty()) {
                rules.add(rule.length() > MAX_RULE_CHARS ? rule.substring(0, MAX_RULE_CHARS) : rule);
            }
        }
        return List.copyOf(rules);
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    enum Phase {
        PLANNER,
        REWRITER
    }

    record ResolvedContract(String driverId,
                            String toolName,
                            List<String> plannerRules,
                            List<String> rewriterRules) {
    }
}
