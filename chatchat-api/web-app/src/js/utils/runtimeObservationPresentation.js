function upper(value) {
  return String(value || "").trim().toUpperCase();
}

function objectValue(value) {
  return value && typeof value === "object" && !Array.isArray(value) ? value : {};
}

const BUSINESS_STEPS = ['接收任务', '理解需求', '准备数据', '执行分析', '返回结果'];
const STOPPED = new Set(['error', 'cancelled', 'timeout', 'empty']);
function businessState(state) {
  const value = upper(state);
  if (['TIMEOUT', 'TIMED_OUT', 'TIMEOUT_CANCELLED', 'TIME_BUDGET_EXHAUSTED', 'DEADLINE_EXCEEDED'].includes(value)) return 'timeout';
  if (['CANCELLED', 'CANCELED', 'KILLED'].includes(value)) return 'cancelled';
  if (['FAILED', 'ERROR', 'REJECTED', 'DLQ', 'MODEL_BUDGET_EXHAUSTED'].includes(value)) return 'error';
  if (['EMPTY', 'NO_PRESENTABLE_RESULT', 'INPUT_REQUIRED', 'NEEDS_CLARIFICATION'].includes(value)) return 'empty';
  if (['SUCCESS', 'SUCCEEDED', 'COMPLETED', 'DONE', 'VALIDATED', 'PASSED', 'APPLIED'].includes(value)) return 'done';
  if (['PARTIAL', 'PARTIAL_SUCCESS', 'PARTIAL_RESULT'].includes(value)) return 'partial';
  if (['WAIT_CONFIRMATION', 'WAITING_CONFIRMATION', 'WAITING'].includes(value)) return 'waiting';
  return 'active';
}
function businessReason(value, state) {
  const reason = String(value || '').split('\n')[0].slice(0, 180)
    .replace(/(?:Runtime OS|Cline|Python)/gi, '执行服务')
    .replace(/\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b/g, '执行异常');
  return reason || ({ error: '执行失败，请查看任务结果或联系管理员排查。', cancelled: '任务已取消。',
    timeout: '执行超时，未取得完整结果。', empty: '缺少必要输入或未取得有效结果。' }[state] || '');
}

function moment(value) {
  if (typeof value === 'number') return Number.isFinite(value) ? value : 0;
  const parsed = Date.parse(value || ''); return Number.isFinite(parsed) ? parsed : 0;
}
const OPERATION_LABELS = {
  MODEL: ['等待分析响应', '本轮分析响应已返回'], DATA: ['正在获取授权数据', '数据操作已完成'],
  PROGRAM: ['正在准备分析程序', '分析程序已准备'], EXECUTION: ['分析计算正在执行', '分析计算已结束'],
  CODE_CHECK: ['正在检查分析程序', '分析程序检查已结束'],
  VALIDATION: ['正在校验结果产物', '结果校验操作已结束'], PLAN: ['正在识别任务目标', '任务方案已确定']
};
function executionActivity(event, runtime, metadata) {
  const worker = objectValue(metadata.workerActivity || metadata.payload);
  const kind = upper(metadata.eventKind), type = upper(event.type), codingType = upper(metadata.codingEventType);
  const workerType = upper(worker.type), stage = upper(metadata.stage || runtime.stage);
  let category, state, identity, phase;
  const status = metadata.eventState || runtime.status || event.status;
  const observedAt = moment(metadata.observedAt || worker.timestamp || metadata.occurredAt || event.createTime || event.timestamp);
  const owner = metadata.taskId || event.runId || event.attemptId || 'task';
  const operation = metadata.operationId || worker.operationId;
  if (kind === 'ANALYSIS_OPERATION') {
    category = 'EXECUTION'; phase = 3; identity = operation;
    state = metadata.executorStopped ? businessState(status) : metadata.executorConfirmed ? 'active' : 'waiting';
    if (metadata.executorStopped && state === 'active') state = 'waiting';
  } else if (kind === 'CODING_TRACE') {
    phase = 3;
    if (workerType.startsWith('MODEL_') && Number(worker.modelCall) > 0) {
      category = 'MODEL'; identity = `model:${worker.modelCall}`;
      if (['MODEL_USAGE','MODEL_TIMING'].includes(workerType)) state = null;
      else state = workerType === 'MODEL_RESOLVED' ? 'done' : ['MODEL_FAILED','MODEL_RESPONSE_REJECTED'].includes(workerType) ? 'error'
        : operation && worker.admissionPending !== true ? 'active' : 'waiting';
    } else if (workerType === 'CODE_READY' || codingType === 'CODE_COMMITTED') {
      category = 'PROGRAM'; identity = `program:${worker.scriptVersion || worker.codeSha256 || operation || metadata.eventId || event.eventId}`; state = 'done';
    } else if (['EXECUTION_STARTED','EXECUTION_FINISHED'].includes(codingType)) {
      category = 'EXECUTION'; identity = operation || metadata.stepId;
      state = codingType === 'EXECUTION_STARTED' ? 'active' : businessState(status);
      if (codingType === 'EXECUTION_FINISHED' && state === 'active') state = 'waiting';
    } else if (codingType === 'VALIDATION_RECORDED') {
      category = 'VALIDATION'; phase = 4; identity = operation || metadata.stepId;
      state = businessState(worker.validationStatus || status);
    } else if (['TOOL_STARTED','TOOL_FINISHED'].includes(workerType)) {
      const tool = worker.tool;
      // A proposed calculation is not execution-node confirmation. Its actual running/stop receipts are separate.
      if (tool === 'python_execute') return null;
      category = {data_request:'DATA',metadata_read:'DATA',workspace_read:'DATA',input_read:'DATA',skill_read:'PLAN',
        sql_validate:'CODE_CHECK',workspace_propose:'PROGRAM',artifact_read:'VALIDATION',report_submit:'VALIDATION'}[tool];
      if (!category) return null;
      phase = {DATA:2,PLAN:1,CODE_CHECK:3,PROGRAM:3,VALIDATION:4}[category];
      identity = worker.toolCallId || operation;
      state = workerType === 'TOOL_STARTED' ? 'active' : businessState(worker.status || status);
    } else return null;
  } else if (['PROBLEM_ANALYSIS_PLAN','TASK_CLASSIFICATION'].includes(stage)) {
    category = 'PLAN'; phase = 1; identity = stage; state = businessState(status);
  } else if (kind === 'MODEL_INFERENCE') {
    category = 'MODEL'; phase = 1; identity = `response:${metadata.modelPhase || 'analysis'}:${metadata.stepId || 'run'}`;state = businessState(status);
  } else if (['TOOL_CALL','TOOL_RESULT','TOOL_STARTED','TOOL_COMPLETED','TOOL_FAILED','TOOL_CALL_STARTED','TOOL_CALL_COMPLETED'].includes(type)) {
    category = 'DATA'; phase = 2;
    identity = operation || metadata.toolCallId || (['TOOL_RESULT','TOOL_COMPLETED','TOOL_CALL_COMPLETED','TOOL_FAILED'].includes(type) ? event.parentEventId : event.eventId);
    state = type === 'TOOL_FAILED' || runtime.success === false ? 'error'
      : ['TOOL_RESULT','TOOL_COMPLETED','TOOL_CALL_COMPLETED'].includes(type) ? 'done' : 'active';
  } else return null;
  if (!identity) return null;
  return { id: `${owner}:${category}:${identity}`, operationId: operation || String(identity), kind: category, phase, state,
    observedAt, startedAt: moment(worker.startedAt), finishedAt: moment(worker.finishedAt),
    durationMs: typeof worker.durationMs === 'number' ? worker.durationMs : null,
    sequence: Number(event.sequence || event.order || metadata.sequence || 0),
    sourceEventId: event.eventId || metadata.eventId || '', timeBasis: metadata.timeBasis || 'EVENT_TIME',
    retryAttempt: Number(worker.repairAttempt || worker.repairAttempts || 0),
    reason: STOPPED.has(state) ? businessReason(metadata.publicReason || metadata.errorMessage || worker.reason, state) : '' };
}

/** A stateless display projection. Never changes events, scheduling or execution state. */
export function businessEventPresentation(event = {}) {
  if (Object.prototype.hasOwnProperty.call(event, 'businessProgress')) return event.businessProgress;
  let payload = event.payload || {};
  try { if (typeof payload === 'string') payload = JSON.parse(payload); } catch { payload = {}; }
  const runtime = objectValue(payload.payload || payload);
  const metadata = objectValue(runtime.metadata);
  const type = upper(event.type);
  const kind = upper(metadata.eventKind || runtime.eventKind);
  const stage = upper(metadata.stage || runtime.stage);
  const codingType = upper(metadata.codingEventType || runtime.codingEventType);
  const reasonCode = upper(metadata.reasonCode || runtime.reasonCode || event.errorCode);
  const state = businessState(['DEADLINE_EXCEEDED', 'TIME_BUDGET_EXHAUSTED'].includes(reasonCode) ? reasonCode
    : metadata.eventState || runtime.status || event.status);
  const reason = metadata.publicReason || metadata.errorMessage || runtime.publicReason || runtime.errorMessage
    || payload.errorMessage || payload.executionResult?.message || payload.message || event.errorCode;
  const stopped = STOPPED.has(state);
  const activity = executionActivity(event,runtime,metadata);
  const result = (index, status = state, terminal = false) => ({ index, status, terminal,
    occurredAt:moment(metadata.occurredAt||event.createTime||event.timestamp),
    ...((event.attemptId || metadata.attemptId) ? { attemptId: event.attemptId || metadata.attemptId } : {}),
    ...(activity ? {activity} : {}),
    ...(metadata.planningOwner ? {planningOwner:metadata.planningOwner} : {}),
    reason: STOPPED.has(status) ? businessReason(reason, status) : '' });
  if (['RECOVERY_RECONCILED', 'BUDGET_UPDATED'].includes(codingType || type)) {
    return activity ? result(activity.phase, stopped ? state : 'active') : stopped ? result(3) : null;
  }
  if (kind === 'ANALYSIS_OPERATION') return result(3, stopped ? state : 'active');
  if (['ERROR', 'RUNTIME_FAILED', 'RUNTIME_CANCELLED'].includes(type))
    return result(null, type === 'RUNTIME_CANCELLED' ? 'cancelled' : stopped ? state : 'error', true);
  if (['RESULT', 'COMPLETE', 'RUNTIME_COMPLETED', 'FINAL', 'ANSWER'].includes(type)) {
    // An output fragment or a stream-close message is not proof of successful execution.
    const status = businessState(event.status || runtime.status);
    return ['done', 'partial'].includes(status) ? result(4, status, true)
      : STOPPED.has(status) ? result(null, status, true) : null;
  }
  if (type === 'STATUS' || type === 'HEARTBEAT') return stopped ? result(null, state, true) : null;
  if (['NEEDS_CONFIRMATION', 'RUNTIME_CONFIRMATION'].includes(type)) return result(null, 'waiting');
  if (kind === 'CODING_TRACE') {
    if (codingType === 'VALIDATION_RECORDED') return result(4, stopped ? state : 'active');
    return result(3, codingType === 'TASK_FINISHED' ? state : stopped ? state : 'active');
  }
  if (stage === 'RESULT_VALIDATING' || ['RESULT_AGGREGATING', 'RESULT_AGGREGATION_RETRYING', 'FINAL_SYNTHESIS'].includes(stage))
    return result(4, stopped ? state : 'active');
  if (stage === 'CLINE_SUBMITTING' || kind === 'HARNESS_TURN') return result(3, stopped ? state : 'active');
  if (kind === 'ANALYSIS_GRAPH'
      || upper(metadata.type).startsWith('UNIFIED_QUESTION_ANALYSIS_')) return result(3);
  if (stage === 'FILE_REFERENCES_PREPARING' || ['ANALYSIS_TASK_BUILT', 'CODING_REQUEST_VALIDATED', 'CODING_INPUT_PREPARING', 'CODING_INPUT_READY'].includes(kind)
      || stage === 'DATA_PREPARATION_STARTED') return result(2, kind === 'CODING_INPUT_READY' && !stopped ? 'done' : state);
  if (kind === 'SQL_INTENT_IDENTIFIED' || stage === 'PROBLEM_ANALYSIS_PLAN' || stage === 'TASK_CLASSIFICATION'
      || ['KNOWLEDGE_SKILLS', 'MODEL_INFERENCE', 'TOOL_REVIEW_STAGE', 'DAG_VALIDATION', 'DAG_REPAIR'].includes(kind)) return result(1);
  if (upper(metadata.type) === 'LIFECYCLE') {
    const phase = upper(metadata.lifecyclePhase);
    if (phase === 'FINAL_SYNTHESIS') return result(4, stopped ? state : 'active');
    return result(phase === 'STEP_EXECUTION' ? 3 : 1);
  }
  if (['QUESTION', 'TASK_CREATED', 'TASK_STARTED', 'RUNTIME_SUBMITTED', 'RUNTIME_STARTED'].includes(type)) return result(0, stopped ? state : 'done');
  if (type === 'THINK') return null; // A coarse interaction marker does not prove a planning phase.
  if (['PLAN', 'MODEL_REQUEST_STARTED', 'MODEL_RESPONSE_RECEIVED'].includes(type)) return result(1, stopped ? state : type === 'PLAN' ? 'done' : 'active');
  if (['TOOL_CALL', 'TOOL_CALL_STARTED', 'TOOL_STARTED', 'TOOL_RESULT', 'TOOL_COMPLETED', 'TOOL_CALL_COMPLETED', 'TOOL_FAILED'].includes(type))
    return result(2, type === 'TOOL_FAILED' || metadata.success === false || runtime.success === false ? 'error'
      : stopped ? state : ['TOOL_RESULT', 'TOOL_COMPLETED', 'TOOL_CALL_COMPLETED'].includes(type) ? 'done' : 'active');
  if (type === 'RUNTIME_STEP') return result(1, stopped ? state : 'active');
  return stopped ? result(null) : null;
}

export function executionDuration(milliseconds) {
  if (milliseconds == null || !Number.isFinite(milliseconds)) return '未记录';
  const seconds = Math.floor(Math.max(0,milliseconds)/1000);
  return `${String(Math.floor(seconds/60)).padStart(2,'0')}:${String(seconds%60).padStart(2,'0')}`;
}
export function executionStatusLabel(status) {
  return {active:'进行中',waiting:'等待中',retrying:'重试中',done:'已完成',error:'执行失败',timeout:'已超时',
    cancelled:'已取消',partial:'部分完成',empty:'需要补充输入',pending:'未开始',unrecorded:'未单独记录',
    integrated:'并入分析',stopped:'已终止',unconfirmed:'状态待确认',long_running:'执行时间较长',attention:'操作异常'}[status] || '等待状态';
}

/** Read model only: phases and operations derive from persisted receipts; clocks never advance progress. */
export function executionWorkbench(events = [], task = {}, now = Date.now(), policy = {}) {
  const localNow=now;
  const serverClock=moment(task.runtimeHeartbeat?.timestamp),localReceipt=Number(task.runtimeHeartbeat?.receivedAt||0);
  if(serverClock&&localReceipt)now=serverClock+(localNow-localReceipt);
  const phaseTask={...task,attemptId:task.executionSnapshot?.attemptId||task.attemptId,
    canonicalState:task.executionSnapshot?.canonicalState||task.canonicalState};
  let progress = businessTaskProgress(events,phaseTask);
  const ordered = [...events].sort((a,b)=>Number(a.sequence||a.order||0)-Number(b.sequence||b.order||0))
    .map(businessEventPresentation).filter(Boolean);
  const attempt = task.executionSnapshot?.attemptId || task.attemptId || ordered.findLast(value=>value.attemptId)?.attemptId;
  const scoped = attempt && ordered.some(value=>value.attemptId) ? ordered.filter(value=>value.attemptId===attempt) : ordered;
  const operations = new Map();
  for (const observation of scoped) {
    const next = observation.activity;if (!next) continue;
    const previous = operations.get(next.id);
    if (previous && next.sequence && previous.sequence && next.sequence <= previous.sequence) continue;
    const explicitRetry = previous && next.retryAttempt > previous.retryAttempt;
    const state = previous && !explicitRetry && (STOPPED.has(previous.state)
      || previous.state === 'done' && ['active','waiting',null].includes(next.state))
      ? previous.state : next.state || previous?.state || 'waiting';
    const changed = !previous || state!==previous.state;
    operations.set(next.id,{...previous,...next,state,
      startedAt: (explicitRetry ? 0 : previous?.startedAt) || next.startedAt || (['active','waiting'].includes(state)?next.observedAt:0),
      finishedAt: next.finishedAt || (explicitRetry ? 0 : previous?.finishedAt) || (['done','partial',...STOPPED].includes(state)?next.observedAt:0),
      durationMs: next.durationMs ?? (explicitRetry ? null : previous?.durationMs) ?? null,
      changedAt: changed ? next.observedAt : previous.changedAt,
      lastSeenAt: Math.max(previous?.lastSeenAt||0,next.observedAt||0),
      retryAttempt:Math.max(previous?.retryAttempt||0,next.retryAttempt||0)});
  }
  const values=[...operations.values()];
  // A local operation failure may recover; only an explicit repair receipt for
  // that same operation can clear it. Parent terminal failures remain authoritative.
  const recovered = observation => {
    const latest=operations.get(observation?.activity?.id);
    return STOPPED.has(observation?.status) && latest && !STOPPED.has(latest.state)
      && latest.retryAttempt > observation.activity.retryAttempt;
  };
  if (scoped.some(recovered)) {
    progress=businessTaskProgress(events.filter(event=>!recovered(businessEventPresentation(event))),phaseTask);
  }
  const phases=progress.steps.map(step=>({...step}));
  for(const [index,step]of phases.entries()) {
    if(step.status==='done'&&!scoped.some(value=>value.index===index&&value.status==='done')) {
      step.status='unrecorded';step.detail='本阶段未收到独立完成事件，不计为已完成。';
    }
  }
  const active=values.filter(operation=>['active','waiting','retrying'].includes(operation.state));
  for (const operation of active) {
    if (!progress.terminal) {
      phases[operation.phase].status=operation.retryAttempt>0?'retrying':operation.state;
    }
  }
  const snapshot=task.executionSnapshot||task;
  const startedAt=moment(snapshot.createTime)||scoped.find(value=>value.occurredAt>0)?.occurredAt||Number(task.startedAt)||0;
  const finishedAt=moment(snapshot.finishedAt)||scoped.findLast(value=>value.terminal&&value.occurredAt>0)?.occurredAt||moment(task.finishedAt);
  const elapsedMs=startedAt && (!progress.terminal || finishedAt || Number.isFinite(task.latencyMs))
    ? Math.max(0,(progress.terminal ? finishedAt || startedAt+task.latencyMs : now)-startedAt) : null;
  const heartbeat=task.runtimeHeartbeat||{};
  const interval=Number(heartbeat.heartbeatIntervalMs);
  const receivedAt=Math.max(Number(heartbeat.receivedAt||0),Number(task.statusReceivedAt||0));
  const transportStale=!progress.terminal&&interval>0&&receivedAt>0&&localNow-receivedAt>interval*3;
  const currentOperation=active.sort((a,b)=>b.sequence-a.sequence)[0];
  const failedOperation=[...values].reverse().find(operation=>STOPPED.has(operation.state));
  const warningMs=Number(policy.longOperationWarningMs||60000);
  const longOperation=currentOperation?.startedAt>0&&now-currentOperation.startedAt>warningMs;
  const executionUnconfirmed=currentOperation?.timeBasis==='OBSERVED_STATE'&&currentOperation.lastSeenAt>0&&now-currentOperation.lastSeenAt>warningMs;
  const modelRounds=values.filter(operation=>operation.kind==='MODEL'&&operation.state==='done').length;
  const noExecutionResult=!values.some(operation=>operation.kind==='EXECUTION'&&operation.state==='done');
  const repeatedResponses=modelRounds>=Number(policy.responseRoundWarningCount||5)&&noExecutionResult;
  let status=progress.terminal ? progress.status : 'active';
  if (!progress.terminal) status=transportStale||executionUnconfirmed?'unconfirmed'
    :currentOperation?.retryAttempt>0 || ['RETRY_WAIT','REPAIRING'].includes(upper(phaseTask.canonicalState || task.status))?'retrying':failedOperation?'attention'
      :longOperation||repeatedResponses?'long_running':currentOperation?.state||'waiting';
  const alerts=[];
  if(task.progressHydrating)alerts.push('正在同步完整运行记录，当前显示上次收到的执行依据。');
  if(task.historyIncomplete)alerts.push('历史执行记录尚未完整同步，统计仅覆盖当前已收到的操作。');
  if(transportStale)alerts.push('状态同步已中断；当前执行状态无法确认。以下保留最后收到的执行记录。');
  if(executionUnconfirmed&&!transportStale)alerts.push('超过页面运行状态确认提醒间隔，尚未收到新的执行节点确认，无法确认该操作当前仍在执行。');
  if(longOperation&&!transportStale)alerts.push(`当前操作已持续 ${executionDuration(now-currentOperation.startedAt)}，超过页面提醒阈值 ${executionDuration(warningMs)}，尚未收到完成事件；不据此判定执行失败。`);
  if(repeatedResponses)alerts.push(`已收到 ${modelRounds} 轮分析响应，尚未收到计算完成结果。`);
  if(failedOperation&&!progress.terminal)alerts.push(failedOperation.reason||'已收到操作失败事件，尚未收到最终处理结果。');
  const latest=[...values].sort((a,b)=>(b.changedAt-a.changedAt)||(b.sequence-a.sequence));
  const latestAt=latest[0]?.changedAt||0;
  const rows=latest.map(operation=>{
    const label=OPERATION_LABELS[operation.kind];
    const state=progress.terminal&&['active','waiting'].includes(operation.state)?'unrecorded':operation.state;
    const title=state==='done'?label[1]:STOPPED.has(state)?`${label[0].replace(/正在|正在执行/g,'')}未完成`
      :operation.kind==='EXECUTION'&&state==='waiting'?'计算请求已提交，等待执行确认':label[0];
    const duration=operation.durationMs??(operation.startedAt ? Math.max(0,(operation.finishedAt||(!progress.terminal?now:operation.lastSeenAt))-operation.startedAt) : null);
    return {...operation,state,title,statusLabel:executionStatusLabel(state),durationLabel:executionDuration(duration),
      timeLabel:operation.changedAt?new Date(operation.changedAt).toLocaleTimeString('zh-CN',{hour12:false}):'时间未记录',
      detail:operation.reason||(state==='unrecorded'?'任务已结束，本操作的独立结束事件未记录。'
        :operation.retryAttempt>0?`正在进行第 ${operation.retryAttempt} 次修复尝试。`
          :state==='done'?'已收到该操作的真实完成记录。'
            :operation.timeBasis==='OBSERVED_STATE'?'已收到执行节点状态；时长从首次确认开始计算。':'等待该操作的后续执行事件。')};
  });
  // Union intervals by category: parallel operations are never double counted in a category.
  const timings=Object.keys(OPERATION_LABELS).map(kind=>{
    const intervals=values.filter(operation=>operation.kind===kind&&operation.startedAt>0)
      .map(operation=>[operation.startedAt,operation.finishedAt||(!progress.terminal?now:operation.lastSeenAt)])
      .sort((a,b)=>a[0]-b[0]);
    let sum=0,end=0;
    for(const [start,finish]of intervals){if(finish>Math.max(start,end))sum+=finish-Math.max(start,end);end=Math.max(end,finish);}
    return {kind,label:{MODEL:'分析响应',PLAN:'需求识别',DATA:'数据操作',PROGRAM:'程序准备',CODE_CHECK:'程序检查',EXECUTION:'计算执行',VALIDATION:'结果校验'}[kind],
      durationLabel:intervals.length?executionDuration(sum):'未单独记录'};
  });
  const phase=progress.current;
  return {...progress,steps:phases,status,statusLabel:executionStatusLabel(status),
    completed:phases.filter(step=>step.status==='done').length,
    phaseTitle:phases[phase-1].title,completedLabel:`${String(phases.filter(step=>step.status==='done').length).padStart(2,'0')} / ${String(progress.total).padStart(2,'0')}`,
    completedOperations:values.filter(operation=>operation.state==='done').length,
    elapsedLabel:executionDuration(elapsedMs),activities:rows.slice(0,6),allActivities:rows,timings,alerts,
    currentActivity:rows.find(operation=>operation.id===currentOperation?.id)?.title|| (progress.terminal?'本次任务已结束':'等待新的执行状态'),
    currentEvidenceLabel:currentOperation?.timeBasis==='OBSERVED_STATE'?`最近执行确认：${executionDuration(now-currentOperation.lastSeenAt)} 前`
      :currentOperation?'依据最近收到的操作事件':'尚无独立操作确认',
    latestActivityLabel:latestAt?`${executionDuration(now-latestAt)} 前`:'尚无操作记录',
    transportLabel:progress.terminal?'已收到任务终态':transportStale?'状态同步中断':receivedAt?'状态同步正常':'等待状态同步',
    lastHeartbeatLabel:receivedAt?executionDuration(localNow-receivedAt)+' 前':'尚未收到',attemptId:attempt||'',
    warningThresholdLabel:executionDuration(warningMs)};
}

/** Five stable slots, aggregated from typed observations rather than event counts/time. */
export function businessTaskProgress(events = [], task = {}) {
  const parentState = upper(task.canonicalState || task.status);
  const activeParent = ['SUBMITTED','AUTHORIZING','PLANNING','PLAN_VALIDATING','EXECUTING','EVALUATING',
    'REPAIRING','DELIVERING','PENDING','CLAIMED','RUNNING','RETRY_WAIT','WAIT_MODEL','WAIT_TOOL',
    'WAITING_APPROVAL','WAIT_CONFIRMATION','WAITING_CONFIRM','WAITING','STREAMING','FINALIZING'].includes(parentState);
  const steps = BUSINESS_STEPS.map((title, index) => ({ id: `business-step-${index + 1}`, title,
    number: index + 1, total: BUSINESS_STEPS.length, status: 'pending', detail: '', children: [] }));
  let current = 0;
  let terminal = false;
  const apply = (value) => {
    if (!value) return;
    const failedIndex = steps.findIndex(step => STOPPED.has(step.status));
    if (!activeParent && failedIndex >= 0 && !STOPPED.has(value.status)) return;
    const index = value.index ?? (failedIndex >= 0 ? failedIndex : current);
    const step = steps[index];
    if ((activeParent || !STOPPED.has(step.status)) && !(terminal && !value.terminal)) {
      step.status = value.status;
      step.detail = value.reason || '';
    }
    current = Math.max(current, index);
    terminal ||= !activeParent && value.terminal;
  };
  // Work on a copy: the original event sequence remains available for diagnostics.
  const observations = [...events].sort((a, b) => Number(a.sequence || a.order || 0) - Number(b.sequence || b.order || 0))
    .map(businessEventPresentation).filter(Boolean);
  const attempt = task.attemptId || task.executionAttemptId || observations.findLast(value => value.attemptId)?.attemptId;
  const scoped = attempt && observations.some(value => value.attemptId)
    ? observations.filter(value => value.attemptId === attempt) : observations;
  scoped.forEach(apply);
  if (scoped.some(value => value.planningOwner === 'ANALYSIS_WORKER') && steps[1].status === 'pending') {
    steps[1].status = 'integrated';steps[1].detail = '需求理解与分析执行合并进行，未单独计为完成阶段。';
  }
  steps.forEach((step,index) => {
    if (index < current && step.status === 'pending') {step.status='unrecorded';step.detail='已进入后续阶段，本阶段未单独记录，不计为已完成。';}
  });
  if (parentState) {
    const status = businessState(parentState);
    if (STOPPED.has(status)) apply({ status, terminal: true, reason: businessReason(task.errorMessage, status) });
    else if (['done', 'partial'].includes(status) && task.canonicalState) {
      // The parent is authoritative about task completion; earlier failed
      // operations stay visible as historical facts even after a successful repair.
      steps[4].status=status;current=4;terminal=true;
    }
    else if (['done', 'partial'].includes(status)) apply({ index: 4, status, terminal: true });
    else if (parentState === 'WAITING' || parentState === 'WAIT_CONFIRMATION') apply({ status: 'waiting' });
  }
  if (steps.every(step => step.status === 'pending') && !terminal && parentState) steps[0].status = 'active';
  const blocked = steps.find(step => STOPPED.has(step.status));
  if (terminal) steps.forEach(step => {
    if (step.status === 'active') step.status = blocked ? 'stopped' : steps[4].status === 'done' ? 'done' : 'partial';
    if (step.status === 'pending' && !blocked) step.status = 'unrecorded';
  });
  const parentCompleted=Boolean(task.canonicalState) && ['done','partial'].includes(businessState(parentState)) && terminal;
  const status = activeParent ? (['RETRY_WAIT','REPAIRING'].includes(parentState) ? 'retrying' : blocked ? 'attention' : 'active')
    : parentCompleted ? businessState(parentState) : blocked?.status || (terminal ? steps[4].status : steps.some(step => step.status === 'waiting') ? 'waiting' : 'active');
  const number = parentCompleted ? 5 : blocked?.number || current + 1;
  return { steps, current: number, total: steps.length, label: `${number}/${steps.length}`, status,
    completed: steps.filter(step => step.status === 'done').length, terminal,
    reason: parentCompleted ? '' : blocked?.detail || '' };
}

/**
 * Maps structured Runtime observation metadata to timeline semantics. The UI
 * must never infer failures or repairs by matching human-readable log text.
 */
export function runtimeObservationPresentation(runtimePayload = {}) {
  const metadata = objectValue(runtimePayload.metadata);
  const eventKind = upper(metadata.eventKind);
  const eventState = upper(metadata.eventState);
  const type = upper(metadata.type);
  const stage = upper(metadata.stage);
  if (eventKind === 'FILE_ANALYSIS') {
    const titles = { FILE_REFERENCES_PREPARING: '正在准备分析数据', CLINE_SUBMITTING: '正在分析',
      RESULT_VALIDATING: '正在生成结果' };
    return { title: titles[stage] || '文件分析任务', toolName: 'runtime',
      status: eventState === 'FAILED' ? 'error' : eventState === 'CANCELLED' ? 'cancelled'
        : eventState === 'COMPLETED' ? 'done' : 'active' };
  }
  if (eventKind === 'CODING_TRACE') {
    const titles = { TASK_CREATED: '创建 Python 分析任务', TASK_STARTED: '开始自主编程',
      CODE_COMMITTED: 'Python 代码已归档', EXECUTION_STARTED: '执行 Python 程序',
      EXECUTION_FINISHED: 'Python 执行已返回', VALIDATION_RECORDED: '校验计算结果',
      BUDGET_UPDATED: '更新执行预算', CANCELLATION_REQUESTED: '正在取消执行',
      RECOVERY_RECONCILED: '编程交互进展', TASK_FINISHED: 'Python 分析结束' };
    return { title: titles[metadata.codingEventType] || 'Python 分析进展', toolName: 'coding_agent',
      status: eventState === 'FAILED' ? 'error' : eventState === 'CANCELLED' ? 'cancelled'
        : ['CREATED', 'RUNNING'].includes(eventState) ? 'active' : 'done' };
  }
  if (eventKind === "HARNESS_TURN") {
    const titles = { MODEL_RETURNED: "模型已返回探索请求", EVIDENCE_REQUEST_STARTED: "正在执行取证操作",
      EVIDENCE_REQUEST_FINISHED: `取证已返回 ${metadata.completedRequestCount ?? 0}/${metadata.requestCount ?? 0}` };
    return { title: titles[stage] || "模型分析与证据访问", toolName: "model_native_harness",
      status: eventState === "FAILED" ? "error" : ["STARTED", "RUNNING"].includes(eventState) ? "active" : "done" };
  }
  if (eventKind === 'VISUALIZATION_PLANNING') {
    return { title: '图形化规划', toolName: 'visualization_planning', status: 'done' };
  }
  if (eventKind === "ANALYSIS_GRAPH" || type.startsWith("UNIFIED_QUESTION_ANALYSIS_")) {
    const state = eventState || (type.endsWith("_STARTED") ? "STARTED"
      : type.endsWith("_FAILED") ? "FAILED" : "COMPLETED");
    return {
      title: state === "STARTED" ? "分析全部数据" : state === "COMPLETED"
        ? "全部数据分析完成" : state === "CANCELLED" ? "数据分析已取消" : "数据分析未完成",
      toolName: "analysis_graph",
      status: state === "STARTED" ? "active" : state === "COMPLETED"
        ? "done" : state === "CANCELLED" ? "cancelled" : "error"
    };
  }
  if (eventKind === "KNOWLEDGE_SKILLS") {
    if (stage === "SKILLS_EXTRACTED") {
      return {
        title: "\u63d0\u53d6 Knowledge Skills",
        toolName: "knowledge_skills",
        status: eventState === "FAILED" ? "error" : "done"
      };
    }
    const applied = eventState === "APPLIED" || metadata.applied === true;
    return {
      title: applied ? "\u5e94\u7528 Knowledge Skills" : "Knowledge Skills \u672a\u5e94\u7528",
      toolName: "knowledge_skills",
      status: applied ? "done" : "warning"
    };
  }
  if (eventKind === "TOOL_REVIEW_STAGE") {
    const titles = { TOOL_RESULT_REVIEW_COVERAGE: "模板覆盖检查", TOOL_RESULT_REVIEW_BINDING: "受控参数绑定" };
    return { title: titles[upper(metadata.modelPhase)] || "工具结果审查阶段", toolName: "tool_review_stage",
      status: eventState === "FAILED" ? "error" : (eventState === "COMPLETED" ? "done" : "active") };
  }
  if (eventKind === "MODEL_INFERENCE") {
    const phase = upper(metadata.modelPhase);
    const title = phase === "TOOL_RESULT_REVIEW" ? "模型审查工具结果" : "模型分析中";
    return {
      title,
      toolName: "model_inference",
      status: eventState === "FAILED" ? "error" : (eventState === "COMPLETED" ? "done" : "active")
    };
  }
  if (type.startsWith("BUSINESS_ANALYSIS_") || type === "BUSINESS_ANALYSIS_PROGRESS") {
    if (stage === "DATA_PREPARATION_STARTED") {
      return { title: "准备业务数据", toolName: "业务分析", status: "active" };
    }
    if (["RESULT_AGGREGATING", "RESULT_AGGREGATION_RETRYING"].includes(stage)) {
      return { title: "汇总分析结果", toolName: "业务分析", status: "active" };
    }
    if (["DATA_PROCESSING_COMPLETED", "BUSINESS_RESULT_READY"].includes(stage)) {
      return { title: "业务分析完成", toolName: "业务分析", status: "done" };
    }
    if (["PARTIAL_DATA_UNAVAILABLE", "DATA_PROCESSING_FAILED"].includes(stage)) {
      return { title: "部分业务数据未完成", toolName: "业务分析", status: "warning" };
    }
    if (stage === "DATA_PROCESSING_CANCELLED") {
      return { title: "业务分析已取消", toolName: "业务分析", status: "cancelled" };
    }
    return { title: "处理业务数据", toolName: "业务分析", status: "active" };
  }
  if (eventKind === "DAG_REPAIR") {
    if (eventState === "STARTED") {
      return { title: "DAG 自动修复", toolName: "dag_repair", status: "repairing" };
    }
    if (eventState === "APPLIED") {
      return { title: "DAG 自动修复", toolName: "dag_repair", status: "repaired" };
    }
    return { title: "DAG 修复未通过", toolName: "dag_repair", status: "warning" };
  }
  if (eventKind === "DAG_VALIDATION") {
    return eventState === "FAILED"
      ? { title: "检测到 DAG 漂移", toolName: "dag_validation", status: "warning" }
      : { title: "DAG 审核通过", toolName: "dag_validation", status: "done" };
  }
  // Compatibility for lifecycle events persisted by older Runtime builds. They
  // already carry typed validation flags, but did not yet publish eventKind.
  // Do not inspect contentPreview here: status must stay protocol-driven.
  if (type === "LIFECYCLE" && ("valid" in metadata || "executable" in metadata)) {
    const failed = metadata.valid === false || metadata.executable === false;
    return failed
      ? { title: "执行计划校验失败", toolName: "dag_validation", status: "warning" }
      : { title: "执行计划校验通过", toolName: "dag_validation", status: "done" };
  }
  if (type === "LIFECYCLE") {
    const lifecyclePhase = upper(metadata.lifecyclePhase);
    const phases = {
      PROBLEM_IDENTIFICATION: { title: "理解问题", status: "done" },
      TOOL_DISCOVERY: { title: "发现可用能力", status: "done" },
      PLAN_GENERATION: { title: "生成执行计划", status: "done" },
      PLAN_EVALUATION: { title: "校验执行计划", status: "done" },
      STEP_EXECUTION: { title: "执行计划", status: "active" },
      FINAL_SYNTHESIS: { title: "生成回答", status: "active" },
      DRIVER_CHALLENGE_REPAIR: { title: "补充分析", status: "active" }
    };
    return {
      ...(phases[lifecyclePhase] || { title: "后端处理中", status: "active" }),
      toolName: "agent_lifecycle"
    };
  }
  if (metadata.success === false || type === "TOOL_FAILURE") {
    return { title: "工具执行失败", status: "error" };
  }
  return null;
}

export function runtimeObservationIdentity(runtimePayload = {}) {
  const metadata = objectValue(runtimePayload.metadata);
  if (upper(metadata.eventKind) === 'CODING_TRACE') {
    return `coding:${metadata.taskId}:${metadata.eventId || metadata.sequence}`;
  }
  if (upper(metadata.eventKind) === "HARNESS_TURN") {
    return `harness-turn:${metadata.datasetFingerprint || "run"}:${metadata.turn || "unknown"}`;
  }
  if (upper(metadata.eventKind) === "ANALYSIS_GRAPH"
      || upper(metadata.type).startsWith("UNIFIED_QUESTION_ANALYSIS_")) {
    return `analysis-graph:${metadata.graphId || "unified-question-analysis"}`;
  }
  if (upper(metadata.eventKind) === "KNOWLEDGE_SKILLS") {
    return `knowledge-skills:${metadata.stage || "unknown"}`;
  }
  if (upper(metadata.eventKind) === "MODEL_INFERENCE") {
    return `model-inference:${metadata.modelPhase || "unknown"}:${metadata.stepId || "run"}`;
  }
  if (upper(metadata.eventKind) === "TOOL_REVIEW_STAGE") {
    return `tool-review-stage:${metadata.modelPhase || "unknown"}:${metadata.stepId || "run"}:${metadata.attempt || 1}`;
  }
  if (upper(metadata.type) === "BUSINESS_ANALYSIS_PROGRESS" && metadata.progressId) {
    return `business-analysis:${metadata.progressId}`;
  }
  if (upper(metadata.eventKind) === "DAG_VALIDATION"
      || (upper(metadata.type) === "LIFECYCLE" && ("valid" in metadata || "executable" in metadata))) {
    return `dag-validation:${metadata.stage || "unknown"}`;
  }
  if (upper(metadata.type) === "LIFECYCLE" && metadata.lifecyclePhase) {
    return `lifecycle:${metadata.lifecyclePhase}:${metadata.stage || "run"}`;
  }
  if (upper(metadata.eventKind) !== "DAG_REPAIR") return "";
  const repairEvent = objectValue(metadata.repairEvent);
  const attempt = metadata.repairAttempt ?? repairEvent.repairAttempt;
  return attempt === undefined || attempt === null || String(attempt).trim() === ""
    ? ""
    : `dag-repair:${attempt}`;
}
