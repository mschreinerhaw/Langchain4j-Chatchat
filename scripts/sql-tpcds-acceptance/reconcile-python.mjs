/** Verify the executed report against independent JDBC rows, using exact cents. */
import fs from 'node:fs';import assert from 'node:assert/strict';import crypto from 'node:crypto';
const [reportPath,yearPath,monthPath,outputPath]=process.argv.slice(2);
assert.ok(outputPath,'Usage: reconcile-python.mjs report.json yearly-jdbc.json monthly-jdbc.json output.json');
const read=p=>JSON.parse(fs.readFileSync(p)),report=read(reportPath),year=read(yearPath),month=read(monthPath);
for(const baseline of [year,month]){assert.equal(baseline.source,'DIRECT_MYSQL_JDBC');assert.equal(baseline.readOnly,true);}
assert.equal(report.execution.status,'SUCCEEDED');assert.equal(report.execution.executorStopped,true);assert.equal(report.artifactValidationStatus,'PASSED');
function cents(v){const s=String(v).replaceAll(',','').replace(/^\+/,'');assert.match(s,/^-?\d+(?:\.\d{1,2})?$/);const negative=s.startsWith('-'),[a,b='']=s.replace(/^-/,'').split('.');return (negative?-1n:1n)*(BigInt(a)*100n+BigInt(b.padEnd(2,'0')));}
const annual=year.rows.filter(r=>String(r.sales_year)==='1999');assert.equal(annual.length,1);
assert.ok(month.rows.length>0);assert.ok(month.rows.every(r=>String(r.sale_year)==='1999'));
const sum=k=>month.rows.reduce((s,r)=>s+cents(r[k]),0n),a=annual[0];
const expected={m_annual_net_paid:cents(a.total_net_paid),m_monthly_net_paid:sum('total_net_paid'),m_diff_net_paid:cents(a.total_net_paid)-sum('total_net_paid'),m_annual_net_profit:cents(a.total_net_profit),m_monthly_net_profit:sum('total_net_profit'),m_diff_net_profit:cents(a.total_net_profit)-sum('total_net_profit'),m_months_covered:BigInt(new Set(month.rows.map(r=>String(r.sale_month))).size)*100n};
for(const [id,value]of Object.entries(expected)){const metric=report.metrics.find(m=>m.id===id);assert.ok(metric,id);assert.equal(cents(metric.value),value,id);}
let monthlyFields=0;const table=report.tables.find(t=>t.id==='t_monthly');assert.ok(table);
for(let m=1;m<=12;m++){
 const rows=month.rows.filter(r=>Number(r.sale_month)===m),actual=table.rows.find(r=>r[0]===m+'月');assert.ok(actual);
 if(!rows.length){for(const v of actual.slice(1))assert.match(v,/无观测/);continue;}
 for(const [index,key]of [[1,'total_net_paid'],[2,'total_net_profit'],[3,'total_quantity']]){assert.equal(cents(actual[index]),rows.reduce((s,r)=>s+cents(r[key]),0n),`Month ${m} ${key}`);monthlyFields++;}
}
const recon=report.tables.find(t=>t.id==='t_recon');assert.ok(recon);
for(const [label,key]of [['净收款','total_net_paid'],['利润','total_net_profit'],['数量','total_quantity']]){const row=recon.rows.find(r=>r[0]===label);assert.ok(row);assert.equal(cents(row[1]),cents(a[key]));assert.equal(cents(row[2]),sum(key));assert.equal(cents(row[3]),cents(a[key])-sum(key));}
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
fs.writeFileSync(outputPath,JSON.stringify({status:'EXACT_MATCH',scope:'7 report metrics, 33 monthly numeric cells, 9 reconciliation cells; absent month remains unobserved. Not certification of currency, business semantics or completeness.',operationId:report.execution.operationId,codeSha256:report.execution.codeSha256,reportSha256:sha(reportPath),yearlyBaselineSha256:sha(yearPath),monthlyBaselineSha256:sha(monthPath),comparedMetrics:Object.keys(expected).length,comparedMonthlyFields:monthlyFields,comparedReconciliationFields:9,reportBusinessValidation:report.validation,expectedCents:Object.fromEntries(Object.entries(expected).map(([k,v])=>[k,String(v)]))},null,2));
console.log('Exact JDBC match: 7 metrics, 33 monthly cells, 9 reconciliation cells; business findings retained.');
