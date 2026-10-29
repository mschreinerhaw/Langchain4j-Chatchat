import { terminalSqlTask } from './sqlStudioState';
import { businessTaskProgress } from './runtimeObservationPresentation';
import {sqlMetadataProgress,sqlTaskStatus} from './sqlMetadataProgress';

const labels = {
  IDLE: '准备就绪', PREPARING: '正在制定代码协作计划', SUBMITTING: '正在提交分析任务', PREPARED: '任务尚未提交',
  PENDING: '排队等待', QUEUED: '排队等待', SUBMITTING_TASK: '正在提交任务', RUNNING: '正在分析工程',
  WAIT_MODEL: '模型分析中', WAIT_TOOL: '正在执行工具', RETRYING: '正在重试',
  SUCCESS: '分析已完成', PARTIAL: '分析部分完成', PARTIAL_SUCCESS: '分析部分完成',
  FAILED: '分析失败', CANCELLED: '任务已取消', KILLED: '任务已取消', REJECTED: '任务未获准执行',
  EMPTY: '未生成有效结果', NO_PRESENTABLE_RESULT: '未生成可展示结果',
  TIME_BUDGET_EXHAUSTED: '任务已超时', MODEL_BUDGET_EXHAUSTED: '模型预算已用尽', TIMEOUT_CANCELLED: '任务超时取消', DLQ: '任务执行失败'
};
export function sqlExplorationPhase(events=[]){
  const titles={FINDING_TABLES:'正在查找业务表',CONFIRMING_FIELDS:'正在确认字段关联',VALIDATING_SQL:'正在校验 SQL'};
  for(const event of [...events].reverse()){
    let payload=event.payload;try{if(typeof payload==='string')payload=JSON.parse(payload);}catch{continue;}
    if(titles[payload?.sqlProgressPhase])return titles[payload.sqlProgressPhase];
  }
  return '';
}
export function sqlTaskPresentation(result, pendingPhase = '', pendingStartedAt = 0, now = Date.now()) {
  if(result?.generation?.prepared?.taskRequest?.toolInput?.sqlStudioAction==='explore')return sqlMetadataProgress(result?.events,result?.task,pendingPhase,pendingStartedAt,now);
  const task = result?.task ? {...result.task,status:sqlTaskStatus(result.task)} : null;
  const status = pendingPhase || task?.status || (result?.generation ? 'PREPARED' : 'IDLE');
  const terminal = Boolean(task && terminalSqlTask(task.status)) && !pendingPhase;
  const running = Boolean(pendingPhase || task && !terminalSqlTask(task.status));
  const progress = businessTaskProgress(result?.events || [], task || {});
  const steps = progress.steps;
  const files = result?.files || [];
  const title = result?.outputError && !pendingPhase ? '生成结果需要检查'
    : ({error:'分析失败',cancelled:'任务已取消',timeout:'任务已超时',empty:'需要补充输入'}[progress.status])
      || labels[status] || '任务处理中';
  const detail = pendingPhase === 'PREPARING' ? '模型制定任务目标与探索计划，校验通过后继续。'
    : pendingPhase === 'SUBMITTING' ? '正在交给执行服务，请稍候。'
    : status === 'WAIT_MODEL' ? '正在等待模型返回分析结果。'
    : status === 'WAIT_TOOL' ? '正在等待工具执行结果。'
    : running ? steps[progress.current - 1]?.title || '任务正在运行，进展将在这里持续更新。'
    : status === 'IDLE' ? '发送问题后，在这里查看分析与生成进展。'
    : status === 'PREPARED' ? '可继续提交已保存的请求。'
    : result?.outputError || task?.errorMessage || (files.length ? `${files.length} 个文件${result.generation?.applied ? '已保存到工程' : '待确认保存'}` : '本次任务已结束。');
  const start = pendingPhase ? pendingStartedAt : Date.parse(task?.createTime || '') || pendingStartedAt;
  const end = terminal ? Date.parse(task.finishedAt || task.updateTime || '') || start : now;
  const elapsed = start ? Math.max(0, Math.floor((end - start) / 1000)) : 0;
  const exploration=running?sqlExplorationPhase(result?.events):'';
  return { status, title:exploration||title, detail: exploration||progress.reason || detail, running, terminal, steps, progressLabel: exploration||progress.label, elapsed,
    elapsedLabel: `${Math.floor(elapsed / 60).toString().padStart(2, '0')}:${(elapsed % 60).toString().padStart(2, '0')}` };
}
