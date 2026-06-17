package com.chatchat.knowledgebase.search;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.search")
public class SearchProperties {

    private String storePath = "./data/search-rocksdb";
    private String filePath = "./data/search-files";
    private boolean createIfMissing = true;
    private long maxUploadBytes = 5 * 1024 * 1024;
    private int defaultLimit = 20;
    private int maxLimit = 100;
    private int summaryLength = 180;
    private boolean luceneEnabled = true;
    private String luceneIndexPath = "./data/search-lucene";
    private int luceneMaxHits = 500;
    private int luceneChunksPerDocument = 3;
    private int chunkSize = 800;
    private int chunkOverlap = 120;
}
