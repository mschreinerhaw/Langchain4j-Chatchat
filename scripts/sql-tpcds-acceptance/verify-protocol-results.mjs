/** Reconcile real JSON-RPC tools/call results; this is not an Agent execution verdict. */
import fs from 'node:fs';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
const [protocolFile,baselineFile,catalogFile,outputFile]=process.argv.slice(2);
assert.ok(outputFile,'Usage: verify-protocol-results.mjs protocol.json jdbc.json catalog-responses.json output.json');
const read=p=>JSON.parse(fs.readFileSync(p));
const protocol=read(protocolFile),baseline=read(baselineFile),catalog=read(catalogFile);
assert.equal(protocol.clientKind,'INDEPENDENT_MCP_PROTOCOL_CLIENT_NOT_AGENT_EXECUTION');
assert.ok(protocol.initialization.response.result.serverInfo && protocol.protocolVersion);
const discovery=protocol.records.find(r=>r.request.method==='tools/list');
assert.equal(discovery.httpStatus,200);assert.ok(!discovery.response.error);
function unwrap(v,test){for(let depth=0;depth<12&&v&&typeof v==='object';depth++,v=v.data)if(test(v))return v;}
function decimal(value){if(value===null)return null;const s=String(value),m=s.match(/^(-?)(\d+)(?:\.(\d+))?$/);if(!m)return s;const f=(m[3]||'').replace(/0+$/,'');return(m[1]&&(BigInt(m[2])!==0n||f)?'-':'')+BigInt(m[2])+(f?'.'+f:'');}
const cases=[];
for(const [id,name]of [['MCP-NO-PARAM','yearly'],['MCP-PARAM','monthly']]) {
  const record=protocol.records.find(r=>r.caseId===id);assert.ok(record,id);assert.equal(record.httpStatus,200);assert.ok(!record.response.error);assert.equal(record.response.result.isError,false);
  const toolName=record.request.params.name;
  const tool=discovery.response.result.tools.find(t=>t.name===toolName);assert.ok(tool,'Invoked tool absent from actual tools/list');
  const detail=catalog.snapshots.map(s=>s.response.data).find(d=>d?.toolName===toolName);assert.ok(detail,'Missing actual user-entry detail');
  assert.deepEqual(tool.inputSchema,detail.parameterSchema);
  assert.equal(tool._meta?.toolVersion??tool.meta?.toolVersion,detail.version+'.0.0');
  const output=record.response.result.structuredContent;assert.equal(output.success,true);
  assert.equal(output.metadata.templatePublication.releaseId,detail.publicationId);
  assert.equal(output.metadata.traceId,record.traceId);
  const execution=unwrap(output.data,v=>v.kind==='sql_query'&&v.sourceMetadata);assert.ok(execution,'Missing actual JDBC execution envelope');
  assert.equal(execution.success,true);assert.equal(execution.target.id,detail.datasourceId);assert.equal(execution.sourceMetadata.operation.templateId,detail.code);
  const rows=unwrap(output.data,v=>Array.isArray(v.rows)&&Array.isArray(v.columns));assert.equal(rows.complete,true);assert.equal(rows.possiblyTruncated,false);
  const jdbc=baseline.baselines[name];assert.equal(jdbc.source,'DIRECT_MYSQL_JDBC');assert.equal(jdbc.readOnly,true);assert.ok(jdbc.rows.length>0);assert.equal(rows.rows.length,jdbc.rows.length);
  let fields=0;for(let i=0;i<jdbc.rows.length;i++) {assert.deepEqual(Object.keys(rows.rows[i]).sort(),Object.keys(jdbc.rows[i]).sort());for(const [key,value]of Object.entries(jdbc.rows[i])) {assert.equal(decimal(rows.rows[i][key]),decimal(value),`${id} row ${i} ${key}`);fields++;}}
  cases.push({caseId:id,status:'EXACT_MATCH',toolName,releaseId:detail.publicationId,version:detail.version,traceId:record.traceId,parameters:record.request.params.arguments,rows:rows.rows.length,comparedFields:fields,durationMs:record.durationMs,statementHash:execution.sourceMetadata.operation.statementHash});
}
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
fs.writeFileSync(outputFile,JSON.stringify({status:'MCP_PROTOCOL_AND_JDBC_EXACT_MATCH',clientKind:protocol.clientKind,scope:protocol.scope,cases,evidenceSha256:{protocol:hash(protocolFile),jdbc:hash(baselineFile),catalog:hash(catalogFile)}},null,2));
console.log(JSON.stringify({status:'MCP_PROTOCOL_AND_JDBC_EXACT_MATCH',fields:cases.reduce((n,c)=>n+c.comparedFields,0)}));
