package com.chatchat.mcpserver.ops.admin;

import com.chatchat.mcpserver.ops.command.CommandTemplateService;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfig;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfigService;
import com.chatchat.mcpserver.ops.http.HttpRequestToolResult;
import com.chatchat.mcpserver.ops.http.HttpRequestToolService;
import com.chatchat.mcpserver.ops.jmx.JmxMonitorService;
import com.chatchat.mcpserver.ops.jmx.JmxTemplateService;
import com.chatchat.mcpserver.ops.ssh.LinuxCommandService;
import com.chatchat.mcpserver.ops.tool.OpsMcpToolPublisher;
import com.chatchat.mcpserver.ops.ssh.SshHostConfigService;

import com.chatchat.mcpserver.search.McpAssetLuceneIndexService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpsAdminControllerTest {

    @Test
    void forwardsUserEnteredApiArgumentsToGatewayTestExecution() {
        HttpRequestToolService httpRequestToolService = mock(HttpRequestToolService.class);
        OpsAdminController controller = new OpsAdminController(
            mock(SshHostConfigService.class),
            mock(HttpEndpointConfigService.class),
            mock(CommandTemplateService.class),
            mock(JmxTemplateService.class),
            mock(JmxMonitorService.class),
            mock(OpsMcpToolPublisher.class),
            mock(LinuxCommandService.class),
            httpRequestToolService,
            mock(McpAssetLuceneIndexService.class)
        );
        HttpEndpointConfig asset = new HttpEndpointConfig();
        HttpRequestToolResult result = mock(HttpRequestToolResult.class);
        when(httpRequestToolService.execute(same(asset), org.mockito.ArgumentMatchers.anyMap()))
            .thenReturn(result);

        controller.testHttpEndpointWithArguments(
            new OpsAdminController.HttpEndpointTestRequest(asset, Map.of("orderId", "A001")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> arguments = ArgumentCaptor.forClass(Map.class);
        verify(httpRequestToolService).execute(same(asset), arguments.capture());
        assertThat(arguments.getValue())
            .containsEntry("orderId", "A001")
            .containsEntry("sourceTaskId", "asset-center");
    }
}
