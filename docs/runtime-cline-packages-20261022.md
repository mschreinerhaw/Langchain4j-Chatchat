# Cline 五包完整部署

线上原依赖只有 `@cline/agents@0.0.93`，传递安装 `@cline/llms` 和 `@cline/shared`；对实际容器逐包导入确认 `sdk/core` 缺失。根据官方 SDK 依赖关系，sdk 依赖 core，core 依赖 agents/llms/shared。本次五包均显式锁定 `0.0.93`，同步 package-lock，不升级既有 Agent 版本。

新增 `verify:sdk` 实际导入五包并检查版本；Docker 构建和服务启动前执行同样检查。检查通过才开始监听，受保护的 `/healthz` 返回实际已验证版本。运行时不因导入而创建 Session、启动 Hub 或自动注册宿主工具，现有 Java 模型/预算/授权回调与 Python Sandbox 保持。

Linux 依赖必须根据锁文件全新安装，不能复制旧容器缺包的 node_modules。本次在 Linux 构建阶段执行 `npm ci --ignore-scripts`、编译及 `npm prune --omit=dev --ignore-scripts`，再向纯 Node 24 运行镜像复制生产依赖和应用；也可独立用 `npm ci --omit=dev --ignore-scripts` 安装纯生产依赖。验证镜像大小，不以旧业务镜像作为新基础累积历史层。

本地实际五包导入及公共 SDK/Core/Agent 导出一致性检查通过，Node 回归 47 项通过。Linux 镜像、运行用户导入、服务健康及部署结果另外记录，不把本地 Windows 安装作为 Linux 验收。

官方说明：[SDK 包说明](https://github.com/cline/cline/blob/main/sdk/packages/README.md)、[公共 SDK 入口](https://github.com/cline/cline/blob/main/sdk/README.md)。

## 已部署结果

镜像 `runtime-os-coding-agent:1.2.0-20261022-cline-packages`，镜像 ID `sha256:04e56f01e65d4138ecf9dea8ba1f97e8cecbe2d2aa1e44fe3cefe203945575cb`，418,340,103 字节；相较原镜像增加 24,695,110 字节。SDK 源码镜像提交 `3279ad95f21173a178a8b4047f92340ca83f4815`，发布分支 `release/1.2-20261022`。

| 验证 | 结果 |
|---|---|
| Linux Node 24.21.0 五包导入 | 五个包均为 0.0.93，公共 SDK/Core/Agent 导出一致 |
| Linux 生产镜像回归 | 47 项通过，0 失败、0 跳过 |
| 运行用户 | UID 1000，五包导入与受保护健康接口通过 |
| 协议回归 | 原 PARQUET/CSV 输入能力、健康接口认证保持 |
| 缺包拒绝 | 在实际旧镜像中挂载新检查器，真实返回缺 SDK 的 ERR_MODULE_NOT_FOUND，不把缺包状态算作可用 |
| 部署保护 | 全局活动任务和 Node 会话均为 0 才替换；保存私有配置及停机后监控历史，挂载/环境保持 |
| 服务隔离 | 只替换 coding-agent 和 node-proxy，其他容器不变；NODE_IMAGE/CODING_AGENT_IMAGE 同步为新镜像 |
| 自动恢复 | 容器 healthy / unless-stopped，原宿主巡检 timer active |

首次 Linux 测试夹具未完整挂载，两项夹具相关测试未执行成功，另有一项短超时测试在并发执行下超时。补齐夹具并串行运行后，保留原超时断言，47 项全部通过。测试网关为受控夹具，真实 SDK 和实际 Linux 生产依赖参与运行；不把这些测试声称为真实付费模型端到端业务验收，也不声称 Hub/所有默认宿主工具已配置启用。

部署目录 `/opt/chatchat-releases/1.2.0-20261022-cline-packages`，备份引用在 `evidence/deployment.json`。增加 `--node-only`，复用原发布工具的备份、就绪校验和回滚流程。机器可读验收记录见[部署证据](evidence/runtime-cline-packages-20261022.json)。

锁文件对比确认：既有依赖版本没有升级或删除，新增 87 个锁文件条目，现有 Agent 版本保持不变。

## 离线更新包

服务器文件 `/opt/chatchat-releases/chatchat-1.2.0-20261022-cline-packages-linux-amd64.tar.gz`，114,326,420 字节；SHA-256：`72924bd34a0291ccb7781f02e0174b9ae462fa85ff0276938d05bcfab117e971`。

这是原 Runtime OS 1.2 安装的 Node 组件更新包，包含完整 Linux amd64 Node 镜像、镜像 ID 清单、部署工具、文档和脱敏验收记录。没有客户数据、任务历史、凭据、私有配置或备份，不用于空机器部署 Java/PostgreSQL/Python。离线安装无需访问 npm registry。

解包至 `/opt/chatchat-releases/` 下新目录，按 INSTALL.md 校验清单、docker load，再使用 `--node-only --image-manifest` 部署。已真实解包校验所有文件、加载包内镜像、核对镜像 ID，并在包内镜像中以无网络容器实际导入五包。[包校验记录](evidence/runtime-cline-packages-package-20261022.json)独立保存。安装与回滚都应在空闲任务窗口执行，原自动重启和宿主巡检保持。
