# Operations package layout

`com.chatchat.mcpserver.ops` owns operational asset configuration, discovery,
remote execution, monitoring, safety checks, MCP publication, and administration.

| Package | Responsibility |
| --- | --- |
| `ops.command` | Command-template configuration, persistence, seeding, and lifecycle service |
| `ops.discovery` | Cross-asset template discovery, discovery configuration, requirement analysis, and MCP publication |
| `ops.ssh` | SSH host configuration, Linux command execution/results, and command safety policies |
| `ops.http` | HTTP endpoint configuration, request execution, technical classification, and results |
| `ops.jmx` | JMX template configuration, monitoring execution, and monitoring results |
| `ops.tool` | Unified operations MCP tool publication and execution routing |
| `ops.admin` | Administrative HTTP API spanning operational asset types |

## Placement rules

1. Keep the `ops` root free of concrete types.
2. Place configuration entities and repositories beside the execution capability they configure.
3. Keep discovery and requirement analysis separate from SSH, HTTP, and JMX execution.
4. Keep cross-capability MCP routing in `tool` and administrative HTTP orchestration in `admin`.
5. Avoid generic `model`, `service`, `persistence`, and `util` packages when a functional owner exists.
