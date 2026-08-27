# API package layout

`com.chatchat.mcpserver.api` owns maintained API-service registration, classification,
invocation, discovery, and MCP publication.

| Package | Responsibility |
| --- | --- |
| `api.registry` | API-service configuration entity, persistence, validation, lifecycle, and management endpoint |
| `api.category` | Business-category administration, assignment, and discovery filtering |
| `api.invocation` | Validated HTTP invocation, response projection, auditing, and response-cache integration |
| `api.publication` | API discovery, template-service bridges, MCP tool specifications, and transport projection |

## Placement rules

1. Keep the `api` root free of concrete types.
2. Registration state and its management endpoint belong in `registry`.
3. Remote execution behavior and execution results belong in `invocation`.
4. MCP-facing discovery, bridge, and publication types belong in `publication`.
5. Category classification remains in `category`; it may depend on registered services but not on invocation behavior.
6. Avoid generic `model`, `service`, `controller`, and `util` packages when a functional owner exists.
