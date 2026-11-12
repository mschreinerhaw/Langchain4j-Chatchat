# Native 模型发布修复（2026-11-12）

用户界面报错 `Selected model is not published on this Native node`。已发布的 `financial-news-agent` 选择 `qwen3.6-plus`，但主部署 Java 的私有 `modelProfiles`、Node 的模型档案和执行器允许列表只包含默认的 `qwen3.7-plus`。

本次依据现有 `platform_model_publication` 的启用记录补齐该模型的三处映射，保留真正的 provider_model、凭据和 HTTPS 地址。不修改 Agent 定义、平台模型表或联网授权，不回退到默认模型，不引入业务校验或分析规划代码。模型映射是部署配置，Native Core 和镜像应用代码无需重建。

通用操作工具为 `scripts/publish-native-model-profile.py`，模型名通过参数传入。默认只检查，`--apply` 才修改；使用既有 Node 标准加密库解密已有内部凭据，不在主机安装额外包。备份及配置为私有文件，不输出密钥。执行前检查隔离 Docker 中的 Worker 镜像存在。只接受启用的已发布模型和 OpenAI 兼容协议，拒绝已有档案被其他实际模型覆盖。平台允许完整 `/chat/completions` 地址，工具转换为 Native 代理所需基础地址，避免重复追加接口路径。应用后需在任务排空后重新创建 API 并重载 Node/Executor。

真实验证中同时发现上次清理遗漏：隔离 Docker 没有运行 Worker 时，清理器未保留配置中的当前 Worker 镜像。已从主 Docker 恢复原镜像 `sha256:63a961c2be45e0a611f1ffb523f70e3d54f0c2ee507d843504e0497a0f8ca39a`，并修正服务器清理脚本的保留集合，始终包含当前执行器配置的 `workerImage`，不依赖是否存在 Worker 容器。此前未成功启动的检查记录、随后因重复 URL 路径返回 400 的记录均保留，没有修改为成功。增加镜像预检以便在模型配置应用前发现该问题。

验证结果：

* 4 项 Python 配置回归通过：实际模型/凭据与默认模型独立、无效/禁用发布拒绝、完整接口地址归一化、档案冲突保护。
* 在主入口 18100 使用 `financial-news-agent` 和原定 `qwen3.6-plus` 完成真实 Parquet 分析；Cline 自主写 Python、执行、生成 Markdown/结构化结果并交付 Silo。
* task `native-89110ce59cbdcced16e155c49f99ab1105a7542c4a2dc87aa82fc02f3682af54`；独立验证 3 行、合计 47.5、均值 47.5/3 和 Silo 文件 SHA-256。
* 10 次真实模型回执均为 `qwen3.6-plus`，未回退到其他模型，见 [Provider 回执](evidence/native-model-publication-20261112/provider-proof.json) 与 [交付验证](evidence/native-model-publication-20261112/verification.json)。
* 本次两个已停止的验证 Worker 容器已归档日志后删除；再次确认当前隔离 Worker 镜像仍存在。

服务器私有配置备份位于 `/opt/runtime-os/releases/20261112-0d3163eb/model-profile-backup/` 和 `model-profile-backup-2/`；原始失败/成功证据目录为同根路径下的 `model-profile-verification*`。不将这些私有配置提交仓库。

本修复只发布当前 Agent 所需且实际验证的模型，不把未验证的所有模型批量授予节点。今后启用其他 Native 模型时，使用同一通用工具按现有平台发布记录同步；不能只修改 Java 一侧映射。历史失败任务保持真实失败状态，新请求使用修复后的配置。
