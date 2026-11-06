import {it,expect} from 'vitest';
import {explorationTreeRows,sortedTableRows,workspaceBusinessName,businessNodeTitle,explorationCanvasNode,reconcileExploration,explorationNodeState,explorationMetricDisplay,explorationDeliveryStages,explorationLifecycleStatus} from './explorationWorkspace';
it('shows the actual discovery, delivery, execution and review lifecycle without promoting zero exit',()=>{
 const events=[];expect(explorationLifecycleStatus(events).label).toBe('任务已提交');
 events.push({type:'MODEL_STARTED'});expect(explorationLifecycleStatus(events).label).toBe('Cline 分析中');
 events.push({payload:{metadata:{workerActivity:{type:'TOOL_STARTED',tool:'market_get_day_snapshot'}}}});
 expect(explorationLifecycleStatus(events).label).toBe('数据发现中');
 events.push({type:'CODING_INPUT_READY'});expect(explorationLifecycleStatus(events).label).toBe('数据已交付');
 events.push({type:'TOOL_STARTED',tool:'python_execute'});expect(explorationLifecycleStatus(events).label).toBe('Python 执行中');
 events.push({type:'TOOL_FINISHED',tool:'python_execute',executorStopped:true,exitCode:0,resultAvailable:false,outputStatus:'REQUIRED_OUTPUT_MISSING',
  executionFeedback:{error:{message:'Missing /workspace/output/results/rows.jsonl'}}});
 expect(explorationLifecycleStatus(events)).toMatchObject({code:'FAILED',detail:'Missing /workspace/output/results/rows.jsonl'});
 events.push({type:'TOOL_STARTED',tool:'artifact_read'});expect(explorationLifecycleStatus(events).label).toBe('报告生成与校验中');
 expect(explorationLifecycleStatus(events,{terminal:true,resultAvailable:true,status:'COMPLETED'}).label).toBe('报告交付成功');
});
it('retains partial/input-required outcomes and the real missing file on terminal failure',()=>{
 const failed=[{type:'TOOL_FINISHED',tool:'python_execute',executorStopped:true,resultAvailable:false,
  executionFeedback:{error:{message:'Missing /workspace/output/results/rows.jsonl'}}}];
 expect(explorationLifecycleStatus(failed,{terminal:true,status:'FAILED',summary:'Repair attempts exhausted'}).detail)
  .toBe('Missing /workspace/output/results/rows.jsonl；Repair attempts exhausted');
 expect(explorationLifecycleStatus([...failed,{type:'TOOL_STARTED',tool:'python_execute'}],{terminal:true,status:'FAILED',summary:'Latest execution unknown'}).detail)
  .toBe('Latest execution unknown');
 failed[0].executionFeedback.error.stack=[{file:'analysis_v2.py',line:350}];
 expect(explorationLifecycleStatus(failed,{terminal:true,status:'FAILED',reasonCode:'BUDGET_EXCEEDED'}).detail)
  .toBe('Missing /workspace/output/results/rows.jsonl (analysis_v2.py:350)；BUDGET_EXCEEDED');
 expect(explorationLifecycleStatus([],{terminal:true,status:'FAILED',resultAvailable:false,error:'Chart /output/charts/close.json is missing'}))
  .toMatchObject({code:'FAILED',detail:'Chart /output/charts/close.json is missing'});
 expect(explorationLifecycleStatus([],{terminal:true,status:'FAILED',resultAvailable:true}).code).toBe('PARTIAL');
 expect(explorationLifecycleStatus([],{terminal:true,status:'COMPLETED',resultAvailable:true,results:[{analysisStatus:'INPUT_REQUIRED'}]}).code).toBe('INPUT_REQUIRED');
 expect(explorationLifecycleStatus([],{terminal:false,status:'RUNNING',resultAvailable:true}).code).toBe('REPORTING');
});
it('does not let an older partial archive hide the current Python execution stage',()=>{
 const receipt={terminal:false,resultAvailable:true,status:'RUNNING',visualReport:{status:'PARTIAL',execution:{operationId:'old',executorStopped:true}}};
 const events=[{type:'TOOL_STARTED',tool:'python_execute',operationId:'new'}];
 expect(explorationLifecycleStatus(events,receipt).code).toBe('EXECUTING');
 expect(explorationDeliveryStages(events,receipt)[2].value).toBe('执行中');
});
it('restores independent delivery facts from the exact archived execution after bounded events omit stop',()=>{
 const events=[{type:'MODEL_STARTED'},{type:'TOOL_STARTED',tool:'python_execute',operationId:'own'}];
 const receipt={terminal:true,resultAvailable:true,visualReport:{execution:{operationId:'own',executorStopped:true,inputSnapshotRef:'snapshot'}}};
 expect(explorationDeliveryStages(events,receipt).map(s=>s.value)).toEqual(['已停止','快照已就绪','进程已结束','已归档']);
});
it('does not use an older archived stop as proof of a newer execution stopping',()=>{
 const events=[{type:'TOOL_STARTED',tool:'python_execute',operationId:'new'}];
 const receipt={terminal:true,resultAvailable:true,visualReport:{execution:{operationId:'old',executorStopped:true}}};
 expect(explorationDeliveryStages(events,receipt)[2].value).toBe('最终状态待核对');
 receipt.terminal=false;expect(explorationDeliveryStages(events,receipt)[2].value).toBe('执行中');
});
it('restores stopped archive execution without inventing a stopped unarchived process',()=>{
 expect(explorationDeliveryStages([],{visualReport:{execution:{operationId:'own',executorStopped:true}}})[2].value).toBe('进程已结束');
 expect(explorationDeliveryStages([],{terminal:true})[2].value).toBe('未执行');
});
it('keeps the original completed receipt while displaying its input-required business outcome',()=>{
 const receipt={status:'COMPLETED',resultAvailable:true,visualReport:{status:'NO_DATA',requirementStatus:'INPUT_REQUIRED'}};
 expect(explorationNodeState({receipt})).toEqual({code:'COMPLETED',label:'需补充数据'});
 expect(receipt.status).toBe('COMPLETED');expect(explorationCanvasNode([{id:'current',receipt}],'current').id).toBe('current');
 expect(explorationNodeState({receipt:{...receipt,status:'FAILED'}}).code).toBe('FAILED');
});
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
it.each(['1000000000000000.31','1000000000000001.215','0.1234567890123456789','1234567890123456789e-19'])('retains exact high precision Decimal metric %s without a binary Number conversion',value=>{
 expect(explorationMetricDisplay(value)).toBe(value);
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
