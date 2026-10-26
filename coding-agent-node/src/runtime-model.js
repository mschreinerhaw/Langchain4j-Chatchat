/** Cline AgentModel backed by the Runtime's configured Java model connection. */
export function createRuntimeModel(gateway, contract, initialOrdinal = 0, { nativeSdk = false } = {}) {
  if (typeof gateway?.model !== 'function') throw new TypeError('Runtime model callback is required');
  let modelCallIndex = initialOrdinal;
  const managesAdmissions = nativeSdk && gateway.atomicModelAttempts === true;
  return {
    managesAdmissions,
    async *stream(request) {
      // Project only portable text/tool parts. The Java side validates the bound
      // contract, tool names, sizes, lease and budget before contacting a provider.
      const messages = request.messages.map(message => ({
        role: message.role,
        content: message.content.map(part => {
          if (!['text', 'tool-call', 'tool-result'].includes(part.type))
            throw new TypeError(`Unsupported Runtime model content: ${part.type}`);
          return part;
        }),
      }));
      const tools = request.tools.map(tool => ({
        name: tool.name, description: tool.description, inputSchema: tool.inputSchema,
      }));
      const answer = await gateway.model(contract, {
        operationId: `model-${++modelCallIndex}`,
        ...(managesAdmissions ? { managedAttempt: true } : {}),
        modelResponseFeedbackVersion: 'runtime_os.coding.model_feedback.v1',
        systemPrompt: request.systemPrompt ?? '', messages, tools,
      }, request.signal);
      if (answer.error) {
        const feedback = answer.modelResponseFeedback;
        if (feedback?.schemaVersion !== 'runtime_os.coding.model_feedback.v1'
            || feedback.repairAllowed !== true
            || !['MODEL_OUTPUT_TRUNCATED', 'MODEL_TOOL_ARGUMENTS_INVALID'].includes(feedback.diagnosticCode)
            || !Number.isInteger(feedback.maxRepairAttempts) || feedback.maxRepairAttempts < 1
            || feedback.maxRepairAttempts > 8 || answer.toolCalls?.length)
          throw new Error('Runtime rejected the model response without an authorized repair decision');
        // This is provider-response feedback, never a fabricated tool invocation.
        if (!nativeSdk) yield { type: 'runtime-model-feedback', feedback };
        if (nativeSdk && feedback.diagnosticCode === 'MODEL_TOOL_ARGUMENTS_INVALID') {
          if (!answer.nativeToolCalls?.length) throw new Error('Native tool feedback requires authorized provider tool calls');
          for (const call of answer.nativeToolCalls) yield { type: 'tool-call-delta',
            toolCallId: call.id, toolName: call.name, inputText: call.arguments };
        }
        if (!nativeSdk && !answer.text) yield { type: 'text-delta', text:
          `Runtime rejected the provider tool arguments: ${feedback.diagnosticCode}. No tool was executed.` };
      }
      if (answer.modelInfo) yield { type: 'runtime-model-metadata', metadata: answer.modelInfo };
      if (answer.text) yield { type: 'text-delta', text: answer.text };
      for (const call of answer.toolCalls ?? []) {
        yield { type: 'tool-call-delta', toolCallId: call.id,
          toolName: call.name, input: call.input };
      }
      if (answer.usage) yield { type: 'usage', usage: answer.usage };
      const reasons = { STOP: 'stop', TOOL_EXECUTION: 'tool-calls', LENGTH: 'max-tokens',
        CONTENT_FILTER: 'content-filter', OTHER: 'unknown' };
      const reason = nativeSdk ? answer.error
        ? answer.modelResponseFeedback.diagnosticCode === 'MODEL_OUTPUT_TRUNCATED' ? 'max-tokens' : 'tool-calls'
        : reasons[answer.finishReason] ?? (answer.finishReason ? 'unknown' : answer.toolCalls?.length ? 'tool-calls' : 'stop')
        : answer.toolCalls?.length ? 'tool-calls' : 'stop';
      yield { type: 'finish', reason,
        requestId: answer.requestId };
    },
  };
}
