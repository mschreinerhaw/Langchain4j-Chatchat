/** Verify actual SQL -> published Python invocation -> output against independent JDBC. */
import fs from 'node:fs';import assert from 'node:assert/strict';import crypto from 'node:crypto';
const [observationsPath,baselinePath,outputPath,templatePath]=process.argv.slice(2);assert.ok(templatePath,'Usage: reconcile-template-python.mjs observations.json fresh-jdbc.json output.json published-template.json');
const published=JSON.parse(fs.readFileSync(templatePath));assert.equal(published.status,'PUBLISHED');assert.equal(published.mcpSyncStatus,'SYNCED');
const observations=JSON.parse(fs.readFileSync(observationsPath)),baseline=JSON.parse(fs.readFileSync(baselinePath));
const baselines=baseline.baselines;for(const b of [baselines.yearly,baselines.monthly]){assert.equal(b.source,'DIRECT_MYSQL_JDBC');assert.equal(b.readOnly,true);}
function cents(v){const s=String(v).replace(/^\+/,'');assert.match(s,/^-?\d+(?:\.\d{1,2})?$/);const [a,b='']=s.replace(/^-/,'').split('.');return (s.startsWith('-')?-1n:1n)*(BigInt(a)*100n+BigInt(b.padEnd(2,'0')));}
function rows(value){if(!value||typeof value!=='object')return null;if(Array.isArray(value.rows)&&Array.isArray(value.columns))return value;return rows(value.data);}
function execution(value){if(!value||typeof value!=='object')return null;if(typeof value.stdout==='string'&&value.executionId)return value;return execution(value.data);}
const outputs=observations.filter(o=>o.metadata?.stepOutput),sql=outputs.map(o=>({o,r:rows(o.metadata.stepOutput.data)})).filter(v=>v.r);
const annual=sql.find(v=>v.r.rows[0]&&'sales_year'in v.r.rows[0]),monthly=sql.find(v=>v.r.rows[0]&&'sale_month'in v.r.rows[0]);assert.ok(annual&&monthly,'Both actual SQL recordsets are required');
let fields=0;
for(const [item,b]of [[annual,baselines.yearly],[monthly,baselines.monthly]]){
 assert.equal(item.o.metadata.stepOutput.status,'SUCCESS');assert.equal(item.r.complete,true);assert.equal(item.r.possiblyTruncated,false);assert.equal(item.r.rows.length,b.rows.length);
 for(let i=0;i<b.rows.length;i++){const actual=item.r.rows[i],expected=b.rows[i];assert.deepEqual(Object.keys(actual).sort(),Object.keys(expected).sort());for(const k of Object.keys(expected)){if(expected[k]===null)assert.equal(actual[k],null);else if(/^-?\d+(?:\.\d+)?$/.test(String(expected[k])))assert.equal(cents(actual[k]),cents(expected[k]));else assert.equal(String(actual[k]),String(expected[k]));fields++;}}
}
const candidates=outputs.map(o=>({o,e:execution(o.metadata.stepOutput.data)})).filter(v=>v.e);
assert.ok(candidates.length,'Template discovery or ad hoc Cline execution is not published Python execution');
const py=candidates.at(-1);assert.match(py.o.source,/_python_template_execute$/);assert.equal(py.e.templateId,published.id);assert.equal(py.o.metadata.stepOutput.status,'SUCCESS');assert.equal(py.e.status,'SUCCEEDED');assert.equal(py.e.exitCode,0);
const result=JSON.parse(py.e.stdout);assert.equal(result.schemaVersion,'tpcds_reconciliation.v1');
const input=py.o.metadata.resolvedInput;assert.ok(input,'Actual resolved Python arguments required');
const params=typeof input.parameters==='string'?JSON.parse(input.parameters):input.parameters;assert.ok(params);
const requestedYear=Number(params.sales_year??1999);
const annualInput=params.annual_rows?.length===annual.r.rows.length?annual.r.rows:annual.r.rows.filter(r=>Number(r.sales_year)===requestedYear);
assert.deepEqual(params.annual_rows,annualInput,'Python must receive actual annual SQL rows (all years or the requested year)');assert.deepEqual(params.monthly_rows,monthly.r.rows,'Python must receive actual monthly SQL rows');
assert.equal(input.templateId,py.e.templateId);
const year=Number(params.sales_year??1999),a=baselines.yearly.rows.find(r=>Number(r.sales_year)===year),m=baselines.monthly.rows.filter(r=>Number(r.sale_year)===year);assert.ok(a&&m.length);
const sum=k=>m.reduce((n,r)=>n+cents(r[k]),0n),expected={annual_net_paid:cents(a.total_net_paid),monthly_net_paid:sum('total_net_paid'),diff_net_paid:cents(a.total_net_paid)-sum('total_net_paid'),annual_net_profit:cents(a.total_net_profit),monthly_net_profit:sum('total_net_profit'),diff_net_profit:cents(a.total_net_profit)-sum('total_net_profit'),annual_quantity:cents(a.total_quantity),monthly_quantity:sum('total_quantity')};
for(const [k,v]of Object.entries(expected))assert.equal(cents(result.metrics[k]),v,k);
const months=[...new Set(m.map(r=>Number(r.sale_month)))].sort((a,b)=>a-b);assert.equal(result.metrics.months_covered,months.length);
assert.deepEqual(result.missing_months,Array.from({length:12},(_,i)=>i+1).filter(v=>!months.includes(v)));
let monthlyFields=0,chartValues=0;assert.equal(result.monthly.length,months.length);assert.equal(result.chart.data.length,2);
for(const month of months){const actual=result.monthly.find(r=>r.month===month),source=m.filter(r=>Number(r.sale_month)===month);assert.ok(actual);for(const key of ['total_quantity','total_net_paid','total_net_profit']){assert.equal(cents(actual[key]),source.reduce((n,r)=>n+cents(r[key]),0n));monthlyFields++;}}
for(const [i,key]of [[0,'total_net_paid'],[1,'total_net_profit']]){const trace=result.chart.data[i];assert.deepEqual(trace.x,Array.from({length:12},(_,i)=>i+1));assert.equal(trace.connectgaps,false);for(let month=1;month<=12;month++){const source=m.filter(r=>Number(r.sale_month)===month);if(!source.length)assert.equal(trace.y[month-1],null);else{assert.equal(cents(trace.y[month-1]),source.reduce((n,r)=>n+cents(r[key]),0n));chartValues++;}}}
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
fs.writeFileSync(outputPath,JSON.stringify({status:'EXACT_MATCH',scope:'Actual SQL records, actual Python input mapping, 9 metrics, monthly table and chart values; no currency or business-semantic certification.',capturedAt:new Date().toISOString(),baselineSha256:sha(baselinePath),observationsSha256:sha(observationsPath),comparedSqlFields:fields,comparedMetrics:9,comparedMonthlyFields:monthlyFields,comparedChartValues:chartValues,publishedSourceSha256:crypto.createHash('sha256').update(published.sourceSnapshot).digest('hex'),publishedVersion:published.version,executionId:py.e.executionId,templateId:py.e.templateId,tool:py.o.source,requestId:py.o.metadata.stepOutput.requestId,executionTraceId:py.o.metadata.executionTraceId,resolvedInput:input,result},null,2));
console.log('Verified actual SQL -> published Python parameters -> precise JDBC totals, monthly rows and chart values.');
