# ChatChat Production E2E Test Module

This module is the backend production-release gate. It runs after all required application modules and verifies cross-module contracts that unit tests cannot own individually.

Run the complete release gate from the repository root:

```powershell
.\scripts\test-production-release-e2e.ps1
```

The default command is the strict product-release gate: it requires zero failed, errored, or skipped tests and enforces 70% line / 60% branch coverage. A local regression baseline may explicitly allow environment-dependent skips, but that result is not releasable:

```powershell
.\scripts\test-production-release-e2e.ps1 -AllowConditionalSkips
```

For a production-connected release, enable the applicable live gates (WSA, deployed API/MCP/News topology, SQL metadata) and supply the enterprise metadata dataset. Credentials are read from environment variables or passed as runtime properties; they must not be committed.

Equivalent Maven command:

```text
mvn -pl chatchat-e2e-tests -am -Dfrontend.skip=true verify
```

Coverage includes Agent planning and repair, MCP authorization and retry, API/gateway assets, SSH/server assets, SQL/database assets, capability-center discovery, News/Market runtime dependencies, evidence boundaries, tenant context, release artifacts, and deployment-hardcoding checks.

The E2E tests use generated tenant, namespace and template identifiers. They must never depend on one deployed MCP server name or one maintained production template ID.
