// Offline comparison only. Full Runtime evidence is read, never changed/exported.
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {dirname} from 'node:path';
import assert from 'node:assert/strict';
import {compactSqlMetadata} from '../src/runner.js';

const [input,output]=process.argv.slice(2);
assert.ok(input&&output,'Usage: node scripts/benchmark-sql-metadata-context.mjs private-receipts.json summary.json');
const exports=JSON.parse(await readFile(input,'utf8'));
const receipts=exports.map(row=>(row.record||row)).filter(row=>row.evidenceType==='CODING_WORKSPACE_TOOL_RECEIPT')
  .map(row=>row.payload.response).filter(row=>row.tool==='workspace_metadata'&&Array.isArray(row.items));
assert.ok(receipts.length,'No physical metadata receipts');
// Exact field projection from base HEAD 2dad077c0, for an incremental comparison.
const previous=receipt=>({...receipt,items:receipt.items.map(table=>{
  if(!Array.isArray(table?.columns))return table;
  return {...table,columns:table.columns.map(column=>{
    const field=Object.fromEntries(Object.entries(column).filter(([,value])=>value!==null));
    for(const [key,parent] of [['table','tableName'],['database','database'],['schema','schema']])if(field[key]===table[parent])delete field[key];
    delete field.datasourceId;if(field.columnType===field.dataType)delete field.columnType;return field;
  })};
})});
const size=rows=>rows.reduce((total,row)=>total+Buffer.byteLength(JSON.stringify(row),'utf8'),0);
const original=size(receipts),base=size(receipts.map(previous)),current=size(receipts.map(compactSqlMetadata));
const summary={mode:'RECORDED_RECEIPTS_OFFLINE_CONTEXT_COMPARISON',modelCalled:false,toolCalls:receipts.length,
  runtimeReceiptBytes:original,baseModelViewBytes:base,currentModelViewBytes:current,
  incrementalReductionPercent:Math.round((1-current/base)*10000)/100,totalReductionPercent:Math.round((1-current/original)*10000)/100,
  doesNotMeasureEndToEndLatency:true,auditReceiptsChanged:false};
await mkdir(dirname(output),{recursive:true});await writeFile(output,JSON.stringify(summary,null,2)+'\n');console.log(JSON.stringify(summary));
