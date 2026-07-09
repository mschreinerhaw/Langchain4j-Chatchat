package com.chatchat.mcpserver.search;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "chatchat.mcp.lucene")
public class LuceneSearchProperties {

    private boolean enabled = true;

    private String engine = "lucene";

    private String indexDir = "./data/lucene/mcp";

    private int maxResults = 50;

    private OpenSearch openSearch = new OpenSearch();

    public boolean isOpenSearchEngine() {
        return "opensearch".equalsIgnoreCase(engine) || "open-search".equalsIgnoreCase(engine);
    }

    public boolean isLuceneEngine() {
        return engine == null || engine.isBlank() || "lucene".equalsIgnoreCase(engine);
    }

    @Data
    public static class OpenSearch {
        private boolean enabled = true;
        private String url = "http://192.168.195.221:9200";
        private String username = "admin";
        private String password = "apexSoft12345";
        private String indexPrefix = "chatchat_mcp_";
        private boolean insecureSsl = false;
        private int requestTimeoutMs = 30000;
        private Embedding embedding = new Embedding();

        @Data
        public static class Embedding {
            private boolean enabled = false;
            private String endpoint = "";
            private String apiKey = "";
            private String model = "";
            private int dimension = 1024;
            private String vectorField = "mcpContentVector";
            private int maxInputChars = 6000;
            private int vectorCandidateLimit = 50;
            private float bm25Weight = 0.55F;
            private float vectorWeight = 0.45F;
        }
    }
}
