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
    private boolean lucenePrfEnabled = true;
    private int lucenePrfTopN = 20;
    private int lucenePrfMaxTerms = 8;
    private int lucenePrfMinTermLength = 2;
    private boolean luceneMmrEnabled = true;
    private float luceneMmrLambda = 0.72F;
    private boolean luceneRocchioEnabled = false;
    private int luceneRocchioMaxTerms = 6;
    private int luceneRocchioFeedbackLimit = 80;
    private float luceneTitleBoost = 5.0F;
    private float luceneSectionBoost = 4.6F;
    private float luceneKeywordBoost = 4.2F;
    private float luceneTagBoost = 4.5F;
    private float luceneCompanyBoost = 3.5F;
    private float luceneIndustryBoost = 3.5F;
    private float luceneContentBoost = 1.2F;
    private float luceneSourceBoost = 0.7F;
    private int chunkSize = 800;
    private int chunkOverlap = 120;
}
