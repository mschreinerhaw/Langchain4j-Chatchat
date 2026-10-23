import { describe,it,expect } from 'vitest';
import { readFileSync } from 'node:fs';
import McpCenterView from './McpCenterView.js';
describe('published business tool directory',()=>{
 it('defaults to business categories and hides connection and protocol details behind advanced information',()=>{
  expect(McpCenterView.data().toolGroupMode).toBe('category');
  const template=readFileSync(new URL('../../views/McpCenterView.vue',import.meta.url),'utf8');
  expect(template).toContain('已发布工具');expect(template).toContain('业务分类');expect(template).not.toContain('已注册工具');expect(template).not.toContain('后端服务类型');expect(template).toContain('连接与运维信息（高级）');
 });
 it('uses the backend business classification consistently for grouping',()=>{
  const group=McpCenterView.methods.resolveToolGroup.call({toolGroupMode:'category'},{functionalCategory:'客户服务',serviceName:'SQL technical service'});
  expect(group.label).toBe('客户服务');expect(group.key).toBe('category:客户服务');
 });
});
