# 通用 Coding Agent 接入与后续扩展边界

本入口与原业务 `python_execute` 路径并存，默认关闭。通用验收未全部通过，暂不进入金融、SQL Studio 或知识探索集成。SDK 固定 0.0.93，不混用官方主线接口。

## 独立项目合同

认证后 `PUT /api/v1/coding/projects/{requestId}` 创建任务；requestId 由调用方固定，租户和用户只取服务端认证身份。同一编号重试必须保持原始需求、文件、测试命令、模型、编辑策略和 Skills 绑定；不会刷新 deadline 或重置预算。

```json
{
  "instruction": "修复该项目并运行已有测试，交付代码和实际结果。",
  "files": {
    "calc.py": "def add(a, b):\n    return a - b\n",
    "tests/test_calc.py": "import unittest\nfrom calc import add\nclass TestCalc(unittest.TestCase):\n    def test_add(self):\n        self.assertEqual(add(2, 3), 5)\n"
  },
  "readOnlyPaths": ["tests/test_calc.py"],
  "acceptanceCommand": "python -m unittest discover -s tests -v",
  "editStrategy": "auto",
  "skills": []
}
```

`modelName` 可选择已有授权模型；省略时冻结现有默认模型及其公开连接指纹，不修改共享配置。预算及环境由 Runtime 固定，客户端不能提交宿主目录、Docker 参数、网络、任意 MCP 地址或执行器版本。文件限额及可移植路径规则见 [工作区设计](native-coding-agent-20261107.md)。只读测试由 Runtime 校验，模型不能通过更改测试制造成功。

## 编辑、执行与证据

SDK 自主决定 read_files、search_codebase、Editor 或 Patch 及 run_commands 的顺序。编辑可以连续发生而不执行；执行先冻结当前树并校验模型真实工具参数、版本和 Hash，再由既有隔离 Sandbox 运行。Java 不制定修复步骤，也不把检查命令退出 0 直接改写成项目成功。

现有接口：

| 接口 | 用途 |
| --- | --- |
| `GET /api/v1/coding/projects/{taskId}` | 权威状态、累计预算、停止与待核对状态 |
| `GET .../{taskId}/files` | 当前持久源码及 manifest |
| `GET .../{taskId}/history` | 不可变版本与父子关系 |
| `GET .../{taskId}/diff/{version}` | 已归档原生修改结果 |
| `GET .../{taskId}/events` | 最近事件；完整证据留在现有证据库 |
| `POST .../{taskId}/resume` | 原任务、原 deadline、原累计预算恢复；未知执行须先核对停止 |
| `POST .../{taskId}/cancel` | 请求停止，不能替代执行节点的独立停止证明 |
| `GET /api/v1/interactions/user-assets?taskId=...` | 原有代码和执行产物目录，受用户与租户权限控制 |
| `GET /api/v1/interactions/user-assets/{assetId}/content` | 再读取归档原字节并核对 Hash |

`validationStatus=INCONCLUSIVE` 与 SDK completed 是不同层次。产品不能把该状态展示成通过全部独立验收；完整交付还须检查原需求、只读测试、额外约束、版本匹配、停止、归档及再次读取。

## Skills、MCP 和业务插件

已发布 Skill 使用原有导入、发布、授权及撤回体系，任务冻结 `{id, agentId, version}`；SDK 负责原生发现和按需读取，逐次读取仍复核当前访问资格。读取 Skill 不授予其脚本的宿主执行权。实际发布、长任务及撤回的整链验证仍有未通过项，不能用组件测试代替。

Node 的 MCP 扩展使用 SDK `createMcpTools` provider，生产 Java 动态工具治理绑定尚未完成。独立项目 API 当前不开放任意服务器地址或工具注册。后续业务插件须由 Runtime 在模型调用前授权并冻结资源及 Schema，执行时再次校验访问权；不允许通过 run_commands 联网或直连数据库绕过这一入口。

业务输入与报告可作为已有 Silo 产物及授权资源接入；不得让业务场景重新决定编辑协议、修复步骤或新增 Agent Loop。需要网络的扩展走 Runtime 的专用资源/工具治理，继续保留本代码执行环境的 NONE 网络边界。

## 开关及回滚

开关、冻结策略及排空顺序见 [架构设计](native-coding-agent-20261107.md)。回滚先停止新准入，维持已有任务的恢复、取消、结算和归档读取；所有执行独立停止后再回滚三端。禁止删除 Silo 历史、清零预算或把未知执行当作已停止。旧业务入口与原修复协议保留。
