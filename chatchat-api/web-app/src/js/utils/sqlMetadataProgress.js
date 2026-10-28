const titles={AUTHORIZE:'正在确认授权数据源',LIST_ASSETS:'正在检索数据库目录',LIST_NAMESPACES:'正在检索命名空间',SEARCH:'正在检索表目录',DESCRIBE:'正在分析表结构',MODEL:'正在分析元数据',ASSEMBLE:'正在整理分析结果'};
const reasonLabels={INVALID_METADATA_ARGUMENTS:'元数据参数需修正',AUTHORIZATION_DENIED:'元数据访问未获授权',METADATA_UNAVAILABLE:'元数据索引不可用，需要刷新',SNAPSHOT_CONFLICT:'结构或授权范围已变化',MODEL_REQUEST_TIMEOUT:'模型请求超时',METADATA_TOOL_TIMEOUT:'元数据工具超时',TASK_DEADLINE_EXCEEDED:'任务总截止时间已到',CANCELLED:'任务已取消'};
export function sqlTaskStatus(task={}){
  task ||= {};
  const state=task.canonicalState;
  return ({SUCCEEDED:'SUCCESS',PARTIAL:'PARTIAL',CANCELLED:'CANCELLED',FAILED:'FAILED',REJECTED:'REJECTED',REPAIRING:'RETRYING',RETRY_WAIT:'RETRYING'})[state]
    || (['CREATED','QUEUED','PLANNING','EXECUTING','VERIFYING','REPAIRING','RETRY_WAIT','CANCEL_REQUESTED','WAIT_MODEL','WAIT_TOOL','RUNNING'].includes(state)&&['FAILED','SUCCESS','PARTIAL'].includes(task.status)?'RUNNING':task.status);
}
export function sqlMetadataProgress(events=[],task={},pending='',started=0,now=Date.now()){
  task ||= {};events ||= [];
  const operations=new Map();
  for(const event of events){let value=event.payload??event;try{if(typeof value==='string')value=JSON.parse(value);}catch{continue;}
    const meta=value.payload?.metadata||value.metadata||value;
    if(meta.eventKind!=='SQL_METADATA_EXPLORATION'||!meta.operationId)continue;
    const attempt=value.runId || event.attemptId || event.runId || '';
    const id=attempt?`${attempt}:${meta.operationId}`:meta.operationId;
    operations.set(id,{id,operation:meta.operation,title:titles[meta.operation]||'正在读取元数据',
      state:meta.eventState,elapsedMs:Number(meta.elapsedMs)||0,reused:meta.reused===true,reason:reasonLabels[meta.reasonCode]||meta.reasonCode||''});
  }
  const status=sqlTaskStatus(task)||'IDLE',terminal=['SUCCESS','PARTIAL','FAILED','CANCELLED','REJECTED','TIMEOUT_CANCELLED','TIME_BUDGET_EXHAUSTED'].includes(status)&&!pending;
  const running=Boolean(pending||task.taskId&&!terminal),groups=new Map();
  for(const operation of operations.values()){
    if(!groups.has(operation.operation))groups.set(operation.operation,{id:operation.operation,title:operation.title,operations:[],elapsedMs:0,count:0});
    const group=groups.get(operation.operation);group.operations.push(operation);group.elapsedMs+=operation.elapsedMs;group.count++;
  }
  const metadataGroups=[...groups.values()].map(group=>({...group,state:group.operations.some(o=>o.state==='RUNNING')&&!terminal?'RUNNING':group.operations.some(o=>o.state==='FAILED')?'FAILED':status==='CANCELLED'?'CANCELLED':group.operations.some(o=>o.state==='RUNNING')?'STOPPED':'COMPLETED'}));
  const current=[...operations.values()].findLast(o=>o.state==='RUNNING');
  const beginnings=[Date.parse(task.createTime||''),started,...events.map(event=>Date.parse(event.createTime||''))].filter(value=>Number.isFinite(value)&&value>0);
  const beginning=pending&&started?started:beginnings.length?Math.min(...beginnings):0,end=terminal?Date.parse(task.finishedAt||task.updateTime||'')||now:now;
  const elapsed=beginning?Math.max(0,Math.floor((end-beginning)/1000)):0;
  return {status,running,terminal,metadataGroups,steps:[],elapsed,elapsedLabel:`${Math.floor(elapsed/60).toString().padStart(2,'0')}:${(elapsed%60).toString().padStart(2,'0')}`,
    title:pending?'正在提交数据库探索请求':running?current?.title||'正在等待后端事件':({SUCCESS:'分析已完成',PARTIAL:'分析部分完成',CANCELLED:'任务已取消',FAILED:'分析失败',REJECTED:'任务未获授权'})[status]||'准备就绪',
    detail:terminal?status==='SUCCESS'?'元数据分析已完成；未执行业务 SQL。':[...operations.values()].findLast(o=>o.reason)?.reason||task.errorMessage||'元数据分析已结束；未执行业务 SQL。':current?.title||'发送问题后查看真实检索和模型执行进展。'};
}
