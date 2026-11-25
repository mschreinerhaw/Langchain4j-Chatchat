# 本地 Docker 验收报告

验收环境为 Windows Docker Desktop 的 Linux/amd64 引擎。产品镜像为 `llm-compatibility-gateway:1.0.0`，Node.js 24。使用独立 Compose 项目和本地 Mock Upstream。Mock 的固定输出仅用于协议测试。

## 功能和浏览器

- Vitest 10 项通过，覆盖字段级规则、核心字段保护、重命名冲突、空对象和数组、UTF-8 分片、CRLF、多行 Event 和异常 EOF。
- Docker 集成的 6 个子测试通过，覆盖双监听器隔离、管理与推理认证、客户端模型权限、上游无模型列表、特殊字符模型 ID、声明式请求/响应规则、工具参数分片和工具结果回传、Usage、有损结构错误、首包和空闲超时、并发释放、配置快照、回滚和容器重启。
- Playwright 浏览器测试通过，覆盖登录、发布模型映射、四阶段实验室、请求趋势、请求日志和退出。[实验室截图](admin-lab.png)
- npm 审计未报告已知漏洞。镜像使用非 root 用户。通过 Windows 离线安装脚本完成 SHA-256 校验、镜像加载和 Compose 启动，两端口返回 200。在 `--network none` 的独立容器中，推理就绪端点和管理首页同样返回 200。
- 生产网络的内部 CA 证书与客户并发上限仍需现场验收。P2 的 Responses、Embeddings 和 Anthropic Messages 接口返回 501。

## 性能

同一 Docker 网络中，对 Mock 分别执行直接请求和经过网关的请求。各组预热后采集 240 对小请求，交替调换直连和经网关的顺序。SSE 首响应按完整 Event 测量，包含 Mock 主动分片所用时间。单位为毫秒。

| 请求 | 并发 | 网关 P50 / P95 / P99 | 直连 P50 / P95 / P99 | 配对差值 P50 / P95 / P99 |
| --- | ---: | ---: | ---: | ---: |
| JSON | 1 | 2.602 / 4.781 / 5.720 | 0.583 / 1.043 / 1.475 | 1.996 / 3.949 / 4.819 |
| SSE | 1 | 19.018 / 20.810 / 21.965 | 16.165 / 16.556 / 16.787 | 2.886 / 4.798 / 6.124 |
| JSON | 2 | 2.804 / 4.178 / 6.782 | 0.478 / 0.718 / 0.873 | 2.310 / 3.646 / 6.368 |
| SSE | 2 | 17.684 / 19.731 / 20.173 | 15.841 / 16.721 / 17.056 | 1.837 / 3.839 / 5.291 |

并发 1 时首个完整 SSE Event P95 为网关 20.766 ms、直连 16.517 ms。并发 2 时为 19.714 ms、16.705 ms。压测后单次 Docker 样本为 9.58% CPU、66.48 MiB 内存，容器上限 512 MiB。以上是本机短测样本，24 小时连续流式稳定性、持续内存增长和高并发负载均未验证。

复测时，在测试项目已发布 Fixture 映射的条件下执行以下命令。PowerShell 通过管道传递脚本时先设置 `$OutputEncoding=[Text.UTF8Encoding]::new($false)`。

```sh
docker exec -i llm-gateway-devtest-llm-gateway-1 node --input-type=module - < tests/performance.mjs
docker exec -i -e GATEWAY_BENCH_CONCURRENCY=2 llm-gateway-devtest-llm-gateway-1 node --input-type=module - < tests/performance.mjs
```

## Cline 验收

使用本地 `cline-agent-server:4.1.27-local`，经网关访问 Mock Upstream。Cline 原生 worker 启动、提交任务、记录流式推理及上游 Usage，并到达 `turn_completed`。网关监控记录实际的 Cline SSE 请求。工具调用闭环另由 Docker 集成测试独立覆盖。

客户上游 `10.2.25.111:32004` 从开发宿主和网关容器 TCP 探测均未连通。真实模型认证、真实模型的原生工具调用、Cline 客户业务任务和产物校验均未验证。现场验收需使用客户授权凭据，先发布任务书中的模型映射，再通过实际 Cline 执行一个明确要求调用工具并生成可独立校验产物的业务任务，保存原生事件、网关请求 ID 与产物验证结果。

## 复现

在隔离工作目录将 `tests/fixture.env` 复制为 `.env`，安装 npm 依赖并运行以下命令。浏览器测试可预装 Chromium，或通过 `PLAYWRIGHT_EXECUTABLE_PATH` 使用已安装的 Chromium。设置 `TEST_RESTART=1` 才会执行集成测试中的容器重启分支。

```sh
npm ci
npm test
docker compose -p llm-gateway-devtest -f docker-compose.yml -f tests/compose.test.yml up --build -d --wait
TEST_RESTART=1 npm run test:docker
npm run test:ui
docker compose -p llm-gateway-devtest -f docker-compose.yml -f tests/compose.test.yml down -v
```

Cline Mock 测试使用已有的 Cline 镜像。先完成 Docker 集成测试以发布 Fixture 服务，再运行以下命令。

```sh
docker run -d --name llm-gateway-cline-smoke --network llm-gateway-devtest_default \
  --cpus 2 --memory 2g --cap-drop ALL --security-opt no-new-privileges \
  --publish 127.0.0.1:18329:18329 --volume "$PWD/tests/cline-entrypoint.sh:/fixture.sh:ro" \
  --entrypoint sh cline-agent-server:4.1.27-local /fixture.sh
node tests/cline-smoke.mjs
docker rm -f llm-gateway-cline-smoke
```
