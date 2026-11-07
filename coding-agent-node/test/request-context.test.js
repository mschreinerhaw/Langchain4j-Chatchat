import test from 'node:test';
import assert from 'node:assert/strict';
import { measureRequestContext, createRequestContextTracker, modelToolDisclosure } from '../src/request-context.js';
import { sdkPrepareTurn, boundCompactionCheckpoint } from '../src/sdk-adapter.js';

test('actual disclosure preserves authorized subsets and fingerprints schemas without retaining private contents',()=>{
  const contract={authorization:{allowedTools:['python_execute','market_search']}};
  const request={systemPrompt:'PRIVATE_PROMPT',messages:[{text:'PRIVATE_DATA'}],
    tools:[{name:'python_execute',description:'PRIVATE_DESCRIPTION',inputSchema:{type:'object',description:'PRIVATE_SCHEMA'}}]};
  const before=modelToolDisclosure(contract,request);
  assert.deepEqual(before.authorizedTools,['market_search','python_execute']);
  assert.deepEqual(before.disclosedTools,['python_execute']);
  assert.doesNotMatch(JSON.stringify(before),/PRIVATE/);
  assert.match(before.toolSchemaSha256,/^[a-f0-9]{64}$/);
  const after=modelToolDisclosure(contract,{...request,tools:[{...request.tools[0],inputSchema:{type:'string'}}]});
  assert.notEqual(before.toolSchemaSha256,after.toolSchemaSha256);
  assert.throws(()=>modelToolDisclosure(contract,{tools:[{name:'bash'}]}),/authorization/);
  assert.throws(()=>modelToolDisclosure(contract,{tools:[request.tools[0],request.tools[0]]}),/authorization/);
});

test('initial discovery catalog is attributed separately from objective and tool schemas',()=>{
  const text='Goal\nRuntime task configuration snapshot (methodology only; never additional permissions):\n'
    +JSON.stringify({skills:{skills:[{id:'method',description:'Method description'}]}})
    +'\nAuthorized tool index (metadata only). Load schemas with tool_describe, batching the next necessary tools. Skills remain discoverable through skill_read.\n'
    +JSON.stringify([{name:'read',description:'Authorized read'}]);
  const result=measureRequestContext({messages:[{role:'user',content:[{type:'text',text}]}],tools:[]});
  assert.ok(result.contextSkillsBytes>0);assert.ok(result.contextToolIndexBytes>0);assert.ok(result.contextTaskBytes>0);
  assert.equal(Object.values(result.components).reduce((sum,part)=>sum+part.bytes,0),result.requestBytes);
});

test('final payload attribution includes tools, skills, report, code and multibyte text without leaking contents', () => {
  const request = { systemPrompt: 'instruction', tools: [{ name: 'python_execute', inputSchema: {} }], messages: [
    { role: 'user', content: [{ type: 'text', text: '用户请求' }] },
    { role: 'assistant', content: [{ type: 'tool-call', toolName: 'python_execute', input: { source: 'secret_source' } }] },
    { role: 'tool', content: [{ type: 'tool-result', toolName: 'skill_read', output: { text: 'secret_skill' } },
      { type: 'tool-result', toolName: 'artifact_read', output: { report: 'secret_report' } }] },
  ] };
  const measured = measureRequestContext(request, 10);
  assert.equal(Object.values(measured.components).reduce((n, item) => n + item.bytes, 0), measured.requestBytes);
  for (const key of ['tools','skills','report','code','task']) assert(measured.components[key].bytes > 0);
  assert.equal(measured.exceedsSoftBudget, true);
  assert.equal(measured.estimatedInputTokens, Math.ceil(Buffer.byteLength(JSON.stringify(request)) / 4));
  assert.doesNotMatch(JSON.stringify(measured), /secret_|用户请求/);
});

test('request replay accounting retains only hashes and separates stable context from new parts', () => {
  const track = createRequestContextTracker();
  const request = { systemPrompt: 'PRIVATE_INSTRUCTION', tools: [], messages: [
    { role: 'user', content: [{ type: 'text', text: 'PRIVATE_INPUT' }] }] };
  const first = track(request);
  assert.equal(first.repeatedContextBytes, 0);
  const unchanged = track(request);
  assert.equal(unchanged.contextChangeReason, 'UNCHANGED_CONTEXT');
  assert.equal(unchanged.repeatedContextBytes, first.newContextBytes);
  const next = track({ ...request, messages: [...request.messages,
    { role: 'tool', content: [{ type: 'text', text: 'NEW_DIAGNOSIS' }] }] });
  assert(next.newContextBytes > 0);assert(next.repeatedContextBytes > 0);
  assert.doesNotMatch(JSON.stringify(next), /PRIVATE|NEW_DIAGNOSIS/);
});

test('native state-aware compaction is reused and restored against canonical prefix hashes', async () => {
  const messages = [{ role: 'user', content: [{ type: 'text', text: 'AUTHORIZED_GOAL' }] },
    ...Array.from({ length: 12 }, (_, i) => ({ role: 'assistant', content: [{ type: 'text', text: String(i) + 'x'.repeat(20000) }] }))];
  const context = { messages, tools: [], model: { info: { maxInputTokens: 4000 } }, systemPrompt: '', signal: new AbortController().signal };
  const prepare = sdkPrepareTurn('state', () => null, { statefulCompactionEnabled: true });
  const first = await prepare(context);
  assert(first.messages.length < messages.length);
  const saved = structuredClone(prepare.snapshot());
  assert.equal(saved.compactionState.source_message_count, messages.length);
  assert(saved.compactionState.source_prefix_hash);
  const added = { role: 'user', content: [{ type: 'text', text: 'LATEST_DIAGNOSIS' }] };
  const resume = sdkPrepareTurn('state', () => null, { statefulCompactionEnabled: true, ...saved });
  const projected = await resume({ ...context, messages: [...messages, added] });
  assert.match(JSON.stringify(projected.messages), /LATEST_DIAGNOSIS/);
  assert.equal(projected.messages.length, first.messages.length + 1);
  assert.equal(resume.snapshot().compactionCount, saved.compactionCount);
  assert.equal(resume.measurement().compactionStateReused, true);
  const stale = await resume({ ...context, messages: [added] });
  assert.equal(stale, undefined);
});

test('source binding becomes a reference only when current task advertises code_read', async () => {
  const code = { source: 'FULL_PROGRAM'.repeat(1000), version: { version: 2, codeHash: 'exact-current' }, lastValidatedHash: null };
  const context = { messages: [{ role: 'user', content: [{ type: 'text', text: 'objective' }] }],
    tools: [{ name: 'code_read' }], model: {}, systemPrompt: '', signal: new AbortController().signal };
  const prepare = sdkPrepareTurn('reference', () => code, { codeReferenceEnabled: true });
  const result = await prepare(context);
  assert.doesNotMatch(JSON.stringify(result), /FULL_PROGRAM/);
  assert.match(JSON.stringify(result), /exact-current/);
  assert.match(JSON.stringify(result), /code_read/);
  const fallback = await prepare({ ...context, tools: [] });
  assert.match(JSON.stringify(fallback), /FULL_PROGRAM/);
});

test('checkpoint size pressure discards only the optional projection, never canonical recovery evidence', () => {
  const checkpoint = { messages: [{role:'user',content:[{type:'text',text:'canonical'}]}],
    codeLifecycle: { codeHash: 'current', source: 'latest' }, compactionCount: 2,
    compactionState: { messages: [{ content: 'x'.repeat(300000) }] } };
  const bounded = boundCompactionCheckpoint(checkpoint,262144);
  assert.equal(bounded.compactionState,undefined);
  assert.deepEqual(bounded.messages,checkpoint.messages);assert.deepEqual(bounded.codeLifecycle,checkpoint.codeLifecycle);
  assert.equal(bounded.compactionCount,2);assert(checkpoint.compactionState);
  assert.equal(boundCompactionCheckpoint(checkpoint,750000),checkpoint);
});

test('compacted tool results round trip objects without a second JSON string envelope', async () => {
  const messages = [{ role: 'user', content: [{ type: 'text', text: 'objective' }] },
    ...Array.from({ length: 10 }, () => ({ role: 'assistant', content: [{ type: 'text', text: 'x'.repeat(20000) }] })),
    { role: 'assistant', content: [{ type: 'tool-call', toolCallId: 'read', toolName: 'code_read', input: { codeHash: 'hash' } }] },
    { role: 'tool', content: [{ type: 'tool-result', toolCallId: 'read', toolName: 'code_read', output: { version: 2 } }] }];
  const prepare = sdkPrepareTurn('roundtrip');
  const result = await prepare({ messages, tools: [], model: { info: { maxInputTokens: 4000 } }, systemPrompt: '', signal: new AbortController().signal });
  const output = result.messages.flatMap(m => m.content).find(p => p.type === 'tool-result');
  assert.equal(typeof output.output, 'object');assert.equal(output.output.version, 2);
  const pending = await prepare({ messages: messages.slice(0,-1), tools: [], model: {}, systemPrompt: '' });
  assert.equal(pending, undefined);
});

test('review projection preserves exact version/hash while omitting fresh full-source injection; repair restores it', async () => {
  const binding = { source: 'CURRENT_PROGRAM'.repeat(2000), version: { version: 3, codeHash: 'bound-hash' }, lastValidatedHash: 'bound-hash' };
  let reviewOnly = true;
  const prepare = sdkPrepareTurn('review', () => binding, { reviewOnly: () => reviewOnly });
  const context = { messages: [{ role: 'user', content: [{ type: 'text', text: 'authorized objective' }] }],
    tools: [], model: { info: { maxInputTokens: 128000 } }, systemPrompt: '', signal: new AbortController().signal };
  const review = await prepare(context);
  assert.doesNotMatch(JSON.stringify(review.messages), /CURRENT_PROGRAM/);
  assert.match(JSON.stringify(review.messages), /bound-hash/);
  assert.match(JSON.stringify(review.messages), /code_read/);
  reviewOnly = false;
  const repair = await prepare({ ...context, messages: review.messages });
  assert.match(JSON.stringify(repair.messages), /CURRENT_PROGRAM/);
  assert.doesNotMatch(JSON.stringify(repair.messages), /Latest program binding/);
  assert.equal(binding.version.version, 3);
});
