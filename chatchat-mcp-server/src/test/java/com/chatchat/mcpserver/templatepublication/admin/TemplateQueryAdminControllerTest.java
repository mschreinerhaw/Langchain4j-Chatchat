package com.chatchat.mcpserver.templatepublication.admin;

import com.chatchat.mcpserver.templatepublication.binding.TemplateQueryBindingService;
import com.chatchat.mcpserver.templatepublication.catalog.TemplateAssetCatalogService;
import com.chatchat.mcpserver.templatepublication.catalog.TemplateQueryParentCatalog;
import com.chatchat.mcpserver.templatepublication.publisher.TemplateQueryMcpToolPublisher;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemplateQueryAdminControllerTest {

    @Test
    void listReturnsBindingsFromTheService() {
        TemplateQueryBindingService bindings = mock(TemplateQueryBindingService.class);
        TemplateAssetCatalogService catalog = mock(TemplateAssetCatalogService.class);
        TemplateQueryParentCatalog parents = mock(TemplateQueryParentCatalog.class);
        McpAuthorizationService authorization = mock(McpAuthorizationService.class);
        TemplateQueryMcpToolPublisher publisher = mock(TemplateQueryMcpToolPublisher.class);
        when(bindings.list()).thenReturn(List.of());
        TemplateQueryAdminController controller = new TemplateQueryAdminController(
            bindings, catalog, parents, authorization, publisher);

        ApiResponse<List<TemplateQueryBindingService.BindingView>> response = controller.list();

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData()).isEmpty();
        verify(bindings).list();
    }

    @Test
    void deleteRefreshesPublishedToolsAfterTheBindingChanges() {
        TemplateQueryBindingService bindings = mock(TemplateQueryBindingService.class);
        TemplateQueryMcpToolPublisher publisher = mock(TemplateQueryMcpToolPublisher.class);
        TemplateQueryAdminController controller = new TemplateQueryAdminController(
            bindings,
            mock(TemplateAssetCatalogService.class),
            mock(TemplateQueryParentCatalog.class),
            mock(McpAuthorizationService.class),
            publisher);

        ApiResponse<Void> response = controller.delete("binding-1");

        assertThat(response.getCode()).isEqualTo(200);
        verify(bindings).delete("binding-1");
        verify(publisher).refresh();
    }
}
