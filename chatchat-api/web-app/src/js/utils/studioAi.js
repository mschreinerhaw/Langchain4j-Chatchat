export const studioAiLabels = {explore:'数据库问答',generate:'代码生成',fix:'代码修复',optimize:'代码优化',continue:'代码续写',explain:'代码解释',ask:'上下文问答',test:'生成测试'};
export const studioAiProgress = {generate:'正在生成代码',fix:'正在分析问题并修复',optimize:'正在优化代码',continue:'正在续写代码',explain:'正在分析并解释代码',ask:'正在分析工程上下文',test:'正在生成测试'};
export const sqlGenerationAction = result => result?.generation?.prepared?.taskRequest?.toolInput?.sqlStudioAction || 'generate';
// Only infer read-only intent from this turn, never from prior conversation or source code.
export function studioReadOnlyAction(action, prompt = '') {
  if (!['generate','explore'].includes(action)) return action;
  if (action==='explore' && !/代码|脚本|SQL|查询|\b(?:code|script|query)\b/i.test(prompt)) return action;
  const text = String(prompt).replace(/(?:不要|不需要|无需|不必|禁止)\s*(?:修改|修复|重写|优化|生成|执行)(?:代码|脚本|SQL)?/gi, '')
    .replace(/\b(?:do not|don't|without)\s+(?:modify|fix|rewrite|optimize|generate|execute)(?:\s+(?:code|sql))?/gi, '');
  if (/修复|修改|重写|重构|优化|生成|编写|写个|写一个|实现|\b(?:fix|rewrite|refactor|optimi[sz]e|generate|implement)\b/i.test(text)) return action;
  return /审查|审阅|代码评审|检查.*(?:代码|脚本|SQL)|(?:代码|脚本|SQL).*检查|解释|讲解|\b(?:review|explain)\b/i.test(text) ? 'explain' : action;
}
export function pythonAssistantText(suggestion, fileName='') {
  if (['explain','ask'].includes(suggestion?.action)) return String(suggestion.code || '');
  return String(suggestion?.notes || '').trim() || `为 ${fileName} 生成了${studioAiLabels[suggestion?.action] || '代码'}建议，等待审查。`;
}
export function pythonProposalSource(context, suggestion) {
  const code=String(suggestion?.code || '').replace(/\r\n/g,'\n');
  if(suggestion?.action==='test')return code;
  const source=context?.source || '';
  if(suggestion?.replaceSelection && context?.selectionOffsets) {
    return source.slice(0,context.selectionOffsets.start)+code+source.slice(context.selectionOffsets.end);
  }
  if(suggestion?.action==='continue') {
    const offset=context?.cursorOffset ?? source.length;
    return source.slice(0,offset)+code+source.slice(offset);
  }
  return code;
}
