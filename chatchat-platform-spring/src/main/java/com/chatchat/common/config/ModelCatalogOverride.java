package com.chatchat.common.config;

import java.util.List;

/** Optional persisted model catalog; configuration properties remain the fallback. */
public interface ModelCatalogOverride {
    boolean hasChatModels();
    String defaultChatModel();
    List<String> chatModelNames();
    ModelsConfig.ResolvedModelConnection resolveChatModel(String name);
}
