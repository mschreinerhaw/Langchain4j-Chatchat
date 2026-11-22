# OpenSearch 就绪探针故障分析

## 生产观察

目标容器为 `runtime-os-source-production-opensearch-ready-1`。宿主机为 Linux 4.18.0-553.el8_10.x86_64、Docker 26.1.3、Compose 2.27.0、cgroup v1。故障数据由运维任务书提供。

| 指标 | 故障期间 | 责任容器停止后 |
| --- | ---: | ---: |
| 僵尸进程 | 26,938，均归属 PID 57399 | 0 |
| BPF JIT 映射 | 约 27,045 | 105 |
| BPF JIT 内存 | 737.70 MiB | 1.06 MiB |
| 默认 seccomp 的 Worker 启动 | `errno 524` | 据任务书报告已恢复 |

只读复核确认该容器已退出。Docker inspect 仍显示旧入口 `sleep infinity`、`Init=null`、5 秒间隔、无 PID 限额。宿主机三次采样均为 761 个可见进程、0 个僵尸进程、105 条 `bpf_jit` 映射和 1,110,016 字节。现有 API、MCP 容器健康，宿主机 OpenSearch HTTPS 返回 401。

## 已证实的产生和回收机制

隔离 Docker daemon 中，使用相同镜像和探针命令，仅把原生健康检查周期缩短到 1 秒。测试未对探针容器执行 `docker exec`。四次健康检查均成功，原配置的 PID 1 下增加四个 `ssl_client` 僵尸进程。相同条件下启用 `init: true`，四次检查后僵尸进程为零。另一个单子进程实验显示，孤儿交给 `sleep` 时留下一个僵尸，交给 Docker init 时归零。原配置的批次对照在十次成功 HTTPS 检查后观察到十个 `ssl_client` 僵尸进程。

BusyBox HTTPS wget 实现存在派生 TLS 子进程的路径。子进程结束后需要父进程调用 wait。孤儿会被 PID 命名空间中的 init 或相应 subreaper 接管。Docker Compose 的 `init: true` 启用负责转发信号和回收子进程的 PID 1。 [BusyBox wget 源码](https://coral.googlesource.com/busybox/+/refs/heads/master/networking/wget.c) · [Linux wait(2)](https://www.man7.org/linux/man-pages/man2/waitpid.2.html) · [Docker init 说明](https://docs.docker.com/reference/compose-file/container-spec/#init)

## 尚待验证

- 隔离对照证实原生健康检查可独立造成同类僵尸。生产事件缓存没有覆盖故障窗口的完整记录，无法排除当时还有脚本发起 `docker exec`，也未跟踪生产故障期每一轮检查。
- 生产观测支持僵尸存续、JIT 映射与 seccomp 启动错误之间的关联。具体过滤器引用由哪个内核对象长期持有、分配路径如何达到资源限制、EL8 内核是否有额外释放缺陷仍未验证。Linux 文档说明 BPF JIT 有全局内存限制。 [Linux BPF JIT 文档](https://docs.kernel.org/5.18/admin-guide/sysctl/net.html)
- 隔离机使用 Linux 6.18、Docker 29.6.2 和 cgroup v2，`/proc/vmallocinfo` 没有匹配指定 `bpf_jit` 字符串的记录。隔离试验验证了进程回收，EL8 上的 JIT 资源曲线仍为 **BLOCKED**。
