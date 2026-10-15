/** Cline AgentModel backed by the Runtime's configured Java model connection. */
export function createRuntimeModel(gateway, contract) {
  if (typeof gateway?.model !== 'function') throw new TypeError('Runtime model callback is required');
  let modelCallIndex = 0;
  return {
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
        systemPrompt: request.systemPrompt ?? '', messages, tools,
      }, request.signal);
      if (answer.text) yield { type: 'text-delta', text: answer.text };
      for (const call of answer.toolCalls ?? []) {
        yield { type: 'tool-call-delta', toolCallId: call.id,
          toolName: call.name, input: call.input };
      }
      if (answer.usage) yield { type: 'usage', usage: answer.usage };
      yield { type: 'finish', reason: answer.toolCalls?.length ? 'tool-calls' : 'stop',
        requestId: answer.requestId };
    },
  };
}
