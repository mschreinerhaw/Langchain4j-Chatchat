import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createCodingRun } from '../src/runner.js';
import { createRuntimeModel } from '../src/runtime-model.js';

const contract = {
  schemaVersion: 'runtime_os.coding.v3', taskId: 'reviewed-task',
  scope: { tenantId: 'tenant', userId: 'owner', runId: 'reviewed-run' },
  objective: { instruction: 'Analyze the authorized immutable input and review the actual report.' },
  inputSnapshotRef: 'runtime-evidence:input', validationPlanRef: 'validation:fixture',
  metadataRefs: ['runtime-coding:analysis-reviewed-path:v1'],
  authorization: { allowedTools: ['python_execute', 'artifact_read'],
    allowedSideEffects: ['CODE_EXECUTION'], allowedDataRefs: [`c${'a'.repeat(63)}`], networkPolicy: 'NONE' },
  outputContract: { resultFormat: 'JSON_LINES', requiredFiles: ['rows.jsonl'] },
  budget: { maxModelCalls: 5 },
};
const python = (turn) => ({ toolCalls: [{ id: `code-${turn}`, name: 'python_execute',
  input: { fileName: 'main.py', source: `print(${turn})` } }], usage: { inputTokens: 10, outputTokens: 5 } });
const complete = { text: 'Reviewed the actual report and its limitations.', toolCalls: [],
  usage: { inputTokens: 10, outputTokens: 5 } };
const verified = (version) => ({ status: 'SUCCEEDED', executorStopped: true, resultAvailable: true,
  resultDatasetRef: `runtime-evidence:result-${version}`, validationRecordRef: `validation-${version}`,
  evidenceBundleRef: `bundle-${version}`, validationStatus: 'INCONCLUSIVE' });

function fixture(model, execute, read, taskContract = contract, overrides = {}) {
  const counts = { models: 0, executions: 0, reads: 0, checkpoints: [], finish: null };
  const gateway = {
    async reserveModel() {}, async settleModel() {},
    async checkpoint(_task, snapshot) { counts.checkpoints.push(structuredClone(snapshot)); },
    async preparePython(_task, input) {
      const version = counts.executions + 1, fileName = `analysis_20261026T000000000Z_abcdef012345_v${version}.py`;
      return { fileName, scriptVersion: version, scriptPath: '/workspace/scripts/' + fileName,
        workingDirectory: 'tasks/' + ['a'.repeat(16), 'b'.repeat(16), 'c'.repeat(16), 'd'.repeat(24)].join('/') };
    },
    async model(_task, request) { return model(++counts.models, request); },
    async executePython(_task, input) { return execute(++counts.executions, input); },
    async readArtifact(task, reference) { assert.equal(task.scope.userId, 'owner'); return read(++counts.reads, reference); },
    async finish(_task, outcome) { counts.finish = outcome; },
    ...overrides,
  };
  return { counts, gateway, run: createCodingRun(taskContract, gateway, { model: createRuntimeModel(gateway, taskContract) }) };
}
test('real SDK checkpoints only after every call in a multi-tool turn has its result',async()=>{
  const task={...contract,authorization:{...contract.authorization,allowedTools:['python_execute','artifact_read','input_read']}};
  let saved=0;
  const {run,counts}=fixture(turn=>turn===1?{...python(turn),toolCalls:[...python(turn).toolCalls,{id:'input-1',name:'input_read',input:{datasetReference:'source',offset:0,limit:1}}]}:complete,verified,
    (_count,reference)=>({reference,complete:true,rows:[{reportMarkdown:'actual derived result'}],reportReferenceValidation:{valid:true}}),task,
    {async readInput(){return {rows:[{record:'authorized input'}]};},async checkpoint(_task,snapshot){
      const pending=new Set();for(const message of snapshot.messages)for(const part of message.content){if(part.type==='tool-call')pending.add(part.toolCallId);if(part.type==='tool-result')pending.delete(part.toolCallId);}
      assert.equal(pending.size,0,'Java rejects checkpoints with pending SDK tool calls');saved++;
    }});
  assert.equal((await run.run()).status,'completed');assert.equal(counts.executions,1);assert.equal(counts.models,2);assert(saved>=2);
});
test('native SDK reuses the supplied report and keeps Python repair without a second text submission path', async () => {
  const task={...contract,runtimeContext:{analysisResultMode:'TEXT_OR_CODE'},
    authorization:{...contract.authorization,allowedTools:['python_execute','artifact_read','report_submit']}};
  const {run,counts}=fixture((turn,request)=>{
    if(turn===1) {
      assert(request.tools.some(t=>t.name==='report_submit'));
      return python(turn);
    }
    assert.match(JSON.stringify(request.messages),/ACTUAL_REPORT_FOR_REVIEW/);
    assert(request.tools.some(t=>t.name==='python_execute'));
    assert(request.tools.some(t=>t.name==='artifact_read'));
    assert(!request.tools.some(t=>t.name==='report_submit'));
    return complete;
  },verified,(_read,reference)=>({reference,complete:true,rows:[{reportMarkdown:'ACTUAL_REPORT_FOR_REVIEW'}],
    reportReferenceValidation:{valid:true}}),task);
  assert.equal((await run.run()).status,'completed');
  assert.equal(counts.models,2);assert.equal(counts.executions,1);assert.equal(counts.reads,1);
});

test('real SDK repairs references inside an earlier patch without freezing text or reusing rejected evidence', async () => {
  const task = { ...contract, runtimeContext: { codingLifecycle: {
    schemaVersion: 'runtime_os.code_lifecycle.v1', patchOnlyAfterFirst: true } } };
  const sources = ["reference = 'draft'", "reference = 'bad-reference'", "reference = 'authorized-reference'"];
  const { run, counts } = fixture((turn, request) => {
    if (turn === 4) return complete;
    if (turn === 3) assert.match(JSON.stringify(request.messages), /INVALID_RESULT_REFERENCES/);
    const input = turn === 1 ? { source: sources[0] } : { repair: {
      parentCodeVersion: turn - 1,
      baseCodeSha256: createHash('sha256').update(sources[turn - 2]).digest('hex'),
      edits: [{ oldText: sources[turn - 2], newText: sources[turn - 1] }],
    } };
    return { toolCalls: [{ id: `repair-${turn}`, name: 'python_execute', input }],
      usage: { inputTokens: 10, outputTokens: 5 } };
  }, verified, (read, reference) => ({ reference, complete: true,
    rows: [{ reportMarkdown: 'Computed findings' }], reportReferenceValidation: { valid: read !== 2 } }), task);
  assert.equal((await run.run()).status, 'completed');
  assert.equal(counts.executions, 3); assert.equal(counts.reads, 3); assert.equal(counts.models, 4);
  assert(!run.events.some(event => event.type === 'CODE_PROPOSAL_REJECTED'));
  const latest = counts.checkpoints.filter(snapshot => snapshot.codeLifecycle?.versions.length === 3).at(-1);
  assert.deepEqual(latest.codeLifecycle.versions.map(version => version.status), ['VALIDATED', 'REJECTED', 'VALIDATED']);
  assert.equal(counts.finish.operationId, latest.lastCollectedOperation);
});

test('reviewed analysis delivers the actual authorized report to the next semantic turn without a routing model call', async () => {
  const { run, counts } = fixture((turn, request) => {
    if (turn === 1) return python(turn);
    assert.equal(turn, 2);
    assert.match(JSON.stringify(request.messages), /ACTUAL_COMPUTED_REPORT/);
    assert.match(JSON.stringify(request.messages), /INCONCLUSIVE/);
    assert.match(request.systemPrompt, /finish with one concise confirmation/);
    assert.match(request.systemPrompt, /Keep all required review checks/);
    return complete;
  }, verified, (_read, reference) => {
    assert.equal(reference, 'runtime-evidence:result-1');
    return { reference, complete: true, rows: [{ reportMarkdown: 'ACTUAL_COMPUTED_REPORT' }],
      reportReferenceValidation: { valid: true } };
  });
  assert.equal((await run.run()).status, 'completed');
  assert.equal(counts.models, 2); assert.equal(counts.executions, 1); assert.equal(counts.reads, 1);
  assert.match(counts.finish.operationId, /^cline-[a-f0-9]{64}$/);
  assert(run.events.some(e => e.type === 'TOOL_FINISHED' && e.tool === 'artifact_read' && e.automatic === true));
  assert(counts.checkpoints.some(c => c.lastCollectedOperation
    && JSON.stringify(c.messages).includes('ACTUAL_COMPUTED_REPORT')));
});

test('the native Cline SDK carries the frozen report layout preference without another model or execution round', async () => {
  const presentation={strategy:'auto',density:'compact',template:'briefing',autoCharts:true,autoImages:false};
  const task={...contract,runtimeContext:{reportMode:'visual',reportPresentation:presentation}};
  const {run,counts}=fixture((turn,request)=>{
    assert.match(JSON.stringify(request.messages),/reportPresentation/);
    assert.match(JSON.stringify(request.messages),/compact/);
    return turn===1?python(turn):complete;
  },verified,(_read,reference)=>({reference,complete:true,rows:[{reportMarkdown:'Same computed facts',
    visualReport:{schemaVersion:'python_visual_report.v1',presentation,layout:{schemaVersion:'report_layout.v1',blocks:[]}}}],
    reportReferenceValidation:{valid:true},visualReportValidation:{status:'PASSED'}}),task);
  assert.equal((await run.run()).status,'completed');
  assert.equal(counts.executions,1);assert.equal(counts.models,2);assert.equal(counts.reads,1);
});

test('invalid report references still reach Cline and require a repaired execution and fresh authorized read', async () => {
  const { run, counts } = fixture((turn, request) => {
    if (turn === 1) return python(turn);
    if (turn === 2) { assert.match(JSON.stringify(request.messages), /UNAUTHORIZED_REFERENCE/); return python(turn); }
    assert.equal(turn, 3); assert.match(JSON.stringify(request.messages), /CORRECTED_REPORT/); return complete;
  }, verified, (read, reference) => ({ reference, complete: true,
    rows: [{ reportMarkdown: read === 1 ? 'INVALID_REPORT' : 'CORRECTED_REPORT' }],
    reportReferenceValidation: { valid: read !== 1, diagnostic: read === 1 ? 'UNAUTHORIZED_REFERENCE' : 'VALID' } }));
  assert.equal((await run.run()).status, 'completed');
  assert.equal(counts.models, 3); assert.equal(counts.executions, 2); assert.equal(counts.reads, 2);
  const codes = run.events.filter(e => e.type === 'CODE_READY');
  assert.equal(counts.finish.operationId, codes[1].proposedOperationId);
});

test('a stopped process with missing output is repaired before any automatic report read', async () => {
  const { run, counts } = fixture((turn, request) => {
    if (turn === 1) return python(turn);
    if (turn === 2) { assert.match(JSON.stringify(request.messages), /REQUIRED_OUTPUT_MISSING/); return python(turn); }
    return complete;
  }, version => version === 1 ? { status: 'SUCCEEDED', executorStopped: true,
    resultAvailable: false, validationStatus: 'FAILED', outputStatus: 'REQUIRED_OUTPUT_MISSING' } : verified(version),
  (_read, reference) => {
    assert.equal(reference, 'runtime-evidence:result-2');
    return { reference, complete: true, rows: [{ reportMarkdown: 'ACTUAL_REPORT' }], reportReferenceValidation: { valid: true } };
  });
  assert.equal((await run.run()).status, 'completed');
  assert.equal(counts.executions, 2); assert.equal(counts.reads, 1);
});

test('native review confirmation finishes with the exact admitted report without another read or execution', async () => {
  const {run,counts}=fixture((turn,request)=>{
    if(turn===1)return python(turn);
    assert.equal(turn,2);
    assert.match(JSON.stringify(request.messages),/ACTUAL_REVIEWED_REPORT/);
    assert(request.tools.some(t=>t.name==='artifact_read'));
    return {toolCalls:[{id:'confirm',name:'artifact_read',input:{reference:'runtime-evidence:result-1'}}]};
  },verified,(_count,reference)=>({reference,complete:true,rows:[{reportMarkdown:'ACTUAL_REVIEWED_REPORT'}],reportReferenceValidation:{valid:true}}));
  assert.equal((await run.run()).status,'completed');
  assert.equal(counts.models,2);assert.equal(counts.executions,1);assert.equal(counts.reads,1);
  assert.equal(counts.finish.operationId,counts.checkpoints.at(-1).lastCollectedOperation);
  assert(run.events.some(e=>e.tool==='artifact_read'&&e.reused===true));
});

test('Runtime STOP takes precedence over a contradictory successful receipt and never reads an artifact', async () => {
  const { run, counts } = fixture(turn => python(turn), version => ({ ...verified(version),
    executionFeedback: { repair: { action: 'STOP', allowed: false, reason: 'POLICY_STOP' } } }),
  () => { throw Error('must not read'); });
  assert.equal((await run.run()).status, 'failed');
  assert.equal(counts.models, 1); assert.equal(counts.executions, 1); assert.equal(counts.reads, 0);
});

test('a later failed execution invalidates the cached earlier report for completion',async()=>{
  const task={...contract,budget:{maxModelCalls:3}};
  const {run,counts}=fixture(turn=>turn<3?python(turn):{
    toolCalls:[{id:'stale-confirm',name:'artifact_read',input:{reference:'runtime-evidence:result-1'}}],
  },version=>version===1?verified(version):{status:'FAILED',executorStopped:true,resultAvailable:false},
  (_count,reference)=>({reference,complete:true,rows:[{reportMarkdown:'earlier report'}],reportReferenceValidation:{valid:true}}),task);
  assert.equal((await run.run()).status,'failed');assert.equal(counts.executions,2);assert.equal(counts.reads,2);
  assert.equal(counts.finish?.operationId??null,null);
});

test('native loop repairs an output-only probe after a rejected anchor using the refreshed uncompacted source',async()=>{
  const initial="value = 3\nprint('inspection only')\n";
  const task={...contract,runtimeContext:{codingLifecycle:{schemaVersion:'runtime_os.code_lifecycle.v1',patchOnlyAfterFirst:true}}};
  const {run,counts}=fixture((turn,request)=>{
    if(turn===1)return {toolCalls:[{id:'inspect',name:'python_execute',input:{source:initial}}]};
    if(turn===4)return {toolCalls:[{id:'confirm',name:'artifact_read',input:{reference:'runtime-evidence:result-2'}}]};
    const binding=request.messages.flatMap(m=>m.content).filter(p=>p.type==='text'&&p.text.startsWith('Untrusted latest executed program')).at(-1);
    assert(binding,'The ordinary short conversation must receive fresh source without requiring compaction');
    assert(binding.text.includes(JSON.stringify(initial)));assert(binding.text.includes('Rejected proposals did not change this program'));
    return {toolCalls:[{id:'repair-'+turn,name:'python_execute',input:{repair:{parentCodeVersion:1,baseCodeSha256:createHash('sha256').update(initial).digest('hex'),
      edits:[{oldText:turn===2?"print('imagined rejected program')":"print('inspection only')",newText:"print('contracted report')"}]}}}]};
  },version=>version===1?{status:'SUCCEEDED',executorStopped:true,resultAvailable:false,validationStatus:'FAILED',outputStatus:'REQUIRED_OUTPUT_MISSING'}:verified(version),
  (_count,reference)=>({reference,complete:true,rows:[{reportMarkdown:'actual computed result'}],reportReferenceValidation:{valid:true}}),task);
  assert.equal((await run.run()).status,'completed');assert.equal(counts.models,4);assert.equal(counts.executions,2);assert.equal(counts.reads,1);
  assert.equal(run.events.filter(e=>e.type==='CODE_PROPOSAL_REJECTED').length,1);
});

test('review metadata cannot grant artifact read authority', () => {
  assert.throws(() => createCodingRun({ ...contract, authorization: {
    ...contract.authorization, allowedTools: ['python_execute'] } },
  { reserveModel() {}, executePython() {}, readArtifact() {} }), /authorized Runtime artifact reads/);
});

for (const mode of ['authorization failure', 'different result reference']) {
  test(`automatic artifact ${mode} cannot publish an already stopped Python result`, async () => {
    const { run, counts } = fixture(turn => turn === 1 ? python(turn) : complete, verified,
      () => {
        if (mode === 'authorization failure') throw new Error('ARTIFACT_ACCESS_DENIED');
        return { reference: 'runtime-evidence:another-result', complete: true,
          rows: [{ reportMarkdown: 'WRONG_REPORT' }], reportReferenceValidation: { valid: true } };
      });
    const result = await run.run().catch(error => ({ status: 'failed', error }));
    assert.notEqual(result.status, 'completed');
    assert.equal(counts.executions, 1); assert.equal(counts.reads, 1);
    assert.notEqual(counts.finish?.status, 'completed');
    assert.equal(counts.finish?.operationId ?? null, null);
  });
}
