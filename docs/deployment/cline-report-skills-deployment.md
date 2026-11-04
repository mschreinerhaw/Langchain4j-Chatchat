# Cline 报告 Skills 部署记录

环境：`192.168.195.221`，SSH 端口 `5221`；用户入口 `http://192.168.195.221:18100`。凭据只通过环境变量和发布目录内的私有文件传递，不写入代码、证据包或本文。

## 第一轮部署

2026-11-04 部署源码提交 `8f9208aad9d50abf403d782189cbee52f552eeec`。

| 项目 | 实际值 |
| --- | --- |
| 发布目录 | `/opt/chatchat-releases/cline-report-skills-ff83f6ff5-r1` |
| API 镜像 | `runtime-os-api:report-skills-8f9208aad9d5` |
| 完整 JAR SHA-256 | `54076304a2f248c1fd85f065a2c5bf8140eeb9af6733e8ad1207ef81a3b0d93c` |
| 完整 JAR 大小 | 428,782,038 字节 |
| Worker 镜像 | `runtime-os-coding-agent:agent-skills-report-ff83f6ff5` |
| Worker 镜像 ID | `sha256:7f47f974bff7464f32d930c0f444ec01e6505428a910308ffb05e2d171722a72` |
| Worker SDK | 既有固定版本 `@cline/{agents,core,sdk}` 0.0.93 |
| Python | 复用既有 `chatchat-python-runtime:3.11-v1.2-fonts-r2-20261022`，未安装额外包 |

先从源码构建完整 Maven JAR；前端入口验证发现缺少构建资源后，执行现有 Vue/Vite 构建，再重新打包，资源校验通过。静态生成文件未作为业务源码提交。上传采用完整包的字节段复用：只传输 10,252,579 字节的新字节段，服务器重建后校验完整 JAR 大小与 SHA-256，再进行 ZIP 完整性检查。没有向运行中容器修改 class 或替换嵌套依赖。

`build-cline-skills-stage.py` 基于原 Worker 镜像离线构建，复制完整 dist 与 Skills，保留非 root 用户及 `.cline/skills` 发现链接；验证条件资源技能可发现，再在无网络、只读容器中运行全部 179 个 Node 测试，全部通过。`prepare-cline-report-stage.py` 核对包、模块哈希及离线测试证明。

`deploy-integrated-runtime.py` 在停止服务前核对当前 JAR、活跃任务、Cline 会话、Python 容器和挂载路径，备份数据库及现有容器/配置。发布清单设置 `preserveRuntimeConfiguration=true`，不执行原脚本中的知识索引配置覆盖。不修改模型、MCP、新闻采集、数据库结构或资源权限。只替换 API、Worker 及依赖 API 网络命名空间的 Node proxy，并重建既有网络隔离规则。

部署记录 `deployment.json` 已确认：API MCP 注册表 HEALTHY、39 个工具、必需工具存在；历史会话内容及消息索引数量不变，无关容器 ID 不变；没有过期任务豁免。

## 升级与回滚

升级先固定上游 Skill 提交与所有文件哈希，复核许可证和完整目录，再通过既有 parser、冲突及按需读取测试。完整源码构建、离线 Worker 测试及真实验收通过后才能发布；不得运行未固定版本的远程安装脚本。

第一轮原容器均保留，后缀为 `-before-studio-1793795399`，涉及 API、coding-agent、node-proxy 三个名称。私有备份位于发布目录的 `backup/`，包括 `containers.json`、`compose.yaml`、`deployment.env`、`node-proxy.conf`、`channels.json` 和 `api.dump`。发生启动或就绪检查失败时，部署脚本自动恢复原配置与原容器。

人工回滚也必须先确认无活跃任务、Cline 会话及 Python 执行，再停止当前 proxy、Worker、API，给候选容器另存名称，将以上三个原容器恢复原名，恢复备份中的三个配置文件，按 API、Worker、proxy 顺序启动，重新运行发布目录中的 `restrict-coding-worker.sh` 并检查/重载 gateway Nginx。随后验证登录、MCP 目录、镜像及历史会话。不得删除候选或原容器来代替核验。普通代码回滚不恢复数据库 dump，避免覆盖部署后的真实会话和验收证据；本次没有数据库迁移。

第一轮真实验收发现了权限配置不足与现有报告校验反馈不明确的问题，不能将“部署就绪”视为“业务验收通过”。最终候选及验收结论另见测试文档。
