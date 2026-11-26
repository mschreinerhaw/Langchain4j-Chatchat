import { createServer } from 'node:http';
import { createServer as createSecureServer } from 'node:https';
import { readFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';

let cancellations = 0;
const clineRequests = [];
const lines = value => 'data: ' + JSON.stringify(value) + '\r\n\r\n';
const handler = async (req, res) => {
  if (req.url === '/fixture/metrics') return res.end(JSON.stringify({ cancellations, clineRequests }));
  if (req.headers.authorization !== 'Bearer fixture-upstream-credential') {
    res.writeHead(401, { 'content-type': 'application/json' });
    return res.end(JSON.stringify({ error: { message: 'Invalid credential fixture-upstream-credential' } }));
  }
  if (req.url === '/redirect') {
    res.writeHead(302, { location: 'http://example.invalid' });
    return res.end();
  }
  if (req.url === '/compatible-mode/v1/models') {
    res.setHeader('content-type', 'application/json');
    return res.end(JSON.stringify({ data: [{ id: '/models/Qwen中文' }] }));
  }
  if (!['/v1/chat/completions', '/compatible-mode/v1/chat/completions'].includes(req.url)) {
    res.writeHead(404); return res.end('No model list');
  }
  const parts = [];
  for await (const chunk of req) parts.push(chunk);
  const input = JSON.parse(Buffer.concat(parts));
  if (input.model === '/models/ClineRuntimeAnalysis') return runtimeAnalysis(input, res);
  if (input.model === '/models/ClineLegacy') return clineLegacy(input, res);
  const clineCancel = input.model === '/models/ClineCancel';
  const clineTask = input.model === '/models/ClineTask' || clineCancel;
  const cline = clineTask || input.model === '/models/ClineFixture';
  const mode = cline ? 'cline' : input.messages?.[0]?.content;
  if (typeof mode !== 'string' || !input.model.startsWith('/models/')) {
    res.writeHead(400);
    return res.end('Fixture rejects the request');
  }
  if (cline) clineRequests.push({ model: input.model, path: req.url, stream: input.stream === true });
  if (mode === 'never') { await delay(1800); if (res.destroyed) return; }
  const basic = { id: 'fixture-id', model: input.model, object: 'chat.completion', created: 1 };
  const hasWritten = clineTask && input.messages.some(message => message.role === 'assistant' &&
    /write_to_file|gateway-result\.txt/.test(JSON.stringify(message)));
  const tool = cline ? input.tools?.find(item => item.function?.name === (clineTask && !hasWritten
    ? 'editor' : 'attempt_completion'))?.function.name : input.tools?.[0]?.function?.name;
  const args = tool === 'editor' ? JSON.stringify({ path: '/tmp/gateway-cline/workspace/gateway-result.txt', new_text: 'HTTPS gateway verified\n' })
    : cline ? '{"result":"Gateway fixture completed"}' : '{"value":"gateway-check"}';
  const toolResult = input.messages.find(message => message.role === 'tool' && message.tool_call_id === 'call_fixture')?.content;
  const calls = tool && (cline || !toolResult) ? [{ id: clineTask ? 'call_file_' + Number(hasWritten) : 'call_fixture',
    type: 'function', function: { name: tool, arguments: args } }] : [];
  const clineText = clineTask ? 'Gateway fixture completed' : '<attempt_completion><result>Gateway fixture completed</result></attempt_completion>';
  const finalText = toolResult ?? (cline ? clineText :
    mode.includes('中文') ? '网关正常' : mode.includes('"result"') ? '{"result":"OK"}' : '网关正常');
  const usage = { prompt_tokens: 3, completion_tokens: 4, total_tokens: 7 };
  if (!input.stream) {
    res.writeHead(200, { 'content-type': 'application/json' });
    return res.end(JSON.stringify({ ...basic,
      choices: [{ index: 0, message: { role: 'assistant', content: calls.length ? null : finalText,
        reasoning_content: '推理', ...(calls.length ? { tool_calls: calls } : {}) }, finish_reason: calls.length ? 'tool_calls' : 'stop' }],
      ...(mode === 'no usage' ? {} : { usage }), fixture: { input, marker: req.headers['x-test'] } }));
  }
  res.on('close', () => { if (!res.writableFinished) cancellations++; });
  if (clineTask) await delay(clineCancel ? 15_000 : Number(process.env.FIXTURE_CLINE_FIRST_MS ?? 0));
  if (res.destroyed) return;
  res.writeHead(200, { 'content-type': 'text/event-stream' });
  res.flushHeaders();
  const chunk = (delta, finish_reason = null) => ({ ...basic, object: 'chat.completion.chunk', choices: [{ index: 0, delta, finish_reason }] });
  if (mode === 'stall-first') { await delay(1800); return res.end(); }
  const content = Buffer.from(lines(chunk({ role: 'assistant', reasoning_content: '推理', content: cline ? (calls.length ? '' : finalText) : '你好' })));
  const split = content.indexOf(Buffer.from('推')) + 1;
  res.write(content.subarray(0, split));
  await delay(10);
  res.write(content.subarray(split, content.length - 1));
  await delay(5);
  res.write(content.subarray(content.length - 1));
  if (clineTask) await delay(Number(process.env.FIXTURE_CLINE_PAUSE_MS ?? 0));
  if (res.destroyed) return;
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
};

async function clineLegacy(input, res) {
  const definitions = input.functions?.map(tool => tool.name) ?? [];
  const alreadyWrote = input.messages.some(message => message.role === 'assistant' && message.function_call?.name === 'editor');
  const result = input.messages.find(message => message.role === 'function' && message.name === 'editor');
  if (!input.stream || input.tools || !definitions.includes('editor') || (alreadyWrote && !result)) {
    res.writeHead(400, { 'content-type': 'application/json' });
    return res.end(JSON.stringify({ error:{ message:'Legacy function definitions or result are missing' } }));
  }
  const base = { id:'legacy-test', object:'chat.completion.chunk', created:1, model:input.model };
  const frame = (delta, finish_reason = null) => lines({ ...base, choices:[{ index:0, delta, finish_reason }] });
  res.writeHead(200, { 'content-type': 'text/event-stream' });
  res.write(frame({ role:'assistant', content:'<th' }));
  await delay(10);
  res.write(frame({ content:'ink>验证工具</think>' }));
  if (!alreadyWrote) {
    const args = JSON.stringify({ path:'/tmp/gateway-cline/workspace/gateway-result.txt', new_text:'HTTPS gateway verified\n' });
    const splitAt = 21;
    res.write(frame({ function_call:{ name:'editor', arguments:args.slice(0, splitAt) } }));
    await delay(10);
    res.write(frame({ function_call:{ arguments:args.slice(splitAt) } }) + frame({}, 'function_call'));
  } else res.write(frame({ content:'Gateway fixture completed' }) + frame({}, 'stop'));
  res.end('event: metering\n' + lines({ meter:{ input:3, output:4, total:7 } }) + 'event: finished\n\n');
}

async function runtimeAnalysis(input, res) {
  const offered = input.tools?.map(tool => tool.function?.name).filter(Boolean) ?? [];
  clineRequests.push({ model: input.model, path: '/v1/chat/completions', stream: input.stream === true, offered });
  res.on('close', () => { if (!res.writableFinished) cancellations++; });
  await delay(Number(process.env.FIXTURE_CLINE_FIRST_MS ?? 0));
  if (res.destroyed) return;
  const source = [
    'import csv, json',
    'from pathlib import Path',
    'source = next(Path("/workspace/input").glob("**/gateway-acceptance.csv"))',
    'with source.open(newline="") as file: rows = list(csv.DictReader(file))',
    'result = {"rowCount": len(rows), "total": sum(int(row["value"]) for row in rows)}',
    'root = Path("/workspace")',
    '(root / "output").mkdir(exist_ok=True)',
    '(root / "output/analysis.json").write_text(json.dumps(result))',
    '(root / "output/report.md").write_text("# Gateway fixture analysis\\n\\nRows: {}. Total: {}.\\n".format(result["rowCount"], result["total"]))',
    '(root / "delivery_manifest.json").write_text(json.dumps({"schemaVersion":"coding_node_delivery_selection.v1","artifacts":[{"path":"output/report.md","type":"REPORT"},{"path":"output/analysis.json","type":"RESULT"}]}))',
    'print("Input analyzed and output written.")',
  ].join('\n');
  const computed = input.messages.some(message => message.role === 'tool' && message.tool_call_id === 'runtime_compute');
  const name = computed ? offered.find(tool => tool === 'submit_and_exit' || tool === 'attempt_completion') : 'run_commands';
  if (!input.stream || (!computed && !offered.includes(name))) {
    res.writeHead(400, { 'content-type': 'application/json' });
    return res.end(JSON.stringify({ error: { code: 'fixture_tool_missing', message: 'Required Native fixture tool was not offered' } }));
  }
  const args = computed ? { result: 'The workspace analysis is ready for Runtime validation.' }
    : { commands: [{ command: 'python3', args: ['-c', source] }] };
  const call = { index: 0, id: computed ? 'runtime_submit' : 'runtime_compute', type: 'function',
    function: { name, arguments: JSON.stringify(args) } };
  const chunk = (delta, finish_reason) => lines({ id: 'runtime-analysis', object: 'chat.completion.chunk', created: 1,
    model: input.model, choices: [{ index: 0, delta, finish_reason }] });
  res.writeHead(200, { 'content-type': 'text/event-stream' });
  res.write(chunk({ role: 'assistant', reasoning_content: 'Analyze the supplied fixture locally.' }, null));
  await delay(Number(process.env.FIXTURE_CLINE_PAUSE_MS ?? 0));
  if (res.destroyed) return;
  const finished = name ? chunk({ tool_calls: [call] }, null) + chunk({}, 'tool_calls')
    : chunk({ content: 'The workspace analysis is ready for Runtime validation.' }, null) + chunk({}, 'stop');
  res.end(finished + 'data: [DONE]\r\n\r\n');
}
const server = process.env.FIXTURE_TLS_CERT
  ? createSecureServer({ cert: readFileSync(process.env.FIXTURE_TLS_CERT), key: readFileSync(process.env.FIXTURE_TLS_KEY) }, handler)
  : createServer(handler);
server.listen(Number(process.env.FIXTURE_PORT ?? 19090), '0.0.0.0');
