package com.chatchat.api.runtime.coding.analysis;

import com.chatchat.agents.model.ModelEndpoint;
import com.chatchat.api.model.PlatformModelCatalogService;
import com.chatchat.api.runtime.coding.nativecline.NativeS3Settings;
import com.chatchat.common.config.ModelResourceRegistry;
import com.chatchat.common.runtime.coding.CodingReasoning;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Model permissions remain on the normal Runtime path. Only a published mapping can add provider parameters. */
@Service
public class CodingReasoningPolicy {
    private final CodingAnalysisProperties settings;
    private final ModelResourceRegistry models;
    private final ObjectProvider<NativeS3Settings> nativeSettings;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private PlatformModelCatalogService catalog;

    public CodingReasoningPolicy(CodingAnalysisProperties settings, ModelResourceRegistry models,
                                 ObjectProvider<NativeS3Settings> nativeSettings) {
        this.settings = settings;
        this.models = models;
        this.nativeSettings = nativeSettings;
    }

    public List<String> options(String model) {
        var profile = profile(model);
        return CodingReasoning.LEVELS.stream().filter(profile.levels()::containsKey).toList();
    }

    private record Profile(String parameter, Map<String,String> levels) {}
    private static final Profile DISABLED = new Profile(null,Map.of());

    private Profile profile(String model) {
        String name = resolve(model);
        if (!compatible(name)) return DISABLED;
        var published = catalog == null ? null : catalog.publishedReasoning(name);
        if (published != null)
            return published.enabled() ? new Profile(published.parameter(),published.levels()) : DISABLED;
        var legacy = new LinkedHashMap<String,String>();
        for (String level : settings.getReasoningEffortsByModel().getOrDefault(name,List.of()))
            if (CodingReasoning.LEVELS.contains(level)) legacy.put(level,level);
        return new Profile(null,legacy);
    }

    private String resolve(String model) {
        return model == null || model.isBlank() ? models.defaultChatModel() : model;
    }

    private boolean compatible(String name) {
        var nativeNode = nativeSettings.getIfAvailable();
        if (nativeNode != null && !nativeNode.dynamicModels) return false;
        if (name == null || !models.selectableChatModels().contains(name)) return false;
        var connection = models.connection(name);
        if (connection == null) return false;
        try {
            return ModelEndpoint.resolve(connection.getBaseUrl(),connection.getProtocol()).protocol()
                == ModelEndpoint.Protocol.OPENAI;
        } catch (IllegalArgumentException invalidEndpoint) {
            return false;
        }
    }

    public Map<String,String> freeze(String requested, String model, int sources, long rows) {
        var profile = profile(model);
        var levels = profile.levels();
        var frozen = new LinkedHashMap<String,String>();
        if (requested == null) {
            if (levels.isEmpty()) return Map.of("requested",CodingReasoning.OFF,"source","model-default");
            if (profile.parameter() == null) return Map.of();
            requested = levels.containsKey("medium") ? "medium"
                : CodingReasoning.LEVELS.stream().filter(levels::containsKey).findFirst().orElseThrow();
            frozen.put("source","model-default");
        }
        frozen.put("requested",requested);
        String level = "auto".equals(requested)
            ? (String) CodingExecutionProfile.select(settings, Math.min(sources,
                com.chatchat.common.runtime.coding.CodingProtocol.MAX_ANALYSIS_SOURCES), rows).get("reasoningEffort") : requested;
        String effective = level == null ? null : levels.get(level);
        if (effective != null) {
            frozen.put("effective",effective);
            if (profile.parameter() != null) frozen.put("parameter",profile.parameter());
        }
        return Map.copyOf(frozen);
    }

    public String supportedLegacyEffort(String model, Object configured) {
        if (!(configured instanceof String level)) return null;
        var profile = profile(model);
        return profile.parameter() == null ? profile.levels().get(level) : null;
    }

    public String compatibleEffort(String model, String frozenEffort, String parameter) {
        if (frozenEffort == null) return null;
        var current = profile(model);
        return java.util.Objects.equals(current.parameter(),parameter)
            && current.levels().containsValue(frozenEffort)
            && CodingReasoning.permits(parameter == null ? "standard" : parameter,frozenEffort)
            ? frozenEffort : null;
    }
}
