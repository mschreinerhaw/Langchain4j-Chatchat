# MCP execution workflow

## Responsibility

`McpExecutionWorkflow` is the Runtime OS execution boundary for an MCP tool call.
It owns the lifecycle from the live tool template lookup to the canonical MCP
result returned to the caller.

```text
McpServiceCall
    -> discover service
    -> discover live MCP tool template
    -> validate Runtime template binding
    -> validate tool contract snapshot
    -> contract preflight
    -> refresh and rediscover once when the catalog is stale
    -> apply execution extensions
    -> dispatch to McpServiceProvider
    -> repair a raw result when required
    -> contract postflight
    -> McpServiceResult + workflow trace
```

The workflow id is `execute.mcp-tool.v1`.

## Internal and external tools

Both routes use `McpServiceDirectory` and `McpServiceProvider`.

| Tool source | Provider | Transport ownership |
| --- | --- | --- |
| Tools published by this MCP module | `LocalMcpRuntimeServiceProvider` | In-process `ToolRegistry` |
| Administratively configured MCP servers | `ConfiguredRemoteMcpServiceProvider` | `McpGatewayClient` adapters |
| Future plugins or service families | New `McpServiceProvider` | Owned by that provider |

The workflow does not branch on HTTP, Streamable HTTP, SSE, STDIO, gRPC, or an
MCP SDK type. A new transport is implemented below the provider boundary, so it
does not change the Runtime workflow.

## Extension boundary

`McpExecutionWorkflowExtension` provides ordered hooks immediately before and
after provider dispatch. It is intended for behavior shared across providers,
such as policy enrichment, trace propagation, metrics, or result normalization.

An extension may enrich arguments and context, or transform the result. It may
not change `requestId`, `serviceId`, or `toolName`; the workflow rejects an
extension that changes the invocation identity. Spring collects extension beans
and injects them into the local MCP Runtime kernel in order.

Transport authentication, reconnect behavior, protocol negotiation, and wire
format conversion remain provider responsibilities.

## Runtime OS boundary

`DefaultMcpRuntimeKernel` owns catalog lifecycle, health, revision, and workflow
dispatch. It no longer contains the MCP execution algorithm. The workflow owns
analysis, planning, execution, verification, repair, and result assembly.

Every returned result includes `metadata.mcpExecutionWorkflow`, containing the
workflow id, executed stages, provider route, discovery state, repair state, and
applied extensions. Existing preflight, postflight, template binding, and kernel
protocol metadata remain available at their previous locations.
