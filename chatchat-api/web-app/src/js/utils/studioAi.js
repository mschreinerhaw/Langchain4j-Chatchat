export const studioAiLabels = {explore:'数据库问答',generate:'代码生成',fix:'代码修复',optimize:'代码优化',continue:'代码续写',explain:'代码解释',ask:'上下文问答',test:'生成测试'};
export const studioAiProgress = {generate:'正在生成代码',fix:'正在分析问题并修复',optimize:'正在优化代码',continue:'正在续写代码',explain:'正在分析并解释代码',ask:'正在分析工程上下文',test:'正在生成测试'};
export const sqlGenerationAction = result => result?.generation?.prepared?.taskRequest?.toolInput?.sqlStudioAction || 'generate';
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
