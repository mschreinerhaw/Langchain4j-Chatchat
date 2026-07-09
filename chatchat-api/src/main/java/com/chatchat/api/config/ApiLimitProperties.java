package com.chatchat.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.api.limits")
public class ApiLimitProperties {

    /**
     * Maximum chunks returned by the frontend search endpoint. -1 means unlimited.
     */
    private int searchResultMaxChunks = 3;

    /**
     * Maximum characters retained for each frontend search chunk. -1 means unlimited.
     */
    private int searchResultChunkMaxChars = 1200;

    /**
     * Maximum characters retained for each frontend search summary. -1 means unlimited.
     */
    private int searchResultSummaryMaxChars = 800;
}
