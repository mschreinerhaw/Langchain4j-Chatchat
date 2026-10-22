# Python 默认环境与 Cline 执行兼容修复（2026-10-22）

## 问题定位

历史任务 `0523d722-adb6-4c44-aae3-83b5c143e8f9` 两次执行分别在脚本第 430、398 行失败。生成脚本对 `stock_amounts.items()` 排序后，把数值 `v` 继续按字典读取，触发 `TypeError`；第二次修复仍保留这一错误。两个操作实际都使用已发布的 v5 CJK 镜像，因此该次错误不是旧镜像执行造成。

同时发现两个环境配置问题：API 通过部署环境变量固定分析环境 ID；数据车间资产缓存仍显示 v4 和旧镜像，MCP 实际已发布 v5。原有日志脱敏又只返回异常类型与行号，Cline 无法获得足够的技术修复信息。

## 产品使用

1. 管理员先在 MCP 管理端配置并发布 Python 环境。镜像必须在执行服务器可用，并满足现有安全校验及无网络要求。
2. 当前用户打开「数据车间 → Python 环境」，选择环境卡片上的「设为默认」。页面显示默认标记、发布版本及镜像名称。
3. 新建分析任务自动使用该默认环境的最新发布版本。无需同步修改 API 的 `CHATCHAT_CODING_ANALYSIS_ENVIRONMENT_ID`。
4. 更新同一 MCP 环境的镜像并重新发布后，下一次新任务自动读取新版本及实际依赖。进行中的任务保留既有快照；如果新操作发现发布版本或镜像摘要变化，会要求重新提交，避免混用环境。

默认设置按租户和用户隔离。尚未设置默认且只有一个数据车间环境时兼容自动选择；存在多个环境则明确要求选择默认。没有数据车间资产的服务账号保留旧部署配置兼容入口。不可用、未发布或允许联网的默认环境不会自动回退到其他环境。

环境快照包含环境 ID、发布版本、镜像名称与实际摘要、实际 Python 版本及常用已安装分析库版本。能力探测使用受限的无网络临时容器，按镜像摘要缓存，镜像更换后自动重新探测。Cline 根据该快照选择兼容 API。

已知 Python 技术错误返回固定诊断代码和修复建议，包括数值不可下标读取、数值类型冲突、JSON 类型、缺少包及包 API 不可用。原始 stdout、stderr 和业务值继续保持脱敏。该机制改善修复依据，不保证模型生成的所有脚本都正确。

## 数据库升级

在 API 数据库执行 `database/migration/postgresql/V20261022_01__python_default_environment.sql`，然后更新 API 与 MCP 镜像。生产环境使用 schema validate 时必须先建表。此迁移只新增 `ds_python_environment_preference`，不修改已有业务表或 Python 环境。

默认接口：`POST /api/v1/data-science/python/assets/{assetId}/default`，使用现有产品登录凭据；只允许选择自己的资产。`GET /api/v1/data-science/python/workbench` 增加 `defaultAssetId`。

MCP 内部能力接口：`GET /api/v1/python/environments/{id}/runtime`，沿用管理端鉴权。受管执行接口增加可选 `expectedEnvironmentVersion` 与 `expectedImageDigest`，旧调用兼容；新 Cline 任务携带快照进行一致性校验。

## 部署与回滚原则

本次补丁使用独立目录 `/opt/chatchat-releases/1.2.0-20261022-python-default`，镜像标签 `runtime-os-api:1.2.0-20261022-python-default` 与 `runtime-os-mcp:1.2.0-20261022-python-default`。原始 `1.2.0-20261022` 发布包保持不变。

更新前检查活动任务、备份 PostgreSQL 和 Compose 配置；API 停止后备份 RocksDB。API 使用已有纯运行时基础镜像，MCP 使用纯 Java 基础镜像；不以业务镜像为基础叠加 JAR。每个镜像只允许一个业务 JAR 复制层且小于 1200 MiB。

只更新 API、MCP 并刷新代理。保留数据库、用户文件、环境定义、密钥、现有挂载和无关容器。启动失败恢复备份 Compose/env 并重建原 API/MCP、刷新代理。新增偏好表可保留，回滚旧版本不需要删除表。

## 本地验证

- 默认设置的归属隔离、多个环境不随意回退、默认环境更新后自动读取新版本。
- 分析任务合约采用默认环境及真实 Python/依赖版本、镜像摘要。
- 发布版本或镜像摘要改变时，受管执行在创建操作及容器前拒绝。
- 历史标量下标错误的子进程复现及脱敏诊断；未知异常不泄露原消息。
- 数据车间已有前端测试及 Vite 构建。

本地 Docker 不可用，真实容器验证在部署服务器进行。
