/** Compare actual Runtime tool observations with an independently executed JDBC baseline. */
import fs from 'node:fs';import assert from 'node:assert/strict';import crypto from 'node:crypto';
const [observationsPath,baselinePath,toolName,outputPath]=process.argv.slice(2);
assert.ok(outputPath,'Usage: node reconcile.mjs observations.json baseline.json exact-local-tool-name output.json');
const observations=JSON.parse(fs.readFileSync(observationsPath)),baseline=JSON.parse(fs.readFileSync(baselinePath));
assert.equal(baseline.source,'DIRECT_MYSQL_JDBC');assert.equal(baseline.readOnly,true);assert.ok(baseline.rows.length>0);
function recordset(value){
 if(!value||typeof value!=='object')return null;
 if(Array.isArray(value.rows)&&Array.isArray(value.columns))return value;
 // Follow actual tool data envelopes only, never schema examples or prose.
 return recordset(value.data);
}
const evidence=observations.filter(o=>o.source===toolName&&o.metadata?.stepOutput).map(o=>({observation:o,result:recordset(o.metadata.stepOutput.data)})).filter(e=>e.result);
assert.ok(evidence.length,'No actual returned SQL recordset for this tool');
const {observation,result}=evidence.at(-1);assert.equal(result.complete,true);assert.equal(result.possiblyTruncated,false);
assert.equal(result.rows.length,baseline.rows.length,'Returned row count differs');
function decimal(value){
 const m=String(value).match(/^(-?)(\d+)(?:\.(\d+))?$/);if(!m)return null;
 let fraction=(m[3]||'').replace(/0+$/,'');return (m[1]&&!(BigInt(m[2])===0n&&!fraction)?'-':'')+BigInt(m[2]).toString()+(fraction?'.'+fraction:'');
}
let fields=0;
for(let i=0;i<baseline.rows.length;i++){
 const expected=baseline.rows[i],actual=result.rows[i];assert.deepEqual(Object.keys(actual).sort(),Object.keys(expected).sort(),'Column contract changed');
 for(const key of Object.keys(expected)){
  if(expected[key]===null)assert.equal(actual[key],null);
  else if(decimal(expected[key])!==null)assert.equal(decimal(actual[key]),decimal(expected[key]),`Row ${i}, ${key}`);
  else assert.equal(String(actual[key]),expected[key],`Row ${i}, ${key}`);
  fields++;
 }
}
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const proof={status:'EXACT_MATCH',toolName,observationId:observation.id??observation.observationId,rows:result.rows.length,comparedFields:fields,baselineSha256:sha(baselinePath),observationsSha256:sha(observationsPath),actualRows:result.rows};
fs.writeFileSync(outputPath,JSON.stringify(proof,null,2));console.log(JSON.stringify({status:proof.status,rows:proof.rows,comparedFields:fields}));
