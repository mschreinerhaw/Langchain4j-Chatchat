import {it,expect} from 'vitest';
import {explorationTreeRows,sortedTableRows,workspaceBusinessName,businessNodeTitle,explorationCanvasNode,reconcileExploration,explorationNodeState,explorationMetricDisplay} from './explorationWorkspace';
it.each(['FAILED','BUDGET_EXCEEDED','COMPLETED'])('does not borrow historical results after authoritative %s without a result',status=>{
 const root={id:'root',receipt:{status:'COMPLETED',resultAvailable:true}};
 const current={id:'current',parentId:'root',receipt:{status,terminal:true,resultAvailable:false}};
 expect(explorationNodeState(current,'current').code).toBe(status==='CANCELLED'?'CANCELLED':status==='COMPLETED'?'COMPLETED':'FAILED');
 expect(explorationCanvasNode([root,current],'current')).toBeNull();
 current.receipt.resultAvailable=true;
 expect(explorationCanvasNode([root,current],'current')).toBe(current);
});
it('labels incomplete archived reports separately from full delivery without changing execution facts',()=>{
 const receipt={status:'COMPLETED',terminal:true,resultAvailable:true,visualReport:{status:'PARTIAL'}};
 expect(explorationNodeState({receipt})).toEqual({code:'COMPLETED',label:'部分交付'});
 expect(receipt.status).toBe('COMPLETED');
 expect(explorationNodeState({receipt:{status:'BUDGET_EXCEEDED'}}).label).toBe('预算已耗尽');
});
it('distinguishes a pending stop from an authoritative cancellation while retaining stage results',()=>{
 const node={id:'node',receipt:{agentTaskId:'task',status:'EXECUTING',terminal:false,cancellationRequested:true,resultAvailable:true}};
 expect(explorationNodeState(node)).toEqual({code:'STOPPING',label:'正在停止'});
 node.receipt.status='CANCELLED';node.receipt.terminal=true;
 expect(explorationNodeState(node,'node')).toEqual({code:'CANCELLED',label:'已停止'});
 expect(node.receipt.resultAvailable).toBe(true);
 node.receipt.status='SUCCEEDED';node.receipt.terminal=false;
 expect(explorationNodeState(node).code).toBe('COMPLETED');
});
it('formats metric presentation without reinterpreting identifiers, unsafe integers or tiny nonzero values',()=>{
 expect(explorationMetricDisplay('1228458.3900')).toBe('1,228,458.39');expect(explorationMetricDisplay('001234')).toBe('001234');expect(explorationMetricDisplay('90071992547409930000')).toBe('90071992547409930000');expect(explorationMetricDisplay('1e-10')).not.toBe('0');expect(explorationMetricDisplay('未知')).toBe('未知');
});
it('keeps a completed ancestor visible for pending and failed branches, with explicit independent statuses',()=>{
 const root={id:'root',receipt:{resultAvailable:true}},pending={id:'new',parentId:'root',receipt:{agentTaskId:'task',terminal:false,resultAvailable:false,status:'EXECUTING'}};
 expect(explorationCanvasNode([root,pending],'new')).toBe(root);expect(explorationNodeState(pending).code).toBe('RUNNING');
 const failed={...pending,receipt:{...pending.receipt,terminal:true,status:'FAILED',resultAvailable:true}};
 expect(explorationNodeState(failed).code).toBe('FAILED');expect(explorationCanvasNode([root,failed],'new')).toBe(failed);
 expect(explorationCanvasNode([root,pending],'')).toBeNull();
});
it('preserves stable chart evidence across unrelated status updates and drops late workspace revisions',()=>{
 const node={id:'root',receipt:{visualReport:{charts:[{id:'real',artifact:{fileId:'file'}}]}}},old={id:'space',version:3,nodes:[node,{id:'pending',status:'QUEUED'}]};
 const next=reconcileExploration(old,{id:'space',version:4,nodes:[structuredClone(node),{id:'pending',status:'RUNNING'}]});
 expect(next.nodes[0]).toBe(node);expect(next.nodes[0].receipt).toBe(node.receipt);expect(reconcileExploration(next,old)).toBe(next);
});
it('preserves branches, collapse, orphans and cycles',()=>{
 const nodes=[{id:'a'},{id:'b',parentId:'a'},{id:'c',parentId:'a'},{id:'d',parentId:'b'},{id:'orphan',parentId:'absent'}];expect(explorationTreeRows(nodes).map(n=>[n.id,n.depth])).toEqual([['a',0],['b',1],['d',2],['c',1],['orphan',0]]);expect(explorationTreeRows(nodes,['b']).map(n=>n.id)).toEqual(['a','b','c','orphan']);expect(explorationTreeRows([{id:'a',parentId:'b'},{id:'b',parentId:'a'}])).toHaveLength(2);
});
it('uses business names and stable numeric sorting without mutation',()=>{
 expect(workspaceBusinessName('客户交易分析 4e417c36-5998-4c8c-90b3-7fa3e5a58832')).toBe('客户交易分析');
 expect(workspaceBusinessName('客户资产探索验收 2026-10-30T06:58:56.704Z')).toBe('客户资产分析');expect(businessNodeTitle({question:'请校正资产单位和图表轴；不得虚构'})).toBe('资产结构与口径核对');const rows=[['10'],['2'],['2'],[null]],copy=JSON.stringify(rows);expect(sortedTableRows(rows,0,'asc')).toEqual([['2'],['2'],['10'],[null]]);expect(JSON.stringify(rows)).toBe(copy);
});
