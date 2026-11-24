import assert from 'node:assert/strict';
import OpenAI from 'openai';

const client = new OpenAI({ baseURL: process.env.GATEWAY_BASE_URL, apiKey: process.env.SDK_GATEWAY_KEY, maxRetries: 0 });
const model = 'sdk-model';
const models = await client.models.list();
assert.ok(models.data.some(item => item.id === model));
const messages = [{ role: 'user', content: 'Please reply.' }];
const normal = await client.chat.completions.create({ model, messages });
assert.equal(normal.choices[0].message.content, '网关正常');
const definitions = [{ type: 'function', function: { name: 'gateway_echo', parameters: { type: 'object' } } }];
const stream = await client.chat.completions.create({ model, messages, tools: definitions, stream: true });
const pieces = [];
for await (const event of stream) pieces.push(...(event.choices[0]?.delta.tool_calls ?? []));
const args = JSON.parse(pieces.map(piece => piece.function.arguments).join(''));
assert.equal(args.value, 'gateway-check');
const tool = { id: pieces[0].id, type: 'function', function: { name: pieces[0].function.name, arguments: JSON.stringify(args) } };
const result = await client.chat.completions.create({ model, messages: [...messages, { role: 'assistant', tool_calls: [tool] },
  { role: 'tool', tool_call_id: tool.id, content: 'Node SDK tool result' }], tools: definitions });
assert.equal(result.choices[0].message.content, 'Node SDK tool result');
console.log('OpenAI Node SDK HTTPS models + JSON + SSE tool round trip PASS');
