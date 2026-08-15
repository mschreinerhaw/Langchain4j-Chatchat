# ChatChat Production E2E Test Module

完整的生产发布范围、场景矩阵、证据要求和 Go/No-Go 标准见
[`docs/production-release-test-specification.md`](../docs/production-release-test-specification.md)。

This module is the backend production-release gate. It runs after all required application modules and verifies cross-module contracts that unit tests cannot own individually.

测试分为两级：

- 进程内场景测试可使用 mock 或内存存储，用于快速验证跨模块契约；它们属于集成/组件回归，不能单独作为严格 E2E 放行证据。
- 部署级 E2E 只能通过真实 HTTP 入口访问已经部署的服务，不得在测试 JVM 中实例化或 mock 应用服务。它必须验证最终响应，并通过查询接口回读 Runtime 和会话持久化证据。

当前严格部署链路覆盖 API → Agent → MCP → Runtime 持久化 → Conversation 持久化 → 最终答案。仓库暂未引入浏览器驱动，因此 Web 页面 DOM 渲染不属于当前后端 E2E 的覆盖范围；涉及页面交互或展示的发布必须另行执行浏览器 E2E，不能用本模块结果替代。

Run the complete release gate from the repository root:

```powershell
.\scripts\test-production-release-e2e.ps1
```

The default command is the strict product-release gate: it requires zero failed, errored, or skipped tests and enforces 70% line / 60% branch coverage. A local regression baseline may explicitly allow environment-dependent skips, but that result is not releasable:

```powershell
.\scripts\test-production-release-e2e.ps1 -AllowConditionalSkips
```

For a production-connected release, enable the WSA, deployed API/MCP/News topology, and capacity/soak live gates; also enable SQL metadata and provide the enterprise metadata dataset when those assets are part of the release. Credentials are read from environment variables or passed as runtime properties; they must not be committed.

After changing an upstream module, do not use Maven `-rf` as release evidence because it can resolve a stale local SNAPSHOT for the changed dependency. Rebuild the affected reactor with `-pl ... -am` (or run the complete release script).

Equivalent Maven command:

```text
mvn -pl chatchat-e2e-tests -am -Dfrontend.skip=true verify
```

The mandatory Agent quality gate and its online/offline scoring contract are documented in
[`docs/agent-quality-evaluation-gate.md`](../docs/agent-quality-evaluation-gate.md). The release script exposes explicit thresholds for retrieval quality, tool selection, parameter accuracy, evidence completeness, total case pass rate, and overall quality. When deployed-topology testing is enabled, provide `InferenceExpectedTool` and, when the required query argument differs from the user query, `InferenceExpectedQueryArgument`; the live test evaluates the persisted run through the production evaluation API.

Coverage includes Agent planning and repair, MCP authorization and retry, API/gateway assets, SSH/server assets, SQL/database assets, capability-center discovery, News/Market runtime dependencies, evidence boundaries, tenant context, release artifacts, and deployment-hardcoding checks.

The E2E tests use generated tenant, namespace and template identifiers. They must never depend on one deployed MCP server name or one maintained production template ID.
