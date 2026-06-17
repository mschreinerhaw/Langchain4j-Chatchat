package com.chatchat.agents.evidence;

public record EvidenceSource(
    String name,
    String url,
    String domain,
    String fileId,
    String section
) {
}
