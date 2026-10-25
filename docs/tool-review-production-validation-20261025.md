# Tool reviewer 生产发布与验证

2026-10-25 已完成生产发布及真实只读任务验证。应用源码为 `9b02cfca1`，分支 `feature/tool-review-performance`，未修改主分支。生产入口为 `http://192.168.195.221:18100`，发布控制面为 `http://192.168.195.221:18080/#/ops/stacks`。

## 发布结果

- 发布包：`release-20261025123929`；通过实际 Vue 页面发起的部署任务 `b21ebd6c-4e57-4655-a928-8157dcafba6c` 状态为 `SUCCEEDED`。
- API 镜像：`localhost:18443/runtime-os/api@sha256:268aba79fb6f7ce25e8a6f7c0b2c4cba7ece7dedbf7668c96229cd7c9d84d74a`。
- API JAR SHA256：`e7a132d3f3904d5abcc7c35deff2dad0178ab6cdeeb5efe0c4aa8f9ddb35beab`。
- API 使用干净的既有 Java 运行时底座构建，不从旧业务镜像叠加 JAR。API Vue 及 Maven 依赖打包成功。
- 7 个应用容器由平台重建，仅 API 镜像内容改变；其他 6 个服务镜像不变。共享 PostgreSQL、Redis、OpenSearch 及外部授权服务未重建。
- 最终 7 个服务健康，异常容器数为 0，维护模式已关闭。环境、挂载、只读配置与密钥内容、权限和模板绑定指纹、非目标容器均校验通过，无数据库结构迁移。

## 真实任务

复用历史成功任务的同一 InnoDB 只读分析问题，在独立会话运行 `db_ops_assistant`。使用现有页面发布的模型 `qwen3.7-plus`，发布前后绑定一致，未通过切换模型获得结果。

任务 `9f2c0d5d-4437-3de8-90d7-4982a3b7463a` 最终 `SUCCESS`，观测总耗时 **321,649 ms（约 5 分 22 秒）**，生成 3,388 字符答案。3 次工具结果均为成功；其中 SQL 批次 3 项全部成功，失败、阻断、跳过均为 0。答案包含 InnoDB 与定量指标，未出现此前的执行或参数绑定失败信息。

| 阶段 | 耗时 | 模型输入字符 / 估算 token |
|---|---:|---:|
| 第一次工具结果审查 | 28,733 ms | 8,679 / 2,490 |
| 第二次工具结果审查（包含后续子阶段） | 62,387 ms | 14,828 / 4,204 |
| 独立覆盖检查（第二次审查内） | 17,338 ms | 12,470 / 3,692 |
| 受控参数绑定（第二次审查内） | 27 ms | 既有空 schema 确定性路径，无额外模型调用 |

历史成功任务 `91766347-97f0-4795-b079-4e4052ba8df2` 总耗时约 17 分 35 秒，两轮审查观测耗时为 701,303 ms、102,948 ms。该历史任务的实际模型未确认，数据快照、服务商负载与运行时刻也不同，因此这些是运行观测，不能作为严格 A/B 或承诺固定加速比例。

生产浏览器已登录并加载任务页及新版阶段展示资源，控制台未发现错误。完整原始工具结果和登录凭据只保留在忽略的私有目录，不随证据提交。

## 校验、备份与边界

`scripts/verify-live-monitor-takeover.py` 增加显式升级批准文件，按服务验证不可变镜像及 JAR SHA256，其余服务继续要求不变。真实升级核验通过；将批准的 JAR SHA256 替换为错误值的只读反例被拒绝，未改变生产状态。

平台备份位于 `/opt/live-monitor-managed-releases/agent-production/backups/stack-b21ebd6c-4e57-4655-a928-8157dcafba6c`，包含数据库逻辑备份及静止写入后的文件副本，19,381 个文件通过校验。原数据、旧镜像及发布配置保留用于受控回滚；本次没有执行破坏性数据恢复，不能将备份校验等同于完整恢复演练。

发布前 Java 定向回归 38 项、前端 73 项通过；此次生产构建完成 API Vue 和 Maven 打包，随后执行上述真实任务、浏览器及服务校验。旧 `AgentOrchestratorTest` 仍有 74 个既存失败/报错测试名，不能宣称全仓测试通过，见 [离线记录](tool-review-performance-20261025.md)。

脱敏证据见 [tool-review-production-evidence](tool-review-production-evidence/)。`prepared-public.json` 是准备阶段快照，其 `applications_restarted: false` 不代表后续未部署；最终结果以部署验收及 `final-state.json` 为准。
