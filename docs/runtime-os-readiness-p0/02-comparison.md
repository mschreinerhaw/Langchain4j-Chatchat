# 隔离回归和生产只读验证

## 复跑

在独立 Linux Docker daemon 预置 `nginx:1.28-alpine` 和 `python:3.11-alpine`，准备 Python 和 OpenSSL。测试使用随机命名的容器和独立内部网络。探针使用自签名的临时 HTTPS 测试端点，默认 seccomp 保持启用。测试结束清除本轮测试资源。

~~~sh
python scripts/tests/test-opensearch-readiness.py --context desktop-linux \
  --isolated-daemon --native-only --output native.json
python scripts/tests/test-opensearch-readiness.py --context desktop-linux \
  --isolated-daemon --output pressure.json
~~~

实际隔离环境为 Docker Desktop 的 `desktop-linux`，Linux 6.18.33.2、Docker 29.6.2、Compose 5.3.1、cgroup v2。Windows 运行时通过 `--openssl` 指定了已有 OpenSSL。原生对照和批次对照串行执行。批次对照每次只提交一个 exec，并运行五次相同 HTTPS 检查。僵尸数达到 8、进程数达到 20、JIT 映射增量达到 128 或内存增量达到 16 MiB 时中止试验。原配置在隔离实验中额外使用 32 PID 安全上限，两种中间配置也沿用该安全上限。完整修复使用生产候选的 256 PID 上限。

## 实测

| 指标 | 原配置 | 仅 init | init 和新间隔 | 完整修复 |
| --- | ---: | ---: | ---: | ---: |
| HTTPS 探测成功次数 | 10/10，触发阈值 | 1,000/1,000 | 1,000/1,000 | 1,000/1,000 |
| 峰值僵尸进程 | 10 个 ssl_client | 0 | 0 | 0 |
| 宿主可见进程，前 → 后 | 20 → 30，移除后恢复 | 21 → 21 | 21 → 21 | 21 → 21 |
| 批次探测累计秒数，含 Docker exec | 0.313 | 29.437 | 29.858 | 29.027 |
| 匹配 bpf_jit 的映射增量 | 0 | 0 | 0 | 0 |
| 匹配 bpf_jit 的内存增量 | 0 B | 0 B | 0 B | 0 B |

三种 init 配置均完成 1,000 次。原配置不继续积累到 1,000 次。所用内核没有匹配的 `bpf_jit` 映射，上表不能判定生产 EL8 上的 JIT 释放情况。

- 原生 Docker 健康检查用 1 秒隔离间隔，原配置和启用 init 各四轮均为 healthy，分别留下 4 和 0 个 `ssl_client` 僵尸。
- HTTPS 200、401 成功，503、连接拒绝、TLS 握手失败和超时均返回失败。直接运行 BusyBox wget 时，延迟 fixture 约 6 秒结束。独立的 Docker 原生探针验证 4 秒超时导致健康失败，恢复后变为 healthy。
- 隔离 Compose 用加速的时间参数测试状态转换。探针不健康时，API/MCP 替身容器停在 created。返回 401 后启动。后续探针中断、恢复、重启及单独重建未更换依赖实例。实际发布定时和依赖关系由生成件校验覆盖。
- 配置生成从已哈希的源码构造，默认 wrapper 能找到生成件，篡改生成件后被拒绝。另有 20 次小型容器创建、启动和退出通过，容器内确认 `Seccomp: 2`，失败 0 次。
- 现有存储恢复和 Native Studio 部署回归共 3 项通过，Python 语法与启动脚本 Bash 语法检查通过。

## 生产只读检查

在生产 Compose 2.27.0 上验证旧 wrapper 加载候选 overlay、新 wrapper 从发布目录加载 overlay。实际生产合并配置的差异仅限探针 init、PID 限额和健康检查定时。两个生产配置文件的哈希和旧探针容器 ID、退出状态在检查前后相同。生产应用未被重建或修改。

**BLOCKED**：缺少与生产同版本的独立 EL8 daemon，尚无 EL8 JIT 压力曲线或同版本默认 seccomp 反复创建实测。生产专项应用、回滚及变更后持续监控等待审批。

原始证据见 [批次对照](pressure.json)、[原生探针对照](native.json) 和 [生产只读采样](production-readonly.json)。
