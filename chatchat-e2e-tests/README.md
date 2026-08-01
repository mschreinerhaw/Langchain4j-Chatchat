# ChatChat Production E2E Test Module

This module is the backend production-release gate. It runs after all required application modules and verifies cross-module contracts that unit tests cannot own individually.

Run the complete release gate from the repository root:

```powershell
.\scripts\test-production-release-e2e.ps1
```

For the final environment-connected release gate, provision the maintained datasets/live datasource settings and require that no conditional test is skipped:

```powershell
.\scripts\test-production-release-e2e.ps1 -RequireLive
```

Equivalent Maven command:

```text
mvn -pl chatchat-e2e-tests -am -Dfrontend.skip=true verify
```

Coverage includes Agent planning and repair, MCP authorization and retry, API/gateway assets, SSH/server assets, SQL/database assets, capability-center discovery, News/Market runtime dependencies, evidence boundaries, tenant context and deployment-hardcoding checks.

The E2E tests use generated tenant, namespace and template identifiers. They must never depend on one deployed MCP server name or one maintained production template ID.
