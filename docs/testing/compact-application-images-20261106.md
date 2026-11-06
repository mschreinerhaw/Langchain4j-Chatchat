# API/MCP 镜像重打包与重启验收

2026-11-06，Asia/Shanghai。按用户要求，在验证服务器停止对应容器、切换精简镜像并重启。运行配置、数据挂载及业务 JAR 保留。当前 API/MCP 已恢复服务。

| 工件 | 原镜像大小 | 新镜像大小 | 结果 |
|---|---:|---:|---|
| MCP | 6,183,238,767 B（6.18GB） | 602,698,953 B（603MB） | 已运行 |
| API | 5,472,786,737 B（5.47GB） | 742,776,290 B（743MB） | 已运行 |
| P0-01 API 回滚 | 5,043,973,000 B（5.04GB） | 742,776,026 B（743MB） | 停止态回滚容器已替换 |

## 根因与修复

历史补丁镜像反复 `FROM` 旧业务镜像并覆盖 `app.jar`，旧 JAR 仍保留在祖先层中。最近为绕过缺失基础镜像而使用旧业务镜像的部署辅助路径，也延续了这一问题。本轮纠正该路径：只从不含业务 JAR 的运行时构建，成品必须只有一次业务 JAR COPY，并执行 1200MiB 上限检查；缺失干净运行时即停止，不能回退到旧业务镜像。

从原 MCP 镜像的不可变归档中恢复 Java/SQLGlot 依赖层前缀，验证源 image config 的 SHA-256 与原镜像 ID 一致。只保留已验证前缀的 layer diff IDs 和运行时环境变量，排除业务 JAR、业务入口及容器配置。恢复过程没有导出运行容器的文件系统，也没有拷贝其数据挂载。新基础镜像为 `runtime-os-api-runtime:17-sqlglot-20261024`，314MB。

Java 仍为 Temurin 17.0.20.1+1，SQLGlot 仍为 30.23.0。API、MCP、回滚 API 的 JAR 原字节 SHA-256 不变；MCP Docker CLI 的 SHA-256 也不变。无依赖下载安装或业务源码发布。

初次构建的基础镜像带有运行时保护标签，导致业务镜像继承保护标记；首次完整配置比对明确失败，错误日志保留为 `verification-inherited-labels-attempt1.log`。已去掉基础层新增标签，修正并重新切换成品，最终标签、配置比对和运行验证通过。首次候选、部署及备份文件单独保留，不用最终成功覆盖失败证据。后续 3 天清理规则和关键运行时保护继续有效。

## 最终镜像身份

- API：`runtime-os-api:compact-ee2a057dd3d6-20261106`，`sha256:4c0880d5d2cce6eef6d6689252a20792e7e1f6f5de4354b60ed99e141a6be4ce`。
- MCP：`runtime-os-mcp:compact-3958cbcddb58-20261106`，`sha256:0f19f187f85c8f033fbbbcc8f62401f24a2e444fc1213084a8a4b236419fe72c`。
- API 回滚：`runtime-os-api:compact-rollback-261477bcfaeb-20261106`，`sha256:cde676a45f17806a42b911bb0c97bdaeb9a0914f4f93a2b1dfedb3249508bf97`。
- API JAR：`ee2a057dd3d693f80a904b6487dd58bab578ae849b894af2d724e50de9ed3401`。
- MCP JAR：`3958cbcddb58937a27809d7cbf1d9c1c1ad3b0a264e02b4747ee38ea3060c991`。

Compose 与 deployment.env 已同步成品标签，后续重建容器会使用精简镜像。

## 验证与回滚保护

3 项依赖层边界回归通过，包括排除业务层、拒绝缺失固定 SQLGlot 边界、保留原 diff IDs 且不继承保护标签。候选在真实 Docker 中检查 JAR、Docker CLI 摘要、单 JAR 层和体积，全部通过。

停止服务前检查无活动任务/沙箱，并备份数据库与配置。最终验证确认 API/MCP 环境、入口、标签、挂载、资源限制和端口保持原值；会话内容及消息索引数量保持不变。关联代理已重启到 API 的新网络命名空间，Worker、数据库等其他服务保持原容器。

实际运行验证通过：登录/应用就绪、SQL 解析 `SELECT 1`、API 到 MCP gRPC 端口连通、39 个 MCP 工具注册为 HEALTHY、签名模型桥可用且无签名请求被拒绝。通过 MCP 容器的原 Docker CLI 执行隔离的 Python 中文字体 PNG/SVG 检查，通过；字体镜像摘要仍为 `sha256:3434f7630ff7c54a053c7385710e089d249e65d668699ab96f9603b6e1b27eef`。

真实浏览器报告和 Monitor 检查通过：现有报告正常渲染、断网刷新后回执保持，三次既有故障任务的权威终态和事件历史保持。此轮维护验证未提交模型推理任务，不等同于 Phase 3 全量故障/质量/性能验收。

原三份大镜像已保存到服务器私有目录 `/opt/chatchat-releases/compact-application-images-20261106/original-images.tar`，11,575,006,720 B，SHA-256 `05dc975e91b0aafa8e664591f5aed47b0c94f737a9085c5b843164c044aff65c`。原容器完整配置和数据库备份保存在同一发布目录的私有工件中。摘要再次核对通过后，停止态 API/MCP 回滚容器及其命名空间代理已替换为精简等价容器；原三份大镜像按精确 ID 无 force 移除。保留运行容器、数据卷和其他镜像，不执行全局 prune。没有进行实际业务回滚切换。

## 可复核证据

- [候选、依赖层身份与 JAR 摘要](../evidence/compact-application-candidates-20261106.json)
- [实际部署](../evidence/compact-application-deployment-20261106.json)
- [运行配置、工具注册、字体与模型桥](../evidence/compact-application-verification-20261106.json)
- [回滚容器替换及三份大镜像移除](../evidence/compact-application-retirement-20261106.json)
- [报告浏览器检查](../evidence/compact-images-report-browser-20261106.json)
- [Monitor 浏览器检查](../evidence/compact-images-monitor-browser-20261106.json)
