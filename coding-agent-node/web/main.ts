import type { CodeVersion, NodeSnapshot, TaskDetail, TaskPage } from '../src/monitor-types.ts';
import { repairLinks, executionCounts, sourceDiff, type PythonProjection, type PythonSource } from './python-diagnostics.js';

const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
function el(tag: string, className = '', text = ''): HTMLElement {
  const node = document.createElement(tag); node.className = className; node.textContent = text; return node;
}
function replace(target: HTMLElement, ...children: (HTMLElement | string)[]) { target.replaceChildren(...children); }
const labels: Record<string, string> = { RUNNING: '执行中', COMPLETED: '已完成', FAILED: '失败',
  CANCELLED: '已取消', INTERRUPTED: '已中断', SUCCEEDED: '成功', UNKNOWN: '未确认' };
const badge = (value: string) => el('span', `badge ${value}`, labels[value] ?? value);
const time = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—';
const amount = (value: number | null) => value === null ? '未取得' : value.toLocaleString('zh-CN');
const bytes = (value: number) => value >= 1024 * 1024 ? `${(value / 1024 / 1024).toFixed(1)} MB` : `${(value / 1024).toFixed(1)} KB`;
const duration = (value: number) => value < 60000 ? `${(value / 1000).toFixed(1)} 秒` : `${Math.floor(value / 60000)} 分 ${Math.floor(value / 1000) % 60} 秒`;
function kv(values: [string, string | HTMLElement][]) {
  const list = el('dl', 'kv');
  for (const [label, value] of values) { const item = el('dd'); item.append(value); list.append(el('dt', '', label), item); }
  return list;
}
function empty(title: string, description: string) {
  const node = el('div', 'empty'); node.append(el('span', '', '⌁'), el('h3', '', title), el('p', '', description)); return node;
}
let authenticated = false, paused = false, inFlight = false, activeTab = 'overview', selectedId = '', selectedCode = '';
let detail: TaskDetail | null = null, snapshot: NodeSnapshot | null = null, offset = 0, total = 0;
let sourceCache = new Map<string, {source:string;archived:boolean}>(), timer: ReturnType<typeof setTimeout>, codeEpoch = 0;
let listSignature = '', detailSignature = '';
let pythonState: PythonProjection | null = null, pythonTask = '', pythonLoading = false, pythonError = '', pythonEpoch = 0;
let pythonOpen = new Set<string>();
const pythonTimings=new Map<string,string>();
class ApiError extends Error { constructor(public status: number, message: string) { super(message); } }
async function api<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(path, { ...options, credentials: 'same-origin', signal: AbortSignal.timeout(12000), cache: 'no-store' });
  const value = await response.json();
  if (!response.ok) throw new ApiError(response.status, value.message ?? `请求失败 (${response.status})`);
  return value;
}
function connection(online: boolean) {
  $('connection-dot').classList.toggle('offline', !online);
  $('connection-text').textContent = online ? '实时连接' : '连接中断';
}
function signedOut() {
  pythonState=null;pythonTask='';pythonEpoch++;pythonLoading=false;pythonError='';pythonOpen.clear();pythonTimings.clear();
  authenticated = false; $('login').hidden = false; $('console').hidden = true;
}
function renderNode(node: NodeSnapshot) {
  snapshot = node;
  $('sidebar-host').textContent = node.hostname;
  $('active-badge').textContent = String(node.sessions.active);
  const state = el('span', 'node-status'); state.append(el('span', 'dot'), el('strong', '', node.status === 'READY' ? '节点就绪' : '节点排空中'));
  replace($('node-strip'), state, el('strong', 'mono', node.hostname),
    el('span', '', 'coding-agent'),
    el('span', '', `在线 ${duration(node.uptimeSeconds * 1000)}`));
  const cards = [
    ['执行中', String(node.sessions.active), `会话容量 ${node.sessions.capacity}`, '◉'],
    ['已完成任务', amount(node.tasks.COMPLETED), '当前保留历史中的完成任务', '✓'],
    ['异常 / 中断', String(node.tasks.FAILED + node.tasks.INTERRUPTED), `已取消 ${node.tasks.CANCELLED} 项`, '◇'],
    ['进程内存 RSS', bytes(node.memory.rssBytes), `堆内存 ${bytes(node.memory.heapUsedBytes)}`, '▥'],
  ];
  replace($('metrics'), ...cards.map(([label, value, description, icon], i) => {
    const card = el('div', `metric ${i === 0 ? 'highlight' : ''}`);
    const heading = el('div', 'metric-label', label); heading.append(el('span', 'metric-icon', icon));
    card.append(heading, el('strong', '', value), el('small', '', description)); return card;
  }));
  $('updated').textContent = `更新于 ${new Date(node.observedAt).toLocaleTimeString('zh-CN', { hour12: false })}`;
  const historyError = node.history.persistence === 'DEGRADED' ? node.history.error : null;
  $('error-banner').hidden = !historyError;
  if (historyError) $('error-banner').textContent = `监控历史存储异常：${historyError}`;
}
function renderList(page: TaskPage) {
  total = page.total; $('task-total').textContent = String(total);
  $('prev').toggleAttribute('disabled', offset === 0); $('next').toggleAttribute('disabled', offset + 20 >= total);
  $('page-info').textContent = total ? `${offset + 1}–${Math.min(offset + 20, total)} / ${total}` : '暂无记录';
  const signature = JSON.stringify([selectedId, page.items]);
  if (signature === listSignature) return;
  listSignature = signature;
  if (!page.items.length) { replace($('task-list'), empty('暂无任务', '节点接收任务后，这里会自动显示真实执行记录。')); return; }
  replace($('task-list'), ...page.items.map(task => {
    const row = el('button', `task-row ${task.id === selectedId ? 'selected' : ''}`) as HTMLButtonElement;
    row.dataset.taskId = task.id; row.setAttribute('aria-pressed', String(task.id === selectedId));
    const top = el('div', 'task-top'); top.append(el('span', 'task-name', task.taskId.length > 36 ? `${task.taskId.slice(0, 32)}…` : task.taskId), badge(task.status));
    const meta = el('div', 'task-meta'); meta.append(el('span', '', duration(task.elapsedMs)),
      el('span', '', `${task.modelCalls} 次模型调用`), el('span', '', `${task.codeCount} 份代码`));
    row.append(top, el('div', 'task-objective', task.objective || '未提供任务目标'), meta);
    row.addEventListener('click', () => {
      selectedId = task.id; selectedCode = ''; codeEpoch++; detail = null; detailSignature = ''; sourceCache.clear();
      location.hash = task.id; replace($('detail-content'), empty('加载执行记录', '正在读取节点观测信息。'));
      for (const button of document.querySelectorAll<HTMLElement>('.task-row')) button.classList.toggle('selected', button.dataset.taskId === task.id);
      void refresh();
    });
    return row;
  }));
}
function renderOverview(task: TaskDetail) {
  const node = el('div'); node.append(el('p', 'section-title', '任务目标'), el('p', 'detail-objective', task.objective));
  const stats = el('div', 'mini-stats');
  for (const [label, value] of [['执行耗时', duration(task.elapsedMs)], ['生成代码', `${task.codeCount} 份`], ['模型调用', `${task.modelCalls} 次`]]) {
    const item = el('div', 'mini-stat', label); item.append(el('strong', '', value)); stats.append(item);
  }
  node.append(stats, kv([['任务 ID', el('span', 'mono', task.taskId)], ['Run ID', el('span', 'mono', task.runId)],
    ['租户 / 用户', el('span', 'mono', `${task.tenantId} / ${task.userId}`)], ['当前阶段', task.stage],
    ['开始时间', time(task.createdAt)], ['结束时间', time(task.finishedAt)], ['执行截止', time(task.deadlineAt)],
    ['恢复模式', task.recoveryMode], ['执行代次', String(task.fencingToken)],
    ['模型调用上限', task.budget.maxModelCalls === null ? '未声明' : String(task.budget.maxModelCalls)]]));
  if (task.error) node.append(el('p', 'banner error', task.error));
  if (task.reconciliationRequired) node.append(el('p', 'banner error', '节点重启前执行未结束，真实结果需要由平台核对。请勿仅凭节点记录重放任务。'));
  const persistence = snapshot?.history.persistence;
  node.append(el('p', 'notice', `节点观测记录，不代表业务结果验收。历史存储：${persistence === 'MEMORY' ? '仅内存' : persistence === 'HEALTHY' ? '已启用持久化' : '存储异常'}。任务和代码均有保留上限，完整执行回执由平台管理。`));
  return node;
}
function renderModels(task: TaskDetail) {
  const node = el('div'); node.append(el('p', 'section-title', '模型配置'), kv([
    ['已选模型', task.configuredModel.selectedModel ?? '未声明'], ['配置协议', task.configuredModel.protocol ?? '未声明'],
  ]));
  const stats = el('div', 'mini-stats');
  for (const [label, value] of [['输入 Tokens', amount(task.inputTokens)], ['输出 Tokens', amount(task.outputTokens)], ['用量回执', `${task.usageKnownCalls} / ${task.modelCalls}`]]) {
    const item = el('div', 'mini-stat', label); item.append(el('strong', '', value)); stats.append(item);
  }
  node.append(stats, el('p', 'notice', 'Token 合计只包含已取得的用量；未返回调用不计入。配置模型与实际返回模型分别记录。'));
  if (!task.models.length) node.append(empty('尚无模型调用', '配置不代表模型已实际调用。'));
  for (const call of task.models) {
    const card = el('div', 'model-card'), title = el('div', 'card-title', `调用 #${call.ordinal}`); title.append(badge(call.status));
    card.append(title, kv([['请求模型', call.selectedModel ?? task.configuredModel.selectedModel ?? '未取得'],
      ['实际模型', call.providerModel ?? '未取得'], ['协议', call.protocol ?? '未取得'],
      ['开始', time(call.startedAt)], ['结束', time(call.finishedAt)], ['耗时', call.durationMs === null ? '等待回执' : duration(call.durationMs)],
      ['Java 回调总耗时', call.callbackDurationMs == null ? '未观测' : duration(call.callbackDurationMs)],
      ['供应商首 Token', call.firstTokenMs == null ? '未观测' : duration(call.firstTokenMs)],
      ['回调首事件', call.callbackFirstEventMs == null ? '未观测' : duration(call.callbackFirstEventMs)],
      ['请求 / 响应大小', `${call.requestBytes == null ? '未观测' : bytes(call.requestBytes)} / ${call.responseBytes == null ? '未观测' : bytes(call.responseBytes)}`],
      ['输入 / 输出', `${amount(call.usage.inputTokens)} / ${amount(call.usage.outputTokens)}`],
      ['成本', call.usage.totalCost === null ? '未取得' : String(call.usage.totalCost)]])); node.append(card);
  }
  return node;
}
function renderSkills(task: TaskDetail) {
  const node = el('div'); node.append(kv([['配置状态', task.skills.status], ['加载时间', time(task.skills.loadedAt)],
    ['应用次数', String(task.skills.applications)], ['最近应用', time(task.skills.lastAppliedAt)],
    ['指纹', el('span', 'mono', task.skills.fingerprint ?? '未提供')]]));
  const stages = el('div', 'skill-stage'); for (const stage of task.skills.stages) stages.append(el('span', 'badge', stage)); node.append(stages);
  if (!task.skills.configured.length) node.append(empty('没有配置 Skills', '任务未携带 Skills 清单，控制台不会推断已启用技能。'));
  for (const skill of task.skills.configured) {
    const card = el('div', 'skill-card'); card.append(el('div', 'card-title', skill.name || skill.skillId),
      el('p', 'mono', skill.skillId), el('p', 'muted', `版本 ${skill.version || '未声明'}`)); node.append(card);
  }
  node.append(el('p', 'notice', '“已应用”表示指导已装载到该轮模型上下文，不能证明模型遵循了全部指导。此处不授予任何额外工具权限。'));
  return node;
}
function renderEvents(task: TaskDetail) {
  const node = el('div');
  node.append(el('p', 'notice', `累计 ${task.eventCount} 个事件，当前保留 ${task.events.length} 个。${task.eventsTruncated ? '较早事件已超出保留上限。' : ''}`));
  const timeline = el('ol', 'timeline');
  for (const event of [...task.events].reverse()) {
    const item = el('li'), top = el('div', 'event-top'); top.append(el('strong', '', event.type), el('time', '', time(event.timestamp)));
    const details = el('details'), summary = el('summary', '', `#${event.cursor} · ${event.tool ?? event.stage ?? '节点事件'} · 查看字段`);
    details.append(summary, el('pre', '', JSON.stringify(event, null, 2))); item.append(top, details); timeline.append(item);
  }
  node.append(timeline); return node;
}
function renderCodes(task: TaskDetail) {
  const node = el('div');
  if (!task.codes.length) { node.append(empty('尚未生成代码', '模型提交 Python 或工作区代码后，在此查看真实源文件。')); return node; }
  if (!task.codes.some(code => code.id === selectedCode)) selectedCode = task.codes.at(-1)!.id;
  const toolbar = el('div', 'code-toolbar'), select = document.createElement('select'); select.id = 'code-version'; select.setAttribute('aria-label', '代码版本');
  for (const [index, code] of task.codes.entries()) {
    const option = document.createElement('option'); option.value = code.id; option.textContent = `版本 ${code.scriptVersion ?? index + 1} · ${code.fileName} · ${time(code.createdAt)}`;
    option.selected = code.id === selectedCode; select.append(option);
  }
  select.addEventListener('change', () => { selectedCode = select.value; void loadSource(task.id, selectedCode); });
  toolbar.append(select); node.append(toolbar, el('div', 'code-info'), el('pre', 'code-block', '读取源代码…'));
  if (task.codesTruncated) node.append(el('p', 'notice', '较早代码版本的元信息已超出保留上限。'));
  // The target exists after the synchronous render completes.
  queueMicrotask(() => { void loadSource(task.id, selectedCode); });
  return node;
}
async function loadSource(taskId: string, codeId: string) {
  const epoch = ++codeEpoch;
  const code = detail?.codes.find(code => code.id === codeId);
  const info = $('detail-content').querySelector<HTMLElement>('.code-info'), pre = $('detail-content').querySelector<HTMLElement>('pre');
  if (!info || !pre || !code) return;
  info.textContent = `SHA-256 ${code.sha256}\n${bytes(code.sourceBytes)} · ${code.operationId}`; info.style.whiteSpace = 'pre-wrap';
  const cached=sourceCache.get(codeId);
  if (cached) { if(cached.archived)info.textContent+='\n来源：平台不可变代码归档';pre.textContent = cached.source; return; }
  pre.textContent = '读取源代码…';
  try {
    let archived:PythonSource|undefined;
    if(/^cline-[a-f0-9]{64}$/.test(code.operationId))try{archived=await api<PythonSource>(`/v1/monitor/tasks/${taskId}/python/codes/${code.operationId}`);}
    catch(error){if(!code.sourceRetained)throw error;}
    if(archived){
      if(epoch!==codeEpoch||selectedId!==taskId||activeTab!=='codes')return;
      if(archived.sha256!==code.sha256||archived.operationId!==code.operationId||archived.taskId!==detail?.taskId)throw new Error('平台归档与选中代码版本摘要不一致');
      const source=archived.files.map(file=>`# ${file.path}\n${file.content}`).join('\n\n');
      sourceCache.set(codeId,{source,archived:true});info.textContent+='\n来源：平台不可变代码归档';pre.textContent=source;return;
    }
    if(!code.sourceRetained)throw new Error('Code archive unavailable');
    const value = await api<CodeVersion>(`/v1/monitor/tasks/${taskId}/codes/${codeId}`);
    if (epoch !== codeEpoch || selectedId !== taskId || activeTab !== 'codes') return;
    sourceCache.set(codeId, {source:value.source ?? '源代码未保留',archived:false}); pre.textContent = value.source ?? '源代码未保留';
  } catch (error) {
    if (epoch === codeEpoch) pre.textContent = code.sourceRetained?`无法读取代码：${(error as Error).message}`:'节点未保留该版本正文，平台代码归档暂不可用。代码哈希与版本元信息仍可查阅。';
  }
}
async function loadPython(task: TaskDetail) {
  if(pythonTask!==task.id){pythonTask=task.id;pythonEpoch++;pythonState=null;pythonError='';pythonLoading=false;pythonOpen.clear();}
  if(pythonLoading)return;pythonLoading=true;const epoch=pythonEpoch;
  try {
    const value=await api<PythonProjection>(`/v1/monitor/tasks/${task.id}/python`);
    if(epoch!==pythonEpoch||selectedId!==task.id)return;
    if(value.source!=='DURABLE_CODING_JOURNAL'||value.taskId!==task.taskId)throw new Error('执行诊断身份不匹配');
    const changed=JSON.stringify(value)!==JSON.stringify(pythonState)||!!pythonError;pythonState=value;pythonError='';
    if(changed&&activeTab==='python'){detailSignature='';renderDetail(task);}
  }catch(error){if(epoch===pythonEpoch&&selectedId===task.id){pythonError=(error as Error).message;if(activeTab==='python'){detailSignature='';renderDetail(task);}}}
  finally{if(epoch===pythonEpoch)pythonLoading=false;}
}
function renderPython(task: TaskDetail) {
  const node=el('div','python-diagnostics');
  node.append(el('p','notice','数据来自 Java 持久化执行回执。模型调用成功不代表 Python 成功；原始日志不可用时不补造 Traceback。'));
  const ready=pythonTask===task.id;
  if(ready&&pythonError)node.append(el('p','notice python-unavailable',`真实诊断暂不可用：${pythonError}。保留上次回执，不据此判定任务失败。`));
  const value=ready?pythonState:null;
  if(!value){node.append(empty(pythonError?'暂时无法读取执行回执':'正在读取真实 Python 执行记录','历史记录不完整时将明确显示不可用，不使用 Node 事件推测错误。'));if(!ready||!pythonError)queueMicrotask(()=>{void loadPython(task);});return node;}
  const executions=value.executions,links=repairLinks(executions),last=executions.at(-1),error=executions.filter(e=>e.error).at(-1)?.error;
  const stats=el('div','python-stats'),counts=executionCounts(executions);
  for(const [label,count] of [['已归档执行',counts.total],['进程退出 0',counts.processSucceeded],
    ['执行失败/超时',counts.processFailed],['产物校验失败',counts.outputFailed],['自动修复',counts.repairs],['修复后未通过',counts.repairFailed]]) {
    const card=el('div','mini-stat',String(label));card.append(el('strong','',String(count)));stats.append(card);
  }
  if(counts.cancelled)node.append(el('p','muted',`取消回执 ${counts.cancelled} 条，不计为执行失败。`));
  node.append(stats,kv([['Runtime 任务状态',value.status],['最近执行阶段',last?.stage??'尚无执行回执'],
    ['最近执行代码版本',last?.script?.scriptVersion?`v${last.script.scriptVersion}`:'不可用'],['最后一次错误',error?`${error.exceptionType} / ${error.diagnosticCode}`:'无已记录错误'],
    ['最后一次产物验证',last?`${last.validationStatus} · 结果${last.resultAvailable?'已归档':'不可用'}`:'尚未验证'],['业务独立校验',last?.businessValidationPassed?'PASSED':'未独立确认']]));
  if(!executions.length)node.append(empty('暂无已归档 Python 执行回执','代码生成、工具派发或取消前的中间事件不计为已完成执行。'));
  executions.forEach((execution,index)=>{
    const record=el('details','python-execution') as HTMLDetailsElement;record.dataset.executionId=execution.executionId;record.open=pythonOpen.has(execution.executionId);
    const timing=el('span','python-execution-timing',pythonTimings.get(execution.executionId)??'展开记录后读取原归档时间');
    record.addEventListener('toggle',()=>{record.open?pythonOpen.add(execution.executionId):pythonOpen.delete(execution.executionId);
      if(record.open&&!pythonTimings.has(execution.executionId))void (async()=>{
        const epoch=pythonEpoch;timing.textContent='正在核对原执行回执…';
        try{const value=await api<{operationId:string;sha256:string;available:boolean;source?:string;startedAt?:string;finishedAt?:string}>(`/v1/monitor/tasks/${task.id}/python/timing/${execution.executionId}`);
          if(value.operationId!==execution.executionId||value.sha256!==execution.codeHash)throw new Error('执行时间身份不匹配');
          const text=value.available&&value.source==='MCP_ARCHIVED_RECEIPT'?`${time(value.startedAt??null)} → ${time(value.finishedAt??null)}`:'原执行时间未归档，不推测开始或结束时间';
          if(epoch===pythonEpoch&&selectedId===task.id){pythonTimings.set(execution.executionId,text);timing.textContent=text;}
        }catch{if(timing.isConnected)timing.textContent='原归档时间暂不可用，不使用模型或派发时间代替';}
      })();
    });
    record.append(el('summary','',`第 ${index+1} 次 · v${execution.script?.scriptVersion??'?'} · ${execution.status} · ${execution.error?.exceptionType??(execution.resultAvailable?'产物已归档':'无可用结果')}`));
    record.append(kv([['执行 ID',execution.executionId],['Python 文件',execution.script?.fileName??'未保留'],['实际进程起止时间',timing],
      ['实际耗时',execution.durationMs===null?'不可用':duration(execution.durationMs)],['进程退出码',String(execution.exitCode??'不可用')],['代码 SHA256',execution.codeHash],
      ['错误分类',execution.error?.category??'无'],['异常类型',execution.error?.exceptionType??'无'],['诊断错误码',execution.error?.diagnosticCode??'无'],
      ['安全诊断',execution.error?.message??'无错误诊断'],['产物验证',execution.validationStatus],['业务独立校验',execution.businessValidationPassed?'PASSED':'未独立确认']]));
    if(execution.error?.stack.length)record.append(el('pre','code-block python-stack',execution.error.stack.map(f=>`${f.file}:${f.line}${f.column?':'+f.column:''}`).join('\n')));
    record.append(el('p','muted','原始日志不可用；以上为真实脱敏诊断和受限定位，不是完整 Traceback。'));
    const relation=links.find(link=>link.validation.executionId===execution.executionId);
    if(relation){
      record.append(el('h3','','自动修复与后续验证'),kv([['触发失败执行',relation.failure.executionId],['修复前代码',relation.failure.codeHash],['修复后代码',execution.codeHash],
        ['修复依据',`${relation.failure.error?.exceptionType??relation.failure.status} / ${relation.failure.error?.diagnosticCode??relation.failure.validationStatus}`],
        ['后端修复决策',relation.failure.repair.reason],['修复指导',relation.failure.repair.guidance],['后续真实验证',`${execution.status} · ${execution.resultAvailable?'产物已归档':'结果不可用'}`]]));
      record.append(el('p','muted','修复依据来自执行回执；模型判断不是已证实根因，代码变化以真实归档 Diff 为准。'));
      const button=el('button','button','查看真实修复 Diff') as HTMLButtonElement,output=el('div','python-diff');
      button.addEventListener('click',async()=>{
        button.disabled=true;output.textContent='正在校验并读取归档代码…';const epoch=pythonEpoch;
        try {
          const [before,after]=await Promise.all([relation.failure,execution].map(e=>api<PythonSource>(`/v1/monitor/tasks/${task.id}/python/codes/${e.executionId}`)));
          if(epoch!==pythonEpoch||selectedId!==task.id||!output.isConnected)return;
          if(before.sha256!==relation.failure.codeHash||after.sha256!==execution.codeHash||before.taskId!==task.taskId||after.taskId!==task.taskId)throw new Error('源码摘要与真实执行不一致');
          output.replaceChildren();
          if(before.files.length===1&&after.files.length===1&&before.files[0].path!==after.files[0].path){output.append(el('h4','',`${before.files[0].path} → ${after.files[0].path}`),el('pre','code-block',sourceDiff(before.files[0].content,after.files[0].content)));return;}
          for(const path of new Set([...before.files,...after.files].map(f=>f.path))){const a=before.files.find(f=>f.path===path)?.content??'',b=after.files.find(f=>f.path===path)?.content??'';output.append(el('h4','',path),el('pre','code-block',sourceDiff(a,b)));}
        }catch(error){if(output.isConnected)output.textContent=`归档 Diff 不可用：${(error as Error).message}。不使用推测代码替代。`;}
        finally{button.disabled=false;}
      });record.append(button,output);
    }
    node.append(record);
  });
  node.append(el('h3','','真实执行时间线'));
  const timeline=el('ol','python-timeline');
  executions.forEach((execution,index)=>{timeline.append(el('li','',`代码版本 v${execution.script?.scriptVersion??'?'} → 第 ${index+1} 次 Python：${execution.error?.exceptionType??execution.status} → 产物 ${execution.validationStatus}`));
    if(links.some(l=>l.failure.executionId===execution.executionId))timeline.append(el('li','','根据真实失败修复代码 → 后续执行验证（见上方版本与 Diff）'));});
  node.append(timeline);return node;
}
function renderDetail(task: TaskDetail) {
  detail = task;
  replace($('detail-heading'), el('h2', '', '执行详情'), badge(task.status));
  const signature = JSON.stringify([activeTab, task]);
  if (signature === detailSignature) return;
  // Keep code selection and timeline expansion stable while only elapsed time changes.
  const stable = activeTab==='python'?JSON.stringify([activeTab,task.id,pythonState,pythonError]):JSON.stringify([activeTab, { ...task, elapsedMs: 0 }]);
  if (activeTab !== 'overview' && stable === detailSignature) return;
  detailSignature = activeTab === 'overview' ? signature : stable;
  codeEpoch++;
  const renderers: Record<string, (task: TaskDetail) => HTMLElement> = {
    overview: renderOverview, models: renderModels, skills: renderSkills, events: renderEvents, codes: renderCodes, python: renderPython,
  };
  replace($('detail-content'), renderers[activeTab](task));
}
async function refresh() {
  if (inFlight || paused) return;
  inFlight = true;
  try {
    const query = new URLSearchParams({ limit: '20', offset: String(offset),
      status: ($('status-filter') as HTMLSelectElement).value, q: ($('search') as HTMLInputElement).value });
    const [node, page] = await Promise.all([api<NodeSnapshot>('/v1/monitor/node'), api<TaskPage>(`/v1/monitor/tasks?${query}`)]);
    authenticated = true; $('login').hidden = true; $('console').hidden = false; connection(true);
    renderNode(node); renderList(page);
    if (!selectedId && page.items.length) { selectedId = page.items[0].id; listSignature = ''; renderList(page); }
    if (selectedId) {
      const requested = selectedId;
      try {
        const value = await api<{ task: TaskDetail }>(`/v1/monitor/tasks/${requested}`);
        if (requested === selectedId) renderDetail(value.task);
        if (requested === selectedId && activeTab === 'python') void loadPython(value.task);
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) {
          replace($('detail-content'), empty('记录已过期', '该任务已不在当前节点的保留历史中。')); detail = null;
        } else throw error;
      }
    }
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) signedOut();
    else if (authenticated) {
      connection(false); $('error-banner').hidden = false;
      $('error-banner').textContent = `节点暂时不可达，保留上次观测结果。${(error as Error).message}`;
    } else $('login-error').textContent = (error as Error).message;
  } finally { inFlight = false; }
}
function renderApi() {
  const panel = $('api-panel');
  replace(panel, el('p', 'eyebrow', 'COMPONENT API'), el('h2', '', '将监控接入你的应用'),
    el('p', 'muted', '同源页面与外部应用共用 coding_agent.monitor.v1 只读协议。使用独立 Monitor Token；它不能提交任务、取消执行或调用模型。浏览器接入建议通过应用服务端代理，节点默认不开放跨域访问。'),
    el('pre', 'code-block', 'GET /v1/monitor/node\nGET /v1/monitor/tasks?status=RUNNING&limit=20&offset=0\nGET /v1/monitor/tasks/{id}\nGET /v1/monitor/tasks/{id}/events?after=0&limit=100\nGET /v1/monitor/tasks/{id}/models\nGET /v1/monitor/tasks/{id}/skills\nGET /v1/monitor/tasks/{id}/codes\nGET /v1/monitor/tasks/{id}/codes/{codeId}\n\nAuthorization: Bearer <MONITOR_TOKEN>'),
    el('p', 'notice', `节点 ${snapshot?.hostname ?? ''} · 历史上限 ${snapshot?.history.maxTasks ?? 256} 个任务 / 每任务 ${snapshot?.history.maxEventsPerTask ?? 512} 个事件。events 使用游标增量查询并显式返回 truncated；代码过期返回 410。节点重启后，未完成任务标记 INTERRUPTED，需要平台核对。`));
  const link = el('a', '', '查看 OpenAPI 描述 ↗') as HTMLAnchorElement; link.href = '/v1/monitor/openapi.json'; link.target = '_blank'; link.rel = 'noopener'; panel.append(link);
}
$('login-form').addEventListener('submit', async event => {
  event.preventDefault(); const input = $('token') as HTMLInputElement; const button = $('login-form').querySelector('button')!;
  button.disabled = true; $('login-error').textContent = '';
  try {
    await api('/monitor/session', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ token: input.value }) });
    input.value = ''; await refresh();
  } catch (error) { $('login-error').textContent = (error as Error).message; } finally { button.disabled = false; }
});
async function logout() {
  try { await api('/monitor/logout', { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' }); }
  finally { signedOut(); selectedId = ''; detail = null; sourceCache.clear(); listSignature = ''; detailSignature = ''; codeEpoch++;
    replace($('detail-content'), empty('等待选择任务', '重新连接节点后查看记录。')); replace($('task-list')); }
}
$('logout').addEventListener('click', logout);
$('logout-mobile').addEventListener('click', logout);
$('refresh').addEventListener('click', () => { void refresh(); });
$('search').addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(() => { offset = 0; void refresh(); }, 250); });
$('status-filter').addEventListener('change', () => { offset = 0; void refresh(); });
$('prev').addEventListener('click', () => { offset = Math.max(0, offset - 20); void refresh(); });
$('next').addEventListener('click', () => { if (offset + 20 < total) offset += 20; void refresh(); });
for (const button of document.querySelectorAll<HTMLButtonElement>('[data-tab]')) button.addEventListener('click', () => {
  activeTab = button.dataset.tab!; detailSignature = ''; codeEpoch++;
  for (const item of document.querySelectorAll('[data-tab]')) item.classList.toggle('selected', item === button);
  if (detail) renderDetail(detail);
});
for (const button of document.querySelectorAll<HTMLButtonElement>('[data-view]')) button.addEventListener('click', () => {
  for (const item of document.querySelectorAll('[data-view]')) item.classList.toggle('selected', item === button);
  const view = button.dataset.view!;
  $('api-panel').hidden = view !== 'api'; $('task-area').hidden = view === 'api';
  $('page-title').textContent = ({ all: '节点运行总览', active: '执行中任务', failed: '异常记录', api: '应用接入' })[view] ?? '';
  if (view === 'api') renderApi();
  else { ($('status-filter') as HTMLSelectElement).value = view === 'active' ? 'RUNNING' : view === 'failed' ? 'FAILED' : ''; offset = 0; void refresh(); }
});
document.addEventListener('visibilitychange', () => { paused = document.hidden; if (!paused && authenticated) void refresh(); });
if (/^#[a-f0-9]{64}$/.test(location.hash)) selectedId = location.hash.slice(1);
void refresh();
setInterval(() => { if (authenticated && !paused) void refresh(); }, 3000);
