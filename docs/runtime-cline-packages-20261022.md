# Cline 五包完整部署

线上原依赖只有 `@cline/agents@0.0.93`，传递安装 `@cline/llms` 和 `@cline/shared`；对实际容器逐包导入确认 `sdk/core` 缺失。根据官方 SDK 依赖关系，sdk 依赖 core，core 依赖 agents/llms/shared。本次五包均显式锁定 `0.0.93`，同步 package-lock，不升级既有 Agent 版本。

新增 `verify:sdk` 实际导入五包并检查版本；Docker 构建和服务启动前执行同样检查。检查通过才开始监听，受保护的 `/healthz` 返回实际已验证版本。运行时不因导入而创建 Session、启动 Hub 或自动注册宿主工具，现有 Java 模型/预算/授权回调与 Python Sandbox 保持。

Linux 生产依赖必须根据锁文件全新 `npm ci --omit=dev --ignore-scripts`，不能复制旧容器缺包的 node_modules。发布构建使用纯 Node 24 基础镜像，生产依赖与应用各复制一次，验证镜像大小；不以旧业务镜像作为新基础累积历史层。

本地实际五包导入及公共 SDK/Core/Agent 导出一致性检查通过，Node 回归 47 项通过。Linux 镜像、运行用户导入、服务健康及部署结果另外记录，不把本地 Windows 安装作为 Linux 验收。

官方说明：[SDK 包说明](https://github.com/cline/cline/blob/main/sdk/packages/README.md)、[公共 SDK 入口](https://github.com/cline/cline/blob/main/sdk/README.md)。
