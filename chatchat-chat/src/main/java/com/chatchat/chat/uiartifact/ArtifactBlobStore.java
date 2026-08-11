package com.chatchat.chat.uiartifact;

import java.io.InputStream;
import java.util.Optional;

public interface ArtifactBlobStore {

    void put(ArtifactLocation location, InputStream content, ArtifactObjectMetadata metadata);

    Optional<ArtifactContent> get(ArtifactLocation location);

    boolean exists(ArtifactLocation location);

    void delete(ArtifactLocation location);

    String storeType();
}
