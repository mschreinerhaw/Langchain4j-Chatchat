package com.chatchat.common.runtime.agent;

import java.util.List;

public interface AgentCardDiscoveryPort {
    CardSummary discoverSummary(AgentDescriptor descriptor, String bearerToken);

    record CardSummary(String name, String version, List<String> skills, boolean signatureVerified,
                       String endpoint) { }
}
