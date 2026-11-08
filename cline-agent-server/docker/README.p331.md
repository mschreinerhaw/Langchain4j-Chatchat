# P3.3.1 独立 rootless 评审原型

基线 `32e08b765`；仅使用 `/opt/cline-server-p33-stage1-20261108`、账号 `cline-p331`、私有 Socket 和 loopback `18331`。保留所有既有 Docker/Runtime/模型/MCP 服务。当前资源硬限制未通过，此拓扑不可用于生产。

```sh
# 在新目录 source/ 中按仓库相对路径复制 cline-agent-server/
python3 source/cline-agent-server/scripts/p331-rootless.py
python3 source/cline-agent-server/scripts/p331-deploy.py
python3 source/cline-agent-server/test/p331-live.py
python3 source/cline-agent-server/test/p331-native.py
python3 source/cline-agent-server/test/p331-startup-guards.py
```

rootless 准备脚本创建独立 nologin 普通账号及其 subuid/subgid，使用已安装的 rootlesskit、slirp4netns、newuidmap/newgidmap。没有修改共享 daemon.json、Docker CLI context、systemd、sysctl 或防火墙。daemon 不使用共享数据目录，不开放 Docker TCP，不重启共享 daemon。新 daemon 的二进制沿用当前已安装版本，本轮不升级生产 Docker/内核。

镜像从已有受测 `cline-agent-server:p3-stage2` 只读导出，加载到新 daemon，再在新 daemon 内构建控制服务镜像。Worker 继续使用基线镜像摘要；不重编官方 Agent。模型 profile 从旧私有配置复制，只有 Gateway 能读实际 provider 凭据；Executor 仅有模型名称。配置不进入 Git 或镜像。

本机 `Cgroup Driver=none`，严格模式（`allowUnenforcedPrototype=false`）拒绝启动。评审模式显式启用后，只测试 API、身份、挂载及 namespace 边界；`ulimit` 和并发数量上限不等于容器级 CPU/内存/PID 硬限制。不得把 Docker 参数已写入 spec 当作资源隔离生效。

更新本原型时先检查私有 daemon ID、容器标签 `cline.stage=P3.3.1`，仅停止其 Gateway/Executor，保留挂载根和未知记录。`p331-deploy.py` 不自动替换已存在的容器。完整启动窗口恢复、daemon 重启和多 Authority 故障注入不在本轮执行。

回滚：停止这一个私有 daemon 上的 `cline-p331-gateway`、`cline-p331-executor` 及核实归属后的原型 Worker；保留 evidence、registry、close 标记和未知目录。需要关掉私有 daemon 时，先按专用 UID 和 `$root/run/dockerd.pid` 核对进程实际命令、UID、数据根及 RootlessKit 父进程，再停止对应进程。不得调用默认 host 的 `docker stop/prune`，不得删除 `/var/run/docker.sock`，不得关闭共享 systemd Docker。

本轮不自动递归删除任何运行目录、不删除已有 Docker Runtime，也不切换 Runtime OS 默认 Agent。评审后如果放弃原型，先归档 Hash 与日志，再单独按核实的私有目录和专用账号清理；不得用模糊容器名或通配路径回滚。

源码审查和剩余风险见 [审查报告](../../docs/audits/cline-server-p331-review.md)，API/威胁模型见 [设计](../../docs/design/cline-server-p331-executor.md)。
