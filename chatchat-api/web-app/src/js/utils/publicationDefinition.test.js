import { expect, it } from 'vitest';
import { publicationTemplates, publicationSchemaFields, definitionObject } from './publicationDefinition.js';

it('keeps the saved membership and order when the current catalog adds or removes templates', () => {
  const definition = { templateKeys: ['database_query:old', 'database_query:current'] };
  const before = JSON.stringify(definition);
  const rows = publicationTemplates(definition, [
    { key: 'database_query:current', title: '当前模板', assetType: 'database_query' },
    { key: 'database_query:unbound', title: '未绑定模板' },
  ]);
  expect(rows.map(row => row.key)).toEqual(definition.templateKeys);
  expect(rows[0].available).toBe(false);
  expect(rows[0].family).toBe('数据查询');
  expect(rows[1].title).toBe('当前模板');
  expect(rows.some(row => row.title === '未绑定模板')).toBe(false);
  expect(JSON.stringify(definition)).toBe(before);
});

it('preserves defaults, nullability, requirements, descriptions and readable nested references', () => {
  const rows = publicationSchemaFields({ type: 'object', properties: {
    limit: { type: 'integer', default: 0, minimum: 0, maximum: 20 },
    enabled: { type: 'boolean', default: false },
    category: { type: ['string', 'null'], default: null },
    query: { type: 'string', description: 'Data acquisition request' },
    filters: { $ref: '#/$defs/filter' },
  }, required: ['limit'], $defs: { filter: { type: 'object', properties: { name: { type: 'string', title: '名称' } }, required: ['name'] } } });
  expect(rows[0]).toMatchObject({ title: '返回上限', defaultText: '0', hasDefault: true, required: true, type: '整数' });
  expect(rows[0].bounds).toContain('20');
  expect(rows[1].defaultText).toBe('否');
  expect(rows[2]).toMatchObject({ defaultText: '空值', nullable: true });
  expect(rows[3].description).toBe('描述需要查找的模板或数据。');
  expect(rows.find(row => row.key === 'filters.name')).toMatchObject({ title: '名称', depth: 1, required: true });
});

it('safely handles malformed definitions, unknown schema variants and recursive local references', () => {
  expect(definitionObject('invalid')).toEqual({});
  expect(publicationTemplates({ templateKeys: 'invalid' })).toEqual([]);
  expect(publicationSchemaFields('invalid')).toEqual([]);
  const rows = publicationSchemaFields({ properties: { node: { $ref: '#/$defs/node' }, variant: { anyOf: {} } }, $defs: { node: { type: 'object', properties: { child: { $ref: '#/$defs/node' } } } } });
  expect(rows.find(row => row.key === 'node.child').recursive).toBe(true);
  expect(rows).toHaveLength(3);
});
