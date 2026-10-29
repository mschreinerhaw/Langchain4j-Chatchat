# SQL Studio 发布、Git 提交与磁盘清理记录

## 发布结果

2026-10-29 已将25～27号任务的代码部署到现有生产环境：

- 页面：`http://192.168.195.221:18100/#/w/data-science/sqlStudio`。
- API 镜像：`runtime-os-api:sql-studio-3eb9ab9f99f3-20261029`。
- 完整 JAR SHA256：`2f77411b8e5b83300a8fa355bb749479c789f2275a7879f84325de3e73a7cf36`。
- 构建前暂存源树：`3eb9ab9f99f3a7afead175077fdf3f8dffc7c32c`。此为提交前构建快照，不冒充 Git Commit SHA。
- 采用完整 Maven JAR，验证每一个前端资源的字节内容，再以有 SHA256 校验的 JAR delta 传输完整目标包。远端离线构建，未临时下载依赖。

部署沿用原容器参数、数据挂载、License、密钥及网络限制。只替换生产 API 和依赖其网络命名空间的 Node proxy，Cline Node、MCP、News、OpenSearch、PostgreSQL、Redis 和网关容器未替换。原镜像及 API／proxy 容器保留用于回退。`retired` 中包含由旧发布助手预计算的 Node 名称，但 `nodeUnchanged=true`：本次未创建或替换该 Node 回退容器。

生产使用 `runtime_os_acceptance_api / runtime_os_acceptance_mcp` PostgreSQL；SQL 隔离验收使用 `runtime_sql_metadata_preview_api`，没有混用。发布前真实活跃 Runtime 任务和 Cline 会话均为0，先备份两个数据库及监控数据，再切换服务。没有 Schema 迁移、创建空业务库、重置账号或扩大授权。

## 发布验证

| 检查 | 结果 |
| --- | --- |
| 正式构建 | Maven package 成功；完整 JAR 中所有前端资源与已验证 Vite 构建一致。 |
| 自动化回归 | 前端157项、Java API／适配器111项、MCP元数据12项；详见27号记录。清理保留策略额外3项单元测试通过。 |
| 公网入口静态资源 | 18100 实际返回的 index.html SHA256 与发布包一致。 |
| 正常认证与 SQL 工程接口 | 使用现有已配置服务账号，通过原企业登录；没有改写用户、密码、角色和权限。工程列表及新建受控工程／文件接口通过。 |
| SQL 解释 | 真实生产 generation `48989959-31f5-4b28-9934-3da3faafb4ce`，SUCCESS，文件提案0。 |
| Cline 优化 | 真实生产 generation `5485789c-c578-4531-b07a-df148670ce2b`，SUCCESS，文件提案1；`PARSED; FIELDS_NOT_EVALUATED; SQL_NOT_EXECUTED`。 |
| 真实浏览器 | 三个区域、紧凑控件、180～480宽度、折叠不重建 Monaco、高级设置通过，没有模拟接口或页面 JS 异常。 |
| 原始数据 | 数据库备份可由 pg_restore 读取；原1710条任务、2条SQL工程、1条SQL文件的完整 COPY 行均仍存在且内容未变化。新验收记录另行新增。 |

生产 Cline 验收使用受控常量 `select 1 as original_value;`，提案没有应用或保存，更没有执行业务 SQL；`FIELDS_NOT_EVALUATED` 不冒充真实业务字段校验。真实字段链路在27号隔离环境已验收，两者清楚区分。

### 仍受账号授权限制的生产验收

当前可用的生产服务账号调用既有 MCP 元数据资产接口返回403。保留该拒绝结果，没有放宽权限、伪造成功或将其归因于所有用户功能失效。已向用户请求现有已授权应用账号的安全凭据位置，以完成生产元数据业务验收；当前没有取得新的应用账号。解释和工程生成不要求数据库访问，因此仍使用真实后端正常验收，并明确记录 `noMetadataPermissionBypass=true`。

## 数据与回退

数据库及容器配置备份位于发布目录的 `private/production`，该目录不提交 Git。回退使用：

1. 恢复发布前的 stage Compose、deployment.env 和 node-proxy.conf（均已备份）。
2. 停止新 API／proxy，恢复 `production-deployment.json` 中记录的原 API／proxy 容器名称，按原顺序启动。
3. 重新应用现有 `restrict-coding-worker.sh`，重载网关并检查认证、Node readiness 和回调。

本次没有迁移，正常代码回退不恢复数据库备份覆盖新数据。数据库备份用于事故恢复，不能作为普通代码回退的自动步骤。

## Git 与清理顺序

代码已提交为 `06ef716d4224368392c9d4a047f5e8f82ff3bf1f`，并推送 `origin/fix/runtime-analysis-boundaries-20261028`；没有合并 main。按用户要求，生产部署及验证完成后提交并推送，再实际清理磁盘。另以同一镜像 ID 添加 `runtime-os-api:sql-studio-06ef716d4224-20261029` 标签用于追溯，未重建或重启服务。构建快照与此提交的 `chatchat-api` Git 子树完全相同。

清理脚本 `scripts/cleanup-obsolete-runtime-docker.py` 已完成真实只读规划：当前200个容器、179个镜像，计划移除134个已被替代的停用容器。规则为：

- 保留所有运行容器、其共享 Network／PID／IPC 命名空间依赖、当前发布的明确回退，以及每个已知服务最近一个正常退出的旧版本。
- 保留原名的主动停用服务、未知客户服务及30分钟内的新容器／镜像。
- 仅处理已知 Runtime 旧发布容器和未被容器引用的应用版本镜像；Compose／env 引用的镜像和离线基础镜像保留。
- 不使用 force，不删除 volumes、不执行 system prune，不删除数据库、原始数据、索引、配置、密钥和备份目录。

实际清理已完成：

| 项目 | 清理前 | 清理后 |
| --- | --- | --- |
| 容器 | 200 | 66（移除134个停用旧版本，运行容器仍为16） |
| 镜像 ID | 179 | 51（移除128个旧镜像，合计164个旧标签／引用） |
| 数据卷 | 10 | 10，名称集合未变化 |
| `/opt` 使用率 | 82% | 70% |
| `/opt` 可用空间 | 约34 GiB | 约55 GiB |

容器／镜像清理没有使用 force。旧层仍被构建缓存引用，因此第一阶段实际释放896401408字节；随后通过 `docker builder prune --all --filter until=1h` 清理一小时前的未使用构建缓存，按已授权清理确认输入 `y`，没有使用 `--force`。缓存清理前后全部容器、运行容器、镜像ID和数据卷集合一致，额外实际释放22520291328字节。

合计实际释放 **23416692736字节，约23.4GB（21.8GiB）**，不是 Docker 逻辑镜像大小的估算。保留近期构建缓存、当前／回退镜像、配置引用的镜像、离线基础镜像和未知客户服务。没有删除发布目录、数据库备份、原始业务目录和索引。

清理后再次核对：生产 API 镜像ID与完整发布包一致，原 API／proxy 回退容器仍存在；发布前其余运行容器ID全部一致；Node READY 且活跃会话0；18100首页资源哈希、正常认证和 `/monitor/` 返回正常。数据库原始行再次逐行比对通过。清理错误0。

## 证据

- [发布包清单](28-release-evidence/release-manifest.json)、[容器发布回执](28-release-evidence/production-deployment.json)、[运行服务](28-release-evidence/running-services.json)。
- [数据备份与原始行对比](28-release-evidence/data-preservation.json)、[生产烟测范围及403限制](28-release-evidence/production-smoke.json)。
- [真实浏览器记录](28-release-evidence/production-browser-proof.json)、[生产页面](28-release-evidence/production-workbench.png)、[SQL解释](28-release-evidence/production-explain.png)、[Cline优化](28-release-evidence/production-optimize.png)。
- [清理前规划](28-release-evidence/docker-cleanup-plan.json)。
- [实际容器／镜像清理](28-release-evidence/docker-cleanup.json)、[未使用构建缓存清理](28-release-evidence/build-cache-cleanup.json)、[清理后验证及源代码绑定](28-release-evidence/final-verification.json)。
