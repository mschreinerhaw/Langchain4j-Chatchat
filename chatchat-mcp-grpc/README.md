# MCP Runtime gRPC transport

This module owns the versioned southbound contract between `chatchat-api` and the
standalone MCP Server. Browser-facing REST endpoints remain northbound adapters.

The service uses server-streaming for every response. JSON preserves dynamic MCP
schemas; Protobuf frames provide ordered 1 MiB chunks, SHA-256 verification, request
correlation, gzip compression, deadlines and HTTP/2 flow control.

Development defaults:

- MCP Server: `localhost:9091`, plaintext, internal bearer credential required.
- API client: `localhost:9091`, maximum assembled response 512 MiB.

Production defaults require TLS. Configure `CHATCHAT_MCP_GRPC_CERT_CHAIN` and
`CHATCHAT_MCP_GRPC_PRIVATE_KEY` on MCP Server, and optionally
`CHATCHAT_MCP_GRPC_TRUST_CERT` on API. Set `CHATCHAT_MCP_GRPC_PLAINTEXT=false`.

Embedded compatibility mode is explicit: set `chatchat.mcp.grpc.client.enabled=false`.
The Runtime Kernel and catalog ports then resolve to local implementations.
