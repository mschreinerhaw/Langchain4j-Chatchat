# Docker 关键镜像保护修复

2026-11-06，Asia/Shanghai。按用户要求修改并安装到验证服务器 `/opt/cleanup_docker_images.sh`；源码在 `scripts/cleanup_docker_images.sh`。

原脚本删除全部未运行容器，再使用 `docker rmi -f` 删除未被运行容器引用的镜像。临时 Python 沙箱、闲置基础镜像和停止的回滚容器均可能被该规则清理。这里只确认规则风险，不能据此认定之前工件缺失的执行来源。

新规则：

- 保留全部容器及其引用镜像，包含停止、暂停、重启及回滚容器。
- 保护部署 Compose/config 中引用的镜像名、摘要和 ID；配置缺失或不可读时停止清理。
- 保护 Python、构建基础镜像、基础设施、回滚/baseline/offline-parent 标签，以及清理白名单之外的任意镜像别名。
- 只考虑指定 Runtime OS 应用仓库中超过 7 天的闲置镜像；每个应用仓库至少保留最新两份。
- 默认保留来源不明的无标签镜像；只有显式带 `com.chatchat.cleanup=true` 的无标签镜像才可能成为候选。`com.chatchat.cleanup.protect=true` 始终保护。
- 删除前重新读取全部容器、配置、镜像信息；读取失败即停止。仅按 ID 无 force 删除，由 Docker 继续保护并发引用、多标签及被子镜像依赖的镜像。
- 不清理容器、卷、网络或构建缓存，不调用 prune。

默认预览：

```bash
/opt/cleanup_docker_images.sh
/opt/cleanup_docker_images.sh --dry-run
```

查看候选后可显式使用 `--execute`。可通过 `--min-age-days 14` 增加保留天数，最低一天；`--keep-file /path/to/keep.txt` 添加每行一个镜像名/摘要/glob 的额外保留项。若 `/opt/cleanup_docker_images.keep` 已存在则自动读取；额外保留项不能关闭内置保护。`--config-dir` 默认 `/opt/runtime-os-docker-stage`。

验证：本地离线容器和服务器分别运行 7 项 fake-Docker 安全回归，全部通过，包括闲置 Python/基础镜像、多标签别名、停止容器、配置引用、默认只预览、并发引用/配置变化、读取失败及缺失保留文件。另通过 `bash -n` 和服务器真实 Docker 清单 dry-run，15 个容器和 15 个不同镜像 ID 均未变化，四个当前关键镜像均明确标记保护。没有执行真实清理。

原脚本保存在 `/opt/chatchat-releases/cline-native-phase3-20261106/docker-cleanup-safety-20261106/cleanup_docker_images.original.sh`，替换前后 SHA-256、实际镜像保护和预览结果见[安装证据](../evidence/docker-image-cleanup-safety-20261106.json)。
