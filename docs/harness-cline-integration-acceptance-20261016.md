# Harness / Cline 联调与部署验收（2026-10-16）

范围经用户确认：继续 Harness/Cline 联调验收；SQL Studio 保持未启动，仍需此前全部待办完成并验收。

## 构件与隔离联调

本轮使用[优化计划及实施记录](harness-cline-analysis-optimization-plan-20261016.md)对应的完整 API JAR，SHA-256 为 `4456a99534f5f5d2d6347dddb9e384b4f30db20c6957c31c94555769efdfe39e`。该构件已通过 78 项相关本地测试与完整打包。

独立发布目录：`/opt/harness-cline-integration-20261016`。隔离 Java 使用该 JAR 的原始完整 classes/lib，仅额外编译测试驱动；没有覆盖生产业务 class。复用原隔离 H2 与暂存 Cline 容器，经真实服务端模型和实际 MCP/Python 沙箱验证。

| 场景 | 运行标识 | 结果 |
| --- | --- | --- |
| 数值中位数 | `production-coding-smoke-b22e4130-791d-4798-bf34-63476efdb143` | 输入 4 条，返回 `{"statistic":"6.5"}`；主模型 2 轮、Coding 模型 2 次、Python 1 次 |
| 文本计数 | `production-coding-smoke-e0aae08b-c093-490e-871b-6fe59d444d78` | 输入 5 条，返回 `{"label":"red","count":3}`；主模型 2 轮、Coding 模型 2 次、Python 1 次 |

两例均验证：Coding COMPLETED、resultAvailable=true、reconciliationRequired=false、六项证据完整；主 Harness 消费派生数据，任务目录保留业务校验 NOT_EVALUATED；已完成子任务再次 resume 不增加 revision。

独立验收脚本直接读取 MCP 归档，核对每例 4 个文件的长度与 SHA-256，再从归档输入重新计算期望结果。两个场景通过，详见发布目录 `isolated-archive-validation.json`。夹具、业务算法与预期值只用于验收；Runtime OS 继续使用通用契约与结构校验，未加入业务答案或分支。

两例 bundle：

- `runtime-evidence:coding:1a54632b9b6798c63b0d707e862ec5c765e3e81897ea2218c2b543afc6a8f408:bundle`
- `runtime-evidence:coding:58159c11cb16a03ce5896aec428278439b58466fd96fb3e6a5d2c04c512a7300:bundle`

## 生产部署

隔离联调通过后，确认生产活动任务数为 0，备份原 API JAR，再安装同一完整构件并重启 API。生产 MCP 与 Node 沿用已验收版本。

| 构件 | SHA-256 |
| --- | --- |
| 本轮 API JAR | `4456a99534f5f5d2d6347dddb9e384b4f30db20c6957c31c94555769efdfe39e` |
| MCP JAR | `c49d240ffda0e726dab82f939e076338ead85f07a3127faca1339c604691b7ed` |
| Node 镜像 | `8d8534ee8a9e26f76b8648c1a20fdef7be78d681f717f90f2ad519e084f071d6` |
| 回滚 API JAR | `8b7da1738cf4ef11beb0948f8f382b7185227ec117f4c39d8a93dc899a7aaecb` |

回滚备份位于发布目录 `backup/chatchat-before-harness.jar`，需要时恢复至 `/opt/chatchat-1.0.0-SNAPSHOT/lib/app/chatchat.jar` 并使用既有 `bin/restart.sh`。

部署后 API 登录已通过，历史任务 `69c831bb-732d-3ecb-9b32-2f1b7fe88e1b` 重复提交仍返回 SUCCESS，答案和事件序号 75 未变，Python 执行次数仍为 1。

## 正常入口验收进度

已通过认证后的普通任务入口提交新任务：`cd979da7-897e-3f62-9e60-afe211e70633`，attempt 为 `att-1-2bd99aa7-622d-418d-9c7a-9bf84609dbae`。本段尚待任务完成、独立归档核验及重启后重提验证，不以中间状态宣称通过。

## 门槛说明

本轮验证的是固定输入快照的 Harness/Cline v1。增量补数 v2、活跃 SDK 会话跨进程恢复、历史 provider 超时事实核对、完整 D3/G3 等待办仍分别验收。CPU/费用等计量不完整时继续保留预算预留，不以零费用或本地超时伪造完成。SQL Studio 不因本轮局部验收自动启动。
