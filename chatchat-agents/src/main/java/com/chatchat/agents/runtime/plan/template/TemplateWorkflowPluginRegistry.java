package com.chatchat.agents.runtime.plan.template;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/** Deterministic runtime registry. Additional plugins can be injected or published through Java SPI. */
public final class TemplateWorkflowPluginRegistry {

    private final List<TemplateWorkflowPlugin> plugins;

    public TemplateWorkflowPluginRegistry(Collection<? extends TemplateWorkflowPlugin> plugins) {
        Map<String, TemplateWorkflowPlugin> byId = new LinkedHashMap<>();
        if (plugins != null) {
            plugins.stream().filter(java.util.Objects::nonNull).forEach(plugin -> {
                TemplateWorkflowPlugin previous = byId.putIfAbsent(plugin.id(), plugin);
                if (previous != null && previous.getClass() != plugin.getClass()) {
                    throw new IllegalArgumentException("Duplicate template workflow plugin id: " + plugin.id());
                }
            });
        }
        this.plugins = byId.values().stream()
            .sorted(Comparator.comparingInt(TemplateWorkflowPlugin::priority).reversed()
                .thenComparing(TemplateWorkflowPlugin::id))
            .toList();
    }

    public static TemplateWorkflowPluginRegistry load() {
        List<TemplateWorkflowPlugin> loaded = new ArrayList<>();
        ServiceLoader.load(TemplateWorkflowPlugin.class).forEach(loaded::add);
        if (loaded.isEmpty()) {
            loaded.addAll(builtIns());
        }
        return new TemplateWorkflowPluginRegistry(loaded);
    }

    public static List<TemplateWorkflowPlugin> builtIns() {
        return List.of(new ApiTemplateWorkflowPlugin(), new SqlTemplateWorkflowPlugin(),
            new PythonTemplateWorkflowPlugin(), new SshTemplateWorkflowPlugin(),
            new HttpTemplateWorkflowPlugin(), new LegacyRoleOnlyTemplateWorkflowPlugin());
    }

    public Optional<TemplateWorkflowPlugin> resolve(TemplateWorkflowTool executionTool) {
        List<TemplateWorkflowPlugin> matches = plugins.stream()
            .filter(plugin -> plugin.isExecution(executionTool)).toList();
        if (matches.size() > 1 && matches.get(0).priority() == matches.get(1).priority()) {
            throw new IllegalStateException("Ambiguous template workflow plugins for protocol "
                + executionTool.protocolFamily() + ": " + matches.get(0).id() + ", " + matches.get(1).id());
        }
        return matches.stream().findFirst();
    }

    public List<TemplateWorkflowPlugin> plugins() {
        return plugins;
    }
}
