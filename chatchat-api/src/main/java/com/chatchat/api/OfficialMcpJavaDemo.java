package com.chatchat.api;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;

import java.time.Duration;
import java.util.Map;

/**
 * 参考官方 Java SDK 文档重写
 * https://java.sdk.modelcontextprotocol.io/latest/client/
 *
 * 适配你的服务：
 * http://192.168.195.204:10777/sse
 *
 * 要求：
 * Java 17+
 */
public class OfficialMcpJavaDemo {

    public static void main(String[] args) {

        /*
         Maven

         <dependency>
            <groupId>io.modelcontextprotocol.sdk</groupId>
            <artifactId>mcp</artifactId>
            <version>1.1.1</version>
         </dependency>
        */

        // 1. 创建官方 SSE Transport
        HttpClientSseClientTransport transport =
                HttpClientSseClientTransport.builder("http://192.168.195.204:10777")
                        .sseEndpoint("/sse")
                        .build();

        // 2. 创建同步客户端
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .build();

        try {

            // 3. 初始化（自动协议协商）
            var init = client.initialize();

            System.out.println("连接成功");
            System.out.println("协议版本: " + init.protocolVersion());
            System.out.println("服务端: " + init.serverInfo().name());

            // 4. 获取工具列表
            McpSchema.ListToolsResult tools = client.listTools();

            System.out.println("\n===== 工具列表 =====");

            for (McpSchema.Tool tool : tools.tools()) {
                System.out.println("工具名: " + tool.name());
                System.out.println("说明 : " + tool.description());
                System.out.println("-------------------");
            }

            // 5. 如果有工具，调用第一个工具演示
            if (!tools.tools().isEmpty()) {

                String toolName = tools.tools().get(0).name();

                System.out.println("\n调用工具: " + toolName);

                McpSchema.CallToolResult result =
                        client.callTool(
                                new McpSchema.CallToolRequest(
                                        toolName,
                                        Map.of()
                                )
                        );

                System.out.println("结果:");
                result.content().forEach(System.out::println);
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            client.closeGracefully();
        }
    }
}