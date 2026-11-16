package com.chatchat.deployment.release;

import java.util.*;

/** Transport-independent release contract. Runtime implementations are selected by adapters. */
public record ApplicationRelease(String apiVersion, String name, String version,
                                 List<Component> components) {
    public record Component(String name, String runtime, List<String> dependencies,
                            boolean critical, Map<String, Object> configuration) {}

    public ApplicationRelease {
        if (!"runtimeos.io/v1".equals(apiVersion)) throw new IllegalArgumentException("Unsupported release API version");
        requireName(name);
        if (version == null || version.isBlank() || version.length() > 128) throw new IllegalArgumentException("Release version required");
        if (components == null || components.isEmpty() || components.size() > 100) throw new IllegalArgumentException("1..100 components required");
        components = List.copyOf(components);
        Set<String> names = new HashSet<>();
        for (Component component : components) {
            requireName(component.name());
            if (!names.add(component.name())) throw new IllegalArgumentException("Duplicate component: " + component.name());
            if (component.runtime() == null || component.runtime().isBlank()) throw new IllegalArgumentException("Runtime adapter required");
        }
        Map<String, Set<String>> pending = new LinkedHashMap<>();
        for (Component component : components) {
            Set<String> dependencies = new HashSet<>(component.dependencies() == null ? List.of() : component.dependencies());
            if (!names.containsAll(dependencies)) throw new IllegalArgumentException("Unknown component dependency");
            pending.put(component.name(), dependencies);
        }
        Set<String> done = new HashSet<>();
        while (!pending.isEmpty()) {
            List<String> ready = pending.entrySet().stream().filter(e -> done.containsAll(e.getValue())).map(Map.Entry::getKey).toList();
            if (ready.isEmpty()) throw new IllegalArgumentException("Component dependency cycle");
            ready.forEach(pending::remove);
            done.addAll(ready);
        }
    }

    private static void requireName(String name) {
        if (name == null || !name.matches("[a-z][a-z0-9-]{0,62}")) throw new IllegalArgumentException("Invalid application/component name");
    }
}
