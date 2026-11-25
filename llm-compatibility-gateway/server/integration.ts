import { readEvents } from '../protocol/sse.js';

export function integrationBody(alias: string, mode: 'chat' | 'sse' | 'tool') {
  return { model: alias, stream: mode === 'sse',
    messages: [{ role: 'user', content: mode === 'tool'
      ? 'Call gateway_echo with value GATEWAY_OK.' : 'Reply with GATEWAY_OK.' }],
    ...(mode === 'tool' ? { tools: [{ type: 'function', function: {
      name: 'gateway_echo', description: 'Return the supplied value',
      parameters: { type: 'object', properties: { value: { type: 'string' } }, required: ['value'] },
    } }], tool_choice: 'required' } : {}),
  };
}

export async function checkIntegrationResponse(mode: 'chat' | 'sse' | 'tool', alias: string,
  result: { status: number; body: string; mediaType: string; truncated: boolean }) {
  if (result.status !== 200 || result.truncated) return false;
  try {
    if (mode === 'sse') {
      if (!/^text\/event-stream(?:;|$)/i.test(result.mediaType)) return false;
      let done = false;
      let content = false;
      async function* body() { yield Buffer.from(result.body); }
      for await (const event of readEvents(body())) {
        if (event.data === undefined) continue;
        if (done) return false;
        if (event.data === '[DONE]') { done = true; continue; }
        const data = JSON.parse(event.data);
        if (data.error || data.model !== alias || !Array.isArray(data.choices)) return false;
        content ||= data.choices.some((choice: { delta?: { content?: unknown } }) =>
          typeof choice.delta?.content === 'string' && choice.delta.content.length > 0);
      }
      return done && content;
    }
    const data = JSON.parse(result.body);
    if (data.error || data.model !== alias || !Array.isArray(data.choices)) return false;
    return data.choices.some(({ message }: { message?: { content?: string; tool_calls?: {
      id?: string; type?: string; function?: { name?: string; arguments?: string };
    }[] } }) => mode === 'chat' ? typeof message?.content === 'string' && message.content.trim().length > 0
      : Array.isArray(message?.tool_calls) && message.tool_calls.some(call => {
        if (!call.id || call.type !== 'function' || call.function?.name !== 'gateway_echo'
          || typeof call.function.arguments !== 'string') return false;
        try { return typeof JSON.parse(call.function.arguments)?.value === 'string'; } catch { return false; }
      }));
  } catch { return false; }
}
