# Native SQL / Python Studio 修复验收

## 问题与修复

现有 S3 Coding Node 正常，但 Studio 使用的 `/v1/runs` 工作区协议没有可用的 8791 服务。该地址是 Java 容器内的 loopback，不能通过在宿主机监听同名端口修复。

- 使用已有 Cline 4.1.26 / Python 3.11 固定镜像构建 Studio 适配层镜像；官方 Core 和 Worker 保持原样，没有启用 SDK Worker。
- 将适配服务加入现有应用 Compose，通过 `network_mode: service:api` 使用 Java 的 loopback；依赖设置 `restart: true`。控制端口不向宿主机发布。独立受限 Executor 保留现有 S3 任务。
- 补齐 Native MCP 的 workspace_read / workspace_propose / workspace_metadata / workspace_validate。Java 按工作区配置和任务冻结授权校验，不扩大为分析工具权限。
- 修复回调 URL 双斜杠与签名路径不一致；交付文件归属执行用户，保留只读输入。
- 顺序执行工作区 MCP 事务，兼容 Java 原有账本的顺序预留/结算协议；相同原生调用幂等，不重放模型。
- Python 助手使用认证账户 ID；原 Python 文件资产的存储归属保持原有方式。

源码候选经过以下真实应用接口测试后部署。测试期间的失败、原生事件、模型回执和私有配置保留在服务器发布目录及 Studio 状态目录；不会把私有配置或业务数据库结构提交 Git。

## 验收

| 场景 | 任务 | 结果 |
| --- | --- | --- |
| SQL 源码生成 | sql-coding-27f3a36f-678e-4647-824d-ed078b16c718 | SUCCESS；真实生成 SELECT 42 AS answer，Java SQLGlot 解析通过，未执行 SQL |
| Python 多文件工程 | python-coding-bb18f5de443cbf406c19934196a719fc120fee39e0da30edf5b3d950d0f0d4ad | COMPLETED；arithmetic.py 和 test_arithmetic.py，Java AST 校验通过 |
| 非默认模型 | python-coding-49e716ba0698cfce17b763ddd1441f78835b20d4197898730516b46a3f20d088 | COMPLETED；qwen3.6-plus，动态文件 numeric_operations.py |
| 授权 Oracle 目录探索 | sql-coding-62d2a687-eb3f-47bf-828c-f3fe9cb9ded8 | SUCCESS；真实元数据调用，EVIDENCE_ONLY / SQL_NOT_GENERATED，无 SQL 执行 |

Python 产物另在无网络、只读根目录、删除全部 capabilities、有 CPU/内存/进程限制的临时容器中运行模型生成的 unittest：4 项通过，退出码 0。该验证独立于 Studio 的源码解析，未把生成阶段表述为代码已执行。

回归：Node 36 项通过；Java ClineRuntimeModelBridgeTest 25 项、PythonCodeAssistantServiceTest 12 项、PythonStudioIdentityTest 1 项通过。CodingWorkspaceServiceTest 本机缺少对应集成条件的 13 项跳过，不计入通过。

## 部署和回滚

构建文件：`cline-agent-server/docker/Dockerfile.studio`。先核对基础标签对应的镜像 ID，再构建；BuildKit 的 FROM 不能直接把本地 image ID 当作仓库名称。服务器已有基础镜像 ID：`sha256:60641b46986f7b260de9e89c491f9bb956ca0322038dbbc034683390a448953a`。

```sh
docker build --pull=false -f cline-agent-server/docker/Dockerfile.studio \
  --build-arg CODING_NODE_IMAGE=runtime-os-coding-node:native-document-search-20261112-r1 \
  --build-arg GIT_COMMIT="$(git rev-parse HEAD)" \
  -t runtime-os-coding-node:native-studio-20261112-r1 .
python3 scripts/deploy-native-studio.py --image sha256:<已检查的镜像ID>
```

脚本复用服务器现有私密配置和受限 Docker daemon，生成独立 Studio Executor 的身份、密钥和状态目录。密钥不进入镜像。Java API 仍通过现有 source-stack Dockerfile 从源码构建。应用入口仍为 18100。

回滚应使用发布目录下保存的应用 Compose 备份和旧 API 镜像；恢复前停止本次 Studio gateway，避免旧 Java 容器命名空间引用。S3 Node 不需停止。Studio 镜像升级时仅更新 gateway 的固定 image ID 并通过 Compose 重建该服务。若单独使用 docker 命令重建 Java，必须同步重建 gateway；推荐始终使用同一份应用 Compose。

## 失败与限制

- 初始候选记录了 loopback 配置错误、双斜杠回调错误、工作区模型工具授权缺失、并发预留冲突；没有将这些尝试计入成功。
- Oracle 部分检索和详细对象索引返回过 502 / METADATA_INDEX_MISS。授权目录枚举及最终目录说明已验证；不宣称所有外部数据库索引和网络都正常。
- Studio 的成功表示源码候选经过解析并归档，业务语义及数据库执行不属于该成功状态。实际执行仍走现有用户授权入口。
- 修复前使用登录名创建的 Python 失败任务不自动重放；请使用新请求执行。
