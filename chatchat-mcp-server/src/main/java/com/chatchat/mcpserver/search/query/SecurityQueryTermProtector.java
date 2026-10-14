package com.chatchat.mcpserver.search.query;

import com.chatchat.common.retrieval.query.QueryTermProtector;

import com.chatchat.runtime.market.storage.FinancialDataStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SecurityQueryTermProtector implements QueryTermProtector {
    private final FinancialDataStore store;
    @Override public List<String> protectedPhrases(String source, int maximumPhrases) {
        return store.protectedSecurityPhrases(source, maximumPhrases);
    }
}
