import { Agent } from '@cline/agents';
import { createHash } from 'node:crypto';

const TOOL_NAMES = new Set(['python_execute', 'metadata_read', 'artifact_read']);
const FILE_NAME = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,170}\.py$/;

function required(value, label) {
  if (typeof value !== 'string' || !value.trim()) throw new TypeError(`${label} is required`);
  return value.trim();
}

function operationId(task, toolCallId) {
  const digest = createHash('sha256').update([task.scope.tenantId, task.scope.userId,
    task.scope.runId, task.taskId, toolCallId].join('\0')).digest('hex');
  return `cline-${digest}`;
}

/** Only the Runtime-owned callbacks below can touch data or execute code. */
export function createCodingRun(contract, gateway, options = {}) {
  if (!contract?.scope?.tenantId || !contract?.scope?.userId || !contract?.scope?.runId || !contract?.taskId)
    throw new TypeError('A complete Runtime coding task identity is required');
  if (!gateway || typeof gateway.executePython !== 'function' || typeof gateway.reserveModel !== 'function')
    throw new TypeError('Runtime gateway is required');
  const permitted = new Set(contract.authorization?.allowedTools ?? []);
  for (const name of permitted) if (!TOOL_NAMES.has(name)) throw new TypeError(`Unrecognized Coding tool: ${name}`);
  if (!contract.authorization?.allowedSideEffects?.includes('CODE_EXECUTION')) permitted.delete('python_execute');
  let activeOperation = null;
  let modelIndex = 0;
  let lastUsage = { inputTokens: 0, outputTokens: 0, totalCost: 0 };
  const events = [];
  const forward = async (event) => {
    events.push(event);
    if (typeof gateway.event === 'function') await gateway.event(event);
  };
  const tools = [];
  if (permitted.has('python_execute')) tools.push({
    name: 'python_execute',
    description: 'Run a Python script only in the Runtime-authorized sandbox. Return its execution receipt and verification facts. Repair technical errors by submitting a new script; never claim business validation yourself.',
    inputSchema: { type: 'object', properties: {
      fileName: { type: 'string' }, source: { type: 'string' },
    }, required: ['fileName', 'source'], additionalProperties: false },
    executionMode: 'sequential', retryable: false, maxRetries: 0,
    async execute(input, context) {
      if (!FILE_NAME.test(required(input?.fileName, 'fileName')))
        throw new TypeError('Invalid Python file name');
      const source = required(input?.source, 'source');
      const toolCallId = required(context.toolCallId, 'toolCallId');
      const id = operationId(contract, toolCallId);
      activeOperation = id;
      await forward({ type: 'TOOL_STARTED', tool: 'python_execute', operationId: id });
      const abort = () => { void gateway.cancelPython?.(contract, id).catch(() => {}); };
      context.signal?.addEventListener('abort', abort, { once: true });
      try {
        const receipt = await gateway.executePython(contract, {
          operationId: id, fileName: input.fileName, source, toolCallId,
        }, context.signal);
        await forward({ type: 'TOOL_FINISHED', tool: 'python_execute', operationId: id,
          status: receipt.status, resultDatasetRef: receipt.resultDatasetRef ?? null });
        return receipt;
      } finally {
        context.signal?.removeEventListener('abort', abort);
        activeOperation = null;
      }
    },
  });
  if (permitted.has('metadata_read')) tools.push({
    name: 'metadata_read',
    description: 'Read only metadata explicitly authorized by the Runtime task.',
    inputSchema: { type: 'object', properties: { reference: { type: 'string' } },
      required: ['reference'], additionalProperties: false },
    executionMode: 'sequential', retryable: false, maxRetries: 0,
    execute(input, context) { return gateway.readMetadata(contract, required(input?.reference, 'reference'), context.signal); },
  });
  if (permitted.has('artifact_read')) tools.push({
    name: 'artifact_read',
    description: 'Read a previously committed Coding result through Runtime authorization.',
    inputSchema: { type: 'object', properties: { reference: { type: 'string' } },
      required: ['reference'], additionalProperties: false },
    executionMode: 'sequential', retryable: false, maxRetries: 0,
    execute(input, context) { return gateway.readArtifact(contract, required(input?.reference, 'reference'), context.signal); },
  });
  const systemPrompt = [
    'You are a Coding Agent subordinate to Runtime OS. The Runtime owns the analysis goal, data scope, budget and publication.',
    'Use only the listed tools. Do not access a host shell, Docker socket, arbitrary files, databases, or network resources.',
    'Generate Python when useful, inspect execution receipts, fix technical errors, and report verification limits.',
    `Task objective: ${contract.objective?.instruction ?? ''}`,
    `Input snapshot: ${contract.inputSnapshotRef}`,
    `Validation plan: ${contract.validationPlanRef}`,
  ].join('\n');
  const config = {
    systemPrompt,
    tools,
    maxIterations: Math.max(1, Math.min(Number(contract.budget?.maxModelCalls ?? 1), 32)),
    toolExecution: 'sequential',
    hooks: {
      async beforeModel() {
        const index = ++modelIndex;
        await gateway.reserveModel(contract, { operationId: `model-${index}`, modelCalls: 1,
          maxTokens: Number(options.modelTokenReservation ?? 2048) });
        await forward({ type: 'MODEL_STARTED', modelCall: index });
      },
      async afterModel({ assistantMessage, requestId }) {
        const metrics = assistantMessage.metrics ?? {};
        const usage = {
          inputTokens: Number(metrics.inputTokens ?? 0),
          outputTokens: Number(metrics.outputTokens ?? 0),
          totalCost: metrics.cost == null ? null : Number(metrics.cost),
        };
        lastUsage = usage;
        await gateway.settleModel(contract, { operationId: `model-${modelIndex}`, requestId: requestId ?? null, usage });
        await forward({ type: 'MODEL_USAGE', modelCall: modelIndex, usage });
      },
      async afterRun({ result }) {
        await gateway.finish?.(contract, { status: result.status, iterations: result.iterations,
          summary: result.outputText?.slice(0, 1024) ?? '' });
      },
    },
  };
  if (options.model) config.model = options.model;
  else throw new TypeError('A Runtime-owned model connection is required');
  const agent = new Agent(config);
  return {
    events,
    async run() {
      const result = await agent.run(contract.objective?.instruction ?? 'Complete the authorized Coding task');
      return { status: result.status, text: result.outputText, iterations: result.iterations,
        usage: result.usage, lastUsage };
    },
    abort(reason = 'Runtime cancellation') {
      agent.abort(reason);
      if (activeOperation) void gateway.cancelPython?.(contract, activeOperation).catch(() => {});
    },
    snapshot() { return agent.snapshot(); },
  };
}
