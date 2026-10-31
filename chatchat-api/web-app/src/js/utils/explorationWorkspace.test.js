import {it,expect} from 'vitest';
import {explorationTreeRows,sortedTableRows,workspaceBusinessName,businessNodeTitle} from './explorationWorkspace';
it('preserves branches, collapse, orphans and cycles',()=>{
 const nodes=[{id:'a'},{id:'b',parentId:'a'},{id:'c',parentId:'a'},{id:'d',parentId:'b'},{id:'orphan',parentId:'absent'}];expect(explorationTreeRows(nodes).map(n=>[n.id,n.depth])).toEqual([['a',0],['b',1],['d',2],['c',1],['orphan',0]]);expect(explorationTreeRows(nodes,['b']).map(n=>n.id)).toEqual(['a','b','c','orphan']);expect(explorationTreeRows([{id:'a',parentId:'b'},{id:'b',parentId:'a'}])).toHaveLength(2);
});
it('uses business names and stable numeric sorting without mutation',()=>{
 expect(workspaceBusinessName('客户交易分析 4e417c36-5998-4c8c-90b3-7fa3e5a58832')).toBe('客户交易分析');
 expect(workspaceBusinessName('客户资产探索验收 2026-10-30T06:58:56.704Z')).toBe('客户资产分析');expect(businessNodeTitle({question:'请校正资产单位和图表轴；不得虚构'})).toBe('资产结构与口径核对');const rows=[['10'],['2'],['2'],[null]],copy=JSON.stringify(rows);expect(sortedTableRows(rows,0,'asc')).toEqual([['2'],['2'],['10'],[null]]);expect(JSON.stringify(rows)).toBe(copy);
});
