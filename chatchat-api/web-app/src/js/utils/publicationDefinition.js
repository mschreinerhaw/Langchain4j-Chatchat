export function definitionObject(value) {
  try {
    const parsed = typeof value === 'string' ? JSON.parse(value) : value;
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
  } catch { return {}; }
}

const families = {
  database_query: '数据查询', sql_datasource: '数据库运维', ssh_host: '主机命令',
  http_endpoint: 'HTTP 请求', python_runtime: 'Python 服务', api_service: 'API 服务',
};

export function publicationTemplates(definition, catalog = []) {
  const keys = Array.isArray(definition?.templateKeys) ? definition.templateKeys.filter(key => typeof key === 'string') : [];
  const current = new Map((Array.isArray(catalog) ? catalog : []).map(item => [item.key, item]));
  return [...new Set(keys)].map((key, index) => {
    const item = current.get(key);
    const family = families[item?.assetType || key.split(':')[0]] || '受控模板';
    return { key, family, available: Boolean(item), title: item?.title || `已绑定${family} ${index + 1}`,
      description: item?.description || '', category: item?.businessCategoryName || item?.category || '',
      parameterSchema: item?.parameterSchema || {} };
  });
}

const fields = {
  query: ['查询条件', '描述需要查找的模板或数据。'],
  pageSize: ['每页条数', '每页希望返回的条数。'],
  limit: ['返回上限', '本次最多返回的条数。'],
  filters: ['筛选条件', '在已绑定的模板范围内进一步筛选。'],
  cursor: ['续查标记', '继续读取上一页结果时使用。'],
  pageToken: ['翻页标记', '读取下一页时使用返回的分页标记。'],
  intentZh: ['中文意图', '补充中文业务意图，帮助匹配模板。'],
  intentEn: ['英文意图', '补充英文业务意图，帮助匹配模板。'],
  bilingualIntent: ['多语言意图', '提供多种语言的检索意图。'],
  trace: ['调用跟踪', '随调用传递的跟踪信息。'],
  assetType: ['数据类型', '提供数据类型提示；实际范围由本工具的模板绑定决定。'],
};
const types = { string: '文本', integer: '整数', number: '数值', boolean: '是 / 否', array: '列表', object: '字段集合', null: '空值' };
const formats = { date: '日期', 'date-time': '日期时间', uri: '链接', email: '邮箱地址' };
export function definitionValue(value) {
  if (value === null) return '空值';
  if (value === true) return '是';
  if (value === false) return '否';
  if (Array.isArray(value)) return value.length ? value.map(definitionValue).join('、') : '空列表';
  if (typeof value === 'object') return `${Object.keys(value).length} 个配置项`;
  return String(value);
}

export function publicationSchemaFields(value) {
  const root = definitionObject(value), rows = [];
  function resolve(raw, seen) {
    let field = definitionObject(raw);
    while (field.$ref) {
      const ref = field.$ref;
      if (typeof ref !== 'string' || !ref.startsWith('#/') || seen.has(ref)) return { field, recursive: true };
      seen.add(ref);
      const target = ref.slice(2).split('/').reduce((node, key) => node?.[key.replaceAll('~1', '/').replaceAll('~0', '~')], root);
      if (!target) return { field, recursive: true };
      const { $ref, ...overrides } = field;
      field = { ...definitionObject(target), ...overrides };
    }
    return { field, recursive: false };
  }
  function visit(schema, path = '', depth = 0, seen = new Set()) {
    if (depth > 6 || rows.length >= 100) return;
    const required = new Set(Array.isArray(schema.required) ? schema.required : []);
    for (const [name, raw] of Object.entries(definitionObject(schema.properties))) {
      const branch = new Set(seen), { field, recursive } = resolve(raw, branch);
      const declared = Array.isArray(field.type) ? field.type : field.type ? [field.type] : field.properties ? ['object'] : field.items ? ['array'] : [];
      const variants = Array.isArray(field.anyOf) ? field.anyOf : Array.isArray(field.oneOf) ? field.oneOf : [];
      const alternatives = [...declared, ...variants.flatMap(option => Array.isArray(option?.type) ? option.type : option?.type ? [option.type] : [])];
      const type = [...new Set(alternatives.filter(type => type !== 'null'))].map(type => types[type] || '其他').join(' / ') || (alternatives.includes('null') ? '空值' : '按模板要求');
      const bounds = [];
      if (field.minimum !== undefined) bounds.push(`不小于 ${field.minimum}`);
      if (field.maximum !== undefined) bounds.push(`不大于 ${field.maximum}`);
      if (field.minItems !== undefined) bounds.push(`至少 ${field.minItems} 项`);
      if (field.maxItems !== undefined) bounds.push(`最多 ${field.maxItems} 项`);
      if (field.minLength !== undefined) bounds.push(`至少 ${field.minLength} 个字符`);
      if (field.maxLength !== undefined) bounds.push(`最多 ${field.maxLength} 个字符`);
      const generic = Object.hasOwn(fields, name) ? fields[name] : null;
      const description = typeof field.description === 'string' ? field.description : '';
      const key = path ? `${path}.${name}` : name;
      rows.push({ key, name, depth, title: field.title || generic?.[0] || name,
        type: formats[field.format] || type, required: required.has(name), nullable: alternatives.includes('null'),
        description: generic && !/[\u3400-\u9fff]/.test(description) ? generic[1] : description,
        hasDefault: Object.hasOwn(field, 'default'), defaultText: Object.hasOwn(field, 'default') ? definitionValue(field.default) : '',
        choices: Array.isArray(field.enum) ? field.enum.map(definitionValue).join('、') : '', bounds: bounds.join('；'), recursive });
      if (!recursive) {
        if (field.properties) visit(field, key, depth + 1, branch);
        else if (field.items) {
          const item = resolve(field.items, branch);
          if (!item.recursive) visit(item.field, key + '[]', depth + 1, branch);
        }
      }
      if (rows.length >= 100) break;
    }
  }
  visit(root);
  return rows;
}
