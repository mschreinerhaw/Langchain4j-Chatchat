package com.chatchat.mcpserver.search.engine;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/models/embeddings")
public class McpPublishedEmbeddingController {
    private final McpPublishedEmbeddingCatalog catalog;

    @PutMapping
    public void synchronize(@RequestBody McpPublishedEmbeddingCatalog.Snapshot snapshot) {
        catalog.synchronize(snapshot);
    }
}
