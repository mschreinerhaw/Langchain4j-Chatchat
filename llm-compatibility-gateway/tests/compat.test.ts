import { afterEach, expect, test } from 'vitest';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { modelSchema, serviceSchema } from '../server/config.js';
import { inFlight, prepare, proxy } from '../server/proxy.js';
import type { JsonObject } from '../protocol/rules.js';

const servers: Server[] = [];
afterEach(async () => {
  for (const server of servers.splice(0)) {
    server.closeAllConnections();
    await new Promise<void>(resolve => server.close(() => resolve()));
  }
});
const tool = { type:'function', function:{ name:'count', parameters:{ type:'object',
  properties:{ amount:{ type:'integer' }, label:{ type:'string' } }, required:['amount','label'], additionalProperties:false } } };
const model = modelSchema.parse({ alias:'local/legacy', upstream:'/models/Legacy:1', service:'fixture',
  protocol:{ tools:'legacy-functions', reasoning:'think-tags', doneEvent:'finished', usageEvent:'metering',
    usage:{ prompt_tokens:'meter.input', completion_tokens:'meter.output', cached_tokens:'meter.cache', reasoning_tokens:'meter.reasoning' } } });
const request = { model:model.alias, stream:true, tools:[tool], tool_choice:'auto', messages:[{ role:'user', content:'count' }] };
const data = (value: unknown) => 'data: ' + JSON.stringify(value) + '\n\n';
const chunk = (delta: JsonObject, finish_reason: string | null = null) => data({ model:model.upstream, choices:[{ index:0, delta, finish_reason }] });

async function fixture(invalid = false, truncate = false) {
  const received: JsonObject[] = [];
  const server = createServer(async (req, res) => {
    const bytes: Buffer[] = [];
    for await (const block of req) bytes.push(block);
    const value = JSON.parse(Buffer.concat(bytes).toString());
    received.push(value);
    res.writeHead(200, { 'content-type': 'text/event-stream' });
    const meter = 'event: metering\n' + data({ meter:{ input:10, output:5, cache:2, reasoning:3 } });
    const stream = chunk({ role:'assistant', content:'<th' })
      + chunk({ content:'ink>计' })
      + chunk({ content:'算</thi' })
      + chunk({ content:'nk>答案', function_call:{ name:'count', arguments:'{"amount":' } })
      + (truncate ? 'event: finished\n\n' : chunk({ function_call:{ arguments:(invalid ? '"bad"' : '3') + ',"label":"中文"}' } })
        + chunk({}, 'function_call') + meter + meter + 'event: finished\n\n');
    const output = Buffer.from(stream);
    for (let i = 0; i < output.length; i += 3) res.write(output.subarray(i, i + 3));
    res.end();
  });
  servers.push(server);
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = 'http://127.0.0.1:' + (server.address() as AddressInfo).port;
  return { received, service:serviceSchema.parse({ id:'fixture', name:'fixture', origin }) };
}

test('a real HTTP legacy SSE provider preserves reasoning, tool history, authoritative usage and explicit termination', async () => {
  const { service, received } = await fixture();
  const output: string[] = [];
  const metrics: JsonObject = {};
  await proxy({ model, service, body:request, signal:AbortSignal.timeout(5000), metrics,
    writer:{ start:status => expect(status).toBe(200), async write(text) { output.push(text); } } });
  expect(received[0]).toMatchObject({ model:model.upstream, functions:[tool.function], function_call:'auto', stream:true });
  expect(received[0]).not.toHaveProperty('tools');
  const frames = output.slice(0, -1).map(frame => JSON.parse(frame.trim().slice(6)));
  expect(output.at(-1)).toBe('data: [DONE]\n\n');
  expect(frames.map(frame => frame.choices[0]?.delta?.reasoning_content ?? '').join('')).toBe('计算');
  expect(frames.map(frame => frame.choices[0]?.delta?.content ?? '').join('')).toBe('答案');
  const final = frames.find(frame => frame.choices[0]?.finish_reason === 'tool_calls').choices[0];
  const [call] = final.delta.tool_calls;
  expect(call).toMatchObject({ index:0, type:'function', function:{ name:'count', arguments:'{"amount":3,"label":"中文"}' } });
  expect(call.id).toMatch(/^call_/);
  const history = prepare({ ...request, messages:[...request.messages,
    { role:'assistant', tool_calls:[call], reasoning_content:'计算' },
    { role:'tool', tool_call_id:call.id, content:'3' }, { role:'user', content:'continue' }] }, model);
  expect(history.messages).toMatchObject([...request.messages,
    { role:'assistant', function_call:call.function, reasoning_content:'计算' },
    { role:'function', name:'count', content:'3' }, { role:'user', content:'continue' }]);
  const usage = frames.filter(frame => frame.usage);
  expect(usage).toHaveLength(1);
  expect(usage[0].usage).toEqual({ prompt_tokens:10, completion_tokens:5,
    prompt_tokens_details:{ cached_tokens:2 }, completion_tokens_details:{ reasoning_tokens:3 } });
  expect(metrics.usageStatus).toBe('COMPLETE');
});

test.each([[true,false],[false,true]])('legacy stream rejects invalid or unfinished calls without sending a success marker', async (invalid, truncate) => {
  const { service } = await fixture(invalid, truncate);
  const output: string[] = [];
  const metrics: JsonObject = {};
  await expect(proxy({ model, service, body:request, signal:AbortSignal.timeout(5000), metrics,
    writer:{ start() {}, async write(text) { output.push(text); } } })).rejects.toMatchObject({ code:'upstream_protocol_error' });
  expect(output.join('')).not.toContain('[DONE]');
  expect(output.join('')).not.toContain('tool_calls');
  expect(metrics.usageStatus).toBe('UNKNOWN');
  expect(inFlight()).toBe(0);
});

test('rejects impossible parallel and orphaned tool results before forwarding', () => {
  expect(() => prepare({ ...request, parallel_tool_calls:true }, model)).toThrow(/并行/);
  expect(() => prepare({ ...request, messages:[{role:'tool',tool_call_id:'unknown',content:'3'}] }, model)).toThrow(/匹配/);
});

test('image URL conversion is opted in and preserves bytes and unsupported controls are rejected', () => {
  const image = 'data:image/png;base64,YQ==';
  const messages = [{ role:'user', content:[{ type:'image_url', image_url:{ url:image } }] }];
  const mapped = prepare({ model:model.alias, messages }, { ...model, protocol:{ images:'flat-image-url' } });
  expect(mapped.messages).toEqual([{ role:'user', content:[{ type:'image_url', image_url:image }] }]);
  expect(prepare({ model:model.alias,messages }, { ...model, protocol:undefined }).messages).toEqual(messages);
  expect(() => prepare({ model:model.alias, messages:[{role:'user',content:[{type:'image_url',image_url:{url:image,detail:'high'}}]}] },
    { ...model, protocol:{ images:'flat-image-url' } })).toThrow(/detail/);
});
