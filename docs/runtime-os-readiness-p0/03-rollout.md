# 部署、验证与回滚

## 发布审批记录

当前状态为 **`READY_FOR_APPROVAL`**。根因复现、init 修复验证、1,000 次探测、Git 差异与留存证据复核已完成。独立复核签字和生产灰度审批待完成，生产 48 小时观察未开始。

| 提交 | 作用 |
| --- | --- |
| `07abfa04157424d95bcd01ad7b1e12d51d019a35` | P0 功能修复、发布校验、测试及证据 |
| `41d55e851` | 48 小时生产观察、告警与初步验收规则的文档更新 |

同版本 EL8 隔离压力测试仍为 **BLOCKED**。生产内核的 BPF JIT 长时资源释放尚未验证，审批须保留此项已知风险。

## 上游配置和发布流程

现网 `/opt/runtime-os/compose.yaml` 的 `include` 合并候选发布 JSON、宿主依赖、存储及 Native 配置。实际就绪探针来自 `/opt/runtime-os/compose.host-dependencies.yaml`。仓库对应的权威源码为 `deploy/docker/production/compose.host-dependencies.yaml`。它配置探针的只读根文件系统、capabilities 清空、禁止提权、宿主网关和 control 网络，并让 API 和 MCP 等待探针健康。

修复保持 HTTPS 命令、200/401 判定、网络、依赖和安全策略。Docker init 负责回收。常态探测间隔改为 30 秒，启动宽限 30 秒内每 5 秒探测，单次超时 4 秒，连续 5 次失败判为 unhealthy。256 PID 上限限制影响范围。首轮启动探测仍约 5 秒。常态下恢复取决于下一轮探测，连续失败判定约 150 至 170 秒，具体取决于每次探测耗时。探针持续运行可观察监听状态，Compose 的依赖门控作用于启动过程。 [Docker 健康检查](https://docs.docker.com/reference/dockerfile/#healthcheck) · [Compose 启动顺序](https://docs.docker.com/compose/how-tos/startup-order/)

新的发布准备程序从 SHA-256 源码快照生成发布目录内的 `compose.host-dependencies.yaml`。启动和验收程序校验生成件、仓库 wrapper 及最终合并配置。发布验收同时校验运行容器的 Init、PID 限额、安全选项和实际健康检查。下次获批使用新发布入口时，从同一源码快照分别安装 `compose.release.yaml` 和 `start-production.sh` 到 `/opt/runtime-os/compose.yaml` 和 `/opt/runtime-os/start-production.sh`，并将 `production-current` 指向已生成和验证的发布目录。新 wrapper 默认取当前发布目录内的 overlay。新启动脚本会在 `--no-recreate` 前拒绝仍带旧设置的探针容器。紧急回退到历史应用发布时，保留已批准的修复 overlay，启动前显式设置 `RUNTIME_OS_HOST_DEPENDENCIES_FILE` 指向该文件，再验证合并结果。

## 当前旧 wrapper 的专项修复

生产修改须单独获批。当前 wrapper 仍使用 `/opt/runtime-os/compose.host-dependencies.yaml`，可用专项脚本执行修复。脚本默认从当前检出的源码取候选 overlay。先运行只读预检。

~~~sh
python3 scripts/deploy-opensearch-readiness.py check
~~~

获批后提供两个可执行的业务检查脚本，分别检查 API 和 MCP。它们按需加载安全存放的凭据，并通过退出码返回结果。以下路径应替换为运维现有检查程序。备份目录应尚未创建。

~~~sh
backup=/opt/runtime-os/backups/opensearch-readiness-$(date +%Y%m%d-%H%M%S)
python3 scripts/deploy-opensearch-readiness.py apply \
  --backup "$backup" --api-check /run/runtime-os-ops/check-api \
  --mcp-check /run/runtime-os-ops/check-mcp
python3 scripts/monitor-opensearch-readiness.py --samples 40 --interval 30
~~~

脚本复用 Compose 运维锁，备份原 wrapper 和 overlay，检查语法及非目标服务差异，原子替换 overlay，只重建 `opensearch-ready`，再检查 Docker init、healthy 状态和两项业务探测。重建期间探针短暂不可用，避免并发启动依赖图。验收时核对 API、MCP 实例 ID、启动时间及业务端点，并观察连续采样中 PID 1 子僵尸和 JIT 映射趋势。

生产灰度只能通过 `apply` 脚本执行。脚本持锁检查候选的最终合并配置要求 `init: true`，并逐项比较其他服务与项目设置，覆盖 API、MCP、Cline Worker 和数据库在 Compose 中的配置。候选未通过时立即退出，原 overlay 和容器保持原状态。安装后再次解析，结果必须与已验证候选一致。若不一致，恢复旧 overlay，容器保持原状态。通过两道检查后，脚本才对 `opensearch-ready` 发出 `up --no-deps --force-recreate`。

审批前已使用隔离 Compose 模型和替身执行器复核四种路径。缺少有效 init、非目标服务配置变化及安装后合并结果漂移均在调用 Docker 重建前中止。有效候选只发出一次定向重建调用。

## 48 小时生产灰度观察

审批时记录目标提交、维护窗口、值班负责人、两项业务探测和监控接收渠道。维护窗口开始时采集宿主机基线，记录 API/MCP 的容器 ID、启动时间和业务探测结果。运行 `check` 对比当前与候选合并配置，核对候选 `init: true`。由 `apply` 安装已校验的 overlay 后再执行定向重建。现网 wrapper 在安装前仍会读取旧 overlay。

变更成功后核对 `Init=true`、PID 1 为 Docker init 且 `.State.Health.Status` 为 `healthy`。确认 API/MCP 实例 ID、启动时间与基线一致，两项业务探测通过。把该时点记为 48 小时窗口起点并保存 UTC 时间。

沿用宿主机现有采集器，每 60 秒调用一次采样命令，将 JSON 记录写入现有监控或受限日志。单次调用成功后还需采集 API/MCP 健康状态，业务探测按既定频率执行。采样缺失应标记为缺口，排查并补足观察时间。以下命令读取宿主机级数据，适用于灰度和后续持续监控。

~~~sh
python3 scripts/monitor-opensearch-readiness.py
~~~

| 监控项 | 初始告警条件 |
| --- | --- |
| `hostZombies` | 持续超过 50，连续 6 个一分钟样本 |
| `bpfJitBytes` | 持续超过 104,857,600 字节（100 MiB），连续 6 个一分钟样本 |
| `bpfJitMappings` | 持续超过 2,000，连续 6 个一分钟样本 |
| seccomp 524 | 首次出现立即告警 |

这些是事故后的初始参考值，由运维按生产基线调整。即使绝对量未达阈值，也要调查持续增长以及 `pid1ChildZombies` 反复非零。seccomp 524 监控同步读取 Docker daemon 日志和 Worker/部署调用方的 OCI 启动错误。`docker events` 可补充容器健康、启动和退出事件，采集创建失败须以日志及调用方错误为准。记录窗口内真实 Worker 启动次数和失败数，若没有启动样本，将该项标记为未观察。

48 小时内探针健康、API/MCP 业务探测通过、僵尸和 JIT 指标无持续增长，且采集范围内未观察到 seccomp 524，经值班复核后可记为“生产初步验收通过”。保留宿主级监控，继续观察长期趋势。与生产同版本的 EL8 隔离压力回归仍单独记录。

## 回滚条件与操作

候选差异越界、配置失效、重建失败、探针未健康、PID 1 异常、API 或 MCP 冒烟异常，以及监控显示增长趋势，均应中止。脚本在应用阶段异常时尝试恢复原 overlay。已尝试重建时，保持探针停止，避免重新引入旧缺陷。

~~~sh
python3 scripts/deploy-opensearch-readiness.py rollback \
  --backup "$backup" --api-check /run/runtime-os-ops/check-api \
  --mcp-check /run/runtime-os-ops/check-mcp
~~~

回滚保留其他运行服务。恢复的旧 overlay 仍有缺陷风险，部署修复 overlay 前暂停全栈启动及探针重建。记录业务探测、探针容器 ID、退出状态、宿主机进程及 JIT 指标。生产实施与持续观察尚未执行。
