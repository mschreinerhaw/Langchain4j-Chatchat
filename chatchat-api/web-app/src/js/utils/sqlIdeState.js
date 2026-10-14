import { sqlTaskPresentation } from './sqlTaskPresentation';

export function sqlConversationPrompt(text, turns = [], selectedCode = null) {
  const history = turns.slice(-6).map(turn => `${turn.role === 'user' ? 'User' : 'Assistant'}: ${String(turn.text || '').slice(0, 450)}`).join('\n');
  const selection = selectedCode?.text ? `Selected source (${selectedCode.path || 'current file'}):\n${selectedCode.text.slice(0, 900)}` : '';
  return [history && `Previous project conversation:\n${history}`, selection, `Current request:\n${String(text || '').trim()}`]
    .filter(Boolean).join('\n\n').slice(-4000);
}

export function sqlAssistantAnswer(result) {
  if (!result) return '';
  if (result.outputError) return result.outputError;
  const progress = sqlTaskPresentation(result);
    if (progress.running) return `${progress.title}，进展将在本条消息中持续更新。`;
  const paths = (result.files || []).map(file => file.path);
  let notes = '';
  try { notes = JSON.parse(result.rawAnswer || '{}').notes || ''; } catch { notes = result.rawAnswer || ''; }
  return [notes, paths.length && `建议文件：${paths.join('、')}`].filter(Boolean).join('\n') ||
    (result.task?.status ? progress.title : '正在等待分析结果…');
}

export const clampSqlPanel = (value, min, max) => Math.min(max, Math.max(min, value));

export function restoreSqlLayout(saved = {}) {
  const value = saved && typeof saved === 'object' ? saved : {};
  return { explorer: value.explorer !== false, assistant: value.assistant !== false, bottom: value.bottom === true,
    bottomTab: ['problems', 'tasks', 'output', 'validation'].includes(value.bottomTab) ? value.bottomTab : 'problems',
    explorerWidth: clampSqlPanel(Number(value.explorerWidth) || 252, 180, 520),
    assistantWidth: clampSqlPanel(Number(value.assistantWidth) || 336, 180, 520),
    bottomHeight: clampSqlPanel(Number(value.bottomHeight) || 180, 100, 420) };
}
