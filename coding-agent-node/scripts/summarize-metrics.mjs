/** Read-only statistics over existing monitor exports and optional authoritative Java outcomes. */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const known = value => typeof value === 'number' && Number.isFinite(value) && value >= 0;
const key = task => JSON.stringify([task.scope?.tenantId ?? task.tenantId,
  task.scope?.userId ?? task.userId, task.scope?.runId ?? task.runId, task.taskId]);
function distribution(values) {
  const sorted = values.filter(known).sort((a, b) => a - b);
  return { known: sorted.length, mean: sorted.length ? sorted.reduce((a, b) => a + b, 0) / sorted.length : null,
    p50: sorted.length ? sorted[Math.floor(sorted.length * 0.5)] : null,
    p95: sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * 0.95))] : null };
}
function outcomes(values, positive, negative) {
  const successful = values.filter(positive).length, failed = values.filter(negative).length;
  return { observed: values.length, successful, failed, unknown: values.length - successful - failed,
    successRate: successful + failed ? successful / (successful + failed) : null };
}
export function summarize(document, runtimeOutcomes = []) {
  const groups = new Map();
  for (const task of [...(document.tasks ?? [])].sort((a, b) => (a.fencingToken ?? 0) - (b.fencingToken ?? 0))) {
    const id = key(task);
    if (!groups.has(id)) groups.set(id, { models: new Map(), events: new Map() });
    const group = groups.get(id);
    // Newer fences update the same admitted model ordinal instead of double-counting a restart.
    for (const model of task.models ?? []) group.models.set(model.ordinal, model);
    for (const event of task.events ?? []) group.events.set(JSON.stringify([
      event.type, event.operationId ?? event.toolCallId ?? event.cursor, event.tool, event.modelCall]), event);
  }
  const models = [...groups.values()].flatMap(group => [...group.models.values()]);
  const events = [...groups.values()].flatMap(group => [...group.events.values()]);
  const python = events.filter(event => event.type === 'TOOL_FINISHED' && event.tool === 'python_execute');
  const java = new Map();
  for (const task of runtimeOutcomes) if (groups.has(key(task))) {
    const previous = java.get(key(task));
    if (!previous || task.revision >= previous.revision) java.set(key(task), task);
  }
  const tokens = name => {
    const values = models.map(model => model.usage?.[name]).filter(known);
    return { knownCalls: values.length, total: values.length ? values.reduce((a, b) => a + b, 0) : null };
  };
  const byTool = {};
  for (const tool of new Set(events.filter(event => event.type === 'TOOL_TIMING').map(event => event.tool))) {
    const samples = events.filter(event => event.type === 'TOOL_TIMING' && event.tool === tool);
    byTool[tool] = { boundary: outcomes(samples, e => ['RETURNED','SUCCEEDED'].includes(e.status), e => ['THREW','FAILED'].includes(e.status)),
      durationMs: distribution(samples.map(event => event.toolDurationMs)),
      validationDurationMs: distribution(samples.map(event => event.validationDurationMs)) };
  }
  return { authority: 'JAVA_OUTCOMES_FOR_TASK_SUCCESS; NODE_OBSERVATIONS_FOR_STAGE_METRICS',
    taskCount: groups.size, historyTruncated: (document.tasks ?? []).some(task => task.eventsTruncated || task.codesTruncated),
    runtimeTasks: java.size ? outcomes([...java.values()], t => t.status === 'COMPLETED',
      t => ['FAILED', 'CANCELLED', 'TIMED_OUT'].includes(t.status)) : null,
    models: { ...outcomes(models, m => m.status === 'SUCCEEDED', m => m.status === 'FAILED'),
      durationMs: distribution(models.map(m => m.durationMs)), queueMs: distribution(models.map(m => m.queueTimeMs)),
      firstTokenMs: distribution(models.map(m => m.firstTokenMs)), generationMs: distribution(models.map(m => m.generationTimeMs)),
      retryMs: distribution(models.map(m => m.retryTimeMs)), inputTokens: tokens('inputTokens'), outputTokens: tokens('outputTokens') },
    callbacks: { durationMs: distribution(models.map(m => m.callbackDurationMs)),
      firstEventMs: distribution(models.map(m => m.callbackFirstEventMs)),
      requestBytes: distribution(models.map(m => m.requestBytes)), responseBytes: distribution(models.map(m => m.responseBytes)) },
    tools: byTool,
    python: { execution: outcomes(python, e => e.status === 'SUCCEEDED', e => ['FAILED', 'TIMED_OUT', 'CANCELLED'].includes(e.status)),
      validatedOutput: outcomes(python, e => e.resultAvailable === true, e => e.resultAvailable === false),
      durationMs: distribution(python.map(e => e.durationMs)), cpuMs: distribution(python.map(e => e.cpuTimeMs)),
      maxRssBytes: distribution(python.map(e => e.maxRssBytes)) },
    repairs: { patches: events.filter(e => e.type === 'CODE_READY' && e.changeKind === 'PATCH').length,
      rollbacks: events.filter(e => e.type === 'CODE_READY' && e.changeKind === 'ROLLBACK').length,
      rejectedProposals: events.filter(e => e.type === 'CODE_PROPOSAL_REJECTED').length } };
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (!process.argv[2]) throw new Error('Usage: summarize-metrics.mjs observations.json [java-coding-outcomes.json]');
  const input = JSON.parse(readFileSync(process.argv[2], 'utf8').replace(/^\uFEFF/, ''));
  const java = process.argv[3] ? JSON.parse(readFileSync(process.argv[3], 'utf8').replace(/^\uFEFF/, '')) : [];
  process.stdout.write(JSON.stringify(summarize(input, java), null, 2) + '\n');
}
