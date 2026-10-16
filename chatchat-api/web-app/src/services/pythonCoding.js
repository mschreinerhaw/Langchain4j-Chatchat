import {apiRequest,requestPythonCodeAssist} from './api';

const base='/data-science/python/assist/coding';
export const cancelPythonCoding=task=>apiRequest(`${base}/${encodeURIComponent(task)}/cancel`,{method:'POST',body:'{}'});
export async function resumePythonCoding(task,request,context) {
  await apiRequest(`${base}/${encodeURIComponent(task)}/resume`,{method:'POST',body:'{}'});
  return waitForCoding({taskId:task,plan:context.plan},request,context);
}
export async function runPythonCoding(request,context) {
  if(!['generate','fix','optimize'].includes(request.action))return requestPythonCodeAssist(request);
  const capabilities=await apiRequest(`${base}/capabilities`);
  if(!capabilities.enabled)return requestPythonCodeAssist(request);
  const started=await apiRequest(base,{method:'POST',body:JSON.stringify({request,requestId:context.requestId,
    entryPath:context.entryPath,files:context.files})});
  context.onTask?.(started.taskId);
  return waitForCoding(started,request,context);
}
async function waitForCoding(started,request,context) {
  const deadline=Date.now()+11*60*1000;
  while(Date.now()<deadline) {
    const state=await apiRequest(`${base}/${encodeURIComponent(started.taskId)}`);
    context.onProgress?.(state);
    if(state.status==='COMPLETED') {
      const files=state.output?.files;
      if(!files?.length)throw new Error('Coding task completed without reviewed source files');
      return {code:(files.find(f=>f.path===context.entryPath)||files[0]).content,files,
        action:request.action,replaceSelection:false,modelName:request.modelName,plan:started.plan,
        coding:{taskId:started.taskId,outcome:state.outcome,events:state.events,validation:state.output.validation}};
    }
    if(['FAILED','CANCELLED','BUDGET_EXCEEDED','PARTIAL'].includes(state.status))throw new Error(state.outcome?.summary||`Coding task ${state.status}`);
    if(state.outcome?.executionControl?.reconciliationRequired)throw new Error('Coding execution requires Runtime receipt reconciliation; task retained for recovery');
    await new Promise(resolve=>setTimeout(resolve,500));
  }
  await cancelPythonCoding(started.taskId);
  throw new Error('Coding task wait expired; cancellation requested');
}
