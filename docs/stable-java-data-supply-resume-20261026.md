# 续接标注：Java 稳定供数修复

暂停时间：2026-10-25 18:36（Asia/Shanghai）。按用户要求暂停，待 2026-10-26 用户继续时恢复；不自动部署。

## 当前代码与生产状态

- 开发分支：`fix/stable-java-data-acquisition`。
- 最新功能代码提交：`9877a9012`，已提交，**尚未部署生产**。
- 生产实际版本：`715e09f92`；受管发布任务 `3167a9ff-8366-4449-9d40-953c93d8b90f` 成功。
- 该次发布验证了 7 个服务健康、19,516 个备份文件、41 个无关容器未改变。
- `9877a9012` 的标准打包在用户要求暂停后主动停止，**没有完整 BUILD SUCCESS，不能作为合格发布包使用**。明天必须重新构建 API 和 MCP，避免混用 target 中不同版本的 JAR。
- 服务器上 `release-20261025-stable-supply-9877a9012` 目录仅准备了 Dockerfile 和发布辅助脚本；尚未上传该版本 JAR、构建镜像或执行生产发布。
- 未合并主分支，未强制推送。

## 已修复内容

1. 原任务询问 Docker 镜像和容器，但 Agent 必选工具只发布了 8 个 Kubernetes 模板。模型拒绝这些候选是正确的。已增加正确的 Docker 只读供数发布，保留旧工具，将无关工具改为可选。
2. 明确发布 `mcp.data-supply.v1` 契约的固定无参供数由 Java 校验并生成执行批次，不再依赖模型逐条批准。未声明的工具、参数化模板和权限校验仍保持原有约束。
3. 修复计划优化器误删资产阶段、用户通用依赖覆盖发布工具供数顺序的问题。
4. 审查阶段设置 60 秒预算，区分“审查不可用”和“供数范围不足”。
5. 最新 `9877a9012` 补上双阶段工具按实际返回阶段识别角色、完整规范资产名称的确定性解析，以及注册表在权限查询期间更新时重算必需工具缺口。

禁止增加具体主机、业务名称、模板 ID 或模型名称的 Java 分支判断；不得通过关闭全部审查或执行无关模板掩盖供数失败。

## 已完成的验证及限制

- 相关回归：Agent/runtime **301** 项、聊天工具策略 **14** 项、发布契约 **15** 项，共 **330** 项通过。
- 最后补充身份大小写冲突和否定/排除语句边界后，相关 **24** 项测试再次通过。
- 直接经过生产 Runtime API，按资产发现 → 发布模板查询 → SSH 执行，8 个只读模板全部成功：退出码 0、完整结果，共 31,274 字节真实输出。
- 上述直接供数验证不等于完整 Agent 报告成功。
- 任务 `39916241-8689-32bf-b405-c92c02d9eec5` 在冷启动本地注册表就绪前结束为 `NO_PRESENTABLE_RESULT`。
- 后续任务 `6c2eb79c-72ed-32fe-a91f-6d532d231832` 已通过计划校验，但资产模型审查在 60,012 ms 超时，最终失败。最新补丁正是针对这两处问题，**线上完整链路尚未验收通过**。
- 扩展回归中有 3 项 `CommandTemplateDiscoveryDatabaseQueryTest` 失败，已在未修改基线 `1f7bdadd3` 复现，详见主分析文档；不能宣称全仓测试通过。

## 明天继续顺序

1. 检查 Git 分支和工作区，保留新增修改；检查生产实际活动版本是否仍为 `715e09f92`。
2. 重新执行标准构建：

   ```powershell
   mvn.cmd -pl chatchat-api,chatchat-mcp-server -am package "-DskipTests" "-Dfrontend.install.skip=true"
   ```

   确认整体 `BUILD SUCCESS`，重新计算两个 JAR 的 SHA-256。前端无代码变更；上述选项只复用依赖安装，仍执行前端构建。
3. 沿用受管发布流程：校验活动版本、构建并推送不可变镜像、检查部署包、停机备份、发布、健康检查、失败回滚；不要手动覆盖生产 JAR。
4. 使用原问题复测 `server_ops_assistant`：`分析 Docker 数据库模拟服务器 管理的镜像和容器运行情况`。提交前确认本地 MCP 注册工具已就绪，不能只检查远程目录可见。
5. 核对资产解析审计 `RUNTIME_EXACT_CANONICAL_ASSET_REFERENCE`、基础供数审计 `RUNTIME_PUBLISHED_DATA_SUPPLY`、真实执行结果和业务证据。不能将仅发现模板、服务健康或模型返回文字当作采集成功。
6. 等待并检查完整任务结果，记录失败的真实原因；更新验收记录后再宣称修复完成。

## 本地续接材料

详细分析：[stable-java-data-supply-20261025.md](stable-java-data-supply-20261025.md)。

以下为被 Git 忽略的本地验证材料，包含私密会话或运行数据的文件不得提交、打印或复制到交付文档：

- `target/stable-supply-endpoint-regression.log`：330 项回归结果。
- `target/stable-supply-canonical-final-tests.log`：最后 24 项边界回归。
- `target/stable-supply-canonical-package.log`：本次未完成构建。
- `target/canonical-build-stable-supply-release.py`、`target/canonical-deploy-stable-supply.mjs`：待用发布辅助脚本，部署前须重新检查版本与校验值，不能盲目重跑。
- `target/canonical-verify-stable-supply-task.mjs`：待用线上验证脚本，含本地注册表就绪检查。
- `target/final-stable-supply-deployment-public.json`、`target/final-stable-supply-running-verification-public.json`：生产 `715e09f92` 发布与运行校验证据。

构建产物、认证材料和私有任务快照全部保留在忽略目录；不要执行仓库根目录的 `mvn clean` 清掉续接材料。
