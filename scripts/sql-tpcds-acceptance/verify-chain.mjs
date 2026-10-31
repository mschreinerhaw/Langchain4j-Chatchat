/** SQL-chain evidence gate. HTTP success, tool discovery and prose alone cannot pass. */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';
const sha = value => crypto.createHash('sha256').update(value).digest('hex');
const assetId = 'f0421bd7-1be8-4d5c-8f65-b216d4f997cb';
const unpack = value => typeof value === 'string' ? JSON.parse(value) : value;
function envelope(value, predicate) {
  for (let depth = 0; depth < 12 && value && typeof value === 'object'; depth++, value = value.data) {
    if (predicate(value)) return value;
  }
  return undefined;
}
function scalar(value) {
  if (value === null) return null;
  assert.ok(['number', 'string'].includes(typeof value), 'Unexpected database scalar');
  const m = String(value).match(/^(-?)(\d+)(?:\.(\d+))?$/);
  if (!m) return String(value);
  const fraction = (m[3] || '').replace(/0+$/, '');
  return (m[1] && (BigInt(m[2]) !== 0n || fraction) ? '-' : '') + BigInt(m[2]) + (fraction ? '.' + fraction : '');
}
export function verifyChain(snapshot, task, observations, jdbc) {
  assert.equal(task.canonicalState, 'SUCCEEDED', 'Agent must actually finish successfully');
  assert.ok(task.answerSummary?.trim(), 'Missing final answer');
  assert.equal(task.agentId, snapshot.agent.id, 'Agent binding snapshot mismatch');
  assert.ok(task.attemptId, 'Missing task attempt identity');
  assert.ok(!Number.isNaN(Date.parse(jdbc.capturedAt)), 'Missing independently captured baseline time');
  const results = [];
  for (const name of ['yearly', 'monthly']) {
    const { file, definition: d, generation: g, descriptor } = snapshot.templates[name];
    assert.ok(g.task?.status === 'SUCCESS' && g.generation?.applied === true && !g.outputError, 'Missing successful, applied model generation');
    assert.equal(g.generation.taskId, file.sourceTaskId);
    assert.ok(g.task.question?.trim(), 'Missing natural-language generation request');
    assert.equal(g.files.find(f => f.path === file.path)?.content, file.content, 'Saved SQL differs from model output');
    assert.equal(d.sqlTemplate, file.content, 'Published SQL differs from saved SQL');
    const digest = sha(file.content);
    assert.equal(d.source.projectId, snapshot.projectId);
    assert.equal(d.source.fileId, file.id);
    assert.equal(d.source.fileVersion, file.version);
    assert.equal(d.source.sqlDigest, digest);
    assert.equal(d.datasourceId, assetId);
    const test = d.testEvidence;
    assert.equal(test.type, 'SQL_DATABASE_EXECUTION');
    assert.equal(test.success, true, 'No successful database trial');
    assert.ok(test.rowCount > 0 && test.traceId && test.testId && test.resultDigest, 'Missing durable trial evidence');
    assert.equal(test.sqlDigest, digest, 'Trial belongs to different SQL');
    assert.deepEqual(test.source, d.source, 'Trial belongs to different saved source');
    assert.equal(test.datasourceId, assetId);
    assert.equal(d.active, true);
    assert.equal(d.publication.state, 'RUNNING');
    assert.ok(d.publication.id && d.publication.version > 0);
    assert.equal(descriptor.remoteToolName, d.publication.toolName);
    assert.deepEqual(descriptor.inputSchema, unpack(d.parameterSchema), 'MCP parameter Schema differs from release');
    assert.ok(snapshot.agent.boundMcpToolNames.includes(descriptor.localToolName), 'Agent was not bound to this tool');
    const actual = observations.filter(o => o.source === descriptor.localToolName && o.metadata?.stepOutput);
    assert.equal(actual.length, 1, 'Require exactly one actual invocation per SQL tool in this acceptance');
    const o = actual[0], m = o.metadata, output = m.stepOutput;
    assert.ok(m.executionTraceId.startsWith(task.attemptId + '::'), 'Observation belongs to another attempt');
    assert.equal(m.success, true);
    assert.equal(output.status, 'SUCCESS');
    assert.ok(output.requestId && Number.isFinite(m.durationMs) && m.durationMs >= 0);
    const query = envelope(output.data, v => v.kind === 'sql_query' && v.sourceMetadata);
    assert.equal(query?.success, true, 'Missing actual SQL execution envelope');
    assert.equal(query.target.id, assetId);
    assert.equal(query.sourceMetadata.operation.templateId, d.code, 'Different SQL template executed');
    assert.match(query.sourceMetadata.operation.statementHash, /^[a-f0-9]{64}$/);
    const parameters = m.resolvedInput;
    assert.ok(parameters && typeof parameters === 'object');
    const schema = unpack(d.parameterSchema);
    for (const key of Object.keys(parameters)) assert.ok(Object.hasOwn(schema.properties, key), 'Unexpected execution parameter');
    const effective = Object.fromEntries(Object.entries(schema.properties).filter(([, v]) => Object.hasOwn(v, 'default')).map(([k, v]) => [k, v.default]));
    Object.assign(effective, parameters);
    assert.deepEqual(effective, name === 'yearly' ? {} : { product_id: 1, start_date: '1999-01-01', end_date: '1999-12-31', min_quantity: 0, category: null }, 'Effective parameters differ from JDBC baseline scope');
    const rows = envelope(output.data, v => Array.isArray(v.rows) && Array.isArray(v.columns));
    assert.ok(rows, 'Tool listing or prose is not a recordset');
    assert.equal(rows.complete, true);
    assert.equal(rows.possiblyTruncated, false);
    const baseline = jdbc.baselines[name];
    assert.equal(baseline.source, 'DIRECT_MYSQL_JDBC');
    assert.equal(baseline.readOnly, true);
    assert.ok(baseline.rows.length > 0);
    assert.equal(rows.rows.length, baseline.rows.length);
    // TpcdsJdbc preserves JDBC column-label order in each returned row.
    assert.deepEqual(rows.columns, Object.keys(baseline.rows[0]), 'Column contract changed');
    let comparedFields = 0;
    for (let i = 0; i < baseline.rows.length; i++) {
      assert.deepEqual(Object.keys(rows.rows[i]).sort(), Object.keys(baseline.rows[i]).sort());
      for (const [key, value] of Object.entries(baseline.rows[i])) {
        assert.equal(scalar(rows.rows[i][key]), scalar(value), `${name} row ${i} ${key}`);
        comparedFields++;
      }
    }
    results.push({ name, releaseId: d.publication.id, version: d.publication.version, code: d.code,
      sqlSha256: digest, generationTaskId: g.generation.taskId, trialTraceId: test.traceId,
      tool: descriptor.localToolName, requestId: output.requestId, durationMs: m.durationMs,
      actualParameters: parameters, effectiveParameters: effective, rows: rows.rows.length, comparedFields });
  }
  return { status: 'SQL_CHAIN_EXACT_MATCH', taskId: task.taskId, attemptId: task.attemptId, jdbcCapturedAt: jdbc.capturedAt,
    sourceCapturedAt: snapshot.capturedAt, results,
    limitations: ['Generation PARSED is not static field validation or execution.', 'SQL result verification does not certify business units or Python analysis.'] };
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const [folder, jdbcFile, output] = process.argv.slice(2);
  assert.ok(output, 'Usage: node verify-chain.mjs evidence-folder fresh-jdbc.json output.json');
  const read = p => { const b = fs.readFileSync(p); return JSON.parse(b.toString(b[0] === 255 && b[1] === 254 ? 'utf16le' : 'utf8').replace(/^\uFEFF/, '')); };
  const index = read(path.join(folder, 'run-index.json'));
  const task = read(path.join(folder, 'agent-result.json'));
  assert.equal(index.taskId, task.taskId);
  const files = ['source-chain.json', 'agent-result.json', 'run-index.json', ...index.runs.map(r => 'observations-' + r + '.json')];
  const observations = index.runs.flatMap(r => read(path.join(folder, 'observations-' + r + '.json')));
  const proof = verifyChain(read(path.join(folder, 'source-chain.json')), task, observations, read(jdbcFile));
  proof.evidenceSha256 = Object.fromEntries(files.map(f => [f, sha(fs.readFileSync(path.join(folder, f)))]));
  proof.jdbcSha256 = sha(fs.readFileSync(jdbcFile));
  fs.writeFileSync(output, JSON.stringify(proof, null, 2));
  console.log(JSON.stringify({ status: proof.status, taskId: proof.taskId, comparedFields: proof.results.reduce((n, r) => n + r.comparedFields, 0) }));
}
