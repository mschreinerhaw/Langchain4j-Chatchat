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

## 后续修复部署

第二轮源码 `9ecbe29a03a2906fe7db4aecf799f383efc951b7`，目录 `/opt/chatchat-releases/cline-report-skills-5dd57ec2f-r2`，API `runtime-os-api:report-skills-9ecbe29a03a2`，完整 JAR SHA-256 `bcf89eb6650dcaf4770cfc39451a50fd5691681f8852e3be97af8b38fae5514a`，428,791,177 字节。Worker 不变。原 API/proxy 保留后缀 `-before-studio-1793796749`。结构化校验反馈修复，保留所有权限及源值隔离。

第三轮源码 `8db173b309328da82eeac4fbf46c6746ac1f51b6`，目录 `/opt/chatchat-releases/cline-report-skills-review-r3`。API 完整 JAR 与第二轮完全一致，`apiUnchanged=true`，发布没有替换 API/proxy。Worker 更新为 `runtime-os-coding-agent:agent-skills-report-8db173b30932`，镜像 ID `sha256:c1fe9be9c3d5add60b6571a29438aeb1c3d272aec2f3b0207e08431d9f9e9da4`，源码归档 SHA-256 `8a477d3299804c158886cc0fa6f3ffb83780946fac460dac5387cc0e8deeb194`。服务器离线 182/182 测试通过；原 Worker 为 `runtime-os-app-stage-coding-agent-1-before-studio-1793797162`。该轮修复剩余复核预算提示与字体重置指导。

以上两轮 deployment.json 均记录 MCP HEALTHY、39 个工具、历史内容与索引计数不变、无关容器不变。Worker 只更新时，回滚只需在无活跃执行后按该轮 backup 配置恢复对应 Worker，不停止 API 或还原数据库。

第四轮完整构建源码 `4cb04b1735c42209b03c371dc856c0a119663675`，目录 `/opt/chatchat-releases/cline-report-skills-ui-r4`；JAR SHA-256 `9ae42711cd57a9ca21b01286ae9ed8b6ee678aa940d3374c298e7ea225889c0f`，428,782,592 字节。由完整源码构建，前端与传递修复相关测试 55+24 项通过。复用第三轮已验证且哈希一致的 Worker，仅替换 API 和其网络命名空间中的 proxy。传输复用 ZIP 压缩字节，传送 8,137,378 字节；服务端重建完整 JAR 并核验全包 SHA 与 ZIP，不进行运行中类热补丁。

用户另行明确批准后，使用现有 `/enterprise/resource-grants` 接口为 admin 新增 USER/DATA_RESOURCE/ALLOW 的 financial_market 与 financial_news 授权。授权 ID 及时间见测试证据 `approved-resource-grants.json`；它们是逻辑只读资源，没有授予数据库、网络或主机命令执行权限。此授权不由部署脚本隐式生成，代码回滚也不自动撤销用户单独批准的授权。

第四轮实际就绪时间 `2026-11-04T13:23:18Z`，API 镜像 `runtime-os-api:report-skills-4cb04b1735c4`。原 API/proxy 后缀 `-before-studio-1793798552`，Worker 保持第三轮镜像 ID。deployment.json 已确认 39 工具 HEALTHY、历史内容/索引计数与无关容器不变，无数据库迁移。回滚第四轮时只恢复该轮 API/proxy 及 backup 配置，保留当前 Worker；仍须先确认没有活跃任务，再按下述网络隔离与就绪检查执行，不还原数据库 dump。

第五轮完整构建源码 `2d4020d91f85887618a4e4885fcd6107814216c7`，目录 `/opt/chatchat-releases/cline-report-skills-final-r5`；API 镜像 `runtime-os-api:report-skills-2d4020d91f85`，完整 JAR SHA-256 `6aed45a2fdccfafdfb5c72c23847b5244dbbb89a1b48075874f64a5023aacabf`，428,791,264 字节。传输新字节段 6,048,470 字节，在服务器还原完整包后核验 SHA 与 ZIP。49 项 Finalizer 测试及完整构建通过，修复真实 C3 中 Java 在 Cline 报告后追加原始表格的问题。Worker 保持第三轮镜像，不替换。

第五轮实际就绪时间 `2026-11-04T13:40:36Z`；MCP HEALTHY、39 个工具，历史会话内容/索引计数及无关容器不变，没有数据库迁移。原 API/proxy 后缀为 `-before-studio-1793799589`。普通回滚仅恢复该轮 API/proxy 与 backup 配置，不恢复数据库 dump，不撤销另行批准的资源授权；恢复后重新检查网络隔离、登录和 MCP 就绪。最终业务验收结论见 [真实验收记录](../testing/cline-report-skills-e2e.md)。

22:03 最终容器读取确认 API、Worker、proxy 均运行，运行中的 Python Sandbox 数量为 0，见 [运行状态证据](../testing/cline-report-skills-evidence/runtime-status-r5.json)。其中 catalogReadiness 是部署时就绪记录，不是 22:03 重新检索的目录快照。超时复验的父任务已取消，子任务外部模型用量仍有未核对预留，详见验收记录；未改账本或强行标记执行结束。

## 来源侧栏与底部操作优化（后续 UI 需求）

2026-11-04 22:18 部署源码 `d29daf99842a2b8ff3bb3957d14892e33840a235` 至 `/opt/chatchat-releases/cline-report-skills-source-ui-r6`。API 镜像 `runtime-os-api:report-skills-d29daf99842a`，完整 JAR SHA-256 `d238eba7f6d82cd7ac158629a61ef0fca2c76dc17cce707748cc510608b0d739`，428,783,168 字节；前端生产构建及完整 Maven package 通过。29 项相关 UI/引用回归通过。

来源侧栏采用灰色来源信息与文章链接，发布时间从标题分离，隐藏内部证据地址；引用通过既有授权弹窗打开。移除底部重复的网页/文档引用列表，来源入口与评价、导出 PDF 统一一行。22:18:44 生产浏览器从正常历史入口读取原有报告，确认来源链接打开原文、无底部重复引用、按钮同排、桌面和 390px 手机侧栏无横向溢出、无页面脚本错误，见 [浏览器证据](../testing/cline-report-skills-evidence/source-sidebar-ui.json)、[桌面截图](../testing/cline-report-skills-evidence/source-sidebar-desktop.png)及[底部操作](../testing/cline-report-skills-evidence/source-sidebar-footer.png)。

发布保留 Worker，未迁移数据库；MCP HEALTHY、39 工具，历史内容/索引数量与无关容器保持一致。原 API/proxy 后缀 `-before-studio-1793801854`，私有备份位于该发布目录的 backup。回滚仅恢复对应 API/proxy 与配置，保持 Worker 和数据库数据；该 UI 验收不改变前述报告业务验收尚未全部通过的结论。

## 升级与回滚

原文弹窗关闭图标的后续样式发布于同日 22:26：源码 `3bd17fe99922b34b5981cf5ef4de60e11401d41b`，目录 `/opt/chatchat-releases/cline-report-skills-close-icon-r7`，API 镜像 `runtime-os-api:report-skills-3bd17fe99922`，完整 JAR SHA-256 `f957119c7c4478fcf217afef99407c9e85bdb71588cd8618ea11846a8fe794ed`。旧 API/proxy 保留后缀 `-before-studio-1793802344`，Worker 保持原镜像。前端构建、完整 Maven package 及 3 项原文弹窗回归通过；发布检查确认历史内容/索引与无关容器不变、MCP 39 工具 HEALTHY。22:27 的真实历史页面验证确认关闭按钮为 32×32、透明无边框的 X 图标，悬停浅灰底、键盘焦点 2px，点击能关闭且无脚本错误，见 [实测记录](../testing/cline-report-skills-evidence/source-close-icon.json)及[截图](../testing/cline-report-skills-evidence/source-close-icon.png)。

升级先固定上游 Skill 提交与所有文件哈希，复核许可证和完整目录，再通过既有 parser、冲突及按需读取测试。完整源码构建、离线 Worker 测试及真实验收通过后才能发布；不得运行未固定版本的远程安装脚本。

第一轮原容器均保留，后缀为 `-before-studio-1793795399`，涉及 API、coding-agent、node-proxy 三个名称。私有备份位于发布目录的 `backup/`，包括 `containers.json`、`compose.yaml`、`deployment.env`、`node-proxy.conf`、`channels.json` 和 `api.dump`。发生启动或就绪检查失败时，部署脚本自动恢复原配置与原容器。

人工回滚也必须先确认无活跃任务、Cline 会话及 Python 执行，再停止当前 proxy、Worker、API，给候选容器另存名称，将以上三个原容器恢复原名，恢复备份中的三个配置文件，按 API、Worker、proxy 顺序启动，重新运行发布目录中的 `restrict-coding-worker.sh` 并检查/重载 gateway Nginx。随后验证登录、MCP 目录、镜像及历史会话。不得删除候选或原容器来代替核验。普通代码回滚不恢复数据库 dump，避免覆盖部署后的真实会话和验收证据；本次没有数据库迁移。

第一轮真实验收发现了权限配置不足与现有报告校验反馈不明确的问题，不能将“部署就绪”视为“业务验收通过”。最终候选及验收结论另见测试文档。
