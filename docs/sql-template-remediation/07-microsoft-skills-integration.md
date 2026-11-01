# 独立原生 Skills 审查

既有四包来自 microsoft/skills 固定提交 3f5b461b2314e01c400564051e92469114c5b68f：azure-kusto、azure-ai-ml-py、mcp-builder、skill-creator。原始正文及子资源保持原生结构，每包附 MIT 许可证；文件大小、SHA256、路径及来源记录在 coding-agent-node/skills/catalog.json。

两项原生资源完整性与读取检查本轮通过。worker 镜像保持不变，原有 Java 领域技能编译和需求分析能力没有修改。原生包不转换成 Runtime OS 格式，也不与 SQL 模板建立发布或执行依赖。

Azure/KQL/ML SDK 技能不能冒充 MySQL/Pandas 通用分析技能。python-data-analysis 1.3.0 是 Runtime OS 自有技能，必须如实标注；仓储 SQL 的 Python 分析应按需读取适用方法，不为验收而注入不适用的 Azure 正文。

[来源、许可证、兼容性及按需读取边界](../sql-tpcds-e2e/01-microsoft-skill-assets.md)与[原生真实读取历史记录](../sql-tpcds-e2e/cline-microsoft-live.json)保留。本轮真实仓储 SQL 分析已有 [NODE_CLINE 按需加载证据](sql-python-skill-load-proof-r3.json)，实际加载 Runtime OS 自有 python-data-analysis 1.3.0；[C01](cases/C01/after-r5/python-and-artifact-validation.json)与[C02](cases/C02/after-r6/python-and-artifact-validation.json)进一步保留实际分析执行和产物。未以 Azure 技能冒充 MySQL 通用分析，不以目录存在替代使用证据。

[当前r9两类真实SQL/Python分析的按需加载记录](sql-python-skill-load-proof-r9.json)与对应C01/C02执行原件一起归档。Microsoft包的原始来源、许可、哈希及实际原生读取历史继续有效，worker镜像未变；当前MySQL分析只加载适用的自有Python技能，未将全部Microsoft包正文一次注入Cline。
