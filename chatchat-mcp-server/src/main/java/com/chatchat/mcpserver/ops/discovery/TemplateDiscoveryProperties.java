package com.chatchat.mcpserver.ops.discovery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "chatchat.mcp.template-discovery")
public class TemplateDiscoveryProperties {

    private Map<String, List<String>> intentSynonyms = new LinkedHashMap<>();
    /**
     * Environment label used only for virtual, Runtime-managed data sources.  This is
     * deployment data and must not be inferred or hard-coded by the discovery domain.
     */
    private String runtimeManagedEnvironment;
    /** Logical relationship-policy revision embedded in and validated by cursors. */
    private String policyVersion = CommandTemplateDiscoveryService.RESULT_SCHEMA_VERSION;
    /** Hard ceiling for one bounded retrieval session, independent of the public page size. */
    private int maxCandidateCount = 60;
    /** Hard page budget for one retrieval session. */
    private int maxPages = 3;

    public Map<String, List<String>> getIntentSynonyms() {
        return intentSynonyms;
    }

    public void setIntentSynonyms(Map<String, List<String>> intentSynonyms) {
        this.intentSynonyms = intentSynonyms == null ? new LinkedHashMap<>() : intentSynonyms;
    }

    public String getRuntimeManagedEnvironment() {
        return runtimeManagedEnvironment;
    }

    public void setRuntimeManagedEnvironment(String runtimeManagedEnvironment) {
        this.runtimeManagedEnvironment = runtimeManagedEnvironment;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    public int getMaxCandidateCount() {
        return maxCandidateCount;
    }

    public void setMaxCandidateCount(int maxCandidateCount) {
        this.maxCandidateCount = maxCandidateCount;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }
}
