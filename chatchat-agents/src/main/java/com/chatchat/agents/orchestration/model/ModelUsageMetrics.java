package com.chatchat.agents.orchestration.model;

import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import java.util.*;

/** Provider-reported counters only. Missing cache/reasoning counters remain absent, never guessed zeros. */
public final class ModelUsageMetrics {
    private ModelUsageMetrics() { }
    public static Map<String,Long> observed(TokenUsage usage) {
        var values=new LinkedHashMap<String,Long>();
        if(usage==null)return values;
        put(values,"inputTokens",usage.inputTokenCount());put(values,"outputTokens",usage.outputTokenCount());
        if(usage instanceof OpenAiTokenUsage detailed) {
            if(detailed.inputTokensDetails()!=null)put(values,"cacheReadTokens",detailed.inputTokensDetails().cachedTokens());
            if(detailed.outputTokensDetails()!=null)put(values,"reasoningTokens",detailed.outputTokensDetails().reasoningTokens());
        }
        return Collections.unmodifiableMap(values);
    }
    private static void put(Map<String,Long> values,String key,Number count) {
        if(count!=null && count.longValue()>=0)values.put(key,count.longValue());
    }
}
