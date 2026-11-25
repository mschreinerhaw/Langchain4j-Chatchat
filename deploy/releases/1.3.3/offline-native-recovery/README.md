# Runtime OS 1.3.3 离线执行器分配修复

适用于使用 `f7c093a1c` 源码镜像的 Runtime OS 1.3.3 安装，也可在上一版离线启动恢复补丁之上安装。支持 Python 3.7.9。使用本机镜像离线构建，专用执行 Docker 须已运行。

```bash
sudo python3 apply.py --root /opt/ros21
```

如果安装在 `/opt/ros133`，替换 `--root` 参数。执行前请结束当前任务。可以加 `--check` 预检专用 Docker 和固定 Worker 镜像。脚本校验专用 Docker 的身份和目录后，从宿主 Docker 离线同步缺失的同一镜像，随后复核镜像 ID。两侧都缺少镜像时，从原离线安装介质导入镜像后重跑。已应用上一版恢复补丁的系统仅更新 Native 控制服务和执行器。初次安装也会更新 API 及其关联服务。脚本会保存原 Compose 配置。

执行器现在会在创建任务工作目录之前检查固定镜像，避免镜像缺失时遗留未完成的任务目录。补丁也包含原生模型绑定凭据和启动恢复的上一版修复。

完成后，用 `qwen-3.6-plus` 新建任务，在网关「请求日志」核对对应请求。如果镜像正常仍返回 503，执行器审计文件 `n/1/control/executor-audit.jsonl` 或 `n/2/control/executor-audit.jsonl` 中对应时间的 `request.rejected` 记录包含 Docker 的具体失败信息。分享前请脱敏。
