# Python 数据科学 Asset 与模板

## 领域边界

- `PythonEnvironment`：由 MCP 管理端创建并发布的不可变 Docker 运行规格。API 只能读取 `PUBLISHED` 环境。
- `PythonAsset`：API 用户绑定到某个 MCP 环境版本的专属 workspace，是执行隔离边界。
- `PythonScript`：开发态源码；每次保存生成不可变版本。
- `PythonTemplate`：通过测试后发布的源码快照，是 MCP/Agent 可检索、可执行的能力。
- 开发态脚本不会进入模板索引，也不会注册到 `ToolRegistry`。

发布门禁固定为：Asset 为 `READY`、源码非空、最近在所选 MCP 环境测试成功、场景与功能描述非空、输入/输出 Schema 为 JSON 对象。发布采用两阶段流程：API 将源码用内部共享密钥再次进行 AES-GCM 加密并同步到 MCP；MCP 只保存 `source_ciphertext`，验证并注册真实 MCP Tool；随后 API 写入独立索引 `mcp_python_template_index`。任一步失败，模板都会停用，避免“可检索但不可执行”。

## API 与 MCP 控制通道

- API 使用现有 MCP 管理端登录通道获取短期 Bearer Token。
- 即使通道本身使用 TLS，脚本源码仍以 `ENC(...)` AES-GCM 密文作为业务载荷传输；明文不会出现在 MCP HTTP 请求体或 MCP 数据表。
- API 通过 `/api/v1/python/environments?published=true` 获取可选环境；`/runtime/assets/provision` 只校验受控镜像并创建持久 workspace，`/runtime/preview` 才创建单次执行容器。
- MCP 侧 `mcp_python_template_asset` 是运行时权威快照；执行时才在 MCP 进程内存中解密源码。
- 两端必须配置相同的内部加密凭据。未配置凭据时客户端拒绝发送源码，MCP 也拒绝明文载荷。

## 脚本输入输出约定

执行参数通过环境变量 `CHATCHAT_INPUT_JSON` 传入。脚本应将最终 JSON 结果写到 stdout，例如：

```python
import json
import os

params = json.loads(os.environ.get("CHATCHAT_INPUT_JSON", "{}"))
result = {"customerId": params.get("customer_id")}
print(json.dumps(result, ensure_ascii=False))
```

stdout、stderr、退出码、耗时和执行状态会写入 `mcp_python_execution`。运行中的源码来自发布快照，不受后续开发态修改影响。

## Docker 边界

Asset 和用户 workspace 持久化，容器不持久化。每次调试或 MCP Tool 调用都执行 `docker run --rm`，完成、失败或超时后销毁；异常退出还会执行一次定向 `docker rm -f` 清理。

默认限制为 2 CPU、4 GB 内存、256 个进程、只读根文件系统、丢弃全部 Linux capabilities、禁止提权、以 `10001:10001` 非 root 用户运行并关闭网络。用户 workspace 只读挂载到 `/workspace`，`/tmp` 和 `/workspace/output` 使用带大小限制的 tmpfs。若需要访问数据库或内部 API，只能选择平台预先创建的 `NAMED` Docker 白名单网络。

Runtime 镜像必须使用固定标签或 digest，禁止 `latest` 和无标签镜像。环境发布后不可修改；升级 Python 或依赖需要构建新镜像并创建新的 MCP 环境。依赖清单只接受 `package==version`，作为镜像能力契约保存，执行期间不会调用 `pip install`。参考镜像位于 `deploy/docker/python-runtime`。

可通过环境变量配置：

| 变量 | 默认值 |
| --- | --- |
| `CHATCHAT_MCP_PYTHON_WORKSPACE_ROOT` | `./data/python-assets` |
| `CHATCHAT_MCP_PYTHON_DOCKER_COMMAND` | `docker` |
| `CHATCHAT_MCP_PYTHON_OUTPUT_LIMIT_BYTES` | `1000000` |
| `CHATCHAT_PYTHON_INDEX_NAME` | `mcp_python_template_index` |

生产 MCP 节点需要安装 Docker，并授予 MCP 服务账户访问 Docker daemon 的权限；API 节点不直接访问 Docker。建议使用平台审核过、预装依赖的镜像；当前页面不开放任意在线 `pip install`。

## 检索

OpenSearch 启用时分别执行字段增强的 BM25 与 KNN 搜索，再使用 RRF 合并排名。Embedding 文本由模板名、场景、功能、关键词、领域以及输入输出 Schema 组成。未启用 OpenSearch 的开发环境会降级到数据库词法检索，并将模板索引状态标记为 `LOCAL_ONLY`。
