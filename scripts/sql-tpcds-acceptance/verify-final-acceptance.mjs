/** Offline verifier for the archived TPCDS acceptance fixtures, never production business logic.
 * Run from the repository root. SQL receipts are independently reconciled by reconcile.mjs.
 */
import fs from 'node:fs';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
const root='docs/sql-template-remediation';
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const save=(p,v)=>fs.writeFileSync(p,JSON.stringify(v,null,2));
const base=read(root+'/compatible-geography/independent-jdbc.json').baselines;
const product=read(root+'/productdata-independent-jdbc.json');
const productBaseline=product.baselines?.product_actual??product;
const canonical=v=>{if(v===null)return null;const s=String(v).replaceAll(',','').replaceAll('**',''),m=s.match(/^(-?)(\d+)(?:\.(\d+))?$/);if(!m)return s;const f=(m[3]??'').replace(/0+$/,'');return(m[1]&&(BigInt(m[2])!==0n||f)?'-':'')+BigInt(m[2])+(f?'.'+f:'');};
const equal=(a,b,label)=>assert.equal(canonical(a),canonical(b),label);
function mdTables(text){const tables=[];let rows=[];for(const line of text.split(/\r?\n/)){if(line.trim().startsWith('|'))rows.push(line.trim().split('|').slice(1,-1).map(s=>s.trim()));else if(rows.length){tables.push(rows);rows=[];}}if(rows.length)tables.push(rows);return tables;}
function matchRows(actual,expected,fields){assert.equal(actual.length,expected.length);let cells=0;for(let i=0;i<expected.length;i++)for(let j=0;j<fields.length;j++){if(fields[j]===null)continue;equal(actual[i][j],expected[i][fields[j]],`row ${i}, ${fields[j]}`);cells++;}return cells;}
const dir=id=>root+'/cases/'+id+'/after-r9';
const obs=id=>fs.readdirSync(dir(id)).filter(n=>n.startsWith('observations-')).flatMap(n=>read(dir(id)+'/'+n));
function record(v){if(!v||typeof v!=='object')return null;return Array.isArray(v.rows)?v:record(v.data);}
const plan=JSON.parse(read(dir('A06')+'/agent-plan.json').planJson);
const binding=plan.plan.bindings.find(b=>b.from===1&&b.to===2&&b.input_field==='warehouse_id');
assert.equal(binding.output_path,'$.data.data.rows[0].global_min_warehouse_sk');
assert.ok(plan.plan.steps.find(s=>s.id===2).depends_on.includes(1));
assert.ok(!Object.hasOwn(plan.plan.steps.find(s=>s.id===2).input,'warehouse_id'));
const calls=obs('A06').filter(o=>o.source?.includes('tpl_')&&o.metadata?.success);
assert.equal(calls.length,2);
const first=record(calls[0].metadata.stepOutput);
equal(calls[1].metadata.resolvedInput.warehouse_id,first.rows[0].global_min_warehouse_sk);
equal(calls[1].metadata.resolvedInput.warehouse_id,base.geography.rows[0].global_min_warehouse_sk);
const answer=fs.readFileSync(dir('A06')+'/agent-answer.md','utf8'),tables=mdTables(answer);
const geo=tables.find(t=>t[0].some(c=>c.includes('国家 (w_country)'))),inventory=tables.find(t=>t[0].some(c=>c.includes('快照日期 snapshot_date')));
const a06Cells=matchRows(geo.slice(2),base.geography.rows,Object.keys(base.geography.rows[0]))+matchRows(inventory.slice(2),base.inventory_a06.rows,['snapshot_date','sku_record_count','nonzero_sku_count','zero_sku_count','total_on_hand',null]);
assert.ok(answer.includes('不能当作 0 库存'));
save(dir('A06')+'/answer-and-binding-comparison.json',{status:'PASS',comparedSourceCells:a06Cells,binding,actualResolvedWarehouse:calls[1].metadata.resolvedInput.warehouse_id,queryOrder:calls.map(c=>c.source),scope:'Actual model plan binding, ordered successful tool observations and displayed source table cells; arbitrary reasoning-node computations are not certified.'});
const pAnswer=fs.readFileSync(dir('A02-productdata')+'/agent-answer.md','utf8'),pt=mdTables(pAnswer).find(t=>t[0][0]==='月份');
assert.ok(pt);assert.equal(pt.length,15);
const pRows=pt.slice(2,14).map(r=>[r[0].replace('月',''),...r.slice(1)]);
const pb=productBaseline.rows??Object.values(product.baselines??{}).find(v=>v.rows?.length===12)?.rows;
assert.ok(pb?.length===12,'Explicit nonempty product JDBC baseline required');
let pCells=matchRows(pRows,pb,['sale_month','row_count','total_quantity','total_net_paid','total_net_profit',null]);
for(const key of ['row_count','total_quantity','total_net_paid','total_net_profit']){const index=['row_count','total_quantity','total_net_paid','total_net_profit'].indexOf(key)+1;
const scale=key==='row_count'?0:2;
const scaled=s=>{const [a,b='']=String(s).split('.');return BigInt(a+(b+'00').slice(0,scale));};
equal(scaled(pt.at(-1)[index].replaceAll(',','').replaceAll('**','')),pb.reduce((n,r)=>n+scaled(r[key]),0n));pCells++;}
save(dir('A02-productdata')+'/answer-comparison.json',{status:'PASS',comparedSourceAndTotalCells:pCells,sqlReceiptComparedFields:84,scope:'Displayed month and four source metrics for all 12 rows, plus exact totals. SQL receipts separately verify all seven source columns; currency and profit causation remain undeclared.'});
const protocol=read(root+'/mcp-final-r9.json'),pc=protocol.records.find(x=>x.caseId==='MCP-PRODUCT-NONDEFAULT');
assert.equal(pc.httpStatus,200);assert.equal(pc.response.result.isError,false);
const result=pc.response.result.structuredContent;
assert.equal(result.success,true);assert.equal(result.metadata.traceId,pc.traceId);
const catalog=read(root+'/catalog-final-pagination/catalog-responses.json').snapshots.map(x=>x.response.data).find(x=>x?.toolName===pc.request.params.name);
assert.equal(result.metadata.templatePublication.releaseId,catalog.publicationId);
const actual=record(result.data);assert.equal(actual.complete,true);assert.equal(actual.possiblyTruncated,false);
const protocolCells=matchRows(actual.rows.map(x=>Object.keys(pb[0]).map(k=>x[k])),pb,Object.keys(pb[0]));
assert.equal(protocolCells,84);
save(root+'/mcp-product-final-r9-comparison.json',{status:'PASS',caseId:pc.caseId,traceId:pc.traceId,toolName:pc.request.params.name,releaseId:catalog.publicationId,version:catalog.version,parameters:pc.request.params.arguments,rows:actual.rows.length,comparedFields:protocolCells});
for(const id of ['C01','C02']){
 const d=dir(id),r=read(d+'/visual-report.json'),render=read(d+'/chart-rendering.json'),proof=read(d+'/python-and-artifact-validation.json');
 assert.equal(proof.status,'REAL_PYTHON_AND_UPLOADED_BYTES_VERIFIED');assert.equal(proof.execution.status,'SUCCEEDED');assert.equal(proof.execution.executorStopped,true);assert.equal(render.status,'RENDERED_IN_SYSTEM_PAGE');assert.equal(render.diagnostics.length,0);
 for(const a of proof.artifacts){const files=fs.readdirSync(d).filter(n=>n.startsWith(a.fileId+'.'));assert.equal(files.length,1);assert.equal(crypto.createHash('sha256').update(fs.readFileSync(d+'/'+files[0])).digest('hex'),a.sha256);}
 let cells;
 if(id==='C01'){
  const t=r.tables.find(t=>t.id==='t_detail');assert.equal(t.rows.length,base.geography.rows.length+1);
  cells=matchRows(t.rows.slice(0,-1),base.geography.rows,['w_country','w_state','warehouse_count','city_count','min_warehouse_sk','max_warehouse_sk',null]);
  equal(t.rows[0][6],100);equal(t.rows.at(-1)[2],base.geography.rows.reduce((s,x)=>s+Number(x.warehouse_count),0));
  const chart=render.charts.find(c=>c.kind==='plotly');assert.ok(chart);assert.deepEqual(chart.figure.data[0].y,base.geography.rows.map(x=>Number(x.warehouse_count)));assert.deepEqual(chart.figure.data[0].customdata,base.geography.rows.map(x=>Number(x.city_count)));cells+=4;
 }else{
  const t=r.tables.find(t=>t.id==='t_daily');cells=matchRows(t.rows,base.inventory_a02.rows,['snapshot_date','sku_record_count','nonzero_sku_count','zero_sku_count','total_on_hand']);assert.ok(render.charts.some(c=>c.kind==='static'));assert.equal(t.rows.length,5);
 }
 save(d+'/report-numerical-comparison.json',{status:'PASS',comparedCells:cells,analysisStatus:r.analysisStatus,scope:id==='C01'?'Six displayed SQL fields, derived share/total and interactive chart values; final seventh SQL field is independently verified in sql-comparison.json.':'All five displayed fields across five observed snapshots. Static chart provenance is the actual executed Python and hash-matched uploaded artifact, not OCR certification.'});
}
console.log(JSON.stringify({status:'PASS',cases:['A06','A02-productdata','C01','C02']}));
