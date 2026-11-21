# Native Cline 固定模板试点

使用 `POST /api/v1/coding/tasks` 启动 Native 任务。调用方提供原始问题、已授权的 `agentId`、`publishedTemplateId` 和稳定的 `requestId`。Runtime 从登录会话取得租户和用户，并从当前部署的 `chatchat.coding.analysis.data-supply.grants` 匹配唯一的模板授权。服务端需要启用该组配置的 `enabled` 和 `cline-template-invocation-enabled`。省略 `publishedTemplateId` 的 Native 请求仍走现有路径。

部署授权绑定租户、用户、Skill、模板、逻辑数据源、环境、允许的参数值和结果列。Skill 必须能通过现有 Runtime 工具授权执行 `sql_template_analysis_execute`。数据源必须显式允许该模板。启动时 Runtime 通过同一受控能力取得参数 Schema。

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

目标服务器的真实查询、取消、恢复及 Runtime 模型计量尚待联机验证。端到端性能对比在联机查询验收后进行。
