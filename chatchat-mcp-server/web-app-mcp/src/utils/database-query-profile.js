export function databaseQueryProfile(source, family = 'relational') {
  const type = source?.type || ({ trino: 'TRINO', neo4j: 'GRAPH', opensearch: 'UNSTRUCTURED', elasticsearch: 'UNSTRUCTURED' })[family] || 'RELATIONAL';
  if (type === 'GRAPH') return {
    type, language: 'Cypher', title: '只读 Neo4j Cypher', queryLabel: 'Cypher 查询语句', stepPrefix: 'CYPHER',
    hint: '使用 Cypher 查询实体、关系与路径；支持 MATCH、OPTIONAL MATCH、WITH、RETURN。',
    placeholder: 'MATCH (company:Company)\nWHERE company.name = $name\nRETURN company.name AS name',
    parameterHint: '使用 $name 绑定参数，参数独立传递；实体标签与关系类型不会识别为参数。',
    previewLabel: 'Cypher 查询语句（参数单独绑定）', jsonBody: false
  };
  if (type === 'UNSTRUCTURED') {
    const engine = (source?.databaseType || family) === 'elasticsearch' ? 'Elasticsearch' : 'OpenSearch';
    return {
      type, language: engine + ' DSL', title: engine + ' JSON DSL', queryLabel: engine + ' DSL 查询体（JSON）', stepPrefix: 'DSL',
      hint: '填写 JSON 查询体，支持关键词、条件过滤和引擎原生向量检索；不填写 HTTP 方法或 /_search 路径。',
      placeholder: '{\n  "query": {\n    "match": {\n      "name": "{{name}}"\n    }\n  }\n}',
      parameterHint: '参数使用完整 JSON 字符串值 "{{name}}"；绑定后保留字符串、数字、数组和对象类型。检索索引在上方单独配置。',
      previewLabel: engine + ' DSL 查询模板（参数按 JSON 值绑定）', jsonBody: true
    };
  }
  return {
    type, language: type === 'TRINO' ? 'Trino SQL' : 'SQL', title: type === 'TRINO' ? '只读 Trino SQL' : '只读 SQL',
    queryLabel: 'SQL 查询语句', stepPrefix: 'SQL', hint: '支持 SELECT、SHOW、DESCRIBE、EXPLAIN',
    placeholder: 'SELECT ... WHERE customer_id = :customerId',
    parameterHint: '可识别 :name、${trade_date}、{{name}}；扫描只补充缺失参数，不覆盖已有配置。',
    previewLabel: '参数代入后 SQL', jsonBody: false
  };
}
