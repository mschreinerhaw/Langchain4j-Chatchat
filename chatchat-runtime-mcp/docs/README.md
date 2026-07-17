# chatchat-runtime-mcp 模块说明

## 1. 模块定位

`chatchat-runtime-mcp` 是 ChatChat 内置 MCP 能力的统一注册与调用路由层。它把不同业务模块提供的工具定义和执行器收口到同一个注册中心，再按能力、工具配置和运行状态决定是否发布给 Agent。

本模块是一个可复用 Java 库，不是独立部署的 MCP Server，也不实现新闻、数据库、运维等具体业务逻辑。

职责边界：

| 组件 | 职责 |
| --- | --- |
| 业务 Provider | 声明工具定义并提供执行器，可在进程内执行，也可代理独立 Runtime |
| `chatchat-runtime-mcp` | 发现、校验、配置覆盖、注册、刷新和统一调用 |
| `chatchat-mcp-server` | MCP 协议端点、管理页面、持久化配置和对外发布 |
| Agent `ToolRegistry` | 接收已启用且可发布的工具，供 Agent 选择调用 |

当前 News 能力由 MCP Server 中的 `RemoteNewsMcpToolProvider` 提供，执行时通过内部认证调用独立的 `chatchat-runtime-news`，本模块不依赖 WebMagic、Tika 或 OpenSearch 实现。

## 2. 核心对象

### McpToolProvider

业务模块的扩展入口：

```java
public interface McpToolProvider {
    String capabilityCode();
    Collection<McpToolDefinition> definitions();
    Optional<McpToolExecutor> findExecutor(String toolName);
}
```

一个 Provider 归属一个能力，可以声明多个工具。每个工具必须能找到对应执行器，且定义中的 `capabilityCode` 必须与 Provider 返回值一致。

### McpToolDefinition

工具的静态定义包含：

- `name`：全局唯一工具名；
- `displayName`、`description`：展示名称与说明；
- `capabilityCode`：能力归属；
- `provider`：实现模块标识；
- `parameters`：工具参数 Schema；
- `enabledByDefault`：默认是否启用；
- `agentCallable`：默认是否允许 Agent 调用；
- `timeout`：建议执行超时，未设置时为 30 秒。

### McpToolExecutor

执行器接收统一 `ToolInput` 并返回结构化 `ToolOutput`。执行器还可以报告运行状态：

| 状态 | 含义 |
| --- | --- |
| `AVAILABLE` | 执行依赖正常 |
| `DEGRADED` | 可执行但能力降级 |
| `UNAVAILABLE` | 当前依赖不可用，由执行器返回稳定错误契约 |
| `DISABLED` | 工具或所属能力已关闭 |

### RegisteredMcpTool

注册后的有效视图，由静态定义、执行器和配置覆盖结果组成，包含最终的启用状态、Agent 可调用状态、超时和实时运行状态。

## 3. 注册与发布流程

```text
Spring 收集 McpToolProvider
  → 校验 Provider 与 Definition 能力一致
  → 校验每个工具存在 Executor
  → 拒绝全局重名工具
  → 合并默认值与 chatchat.mcp.capabilities 配置
  → 查询数据库中的能力启停状态
  → 发布到 Agent ToolRegistry
```

`McpToolRegistry` 是内置 MCP 工具的唯一注册入口。Spring Bean 初始化完成后通过 `@PostConstruct` 自动发布。

发布到 Agent 时会生成统一 `ToolMetadata`：

- 分类包含 `mcp` 和能力编码；
- `providerModule` 标识实现模块；
- `runtimeStatus` 表示执行器状态；
- 当前内置工具统一标记为低风险、只读操作；
- 最终 `agentCompatible` 取配置覆盖后的 `agentCallable`。

同名工具无论是否来自不同 Provider 都会导致启动失败，防止执行路由不确定。

## 4. 配置覆盖

配置前缀为 `chatchat.mcp`：

```yaml
chatchat:
  mcp:
    capabilities:
      news:
        enabled: true
        tools:
          web_search:
            enabled: true
            agent-callable: true
            timeout: 30s
          news_source_status:
            enabled: true
            agent-callable: false
            timeout: 10s
```

优先级：

1. 工具级配置覆盖 `McpToolDefinition` 默认值；
2. 能力级配置和数据库中的能力状态共同决定能力是否启用；
3. 未配置能力时默认启用；
4. 工具关闭或能力关闭时，不发布到 Agent 注册表。

调用 `refreshCapability(capabilityCode)` 会先注销该能力的已发布工具，再依据最新状态重新发布，不需要重建整个注册中心。

## 5. 调用语义

`McpToolInvocationService.invoke(toolName, input)` 是统一调用入口：

1. 工具不存在时抛出 `IllegalArgumentException`；
2. 工具或能力关闭时返回失败的 `ToolOutput`；
3. 工具处于 `UNAVAILABLE` 或 `DEGRADED` 时仍交给领域执行器，由领域模块返回稳定、可识别的错误或降级结果；
4. 本模块不吞掉或改写领域数据结构。

`timeout` 当前写入工具元数据，供上层调度和协议层执行策略使用；业务执行器仍应设置自己的网络、数据库和外部 Runtime 超时。

## 6. 新增能力或工具

### 新增能力编码

在 `McpCapabilityCodes` 增加稳定的小写编码，例如：

```java
public static final String NEWS = "news";
```

能力编码一旦进入生产配置或数据库，不应直接重命名。

### 实现 Provider

```java
@Component
public class ExampleMcpToolProvider implements McpToolProvider {
    private final McpToolDefinition definition = new McpToolDefinition(
        "example_query",
        "示例查询",
        "查询示例数据。",
        "example",
        "example-runtime",
        List.of(),
        true,
        true,
        Duration.ofSeconds(20)
    );

    @Override
    public String capabilityCode() {
        return "example";
    }

    @Override
    public Collection<McpToolDefinition> definitions() {
        return List.of(definition);
    }

    @Override
    public Optional<McpToolExecutor> findExecutor(String toolName) {
        return "example_query".equals(toolName)
            ? Optional.of(input -> ToolOutput.success(Map.of("items", List.of())))
            : Optional.empty();
    }
}
```

实现要求：

- 工具名使用稳定的 snake_case，并在所有 Provider 中全局唯一；
- 参数通过 `ToolParameter` 明确类型、必填、默认值和范围；
- 只在查询工具上设置 `agentCallable=true`；管理、配置和破坏性操作不得直接暴露给 Agent；
- 独立 Runtime 使用远程 Provider 代理，内部认证和重试逻辑留在客户端实现中；
- 执行失败返回结构化 `ToolOutput`，不要返回无法识别的纯文本异常页。

## 7. 设计约束

- 本模块不得引入具体采集器、搜索引擎客户端或站点解析依赖。
- 工具定义、启停、Schema、归属和发布必须经过统一注册中心。
- Provider 负责业务执行能力，不得绕过注册中心直接写入 Agent `ToolRegistry`。
- 能力关闭后，刷新时必须注销对应工具。
- 运行状态与配置状态分离：启用不代表依赖一定可用。
- 新增字段应保持向后兼容；工具名、能力编码和参数类型变更需要同步升级调用契约。

## 8. 测试

模块测试覆盖 Provider 发现与发布、工具重名拒绝和工具关闭后不发布：

```bash
mvn -pl chatchat-runtime-mcp -am test
```

