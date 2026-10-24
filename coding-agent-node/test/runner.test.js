import test from 'node:test';
import assert from 'node:assert/strict';
import { createCodingRun as realCreateCodingRun } from '../src/runner.js';
import { createRuntimeModel } from '../src/runtime-model.js';
// Allocation is a Java callback fixture; Python results below remain branch-specific fixtures.
const createCodingRun=(task,gateway,options)=>{
  let version=0;const assigned=new Map();
  return realCreateCodingRun(task,{async preparePython(_task,input){
    if(!assigned.has(input.operationId)){
      const scriptVersion=++version,fileName=`financial-total_20261022T000000000Z_abcdef012345_v${scriptVersion}.py`;
      assigned.set(input.operationId,{fileName,scriptVersion,scriptPath:'/workspace/scripts/'+fileName,
        workingDirectory:'tasks/'+['a'.repeat(16),'b'.repeat(16),'c'.repeat(16),'d'.repeat(24)].join('/')});
    }
    return assigned.get(input.operationId);
  },...gateway},options);
};

const task = {
  schemaVersion: 'runtime_os.coding.v1',
  taskId: 'task-1',
  scope: { tenantId: 'tenant', userId: 'owner', runId: 'run' },
  objective: { instruction: 'Compute the authorized financial total' },
  inputSnapshotRef: 'runtime-evidence:input', validationPlanRef: 'validation:fixture',
  authorization: { allowedTools: ['python_execute'], allowedSideEffects: ['CODE_EXECUTION'],
    allowedDataRefs: [`c${'a'.repeat(63)}`], networkPolicy: 'NONE' },
  outputContract: { requiredFiles: ['rows.jsonl'] },
  budget: { maxModelCalls: 4 },
};

test('malformed provider arguments are repaired in the same SDK with fresh admissions and actual usage', async () => {
  const admitted=[],settled=[],checkpoints=[];let models=0,executions=0;
  const gateway={
    async reserveModel(_task,call){admitted.push(call.operationId);},
    async settleModel(_task,call){settled.push(call.operationId);},
    async checkpoint(_task,value){checkpoints.push(structuredClone(value));},
    async model(_task,request){
      models++;
      if(models===1)return {error:'Invalid tool JSON',text:'Preparing the program.',toolCalls:[],usage:{inputTokens:10,outputTokens:20},
        modelResponseFeedback:{schemaVersion:'runtime_os.coding.model_feedback.v1',diagnosticCode:'MODEL_OUTPUT_TRUNCATED',repairAllowed:true,maxRepairAttempts:2}};
      if(models===2){
        assert.match(JSON.stringify(request.messages),/MODEL_OUTPUT_TRUNCATED/);
        assert.match(JSON.stringify(request.messages),/same immutable inputs/);
        return {toolCalls:[{id:'repaired-call',name:'python_execute',input:{fileName:'main.py',source:'print(42)'}}],usage:{inputTokens:12,outputTokens:8}};
      }
      return {text:'Verified result.',toolCalls:[],usage:{inputTokens:8,outputTokens:2}};
    },
    async executePython(){executions++;return {status:'SUCCEEDED',executorStopped:true,resultDatasetRef:'result',validationRecordRef:'validation',evidenceBundleRef:'bundle'};},
    async finish(){},
  };
  const run=createCodingRun(task,gateway,{model:createRuntimeModel(gateway,task),contractHash:'a'.repeat(64)});
  const result=await run.run();
  assert.equal(result.status,'completed');assert.equal(executions,1);assert.equal(models,3);
  assert.deepEqual(admitted,['model-1','model-2','model-3']);assert.deepEqual(settled,admitted);
  assert(checkpoints.some(c=>c.modelResponseFeedback?.diagnosticCode==='MODEL_OUTPUT_TRUNCATED'&&c.modelOrdinal===1));
  assert(checkpoints.some(c=>c.modelResponseRepairs===1));
  assert(run.events.some(e=>e.type==='MODEL_RESPONSE_REJECTED'));
});

test('repeated malformed provider responses exhaust the repair limit without running Python', async () => {
  let models=0,executions=0,settlements=0;
  const gateway={async reserveModel(){},async settleModel(){settlements++;},async finish(){},
    async executePython(){executions++;},async model(){models++;return {error:'Invalid JSON',toolCalls:[],usage:{inputTokens:1,outputTokens:2},
      modelResponseFeedback:{schemaVersion:'runtime_os.coding.model_feedback.v1',diagnosticCode:'MODEL_TOOL_ARGUMENTS_INVALID',repairAllowed:true,maxRepairAttempts:1}};}};
  const result=await createCodingRun(task,gateway,{model:createRuntimeModel(gateway,task)}).run();
  assert.equal(result.status,'failed');assert.match(result.error,/MODEL_RESPONSE_REPAIR_LIMIT_REACHED/);
  assert.equal(models,2);assert.equal(settlements,2);assert.equal(executions,0);
});

test('restoring a settled checkpoint cannot reset the model response repair limit', async () => {
  let admitted=0,models=0;
  const gateway={async reserveModel(){admitted++;},async settleModel(){},async finish(){},async executePython(){throw new Error('must not execute');},
    async model(){models++;throw new Error('must not call');}};
  const checkpoint={messages:[{role:'user',content:[{type:'text',text:'Original authorized request'}]}],modelOrdinal:2,eventOrdinal:3,
    modelResponseRepairs:1,modelResponseFeedback:{schemaVersion:'runtime_os.coding.model_feedback.v1',diagnosticCode:'MODEL_OUTPUT_TRUNCATED',repairAllowed:true,maxRepairAttempts:1}};
  const result=await createCodingRun(task,gateway,{model:createRuntimeModel(gateway,task,2),checkpoint}).run();
  assert.equal(result.status,'failed');assert.match(result.error,/MODEL_RESPONSE_REPAIR_LIMIT_REACHED/);
  assert.equal(admitted,0);assert.equal(models,0);
});

test('Java STOP feedback terminates the real SDK loop without another model or Python call', async () => {
  let models=0,executions=0,finished;
  const model={async *stream(){
    models++;assert.equal(models,1);
    yield {type:'tool-call-delta',toolCallId:'first',toolName:'python_execute',input:{fileName:'main.py',source:'while True: pass'}};
    yield {type:'usage',usage:{inputTokens:2,outputTokens:2}};yield {type:'finish',reason:'tool-calls'};
  }};
  const gateway={async reserveModel(){},async settleModel(){},async executePython(){
    executions++;return {status:'TIMED_OUT',executorStopped:true,resultAvailable:false,
      executionFeedback:{repair:{allowed:false,action:'STOP',reason:'NON_REPAIRABLE'},error:{category:'TIMEOUT'}}};
  },async finish(_task,outcome){finished=outcome;}};
  const result=await createCodingRun(task,gateway,{model}).run();
  assert.equal(result.status,'failed');assert.equal(executions,1);assert.equal(models,1);assert.equal(finished.status,'failed');
});

test('Java repair feedback reaches the same SDK context and an exact edit creates the next code version', async () => {
  let models=0;const sources=[];const original='value = 1\nprint(value[0])';
  const model={async *stream(request){
    models++;
    if(models===1)yield {type:'tool-call-delta',toolCallId:'first',toolName:'python_execute',input:{fileName:'main.py',source:original}};
    else if(models===2){
      assert.match(JSON.stringify(request.messages),/VALUE_NOT_SUBSCRIPTABLE/);
      const {createHash}=await import('node:crypto');
      yield {type:'tool-call-delta',toolCallId:'repair',toolName:'python_execute',input:{fileName:'main.py',repair:{
        baseCodeSha256:createHash('sha256').update(original).digest('hex'),edits:[{oldText:'value[0]',newText:'value'}]}}};
    }else yield {type:'text-delta',text:'Verified.'};
    yield {type:'usage',usage:{inputTokens:2,outputTokens:2}};yield {type:'finish',reason:models<3?'tool-calls':'stop'};
  }};
  const gateway={async reserveModel(){},async settleModel(){},async executePython(_task,input){
    sources.push(input.source);
    return sources.length===1?{status:'FAILED',executorStopped:true,executionFeedback:{error:{diagnosticCode:'VALUE_NOT_SUBSCRIPTABLE'},
      repair:{allowed:true,action:'REPAIR_IN_CURRENT_CONTEXT',reason:'TECHNICAL_REPAIR'}}}:
      {status:'SUCCEEDED',executorStopped:true,resultDatasetRef:'result',validationRecordRef:'validation',evidenceBundleRef:'bundle'};
  },async finish(){}};
  assert.equal((await createCodingRun(task,gateway,{model}).run()).status,'completed');
  assert.deepEqual(sources,[original,'value = 1\nprint(value)']);
});

test('worker rejects unnegotiated v2 and missing protocol versions before touching Runtime', () => {
  for (const schemaVersion of ['runtime_os.coding.v2', undefined])
    assert.throws(() => createCodingRun({ ...task, schemaVersion }, {}), /VERSION_UNSUPPORTED/);
});

test('actual Cline Agent loop repairs a technical Python error through one Runtime tool', async () => {
  const requests = [];
  let modelCalls = 0;
  const model = {
    async *stream(request) {
      modelCalls++;
      assert.deepEqual(request.tools.map(tool => tool.name), ['python_execute']);
      if (modelCalls <= 2) {
        yield { type: 'tool-call-delta', toolCallId: `call-${modelCalls}`,
          toolName: 'python_execute', input: { fileName: 'main.py',
            source: modelCalls === 1 ? 'raise ValueError("bad")' : 'print(20)' } };
        yield { type: 'usage', usage: { inputTokens: 10, outputTokens: 5 } };
        yield { type: 'finish', reason: 'tool-calls' };
      } else {
        yield { type: 'text-delta', text: 'Execution verified by Runtime.' };
        yield { type: 'usage', usage: { inputTokens: 8, outputTokens: 6 } };
        yield { type: 'finish', reason: 'stop' };
      }
    },
  };
  const gateway = {
    async reserveModel(_task, item) { requests.push(['reserve', item]); },
    async settleModel(_task, item) { requests.push(['settle', item]); },
    async executePython(_task, item) {
      requests.push(['execute', item]);
      return item.source.includes('raise')
        ? { status: 'FAILED', executorStopped: true, technicalError: 'ValueError: bad' }
        : { status: 'SUCCEEDED', executorStopped: true,
          resultDatasetRef: 'runtime-evidence:result',
          validationRecordRef: 'runtime-evidence:validation',
          evidenceBundleRef: 'runtime-evidence:bundle' };
    },
    async finish(_task, item) { requests.push(['finish', item]); },
  };
  const run = createCodingRun(task, gateway, { model });
  const result = await run.run();
  assert.equal(result.status, 'completed');
  assert.match(result.text, /verified/);
  assert.equal(modelCalls, 3);
  const executions = requests.filter(([type]) => type === 'execute').map(([, item]) => item);
  assert.equal(executions.length, 2);
  assert.notEqual(executions[0].operationId, executions[1].operationId);
  assert.equal(requests.filter(([type]) => type === 'reserve').length, 3);
  assert.equal(run.events.filter(event => event.type === 'MODEL_USAGE').length, 3);
  assert.equal(requests.find(([type]) => type === 'finish')[1].operationId,
    executions[1].operationId);
});

test('successful exploration without an output artifact can be corrected without claiming a result', async () => {
  let calls=0;const executions=[];let completed;
  const model={async *stream(request){
    calls++;
    if(calls===2)assert.match(JSON.stringify(request.messages),/REQUIRED_OUTPUT_MISSING/);
    if(calls<=2){
      yield {type:'tool-call-delta',toolCallId:`output-${calls}`,toolName:'python_execute',
        input:{fileName:'main.py',source:calls===1?'print("schema inspection")':'# complete program writes rows.jsonl'}};
      yield {type:'finish',reason:'tool-calls'};
    }else{
      assert.match(JSON.stringify(request.messages),/runtime-evidence:result/);
      yield {type:'text-delta',text:'The structured result is archived; business correctness was not evaluated.'};
      yield {type:'finish',reason:'stop'};
    }
  }};
  const gateway={
    reserveModel(){},settleModel(){},
    executePython(_contract,item){
      executions.push(item);
      return executions.length===1
        ?{status:'SUCCEEDED',executorStopped:true,outputStatus:'REQUIRED_OUTPUT_MISSING',requiredOutput:'rows.jsonl',resultAvailable:false}
        :{status:'SUCCEEDED',executorStopped:true,resultDatasetRef:'runtime-evidence:result',validationRecordRef:'runtime-evidence:validation',evidenceBundleRef:'runtime-evidence:bundle'};
    },
    finish(_contract,value){completed=value;},
  };
  assert.equal((await createCodingRun(task,gateway,{model}).run()).status,'completed');
  assert.equal(executions.length,2);
  assert.equal(completed.operationId,executions[1].operationId);
  assert.notEqual(completed.operationId,executions[0].operationId);
});

test('Python operation is polled until Docker confirms stop before a result is accepted', async () => {
  let polls = 0;
  let turns = 0;
  const model = { async *stream() {
    if (++turns === 1) {
      yield { type: 'tool-call-delta', toolCallId: 'run-1', toolName: 'python_execute',
        input: { fileName: 'main.py', source: 'print(1)' } };
      yield { type: 'finish', reason: 'tool-calls' };
    } else {
      yield { type: 'text-delta', text: 'done' };
      yield { type: 'finish', reason: 'stop' };
    }
  } };
  const gateway = {
    reserveModel() {}, settleModel() {},
    executePython() { return { status: 'RUNNING', executorStopped: false }; },
    getPython() {
      polls++;
      return { status: 'SUCCEEDED', executorStopped: true,
        resultDatasetRef: 'runtime-evidence:result',
        validationRecordRef: 'runtime-evidence:validation',
        evidenceBundleRef: 'runtime-evidence:bundle' };
    },
    finish(_task, outcome) { assert.match(outcome.operationId, /^cline-[0-9a-f]{64}$/); },
  };
  const run = createCodingRun({ ...task,
    budget: { ...task.budget, deadlineAt: new Date(Date.now() + 10_000).toISOString() },
  }, gateway, { model });
  assert.equal((await run.run()).status, 'completed');
  assert.equal(polls, 1);
});

test('unrecognized or unauthorized tools fail closed before a model call', () => {
  assert.throws(() => createCodingRun({ ...task, authorization: {
    allowedTools: ['host_shell'], allowedSideEffects: ['CODE_EXECUTION'], networkPolicy: 'NONE',
  } }, {}, { model: { stream() {} } }), /Runtime gateway|Unrecognized/);
  const gateway = { executePython() {}, reserveModel() {}, settleModel() {} };
  assert.throws(() => createCodingRun({ ...task, authorization: {
    allowedTools: ['host_shell'], allowedSideEffects: ['CODE_EXECUTION'], networkPolicy: 'NONE',
  } }, gateway, { model: { stream() {} } }), /Unrecognized Coding tool/);
  const run = createCodingRun({ ...task, authorization: {
    allowedTools: ['python_execute'], allowedSideEffects: [], networkPolicy: 'NONE',
  } }, gateway, { model: { stream() {} } });
  assert.equal(run.snapshot().status, 'idle');
  assert.throws(() => createCodingRun({ ...task, authorization: {
    ...task.authorization, networkPolicy: 'ALLOWLIST',
  } }, gateway, { model: { stream() {} } }), /no-network/);
});

test('metadata tool rejects a reference outside the frozen contract', async () => {
  let reads = 0;
  let turns = 0;
  const model = { async *stream() {
    if (++turns === 1) {
      yield { type: 'tool-call-delta', toolCallId: 'meta-1', toolName: 'metadata_read',
        input: { reference: 'metadata:other' } };
      yield { type: 'finish', reason: 'tool-calls' };
    } else {
      yield { type: 'text-delta', text: 'Metadata was unavailable.' };
      yield { type: 'finish', reason: 'stop' };
    }
  } };
  const gateway = { executePython() {}, reserveModel() {}, settleModel() {},
    readMetadata() { reads++; return {}; } };
  const run = createCodingRun({ ...task, metadataRefs: ['metadata:authorized'],
    authorization: { ...task.authorization, allowedTools: ['metadata_read'], allowedSideEffects: [] },
  }, gateway, { model });
  await run.run();
  assert.equal(reads, 0);
});

test('a failed finish callback preserves the original Runtime admission error', async () => {
  const gateway = {
    executePython() { throw new Error('not reached'); },
    reserveModel() { throw new Error('reserve callback HTTP 404'); },
    finish() { throw new Error('finish callback HTTP 404'); },
  };
  const model = { async *stream() { throw new Error('model must not be called'); } };
  const run = createCodingRun(task, gateway, { model });
  const result = await run.run();
  assert.equal(result.status, 'failed');
  assert.match(result.error, /reserve callback HTTP 404/);
  assert.equal(run.events.at(-1).type, 'RUNTIME_FINISH_FAILED');
});

test('a failed Runtime model call is visible to Java reconciliation', async () => {
  const gateway = { executePython() {}, reserveModel() {},
    model() { throw new Error('provider unavailable'); },
    finish() {} };
  const model = { async *stream() { throw new Error('provider unavailable'); } };
  const run = createCodingRun(task, gateway, { model });
  const result = await run.run();
  assert.equal(result.status, 'failed');
  assert.match(result.error, /provider unavailable/);
});

test('transient provider errors cannot trigger SDK retries outside Runtime admission', async () => {
  for (const thrown of [false, true]) {
    let calls = 0; let reservations = 0;
    const model = { async *stream() {
      calls++;
      if (thrown) throw Object.assign(new Error('HTTP 503 service unavailable'), { status: 503 });
      yield { type: 'finish', reason: 'error', error: 'HTTP 503 service unavailable', errorRetryable: true };
    } };
    const gateway = { executePython() {}, reserveModel() { reservations++; }, settleModel() {}, finish() {} };
    const result = await createCodingRun(task, gateway, { model }).run();
    assert.equal(result.status, 'failed');
    assert.match(result.error, /503/);
    assert.equal(calls, 1);
    assert.equal(reservations, 1);
  }
});

test('a later unsuccessful program cannot finish using an older successful result', async () => {
  for (const later of ['FAILED', 'MISSING', 'TRANSPORT_ERROR']) {
    let calls = 0; let finished; let cancelled = 0;
    const model = { async *stream() {
      if (++calls <= 2) {
        yield { type: 'tool-call-delta', toolCallId: `attempt-${calls}`, toolName: 'python_execute',
          input: { fileName: 'main.py', source: `# program ${calls}` } };
        yield { type: 'finish', reason: 'tool-calls' };
      } else { yield { type: 'text-delta', text: 'Cannot deliver the revised result' }; yield { type: 'finish', reason: 'stop' }; }
    } };
    const gateway = { reserveModel() {}, settleModel() {},
      executePython() {
        if (calls === 1) return { status: 'SUCCEEDED', executorStopped: true,
          resultDatasetRef: 'result:old', validationRecordRef: 'validation:old', evidenceBundleRef: 'bundle:old' };
        if (later === 'TRANSPORT_ERROR') throw new Error('Response lost after dispatch');
        return { status: later === 'FAILED' ? 'FAILED' : 'SUCCEEDED', executorStopped: true };
      }, cancelPython: async () => { cancelled++; }, finish(_task, value) { finished = value; },
    };
    await createCodingRun(task, gateway, { model }).run();
    assert.equal(finished.operationId, null);
    assert.equal(cancelled, later === 'TRANSPORT_ERROR' ? 1 : 0);
  }
});

test('Runtime deadline aborts the in-flight model and leaves provider reconciliation to Java', async () => {
  let aborted = false; let finish;
  const model = { async *stream(request) {
    await new Promise((resolve, reject) => {
      const stop = () => { aborted = true; reject(new Error('aborted')); };
      if (request.signal.aborted) stop(); else request.signal.addEventListener('abort', stop, { once: true });
    });
  } };
  const gateway = { executePython() {}, reserveModel() {}, settleModel() { assert.fail('No provider receipt'); },
    finish(_task, value) { finish = value; } };
  const result = await createCodingRun({ ...task, budget: { ...task.budget,
    deadlineAt: new Date(Date.now() + 150).toISOString() } }, gateway, { model }).run();
  assert.equal(aborted, true);
  assert.equal(result.status, 'aborted');
  assert.equal(finish.status, 'aborted');
  assert.equal(finish.operationId, null);
});

test('expired contracts do not reserve or execute work', async () => {
  let touched = false;
  const gateway = { executePython() { touched = true; }, reserveModel() { touched = true; } };
  const model = { async *stream() { touched = true; } };
  const run = createCodingRun({ ...task, budget: { ...task.budget,
    deadlineAt: new Date(Date.now() - 1000).toISOString() } }, gateway, { model });
  await assert.rejects(run.run(), /deadline reached before dispatch/);
  assert.equal(touched, false);
});

test('Runtime deadline requests Sandbox cancellation while an operation remains active', async () => {
  let operation; const cancelled = []; let finish;
  const model = { async *stream() {
    yield { type: 'tool-call-delta', toolCallId: 'long-running', toolName: 'python_execute',
      input: { fileName: 'main.py', source: '# long running computation' } };
    yield { type: 'finish', reason: 'tool-calls' };
  } };
  const gateway = { reserveModel() {}, settleModel() {},
    async executePython(_task, value, signal) {
      operation = value.operationId;
      return new Promise((resolve, reject) => {
        const stop = () => reject(new Error('HTTP interrupted; execution unknown'));
        if (signal.aborted) stop(); else signal.addEventListener('abort', stop, { once: true });
      });
    },
    cancelPython: async (_task, id) => { cancelled.push(id); },
    finish(_task, value) { finish = value; },
  };
  const result = await createCodingRun({ ...task, budget: { ...task.budget,
    deadlineAt: new Date(Date.now() + 150).toISOString() } }, gateway, { model }).run();
  assert.equal(result.status, 'aborted');
  assert.match(operation, /^cline-[0-9a-f]{64}$/);
  assert.ok(cancelled.includes(operation));
  assert.equal(finish.operationId, null);
});

test('the same Cline loop stages multi-file SQL and repairs parser feedback without Python authority', async () => {
  const sqlTask = { ...task, schemaVersion: 'runtime_os.coding.v2',
    authorization: { allowedTools: ['workspace_read', 'workspace_propose'],
      allowedSideEffects: ['WORKSPACE_WRITE'], allowedDataRefs: [], networkPolicy: 'NONE' },
    outputContract: { resultFormat: 'WORKSPACE_FILES', requiredFiles: ['proposal.jsonl'] } };
  let calls = 0; let finished; const proposals = [];
  const model = { async *stream(request) {
    assert.deepEqual(request.tools.map(tool => tool.name), ['workspace_read', 'workspace_propose']);
    assert.doesNotMatch(request.systemPrompt, /Generate Python/);
    calls++;
    if (calls === 1) yield { type: 'tool-call-delta', toolCallId: 'read', toolName: 'workspace_read', input: {} };
    else if (calls <= 3) {
      if (calls === 2) assert.match(JSON.stringify(request.messages), /authorized Mapping/);
      if (calls === 3) assert.match(JSON.stringify(request.messages), /Parser error/);
      yield { type: 'tool-call-delta', toolCallId: `propose-${calls}`, toolName: 'workspace_propose',
        input: { files: [{ path: 'sql/a.sql', content: calls === 2 ? 'SELECT * FROM' : '-- preserve\nSELECT 1;' },
          { path: 'etl/b.sql', content: 'SELECT 2;' }], notes: 'No SQL executed' } };
    } else yield { type: 'text-delta', text: 'Parsed candidates are ready for user Diff review.' };
    yield { type: 'finish', reason: calls <= 3 ? 'tool-calls' : 'stop' };
  } };
  const gateway = { reserveModel() {}, settleModel() {},
    readWorkspace: async () => ({ context: 'authorized Mapping and historical SQL' }),
    async proposeWorkspace(_contract, id, proposal) {
      proposals.push({ id, proposal });
      return proposals.length === 1 ? { valid: false, diagnostics: ['Parser error'] }
        : { valid: true, evidenceBundleRef: 'workspace:bundle', sqlExecuted: false };
    }, finish(_contract, value) { finished = value; } };
  const result = await createCodingRun(sqlTask, gateway, { model }).run();
  assert.equal(result.status, 'completed');
  assert.equal(proposals.length, 2);
  assert.equal(proposals[1].proposal.files.length, 2);
  assert.equal(finished.operationId, proposals[1].id);
  assert.match(finished.operationId, /^workspace-[0-9a-f]{64}$/);
  assert.throws(() => createCodingRun({ ...sqlTask, authorization: {
    ...sqlTask.authorization, allowedTools: ['workspace_read', 'python_execute'] } }, gateway, { model }), /Unrecognized/);
});
