# Native Cline 固定模板试点

使用 `POST /api/v1/coding/tasks` 启动 Native 任务。调用方提供原始问题、已授权的 `agentId`、`publishedTemplateId` 和稳定的 `requestId`。Runtime 从登录会话取得租户和用户，并从当前部署的 `chatchat.coding.analysis.data-supply.grants` 匹配唯一的模板授权。服务端需要启用该组配置的 `enabled` 和 `cline-template-invocation-enabled`。省略 `publishedTemplateId` 的 Native 请求仍走现有路径。

部署授权绑定租户、用户、Skill、模板、逻辑数据源、允许的参数值和结果列。Skill 必须已绑定对应的 Studio SQL 发布项。Runtime 在启动和查询时核对发布项及修订，使用发布项的精简输入 Schema 与现有固定 SQL 执行路由。数据源和执行限制由发布项的服务端绑定决定。

## 工具调用

Native MCP 按任务公布 `execute_published_template`，工具参数直接使用现有协议。下例对应用户原文中明确给出 `20260716` 的请求，实际模板和字段由当前授权定义。

```json
{
  "protocol_version": "template_parameter_protocol_v2",
  "template_id": "QUERY_BY_TRADE_DATE",
  "arguments": {
    "trade_date": {
      "value": "20260716",
      "source": "user_query",
      "evidence": {"quote": "20260716"}
    }
  },
  "unresolved_parameters": []
}
```

成功回执含资产 ID、SHA-256、行数、完整性标志、列映射和工作区 Parquet 路径。文件字节经任务范围校验、从 Silo 读取，由受信任 broker 校验哈希并写入工作区。模型收到元数据和路径。

参数错误返回 `PARAMETER_INVALID` 及可用的字段诊断。grant 或模板定义不匹配返回 `AUTHORIZATION_DENIED`。试点允许两个不同的参数提交，并且每个任务只启动一次查询。相同查询可复用已经完成的回执。超过预算返回 `QUOTA_DENIED`。只有现有执行器返回可验证的结构化结果时，Runtime 才登记远端已返回的证明。缺少回执时查询保持待核对状态。查询与交付失败分别返回 `QUERY_FAILED` 和 `DATA_DELIVERY_FAILED`。

## 联机验收

在目标服务器从功能分支源码构建并部署 API、MCP 和 Native broker 后，选择一个已发布且有真实非空结果的只读模板。使用现有授权为验收账号配置固定 grant。

```sh
python scripts/verify-native-published-template-live.py BASE_URL AGENT_ID TEMPLATE_ID '包含明确参数的查询原文'
```

脚本使用真实 Native 任务入口，等待终态，从任务进度取得 broker 回执，下载 Silo Parquet，校验哈希和 Parquet footer，并核对 Cline 提交的结果行数。验收端需要 PyArrow。部署凭据通过交互输入或环境变量传入。

## 2026-11-21 服务器验收结果

在 `192.168.195.221` 从已核对 SHA-256 的 Git 源码构建并部署。API 使用 `925e632db`，MCP 使用 `c508e64f1`，Native broker 使用 `ed44b6266`，Executor 使用 `292edfd67`。应用、MCP 和 broker 通过健康检查，Executor 已启动并用于本次真实运行。验收后 `/opt` 约有 24 GB 可用，未清理镜像。

验收 Agent 为 `native_template_pilot_20261121`。它绑定已发布的 TPCDS 仓库面积明细模板 `STUDIO_271B4D6FA9283DBE9AA526D49F0BFFF7`。Native Cline 使用 `qwen3.6-plus`，通过 `runtime__execute_published_template` 提交 `min_area=1000`。Runtime 校验后执行实际查询，并交付授权的仓库主键和面积字段。用同一 Agent 请求授权范围外的另一个已发布模板，任务入口返回 HTTP 403。调试过程中发出的任务取消请求已观察到 `CANCELLED` 终态。

| 验收项 | 实测 |
| --- | --- |
| 最终任务 | `native-b8cb282a986bd02244334eb91f1e98f9240c47e9375bfeedef9d632817b75af4` |
| Run | `coding-5a541f0cf93e8115e5cee07679b363f5e370affa354847ee7b05f6ffd511d530` |
| 终态 | `COMPLETED`，无待核对操作 |
| 交付回执 | `template.query.delivered`，`OK`，4 行，完整 |
| Silo Parquet | `650d7eaf812d131c68d4c2b2e41f1b45db3b3514f8f6a74e9b996830d1c88d06`，831 字节 |
| SHA-256 | `2849c50a14b1d1bd7d48766691ba54c96da6e3e0f1cb79f87aebe1972645291b` |
| Native 结果文件 | `ef3c2b7236dc41d62351355216b55dc1617c3e8e4016e5f0546f247cf238ada2` |
| Runtime 查询阶段 | 3.590 秒 |
| 任务开始至数据交付 | 36.639 秒 |
| 任务开始至最终完成 | 75.413 秒 |
| Native 模型使用量 | 6 次完成的模型调用，账本记录 42,921 Token，完整计量标志为 false |

验收脚本读取标准任务进度，确认 broker 交付事件及 Python 命令退出码 0。它使用任务账号重新下载 Silo 文件，验证 SHA-256 和 Parquet footer，再读取归档的 `output/result.json`，确认 Cline 在 worker 内取得的行数和哈希与 Silo 文件一致。

Java 最终专项 16 项通过，覆盖发布路由、回执持久化、结果封装读取和 Parquet 编解码。Node 契约与执行治理测试 41 项通过、1 项跳过。

该次回执记录的执行入口为 `governed_template_without_runtime_binding`，查询分支直接调用现有受控工具执行器。Runtime 参数绑定的独立计量、完整的联机故障矩阵及新旧链路 A/B 性能对比仍待验证。上述耗时来自单次成功任务。

首个调试任务 `native-2584fc39ea3b4aadbcdabc050eebcd57eb796d3ac51fa853357ead293f5c28ab` 在 Executor 部署修复前启动失败。其取消请求已登记，任务记录仍待转入终态。最终验收后检查专用 Executor Docker daemon，已无运行中的 worker。

## 加固复验

服务器部署 `b34963fcd` 后，首个启动失败的任务经 Executor 取得未分配证明和分配栅栏，已收敛为 `CANCELLED`，无待核对操作。查询预留、启动意图和请求快照在同一 Runtime 事务提交。

真实复验任务 `native-ee1dad5ecec78206ba7a4d967b6ba9757f2f6443f1dbdf376a73df56c0885d70` 交付 4 行并到达 `COMPLETED`。第二个任务 `native-c8980802853c5bd81aeb05b2af584b52c2d251bc5950d37434878deec5ace72c` 先提交错误参数证据，再修正并原样重试。broker 记录一次工具错误和两次成功交付，两次资产 ID 相同。MCP 日志确认该任务只发起了一次真实查询。第二个任务也成功归档了 Python 读取结果。

Runtime 对每次模板工具请求使用可信任务身份，重新核对当前的 Agent 工具授权、发布修订和部署 grant。成功回执重放另从调用者的实时目录复查模板和数据源读取权限，再由 Runtime 读取当前任务的 Silo 资产。参数和回执仅供 Cline 使用，授权变更由 Runtime 管理。
