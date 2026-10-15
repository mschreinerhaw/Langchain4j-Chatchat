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
  if (contract?.schemaVersion !== 'runtime_os.coding.v1')
    throw new TypeError('VERSION_UNSUPPORTED: Coding worker requires runtime_os.coding.v1');
  if (!contract?.scope?.tenantId || !contract?.scope?.userId || !contract?.scope?.runId || !contract?.taskId)
    throw new TypeError('A complete Runtime coding task identity is required');
  if (!gateway || typeof gateway.executePython !== 'function' || typeof gateway.reserveModel !== 'function')
    throw new TypeError('Runtime gateway is required');
  if (contract.authorization?.networkPolicy !== 'NONE')
    throw new TypeError('D1 Coding Sandbox requires a no-network authorization');
  const permitted = new Set(contract.authorization?.allowedTools ?? []);
  for (const name of permitted) if (!TOOL_NAMES.has(name)) throw new TypeError(`Unrecognized Coding tool: ${name}`);
  if (!contract.authorization?.allowedSideEffects?.includes('CODE_EXECUTION')) permitted.delete('python_execute');
  if (!contract.metadataRefs?.length) permitted.delete('metadata_read');
  if (permitted.has('python_execute') && (contract.authorization?.allowedDataRefs?.length !== 1
      || !/^c[0-9a-f]{63}$/.test(contract.authorization.allowedDataRefs[0])
      || contract.outputContract?.requiredFiles?.length !== 1
      || !/^[A-Za-z0-9_-]+\.jsonl$/.test(contract.outputContract.requiredFiles[0])))
    throw new TypeError('Python execution requires one bound input snapshot and one JSONL result');
  let activeOperation = null;
  let lastCollectedOperation = null;
  let modelIndex = 0;
  let lastUsage = { inputTokens: 0, outputTokens: 0, totalCost: 0 };
  let firstRuntimeError = null;
  const events = [];
  const forward = async (event) => {
    const progress = { ...event, sequence: events.length + 1 };
    events.push(progress);
    if (typeof gateway.event === 'function') await gateway.event(progress);
  };
  const tools = [];
  if (permitted.has('python_execute')) tools.push({
    name: 'python_execute',
    description: `Run main.py in a NEW isolated Python sandbox for EACH call. Only the immutable input /data/input/${contract.authorization.allowedDataRefs[0]}/input.jsonl is mounted; previous calls' output files do not exist here. Each program must perform the calculation and write structured JSONL to /workspace/output/results/${contract.outputContract?.requiredFiles?.[0] ?? 'rows.jsonl'}. Inspect the returned Runtime receipt; repair technical errors with a complete new program. Never claim business validation yourself.`,
    inputSchema: { type: 'object', properties: {
      fileName: { type: 'string', enum: ['main.py'] }, source: { type: 'string' },
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
        let receipt = await gateway.executePython(contract, {
          operationId: id, fileName: input.fileName, source, toolCallId,
        }, context.signal);
        while (!receipt.executorStopped) {
          if (receipt.status === 'UNKNOWN')
            throw new Error('Python execution identity is unknown; Runtime reconciliation is required');
          if (typeof gateway.getPython !== 'function')
            throw new Error('Runtime Python status callback is required for asynchronous execution');
          if (context.signal?.aborted) throw context.signal.reason ?? new Error('Coding task cancelled');
          if (Date.now() >= Date.parse(contract.budget.deadlineAt))
            throw new Error('Coding task deadline reached while awaiting Python execution');
          await new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
              context.signal?.removeEventListener('abort', stop);
              resolve();
            }, 250);
            const stop = () => {
              clearTimeout(timer);
              context.signal?.removeEventListener('abort', stop);
              reject(context.signal.reason ?? new Error('Coding task cancelled'));
            };
            context.signal?.addEventListener('abort', stop, { once: true });
          });
          receipt = await gateway.getPython(contract, id, context.signal);
        }
        if (receipt.status === 'SUCCEEDED' && receipt.resultDatasetRef
            && receipt.validationRecordRef && receipt.evidenceBundleRef)
          lastCollectedOperation = id;
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
    execute(input, context) {
      const reference = required(input?.reference, 'reference');
      if (!contract.metadataRefs?.includes(reference))
        throw new TypeError('Metadata reference is outside the Runtime task authorization');
      return gateway.readMetadata(contract, reference, context.signal);
    },
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
    'When validationStatus is FAILED, inspect and repair the output schema. INCONCLUSIVE structural validation does not certify business correctness.',
    'Each Python call starts a fresh sandbox with the original immutable input, without earlier output files or Python variables. Do not launch a new call just to open a previous output file.',
    'A SUCCEEDED receipt with resultDatasetRef, validationRecordRef and evidenceBundleRef means Runtime archived the result. INCONCLUSIVE with businessValidationPassed=false means business correctness was not evaluated, not an execution failure. You may finish with that limitation; the main analysis can read the registered result.',
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
        try {
          await gateway.reserveModel(contract, { operationId: `model-${index}`, modelCalls: 1,
            maxTokens: Number(options.modelTokenReservation ?? 2048) });
        } catch (error) {
          firstRuntimeError ??= String(error?.message ?? error).slice(0, 512);
          throw error;
        }
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
        try {
          await gateway.finish?.(contract, { status: result.status, iterations: result.iterations,
            summary: result.outputText?.slice(0, 1024) ?? '',
            operationId: lastCollectedOperation });
        } catch (error) {
          if (result.status === 'completed') throw error;
          events.push({ type: 'RUNTIME_FINISH_FAILED',
            message: String(error?.message ?? error).slice(0, 512) });
        }
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
      const failure = firstRuntimeError ?? (result.error == null ? null
        : String(result.error?.message ?? result.error).slice(0, 512));
      return { status: result.status, text: result.outputText, iterations: result.iterations,
        usage: result.usage, lastUsage, error: failure };
    },
    abort(reason = 'Runtime cancellation') {
      agent.abort(reason);
      if (activeOperation) void gateway.cancelPython?.(contract, activeOperation).catch(() => {});
    },
    snapshot() { return agent.snapshot(); },
  };
}
