# Agent MCP Workflow JSON

Agent 配置可以在 `workflowConfig.mcpWorkflow` 中声明 MCP 工具编排契约。Planner 会把它作为执行计划的思考图，Runtime 会按同一契约硬拦截越序、缺依赖和缺确认的工具调用。

```json
{
  "workflowConfig": {
    "mcpWorkflow": [
      {
        "step": "asset_discovery",
        "tool": "asset_query",
        "required": true,
        "dependsOn": [],
        "parallelSteps": [],
        "condition": "当用户问题涉及具体数据库、服务器、集群、组件或业务资产时必须先执行",
        "confirmation": "none",
        "executionStrategy": "deterministic_first"
      },
      {
        "step": "template_retrieval",
        "tool": "template_query",
        "required": true,
        "dependsOn": ["asset_discovery"],
        "parallelSteps": [],
        "condition": "当任务需要数据库状态检查、慢SQL分析、锁分析、备份检查、巡检报告等标准运维能力时执行",
        "confirmation": "none",
        "executionStrategy": "retrieve_rank_then_plan"
      },
      {
        "step": "database_diagnosis",
        "tool": "database_query",
        "required": false,
        "dependsOn": ["asset_discovery", "template_retrieval"],
        "parallelSteps": [],
        "condition": "当需要读取数据库状态、性能指标、系统视图、慢SQL、锁等待、复制状态等信息时执行",
        "confirmation": "none",
        "executionStrategy": "read_only_query"
      },
      {
        "step": "database_change",
        "tool": "database_execute",
        "required": false,
        "dependsOn": ["asset_discovery", "template_retrieval", "database_diagnosis"],
        "parallelSteps": [],
        "condition": "仅当用户明确要求执行变更类数据库操作时执行",
        "confirmation": "required_for_write",
        "executionStrategy": "confirm_then_execute"
      },
      {
        "step": "host_diagnosis",
        "tool": "linux_command_execute",
        "required": false,
        "dependsOn": ["asset_discovery"],
        "parallelSteps": ["database_diagnosis"],
        "condition": "当数据库问题可能与主机CPU、内存、磁盘、网络、进程、日志相关时执行",
        "confirmation": "required_for_risky_command",
        "executionStrategy": "safe_command_only"
      }
    ]
  }
}
```

支持的 `confirmation` 分级：

- `none`
- `required_for_risky_command`
- `required_for_write`
- `required_always`
