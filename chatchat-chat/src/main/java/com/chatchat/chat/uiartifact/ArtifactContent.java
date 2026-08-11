package com.chatchat.chat.uiartifact;

import java.io.IOException;
import java.io.InputStream;

public record ArtifactContent(InputStream stream,
                              ArtifactObjectMetadata metadata) implements AutoCloseable {

    public ArtifactContent {
        if (stream == null) {
            throw new IllegalArgumentException("stream cannot be null");
        }
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }
}
