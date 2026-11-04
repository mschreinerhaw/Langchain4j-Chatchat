// Reads public measurement records only. Expected sources are evaluation oracles, never Agent inputs.
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
const [summaryPath,expectedPath,outputPath]=process.argv.slice(2);
assert.ok(summaryPath&&expectedPath&&outputPath,'Usage: node summarize-knowledge-acceptance.mjs summary.json expected-sources.json output.json');
const bytes=readFileSync(summaryPath),summary=JSON.parse(bytes.toString('utf8'));
const expected=JSON.parse(readFileSync(expectedPath,'utf8')).cases;
assert.equal(summary.executed,50);assert.equal(summary.cases.length,50);
const cases=new Map(summary.cases.map(row=>[row.id,row]));assert.equal(cases.size,50);
const direct=Object.entries(expected).map(([id,documentIds])=>{
  const row=cases.get(id);assert.ok(row&&row.category==='direct');
  const observed=[...new Set((row.citations||[]).filter(citation=>citation.hasOriginalText).map(citation=>citation.documentId))];
  return {id,status:row.status,expectedDocumentIds:documentIds,observedDocumentIds:observed,
    expectedSourceRetrieved:documentIds.every(document=>observed.includes(document))};
});
assert.equal(direct.length,20);
const percentile=rows=>{const times=rows.map(row=>row.durationMs).sort((a,b)=>a-b);assert.ok(times.every(Number.isFinite));return times[Math.ceil(times.length*.95)-1]};
const output={executed:50,models:[...new Set(summary.cases.map(row=>row.model))],sourceReportSha256:createHash('sha256').update(bytes).digest('hex'),
  statusCounts:summary.statusCounts,directExpectedSourceHitCases:direct.filter(row=>row.expectedSourceRetrieved).length,
  directExpectedSourceCases:20,directExpectedSourceHitRate:direct.filter(row=>row.expectedSourceRetrieved).length/20,
  allAttemptP95Ms:percentile(summary.cases),allDirectAttemptP95Ms:percentile(summary.cases.filter(row=>row.category==='direct')),
  timingScope:summary.timingScope,sourceSelectionOnly:true,oraclesSentToAgent:false,directCases:direct,
  factualCitationCoverage:'NOT_AUTOMATICALLY_CERTIFIED',citationConclusionSupport:'NOT_AUTOMATICALLY_CERTIFIED',answerCorrectness:'REQUIRES_SOURCE_REVIEW'};
writeFileSync(outputPath,JSON.stringify(output,null,2));
console.log(JSON.stringify({...output,directCases:undefined}));
