import { describe, expect, it } from 'vitest';
import { applyRules, ruleSchema } from '../protocol/rules.js';
import { eventText, readEvents } from '../protocol/sse.js';

describe('declarative rules', () => {
  it('preserves unconfigured data and nested empty containers', () => {
    const input = { max_completion_tokens: 20, temperature: 3, messages: [{ content: '多语言' }], extra: { a: [], b: {} } };
    const rules = [
      { stage: 'request', op: 'rename', path: 'max_completion_tokens', to: 'max_tokens' },
      { stage: 'request', op: 'clamp', path: 'temperature', min: 0, max: 2 },
      { stage: 'request', op: 'default', path: 'chat_template_kwargs.enable_thinking', value: true },
    ].map(rule => ruleSchema.parse(rule));
    expect(applyRules(input, rules, 'request')).toEqual({ temperature: 2, max_tokens: 20,
      chat_template_kwargs: { enable_thinking: true }, messages: input.messages, extra: input.extra });
    expect(input.max_completion_tokens).toBe(20);
  });
  it.each(['tools', 'messages.0.content', '__proto__.polluted', 'model', 'stream'])('rejects writes to %s', path => {
    expect(ruleSchema.safeParse({ stage: 'request', op: 'delete', path }).success).toBe(false);
  });
  it('reports collisions without discarding data', () => {
    expect(() => applyRules({ old: 1, new: 2 }, [ruleSchema.parse({ stage: 'response', op: 'rename', path: 'old', to: 'new' })], 'response'))
      .toThrow('Rename target already exists');
  });
  it('supports nested array member rules without rewriting unrelated fields', () => {
    const input = { choices: [{ delta: { thought: '推理', content: '' } }] };
    expect(applyRules(input, [ruleSchema.parse({ stage: 'sse', op: 'rename', path: 'choices.0.delta.thought',
      to: 'choices.0.delta.reasoning_content' })], 'sse')).toEqual({ choices: [{ delta: { reasoning_content: '推理', content: '' } }] });
  });
});

describe('SSE framing', () => {
  it('parses byte-fragmented CRLF, UTF-8, comments and multiline data', async () => {
    async function* input() { for (const byte of Buffer.from(': ping\r\n\r\ndata: {"a":\r\ndata: "中"}\r\n\r\ndata: [DONE]\n\n')) yield Buffer.from([byte]); }
    const items = await Array.fromAsync(readEvents(input()));
    expect(items.map(eventText)).toEqual([': ping\n\n', 'data: {"a":\ndata: "中"}\n\n', 'data: [DONE]\n\n']);
    expect(JSON.parse(items[1].data!)).toEqual({ a: '中' });
  });
  it('rejects EOF within an event', async () => {
    async function* input() { yield Buffer.from('data: {"truncated":'); }
    await expect(Array.fromAsync(readEvents(input()))).rejects.toThrow('SSE Event');
  });
});
