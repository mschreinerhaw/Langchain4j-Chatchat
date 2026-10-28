// Public lifecycle facts only. These messages do not simulate model tokens or
// claim tool exploration before the existing Runtime supplies its evidence.
export function beginStudioExchange(messages, text, action, fileName = '') {
  const requestId = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  const startedAt = Date.now();
  messages.push({ id: `user:${requestId}`, requestId, role: 'user', text, action, startedAt },
    { id: `assistant:${requestId}`, requestId, role: 'assistant', text: action==='explore'?'正在提交数据库探索请求…':'正在准备向 Cline 交付代码任务…', action, fileName,
      startedAt, status: 'planning', activity: action==='explore'?[]:[{ id: 'planning', title: '请求已接收，正在校验交付上下文', status: 'active' }] });
  return requestId;
}

export function studioAssistantTurn(messages, requestId) {
  return messages.find(turn => turn.role === 'assistant' && turn.requestId === requestId);
}

export function updateStudioTurn(turn, status, text, details = {}) {
  if (!turn) return;
  const changed = turn.status !== status;
  if (changed) for (const entry of turn.activity || []) if (entry.status === 'active') entry.status = status === 'failed' ? 'error' : 'done';
  turn.status = status;
  turn.text = text;
  Object.assign(turn, details);
  if (changed) {
    const terminal = ['ready', 'failed', 'applied', 'discarded', 'cancelled'].includes(status);
    const title = {ready:'已收到本轮结果',failed:'本轮请求未完成，请查看错误信息',applied:'代码提案已接受',discarded:'代码提案已放弃',cancelled:'任务已取消'}[status] || text;
    turn.activity = [...(turn.activity || []), { id: status, title, status: status === 'failed' ? 'error' : terminal ? 'done' : 'active' }];
  }
}
