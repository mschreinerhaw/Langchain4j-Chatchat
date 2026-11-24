import { createServer } from 'node:http';
import { setTimeout as delay } from 'node:timers/promises';

let cancellations = 0;
const lines = value => 'data: ' + JSON.stringify(value) + '\r\n\r\n';
const server = createServer(async (req, res) => {
  if (req.url === '/fixture/metrics') return res.end(JSON.stringify({ cancellations }));
  if (req.headers.authorization !== 'Bearer fixture-upstream-credential') {
    res.writeHead(401, { 'content-type': 'application/json' });
    return res.end(JSON.stringify({ error: { message: 'Invalid credential fixture-upstream-credential' } }));
  }
  if (req.url === '/redirect') {
    res.writeHead(302, { location: 'http://example.invalid' });
    return res.end();
  }
  if (req.url !== '/v1/chat/completions') { res.writeHead(404); return res.end('No model list'); }
  const parts = [];
  for await (const chunk of req) parts.push(chunk);
  const input = JSON.parse(Buffer.concat(parts));
  const cline = input.model === '/models/ClineFixture';
  const mode = cline ? 'cline' : input.messages?.[0]?.content;
  if (typeof mode !== 'string' || !input.model.startsWith('/models/')) {
    res.writeHead(400);
    return res.end('Fixture rejects the request');
  }
  if (mode === 'never') { await delay(1800); if (res.destroyed) return; }
  const basic = { id: 'fixture-id', model: input.model, object: 'chat.completion', created: 1 };
  const tool = cline ? input.tools?.find(item => item.function?.name === 'attempt_completion')?.function.name : input.tools?.[0]?.function?.name;
  const args = cline ? '{"result":"Gateway fixture completed"}' : '{"value":"gateway-check"}';
  const toolResult = input.messages.find(message => message.role === 'tool' && message.tool_call_id === 'call_fixture')?.content;
  const calls = tool && !toolResult ? [{ id: 'call_fixture', type: 'function', function: { name: tool, arguments: args } }] : [];
  const finalText = toolResult ?? (cline ? '<attempt_completion><result>Gateway fixture completed</result></attempt_completion>' :
    mode.includes('中文') ? '网关正常' : mode.includes('"result"') ? '{"result":"OK"}' : '网关正常');
  const usage = { prompt_tokens: 3, completion_tokens: 4, total_tokens: 7 };
  if (!input.stream) {
    res.writeHead(200, { 'content-type': 'application/json' });
    return res.end(JSON.stringify({ ...basic,
      choices: [{ index: 0, message: { role: 'assistant', content: calls.length ? null : finalText,
        reasoning_content: '推理', ...(calls.length ? { tool_calls: calls } : {}) }, finish_reason: calls.length ? 'tool_calls' : 'stop' }],
      ...(mode === 'no usage' ? {} : { usage }), fixture: { input, marker: req.headers['x-test'] } }));
  }
  res.writeHead(200, { 'content-type': 'text/event-stream' });
  res.flushHeaders();
  res.on('close', () => { if (!res.writableFinished) cancellations++; });
  const chunk = (delta, finish_reason = null) => ({ ...basic, object: 'chat.completion.chunk', choices: [{ index: 0, delta, finish_reason }] });
  if (mode === 'stall-first') { await delay(1800); return res.end(); }
  const content = Buffer.from(lines(chunk({ role: 'assistant', reasoning_content: '推理', content: cline ? (calls.length ? '' : finalText) : '你好' })));
  const split = content.indexOf(Buffer.from('推')) + 1;
  res.write(content.subarray(0, split));
  await delay(10);
  res.write(content.subarray(split, content.length - 1));
  await delay(5);
  res.write(content.subarray(content.length - 1));
  if (mode === 'stall' || mode === 'cancel') { await delay(1800); return res.end(); }
  if (mode === 'disconnect') { await delay(40); return res.destroy(); }
  if (mode === 'malformed') return res.end('data: invalid\n\n');
  if (mode === 'snapshot') {
    await delay(150);
    res.write(lines(chunk({ reasoning_content: '第二段' })));
  }
  if (calls.length) {
    const middle = args.indexOf(':') + 1;
    res.write(lines(chunk({ tool_calls: [{ index: 0, id: calls[0].id, type: 'function', function: { name: tool, arguments: args.slice(0, middle) } }] }))
      + lines(chunk({ tool_calls: [{ index: 0, function: { arguments: args.slice(middle) } }] })));
  }
  res.write(lines(chunk({}, calls.length ? 'tool_calls' : 'stop')) + lines({ ...basic, choices: [], usage }));
  res.end(mode === 'no done' ? '' : 'data: [DONE]\r\n\r\n');
});
server.listen(19090, '0.0.0.0');
