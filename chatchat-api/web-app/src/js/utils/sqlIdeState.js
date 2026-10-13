export function sqlConversationPrompt(text, turns = [], selectedCode = null) {
  const history = turns.slice(-6).map(turn => `${turn.role === 'user' ? 'User' : 'Assistant'}: ${String(turn.text || '').slice(0, 450)}`).join('\n');
  const selection = selectedCode?.text ? `Selected source (${selectedCode.path || 'current file'}):\n${selectedCode.text.slice(0, 900)}` : '';
  return [history && `Previous project conversation:\n${history}`, selection, `Current request:\n${String(text || '').trim()}`]
    .filter(Boolean).join('\n\n').slice(-4000);
}

export function sqlAssistantAnswer(result) {
  if (!result) return '';
  if (result.outputError) return result.outputError;
  const paths = (result.files || []).map(file => file.path);
  let notes = '';
  try { notes = JSON.parse(result.rawAnswer || '{}').notes || ''; } catch { notes = result.rawAnswer || ''; }
  return [notes, paths.length && `建议文件：${paths.join('、')}`].filter(Boolean).join('\n') ||
    (result.task?.status ? `任务状态：${result.task.status}` : '正在等待 Agent 结果…');
}

export const clampSqlPanel = (value, min, max) => Math.min(max, Math.max(min, value));
