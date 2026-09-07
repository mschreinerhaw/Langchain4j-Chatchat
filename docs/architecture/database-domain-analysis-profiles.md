# 数据库维护领域分析 Profile

报告分析框架分为通用方法论、可选领域 Profile、本次问题动态规划三层。金融资产、交易及综合分析的业务视角和章节标题已从 Java 移至 `domain_analysis_profile` 表；运行时不按“资产／交易”等词直接套用模板。

## 加载和生效规则

1. 提示词规划读取当前租户可用 Profile 的类型、名称和适用描述。
2. 如果请求元数据或数据集上下文声明了 `analysisType`，优先使用该声明；否则由规划模型根据实际分析目标选择目录中的类型。`GENERIC` 表示不加载领域内容。
3. 确定类型后，只合并所选 Profile 的分析视角和建议章节。本次模型规划的具体章节标题优先于 Profile 的建议标题。
4. 无对应配置、已停用、类型冲突或数据库不可用时使用通用框架。没有类型声明且规划失败时也保持通用，不根据关键词猜测。
5. 每次规划读取数据库快照；快照内容和版本参与检查点指纹，数据库修改后不会复用旧配置的规划结果。已运行的问题保持本次快照一致性。

Profile 只提供分析指南，不授予字段、公式或事实权威，不绕过后置数据核验。生产方显式提供的固定分析契约仍保持优先。

## 存储与初始化

`domain_analysis_profile` 包含 `tenant_id`、`analysis_type`、名称、适用描述、`enabled`、`guidance_json` 和乐观锁 `revision`。现有 JPA `ddl-auto: update` 配置负责建表。

应用启动时读取 `chatchat-chat/src/main/resources/analysis/domain-profile-defaults.json`，只向全局租户 `__global__` 插入尚不存在的三个金融 Profile。已有数据库记录不会被文件覆盖；该 JSON 是初始数据，不是运行时配置源。停用默认 Profile 应修改 `enabled`，不要删除默认行后期待停用生效。

租户配置覆盖同类型全局配置。停用的租户行会屏蔽对应全局配置。全局配置可由数据库管理人员维护；HTTP 接口只维护认证请求所属租户，不能由请求参数切换租户。

## 维护接口

- `GET /api/v1/analysis-profiles`：查询当前租户的有效配置集合，包含已停用项、`source`（GLOBAL/TENANT）和版本。
- `PUT /api/v1/analysis-profiles/{analysisType}`：新增或更新当前租户的配置；停用时提交 `enabled: false`。

新增租户配置或覆盖全局配置时，`expectedRevision` 为 `null`。编辑已有租户配置时，提交 GET 返回的 `revision`；版本不匹配会拒绝更新，避免覆盖其他维护者的修改。

```json
{
  "name": "金融资产与持仓分析",
  "description": "适用于金融资产、证券持仓和盈亏结构；不用于设备资产分析",
  "enabled": true,
  "expectedRevision": null,
  "guidance": {
    "focus": ["保持币种和观察期间一致", "比较持仓结构与盈亏贡献"],
    "methodology": ["OBSERVE", "DECOMPOSE", "CONTRIBUTION"],
    "output": ["EXECUTIVE_SUMMARY", "KEY_FINDINGS", "LIMITATIONS"],
    "sectionTitles": {
      "EXECUTIVE_SUMMARY": "资产分析摘要",
      "KEY_FINDINGS": "持仓与盈亏分析",
      "LIMITATIONS": "数据口径及范围"
    }
  }
}
```

分析类型支持大写字母、数字和下划线，最长64字符，`GENERIC` 为保留类型。可新增零售、制造等 Profile，无需修改运行时类型枚举。指南只接受 `focus`、`methodology`、`output`、`sectionTitles`，总长度不超过5000字符；方法和章节使用现有动态提示词契约允许的枚举，禁止通过配置增加执行指令字段。

当前提供数据库表和 HTTP 维护接口，未新增前端配置页面。未对正在运行的生产数据库执行修改；启动更新后的应用才进行初始数据入库。
