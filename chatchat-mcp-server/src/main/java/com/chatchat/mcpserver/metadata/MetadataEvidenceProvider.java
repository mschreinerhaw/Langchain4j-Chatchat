package com.chatchat.mcpserver.metadata;

public interface MetadataEvidenceProvider {

    MetadataEvidenceProviderProtocol.MatchResponse match(
        MetadataEvidenceProviderProtocol.MatchRequest request);
}
