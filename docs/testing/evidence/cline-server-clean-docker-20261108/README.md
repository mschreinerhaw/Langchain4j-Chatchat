# 干净 Docker 真实任务证据

阅读入口：[补充验收报告](../../cline-server-clean-docker-tests-20261108.md)、[Docker 构建说明](../../../../cline-agent-server/docker/README.md)。evidence-manifest.json 记录68个导出文件的 SHA256；本 README 和 .gitattributes 是后加索引。保留导出字节，不做换行转换。

- `build-inputs.json`、`build-and-runtime.json`、`image-inventory.json`：固定源/工具镜像、最终 scratch 镜像层、运行配置；工作区原项目 SDK 依赖明确列出。
- `runtime-manifest.json`、`os-files.json`、`source-assets-location.json`：1850个运行资产的 Hash、OS 文件装配清单、descriptor/工具执行器具体源码位置。
- `clean-startup.json`、`docker-health-r1-failure.json`、`build-logs/`：无源码挂载的真实 worker启动、健康检查失败及修复、负向 descriptor、SQLite ABI、Docker build、4项 Linux 单元测试。
- `project-fixture-manifest.json`、`engine-provenance.json`：138个完整 tracked 初始输入、官方引擎派发路径/Hash，以及真实项目 SDK 依赖与 Server 引擎的区别。
- `editor/`、`patch/`：完整 HTTP/原生事件、工具回执、生成文件 Hash/Diff、独立构建/测试结果；`idempotency-and-approval-receipt.json` 对照真实 HTTP、持久化 key 与原生事件计数。
- `search/`：同 native session 的原生搜索；`controls/`：拒绝、晚到审批、真实 Shell 运行中取消与 SSE 重连；`final-clean-smoke.json`：取消35秒后的 marker、进程、OOM、健康及资源 Hash。

events.json.gz / http.json.gz 是压缩 JSON。工具结果与权限输入保留；reasoning、重复序列化 Agent chunk、snapshot 的消息/metadata 已移除，eventId 仍为原值。配置中的模型与操作者密钥已脱敏。原生工具的失败与截断保持原样；native-tool-results 不把结构化失败当成功。独立测试只验证生成成果，不修改或替代 Agent 编码。
