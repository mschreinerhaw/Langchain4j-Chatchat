export interface PythonExecution {
  executionId: string; status: string; stage: string; codeHash: string; exitCode: number | null; durationMs: number | null;
  script?: { fileName?: string; scriptVersion?: number };
  error?: { category: string; exceptionType: string; diagnosticCode: string; message: string; stack: { file: string; line: number; column?: number }[] } | null;
  repair: { allowed: boolean; action: string; reason: string; guidance: string; attemptsUsed: number; maxAttempts: number; baseCodeHash: string };
  resultAvailable: boolean; validationStatus: string; outputStatus?: string | null; businessValidationPassed: boolean;
}
export interface PythonProjection { source: string; taskId: string; status: string; revision: number; executions: PythonExecution[]; rawLogsAvailable: boolean; diagnosticsAvailable: boolean }
export interface PythonSource { source: string; taskId: string; operationId: string; sha256: string; files: { path: string; content: string }[] }
export function executionCounts(executions: PythonExecution[]) {
  const links=repairLinks(executions);
  return {total:executions.length,processSucceeded:executions.filter(e=>e.exitCode===0).length,
    processFailed:executions.filter(e=>e.status!=='CANCELLED'&&(typeof e.exitCode==='number'&&e.exitCode!==0||e.status==='TIMED_OUT')).length,
    outputFailed:executions.filter(e=>e.error?.category==='OUTPUT_CONTRACT').length,cancelled:executions.filter(e=>e.status==='CANCELLED').length,
    repairs:links.length,repairFailed:links.filter(l=>l.validation.status!=='SUCCEEDED'||!l.validation.resultAvailable).length};
}
export function repairLinks(executions: PythonExecution[]) {
  return executions.flatMap((current, index) => {
    const previous = executions[index - 1];
    // A neighbouring code version is not sufficient evidence of a repair.
    if (!previous?.repair?.allowed || previous.status === 'SUCCEEDED' && previous.resultAvailable
        || previous.repair.baseCodeHash!==previous.codeHash || !current.repair
        || current.repair.attemptsUsed !== previous.repair.attemptsUsed+1 || current.codeHash === previous.codeHash) return [];
    return [{ failure: previous, validation: current }];
  });
}
export function sourceDiff(before: string, after: string) {
  if (before === after) return '内容未修改';
  const a=before.split('\n'),b=after.split('\n');let start=0,end=0;
  while(start<a.length&&start<b.length&&a[start]===b[start])start++;
  while(end<a.length-start&&end<b.length-start&&a[a.length-1-end]===b[b.length-1-end])end++;
  const lines=[`@@ 原文件第 ${start+1} 行起 @@`,...a.slice(Math.max(0,start-2),start).map(x=>'  '+x),
    ...a.slice(start,a.length-end).map(x=>'- '+x),...b.slice(start,b.length-end).map(x=>'+ '+x),...b.slice(b.length-end,b.length-end+2).map(x=>'  '+x)];
  const bounded=lines.slice(0,240).join('\n');
  return bounded.slice(0,24000)+(lines.length>240||bounded.length>24000?'\n[展示已截断；版本摘要对应完整归档源码]':'');
}
