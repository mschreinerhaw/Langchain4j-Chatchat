package com.chatchat.mcpserver.tool;

import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolPublicationCoordinatorTest {

    @Test
    void publishesRuntimeToolsBeforeOptionalApplicationReadyListeners() throws Exception {
        Method listener = McpToolPublicationCoordinator.class.getMethod("publishOnStartup");

        assertThat(listener.getAnnotation(EventListener.class)).isNotNull();
        assertThat(listener.getAnnotation(Order.class).value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void isolatesContributorFailureAndPublishesTheRemainingContributors() {
        McpToolContributor failing = mock(McpToolContributor.class);
        McpToolContributor healthy = mock(McpToolContributor.class);
        when(failing.contributorId()).thenReturn("failing");
        when(healthy.contributorId()).thenReturn("healthy");
        doThrow(new IllegalStateException("catalog unavailable")).when(failing).refreshPublication();
        doReturn(new McpToolPublicationPipeline.PublicationResult(
            "healthy", true, 0, 0, 0, List.of(), List.of()))
            .when(healthy).refreshPublication();
        McpToolPublicationCoordinator coordinator =
            new McpToolPublicationCoordinator(List.of(failing, healthy));

        var result = coordinator.refreshAll("test");

        assertThat(result.get("failing").success()).isFalse();
        assertThat(result.get("healthy").success()).isTrue();
        assertThat(coordinator.metrics())
            .containsEntry("failedPublications", 1L)
            .containsEntry("successfulPublications", 1L);
        verify(healthy).refreshPublication();
    }
}
