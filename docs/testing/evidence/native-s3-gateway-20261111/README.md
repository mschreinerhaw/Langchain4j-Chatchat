# 可分享的验证证据

这些是通用 Native/S3 服务的**合成数据执行、协议及取消验证**。
不是正式 Java HTTP/SPI、真实 Silo、客户账户或 A–H 全部业务验收。
生产默认节点未切换，全部记录的 `formalBusinessAcceptance` 保持 false。

- `verification.json`：15 个 v5 Session 的结果、失败、provider 请求与 Token、真实修复事件索引和容器隔离配置。
  `generic-fixed-*` 为第一轮（4/5）；`generic-protocol-v2-fixed-*` 为实际协议反馈修复后的新队列（5/5）；`cancel-fixed-*` 为真实 Python 取消队列（5/5）。
- `repair-event-excerpts.json`：从原生 journal 提取的真实 traceback、代码修改、重跑与清单拒绝；附原 journal SHA-256。未重写原始事件。
- `artifacts/`：新队列 37 个原始源码、辅助 CSV、报告、结果和图表。下载逐个校验大小与 SHA-256，未人工修改。
- `independent-report-and-source-review.json`：独立 CSV/Decimal、源码 AST、报告数值与舍入检查，以及五张原始图表的视觉复核。
- `independent-source-replays.json`：首版复跑 4/5；第 5 次选择了未交付的临时校验脚本，失败证据保留。
- `independent-source-replay-declared-source-v2.json`：修正选择器后，在独立新容器复跑五份交付源码，所有报告、结果与 PNG 逐字节一致（5/5）。没有复制旧输出或 Core 状态，没有新模型请求。
- `python-environment.json`：实际镜像中 181 个 Python 已装包、Python/SQLite/libc 版本。
- `python-environment-container.json`：只读、断网环境验证容器的实际退出记录。
- `build.json`：实际构建的镜像 ID、固定基础镜像 ID、官方提交与构建来源哈希。
- `oci-export.json`：OCI artifact Manifest Digest；未发布为 registry RepoDigest。
- `node-tests.log`：Linux 全部 93 项测试通过的完整日志。包括协议夹具，不能用作模型业务成功证明。
- `service-status.json`：最后探测时新服务 200；Silo 9000、应用 18100 仍连接拒绝，用户确认正在恢复。

这里的 CSV 由 Cline 按明确的合成测试目标创建并作为 AUXILIARY 交付，不能称为 Java 上传的真实授权数据快照。
原始模型请求／响应、完整 SSE/Native journal、失败容器和此前两次先导运行保存在授权服务器，未放入本目录。
