# 图文报告检查状态及历史展示修复

## 联机定位与差异分析

截图对应父任务 `37a99a30-ddce-441e-a43a-a72cffcfa2ef`，Run `att-1-5b4b638a-1c7c-42c8-b238-906f2b6e0d8d`。原 Python 实际执行两次并归档：第一版检查含 PASSED/FAILED，第二版含 PASSED/UNRESOLVED。第二版保留五张图和 1,474 字符正文，但 VisualReportArtifacts 仅接受 PASSED/FAILED，触发 `Invalid computation check`，降级结果还遗漏了正文。

执行成功、产物协议有效、业务检查通过是不同状态。UNRESOLVED 代表尚无足够证据解决问题，不能强制改为 PASSED。复用原 Python Sandbox、ExecutionFeedback、Cline 上下文、任务事件、PG/Silo 归档及图文组件；不重新建设执行框架。

## 改动

- 公共报告生成指令明确 PASSED、FAILED、UNRESOLVED 三种检查状态。UNRESOLVED 使 SUCCESS 降为 PARTIAL，保留图、指标、正文和原检查说明；FAILED 仍使报告失败。未知值依然拒绝。
- 新增 artifactValidationStatus，区分技术协议不合法和业务检查不通过。技术失败反馈 VISUAL_REPORT_INVALID / OUTPUT_CONTRACT，原修复次数、预算、授权与同任务上下文继续生效；不把业务不确定性当成可通过改标签解决的错误。
- 报告降级仍保留经过路径脱敏和长度校验的正文。Java 记录拒绝任务、执行 ID 和异常类型，不输出客户数据或异常原文。
- 既有任务监控增加所有者校验的归档报告展示校验接口。核对 task/run、执行停止证明、代码哈希、结果哈希、manifest 哈希及每份产物哈希后，以当前展示校验器生成视图。不重新执行 SQL/Python/模型，不改写原任务、原代码、检查点和原证据。原图资产继续通过既有幂等归档接口提供引用。
- 历史旧版失败报告由组件根据已授权代码资产找回 run/task，读取重新校验视图；客户端再次匹配原 task/operation/code/result 身份。失败或越权时保留原失败提示，不伪装成恢复成功。
- 前端将 UNRESOLVED 显示为“尚未解决”，与“检查失败”和“分析程序检查通过”区分。

## 验证方式与边界

本地契约、H2 证据重放、策略及组件测试分别记录；它们不代替服务器真实 PG/Silo/MCP 归档验证和浏览器验证。历史报告重新校验限单行、结果 1 MiB、图产物单份 8 MiB、总 64 MiB。检查声明来自原 Python 程序，结构与文件完整性校验不认证业务结论。原第一版 FAILED 及第二版 UNRESOLVED 均保留。

## 部署及实际结果

实现提交 `a09051947`，完成门禁补充提交 `64849e712`，发布分支 `release/1.2-20261022`。部署于原服务器 `192.168.195.221:5221`，仅替换 API 镜像；配置、挂载与其他业务容器保持。发布目录 `/opt/chatchat-releases/1.2.0-20261022-visual-check-repair-r2`，备份引用在该目录的 `evidence/deployment.json`。

API 镜像 `runtime-os-api:1.2.0-20261022-visual-check-repair-r2` 为 743,682,233 字节，仍为一个应用 JAR 层。JAR SHA-256：`15cd9e9320557258100bbb6f5a606c3910ef6f9754db329072505a0008f1725f`。

| 验证范围 | 实际结果 |
|---|---|
| Java 相关回归 | 59 项通过，0 失败、0 跳过，包括原证据重放、manifest 篡改拒绝、跨用户拒绝、技术反馈及禁止 SDK 绕过完成门禁 |
| 前端相关回归 | 13 项通过，Vite 构建通过 |
| 原任务第一版归档 | 仍为 FAILED，3 项 PASSED、2 项 FAILED，保留 4 张图及 825 字符正文 |
| 原任务第二版归档 | PARTIAL，4 项 PASSED、2 项 UNRESOLVED，保留 5 张图及 1,474 字符正文 |
| 原证据与任务 | 19 条原证据 ID/revision/hash 未改变，原任务时间线与用量完全相同；没有新增模型/Python 执行或客户源查询 |
| 真实浏览器历史页 | 登录原产品，打开原会话，自动重新校验成功；五张图均解码成功，正文可见，两项检查显示“尚未解决”，无页面脚本异常 |

此外尝试了合成附件的真实模型故障修复演练：首轮测试描述触发现有多工作流规划保护，未进入 Coding；简化目标后的第二轮因模型响应被拒绝而终止，只发生一次模型调用，未进入 Python。两轮均不计为修复闭环通过，也没有调整预算或绕过授权/工作流。相应新技术反馈与最终门禁由本地测试覆盖；本次不能宣称新场景已经完成真实模型自动修复验收。

原会话现在刷新即可恢复展示。两项实际业务勾稽问题仍是“尚未解决”，本修复没有把数据问题改成通过，也没有重算客户交易数据。机器可读结果见[验收记录](evidence/runtime-visual-check-repair-20261022.json)。重新校验是已归档文件的展示投影，原始失败证据仍可审计；页面显示恢复不意味着这些未决业务问题已经解决。

## 增量部署包

服务器文件 `/opt/chatchat-releases/chatchat-1.2.0-20261022-visual-check-repair-hotfix.tar.gz`，8,481,131 字节，SHA-256：`44a9acd1a0751bef75ac4581335934437f281ae70506591c4907e4b03ff99e29`。

此包基于已经验收的 ASCII-recovery 安装及其 API JAR（SHA-256 `889be8ef5f7e59bd75f86f243d21f25538d6cf2a052f43e71bd285180ce8424c`），不是空机器首次安装包。包括 API 差分、部署工具、Docker 构建文件、文档和脱敏验收记录；不包含客户报告、截图、凭据、私有配置或基础镜像。

解包至 `/opt/chatchat-releases/` 下的新发布目录，按照包内 INSTALL.md 校验文件、重建 JAR、构建和部署。部署拒绝活动任务，并备份配置和持久状态。已真实解包校验所有清单哈希，由包内差分重建出与线上一致的完整 JAR。[包验收记录](evidence/runtime-visual-check-repair-package-20261022.json)单独保存；回滚命令在 INSTALL.md 中。
