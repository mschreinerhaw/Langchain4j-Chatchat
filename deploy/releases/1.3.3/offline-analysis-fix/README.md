# Runtime OS 离线分析修复 20261125

适用于 `runtime-os-1.3.2` 介质目录交付的 Linux amd64 标准安装。API 和 MCP 增量适配 1.3.3 `f7c093a1c` 发布基线，以及 API 已应用同目录 Native 启动恢复或离线执行器修复的实例。脚本按原 JAR 的 SHA-256 选择补丁。

- API 在独立 Executor 确认旧 Worker 停止并封禁其分配后，允许符合条件的分析任务用原输入重跑一次。
- MCP 按确认的数据源授权范围检查固定技术 SQL 模板的完整性，避免同批其他模板超出范围时挡住已授权的 InnoDB 查询。
- Worker 检查专用 Docker 中固定的任务镜像。Shell 探针失败时，使用该镜像离线构建修正版，将容器内 `runner` 的默认 Shell 设置为 `/bin/bash`，验证 UID/GID 与 Python 的 XLSX 支持，并更新固定镜像 ID。

## 安装

在维护窗口暂停入口流量和任务调度，等在途任务结束。将压缩包和同名 `.sha256` 复制到客户机，在文件所在目录执行：

```bash
sha256sum -c runtime-os-1.3.3-offline-analysis-fix-20261125.tar.gz.sha256
tar -xzf runtime-os-1.3.3-offline-analysis-fix-20261125.tar.gz
cd runtime-os-1.3.3-offline-analysis-fix-20261125
sudo python3 apply.py --root /opt/ros21 --check
sudo python3 apply.py --root /opt/ros21
```

用现场安装路径替换 `/opt/ros21`。预检会检查 Compose、基础 JAR 和实际任务镜像，并启动用完即删除的 Shell 探针容器。安装使用本地镜像，备份原 Compose 与涉及的 Native 配置，再重建受影响的服务。脚本会观察服务的稳定性，切换失败时尝试恢复备份。原镜像保留，数据库和数据卷继续沿用原部署。

仍在使用原始 1.3.2 应用镜像的客户，可以先在上述两条 `apply.py` 命令加 `--worker-only` 修复 Shell。完整 API/MCP 修复需先升级到同目录提供的 1.3.3 版本。若专用 Docker 缺失原固定 Worker 镜像，先用同目录的离线执行器修复包恢复它。

## 验收

安装完成后重新发起分析任务。确认 `run_commands` 能执行命令，报告能读出实际工作簿的列名和行数，再核对指标及图表是否来自实际单元格。需要手工回滚时，使用安装输出的备份目录恢复其中的配置文件，再用原 Compose 重建相关服务。备份目录可能含私密配置。
