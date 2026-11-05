# Skills 加载与上下文负担审查

本轮 Worker `0449eba07`（继承 f09cb4b5d 的披露收敛）、Java 冻结任务 `c97962110`，基准 `1ecf091df`，沿用 @cline/agents 0.0.93。

## 保留已有能力

skills.js 已使用 Cline 原生 Markdown parser，启动只投递名称、简短描述和 ID；正文、参考文件按需读取。
物理资源范围仍由任务/image catalog 确定，拒绝路径逃逸、符号链接、未列明引用及原生包 hash/大小不一致。
不修改任何上游原生 Skill。默认能力卡片不人为减少成“两个”，不强制简单任务先读取 Skill。
跨层复核还发现 Java 冻结目标残留两处强制读 Skill 的前置句；已删除，完整映射、精度与授权要求保留。Node 已允许自主选择不等于上游目标没有额外约束。
内置 packageCache / disclosureCache 已复用已校验字节及解析结果，不新增跨任务内容缓存。

Java CodingSkillReader 已冻结动态 Skill 版本与资源，重复读取仍检查实时授权；冻结数据不直接等于送入模型上下文。
本轮动态读仍逐次调用该 Java 接口，不能因为 Node 看过正文而跳过撤权检查。

## 同任务重复交付

新增 skill-disclosure.js 只保存有界请求 hash、已读标识，以及当前实际请求中的正文 hash→toolCallId，不保存第二份 transcript。
每次模型调用在 SDK prepare/compaction 完成后扫描实际请求；只有完整输出对象精确相同才返回内容复用引用。
引用包含 skillId/version/path/content hash/原 toolCallId；通过原 skill_read 可恢复。
若正文已被压缩、截断、版本变化或另一个任务请求，重新交付原文。
读取前仍校验 task catalog、Java 活授权/固定版本、原生完整性，缓存不产生任何数据或执行授权。

重复检测统计与实际复用分开：历史上重复请求并不保证当前仍有正文，不能一律返回“已经读过”。
新必要正文继续构成进展；仅引用相同已在上下文的内容不再重置停滞计数。

## 参考文件分段

skill_read 原参数不变，新增可选 offset/limit，按 Unicode code points，limit 为1..16000，默认分页大小8000。
仅 UTF-8 参考文件允许分段；SKILL.md 原生前言/完整语义和二进制资源不拆分。
先完成原完整文件来源/hash校验，再返回片段、原始 hash/大小、returnedContentSha256、offset/nextOffset/totalCodePoints/complete。
不存在静默截断，也不新增 max Skill 次数限制。模型可按目标读取多个必要参考文件。
这降低披露大小；每次仍需验证完整资源，不能宣称减少底层授权或完整性检查 I/O。

自有 python-data-analysis 从1.3.1到1.3.2：将约1.3KB的特定金融配方用法原样移到现有 financial-reporting.md，
SKILL.md 文件降至4,371字节。原函数代码块及金融语义未删除，需该方法时仍从同一参考文件获得。
manifest、字段映射、Decimal、missing/null、源 hash、业务含义与结果校验要求均保留。
Java UI 打包目录的来源是同一个 catalog.json；API 本轮只替换一个冻结任务类，未刷新其 Skill 展示元数据。旧 API 卡片版本与 Worker 的1.3.2不同，执行时实际版本以 task Skill disclosure 为准。

## 统计口径

SKILLS_LOADED 只增加 ID、版本、路径、内容 hash、正文/完整源大小、重复/复用布尔值，普通 Monitor 不显示敏感正文。
loadedSkillCount 为读取过正文的唯一 Skill 版本，loadedReferenceCount 为唯一参考内容，loadedSkillBytes 为唯一原披露内容字节。
重复失败计数按相同工具、参数和失败载荷的指纹判定；不作错误语义推断，模型响应错误及诊断码单列。指纹仅作轻量提示，不影响现有预算/STOP/恢复决策。
该口径使用原 reader 的 instructions/content：自有 Skill 沿用 SDK parser 处理后的正文，原生 Skill 保持完整原文件；
二进制按实际披露的 base64 字符串计量，不冒充磁盘原始字节。文件完整性 hash 和 reader 返回的 size 仍独立保留。
skillDeliveredBytes 为实际重复交付/分页的正文累计字节，不能与完整源字节相加。
skillEstimatedTokens 使用 UTF8_BYTES_DIV_4，只是说明性估计，不能冒充 Provider 计费；Provider 只提供整个请求的真实 usage。
skillContextRatio 是最终请求字节中 Skills 卡片/工具内容的占比，不是精确 Token 占比。

回归包含原文保持、版本变化、上下文消失、任务隔离、动态撤权和 Unicode 边界，见 [验收](../testing/cline-precode-e2e-acceptance.md)。
