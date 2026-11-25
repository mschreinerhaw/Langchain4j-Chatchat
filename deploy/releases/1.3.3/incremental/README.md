# Runtime OS 1.3.3 离线增量更新

本包由本地 Langchain4j-Chatchat 源码 `f7c093a1c` 构建。源版本和镜像身份见 `offline-images/BUILD.json`。

## 更新内容

- API 与 MCP 更新 Native 分析任务的上下文、业务模板参数与数据供给。可选模板供给支持原有配置继续按 Required 行为运行。
- Native 组件使用 Cline 4.1.27，包含分析交付校验、归档上限和离线 ECharts 报告技能。
- News 更新 Temporal 采集调度。
- 只交付 API、MCP、News、Native 四个变更镜像及一条 PostgreSQL 增量 SQL。沿用现有数据库、数据卷、部署配置、模型映射与登录凭据。

## 更新命令

适用于 Linux amd64、Docker、Bash 和 Python 3.8 或更新版本。脚本依次检查 `docker compose`、独立的 `docker-compose` 和包内 Compose。先在维护窗口暂停入口流量与后台调度，等在途任务结束。把整个更新目录复制到目标机。

```bash
(cd offline-images && sha256sum -c SHA256SUMS)
sudo bash update-config/update.sh check --root /opt/ros21
sudo bash update-config/update.sh apply --root /opt/ros21
```

支持 Runtime OS 1.3.2 标准 PostgreSQL 安装。对使用外置 PostgreSQL 容器的已知源码部署，给上述两条命令追加实际容器名，例如 `--postgres-container postgresql18`。脚本依据 Compose 服务和镜像校验适用性。首次部署继续使用对应的全量安装包。

`check` 读取实例和数据库，打印各组件的镜像更新计划。`apply` 校验归档与镜像身份，备份现有 Compose 配置和 MCP 数据库，执行可重复运行的增量 SQL，再按 API、Worker 和入口的依赖顺序重建容器。升级结果以服务就绪和镜像 ID 校验为准。执行时会暂停网关入口。

要查看已有标准安装的初始管理员密码，可继续用原安装介质的 `sudo bash install-config/install.sh password`。

## 回滚

更新阶段发生错误时会尝试恢复原应用镜像及 Compose 配置。需要手工回滚时，使用 `apply` 打印的绝对备份目录。

```bash
sudo bash update-config/update.sh rollback --root /opt/ros21 --backup /opt/ros21/.runtime-updates/<回滚目录>
```

外置 PostgreSQL 实例仍传入相同的 `--postgres-container`。回滚保留已写入的业务数据，以及兼容旧版的新增可空列。备份目录包含私密配置和数据库快照，限管理员访问。
