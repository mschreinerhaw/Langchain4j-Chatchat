package com.chatchat.mcpserver.ops;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "chatchat.mcp.template-discovery")
public class TemplateDiscoveryProperties {

    private Map<String, List<String>> intentSynonyms = new LinkedHashMap<>();

    public Map<String, List<String>> getIntentSynonyms() {
        return intentSynonyms;
    }

    public void setIntentSynonyms(Map<String, List<String>> intentSynonyms) {
        this.intentSynonyms = intentSynonyms == null ? new LinkedHashMap<>() : intentSynonyms;
    }
}
