/** New-business protocol/JDBC gate; does not certify Agent execution or chart rendering. */
import fs from 'node:fs';import assert from 'node:assert/strict';import crypto from 'node:crypto';
const [protocolFile,jdbcFile,catalogFile,output]=process.argv.slice(2);assert.ok(output);
const read=p=>JSON.parse(fs.readFileSync(p)),p=read(protocolFile),j=read(jdbcFile),c=read(catalogFile);
assert.equal(p.clientKind,'INDEPENDENT_MCP_PROTOCOL_CLIENT_NOT_AGENT_EXECUTION');
assert.ok(p.initialization.response.result.serverInfo);
const discovery=p.records.find(r=>r.request.method==='tools/list').response.result.tools;
function unwrap(v,test){for(let i=0;i<12&&v&&typeof v==='object';i++,v=v.data)if(test(v))return v;}
function canonical(v){if(v===null)return null;const m=String(v).match(/^(-?)(\d+)(?:\.(\d+))?$/);if(!m)return String(v);const f=(m[3]||'').replace(/0+$/,'');return (m[1]&&(BigInt(m[2])!==0n||f)?'-':'')+BigInt(m[2])+(f?'.'+f:'');}
const checks=[];
for(const [id,baseline]of[['MCP-GEOGRAPHY','geography'],['MCP-INVENTORY-DEFAULTS','inventory_a02'],['MCP-INVENTORY-EXPLICIT-NULL','inventory_a02'],['MCP-INVENTORY-VARIANT','inventory_a05']]){
 const r=p.records.find(r=>r.caseId===id);assert.ok(r);assert.equal(r.httpStatus,200);assert.equal(r.response.result.isError,false);
 const tool=r.request.params.name,listed=discovery.find(t=>t.name===tool),detail=c.snapshots.map(s=>s.response.data).find(d=>d?.toolName===tool);assert.ok(listed&&detail);
 assert.deepEqual(listed.inputSchema,detail.parameterSchema);assert.equal(listed._meta?.toolVersion??listed.meta?.toolVersion,detail.version+'.0.0');
 const result=r.response.result.structuredContent;assert.equal(result.success,true);assert.equal(result.metadata.traceId,r.traceId);assert.equal(result.metadata.templatePublication.releaseId,detail.publicationId);
 const sql=unwrap(result.data,v=>v.dataSchema==='sql_result.v1');assert.ok(sql);assert.equal(sql.success,true);
 assert.equal(sql.target.id,detail.datasourceId);assert.equal(sql.sourceMetadata.operation.templateId,detail.code);
 assert.equal(sql.operation.diagnostics.executionMode,'PREPARED_STATEMENT');
 const rows=sql.data;assert.equal(rows.complete,true);assert.equal(rows.possiblyTruncated,false);
 const expected=j.baselines[baseline];assert.equal(expected.source,'DIRECT_MYSQL_JDBC');assert.equal(expected.readOnly,true);assert.equal(rows.rows.length,expected.rows.length);
 let fields=0;for(let i=0;i<rows.rows.length;i++){assert.deepEqual(Object.keys(rows.rows[i]).sort(),Object.keys(expected.rows[i]).sort());for(const[k,v]of Object.entries(expected.rows[i])){assert.equal(canonical(rows.rows[i][k]),canonical(v),`${id} row ${i} field ${k}`);fields++;}}
 checks.push({caseId:id,status:'EXACT_MATCH',toolName:tool,releaseId:detail.publicationId,version:detail.version,traceId:r.traceId,parameters:r.request.params.arguments,effectiveParameters:sql.operation.diagnostics.templateParameters,executionMode:sql.operation.diagnostics.executionMode,boundParameterCount:sql.operation.diagnostics.boundParameterCount,rows:rows.rows.length,comparedFields:fields});
}
for(const id of ['MCP-MISSING-DATES','MCP-INVALID-DATE','MCP-INVALID-RANGE','MCP-INVALID-TYPE','MCP-INJECTION-IS-LITERAL']){
 const r=p.records.find(r=>r.caseId===id);assert.ok(r);assert.equal(r.response.result.isError,true);
 const message=r.response.result.content?.filter(c=>c.type==='text').map(c=>c.text).join('\n');assert.ok(message);
 if(id==='MCP-INJECTION-IS-LITERAL')assert.match(message,/contains SQL syntax/);
 checks.push({caseId:id,status:'REJECTED_AS_EXPECTED',traceId:r.traceId,parameters:r.request.params.arguments,note:id==='MCP-INJECTION-IS-LITERAL'?'The original probe label was an expectation; actual behavior is rejection by the retained strict template validator, before SQL execution.':undefined});
}
assert.notDeepEqual(j.baselines.inventory_a02.rows,j.baselines.inventory_a05.rows);
const hash=f=>crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
fs.writeFileSync(output,JSON.stringify({status:'NEW_SQL_MCP_AND_JDBC_PASS',scope:'PROTOCOL_ONLY_NOT_AGENT_OR_CHART_ACCEPTANCE',checks,evidenceSha256:{protocol:hash(protocolFile),jdbc:hash(jdbcFile),catalog:hash(catalogFile)}},null,2));
console.log(JSON.stringify({status:'NEW_SQL_MCP_AND_JDBC_PASS',checks:checks.length,fields:checks.reduce((n,c)=>n+(c.comparedFields||0),0)}));
