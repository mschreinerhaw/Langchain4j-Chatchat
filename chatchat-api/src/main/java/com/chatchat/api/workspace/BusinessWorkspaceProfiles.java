package com.chatchat.api.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BusinessWorkspaceProfiles {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FIELDS = Set.of("title", "welcome", "defaultAgentId",
        "recommendedQuestions", "recommendedSkillIds", "workGuides", "knowledgeCategories");

    private BusinessWorkspaceProfiles() {}

    // Only the authenticated tenant/organization's profile is projected into the caller manifest.
    static WorkspaceManifest.Page project(WorkspaceManifest.Page page, String tenant, String org) {
        var profiles = read(page.props().get("profiles"));
        Map<String, String> props = new LinkedHashMap<>(profiles.getOrDefault(tenant + "/", Map.of()));
        if (org != null) props.putAll(profiles.getOrDefault(tenant + "/" + org, Map.of()));
        return new WorkspaceManifest.Page(page.id(), page.menuCode(), page.componentKey(), Map.copyOf(props));
    }

    static boolean valid(Map<String, String> props) {
        if (!Set.of("profiles").containsAll(props.keySet())) return false;
        var profiles = read(props.get("profiles"));
        return profiles.size() <= 100 && profiles.entrySet().stream().allMatch(entry ->
            entry.getKey().matches("[^/]{1,128}/[^/]{0,128}") && entry.getValue() != null
                && FIELDS.containsAll(entry.getValue().keySet())
                && entry.getValue().values().stream().allMatch(value -> value != null && value.length() <= 4096));
    }

    private static Map<String, Map<String, String>> read(String input) {
        if (input == null) return Map.of();
        if (input.length() > 64000) throw new IllegalArgumentException("Workspace profiles exceed size limit");
        try {
            var profiles = JSON.readValue(input, new TypeReference<Map<String, Map<String, String>>>() {});
            if (profiles == null) throw new IllegalArgumentException("Workspace profiles must be an object");
            return profiles;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid business workspace profiles", ex);
        }
    }
}
