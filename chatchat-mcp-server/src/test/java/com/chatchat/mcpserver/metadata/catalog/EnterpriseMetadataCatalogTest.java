package com.chatchat.mcpserver.metadata.catalog;

import com.chatchat.mcpserver.metadata.config.EnterpriseMetadataProperties;
import com.chatchat.mcpserver.metadata.ingestion.EnterpriseMetadataWorkbookLoader;
import com.chatchat.mcpserver.metadata.search.EnterpriseMetadataScenarioClassifier;
import com.chatchat.mcpserver.metadata.search.EnterpriseMetadataVectorizer;
import com.chatchat.mcpserver.search.engine.OpenSearchMcpSearchService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnterpriseMetadataCatalogTest {

    @Test
    void startupRefreshFailureDoesNotTerminateMcpRuntime() {
        EnterpriseMetadataProperties properties = new EnterpriseMetadataProperties();
        properties.setEnabled(true);
        properties.setRefreshOnStartup(true);
        EnterpriseMetadataWorkbookLoader loader = mock(EnterpriseMetadataWorkbookLoader.class);
        when(loader.load(org.mockito.ArgumentMatchers.anyString()))
            .thenThrow(new IllegalStateException("metadata backend timed out"));
        properties.setSourceLocationPatterns(java.util.List.of("file:metadata.xlsx"));
        EnterpriseMetadataCatalog catalog = new EnterpriseMetadataCatalog(
            properties,
            loader,
            mock(EnterpriseMetadataScenarioClassifier.class),
            mock(EnterpriseMetadataVectorizer.class),
            mock(OpenSearchMcpSearchService.class));

        assertThatCode(catalog::onApplicationReady).doesNotThrowAnyException();
    }
}
