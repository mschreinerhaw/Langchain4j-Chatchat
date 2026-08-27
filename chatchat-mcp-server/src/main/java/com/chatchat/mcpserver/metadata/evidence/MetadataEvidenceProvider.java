package com.chatchat.mcpserver.metadata.evidence;

public interface MetadataEvidenceProvider {

    MetadataEvidenceProviderProtocol.MatchResponse match(
        MetadataEvidenceProviderProtocol.MatchRequest request);
}
