// Trusted delivery-side checks. Never execute model-supplied validation code.
import {readFile} from 'node:fs/promises';
import {posix} from 'node:path';
export async function reviewProfessionalReports(workspace,artifacts){
 const declared=new Set(artifacts.map(a=>a.path));
 for(const report of artifacts.filter(a=>a.type==='REPORT'&&/\.html?$/i.test(a.path))){
  const folder=posix.dirname(report.path);const manifest=(folder==='.'?'':folder+'/')+'report_manifest.json';
  if(!declared.has(manifest))throw Error('Professional HTML report requires report_manifest.json and evidence/quality companions');
 }
 for(const a of artifacts.filter(a=>a.path.endsWith('report_manifest.json'))){
  const prefix=a.path.slice(0,-'report_manifest.json'.length);
  const read=async name=>{const path=prefix+name;if(!declared.has(path))throw Error('Professional report companion not selected: '+path);return JSON.parse(await readFile(workspace+'/'+path,'utf8'));};
  const manifest=await read('report_manifest.json');
  if(manifest.schemaVersion!=='professional-report.v1')continue;
  if(!declared.has(prefix+'report.html'))throw Error('Professional report HTML not selected');
  const evidence=await read('evidence.json'),quality=await read('quality_report.json');
  if(!['PASSED','INCOMPLETE'].includes(quality.status)||!Array.isArray(quality.errors)||quality.errors.length)throw Error('Professional report quality failed');
  if(quality.status==='PASSED'&&quality.browserCheckedHtmlSha256!==artifacts.find(a=>a.path===prefix+'report.html')?.sha256)throw Error('Professional report browser evidence does not match HTML');
  const sources=new Set((evidence.sources??[]).map(s=>s.id));
  for(const item of [...(evidence.metrics??[]),...(evidence.claims??[])]){
   if(!item.sourceIds?.length||item.sourceIds.some(id=>!sources.has(id)))throw Error('Professional report evidence coverage failed');
   if(item.horizon==='long-term'){
    let minimum={daily:252,weekly:52,monthly:12,quarterly:4,annual:2}[item.frequency];
    const metric=evidence.metrics?.find(m=>m.id===item.metricId);
    if(['fact','calculation'].includes(item.kind)&&metric?.check?.op==='return')minimum=2;
    if(!minimum||(item.windowDays??0)<365||(item.observations??0)<minimum)throw Error('Professional report unsupported long-term conclusion');
    if(!item.timeSeries){if(quality.status==='PASSED')throw Error('Professional report history not bound to source dates');}
    else{
     const binding=item.timeSeries,source=evidence.sources.find(s=>s.id===binding.sourceId);if(!source)throw Error('Professional report time source missing');
     let values=await read(source.file);for(const key of binding.path??[])values=values[key];
     const dates=new Set(values.map(v=>{let value=String(binding.field?v[binding.field]:v).split('T')[0];if(/^\d{8}$/.test(value))value=value.slice(0,4)+'-'+value.slice(4,6)+'-'+value.slice(6);const time=Date.parse(value+'T00:00:00Z');if(!Number.isFinite(time)||new Date(time).toISOString().slice(0,10)!==value)throw Error('Professional report invalid source date');return time;}));
     if(dates.size<(item.observations??0)||(Math.max(...dates)-Math.min(...dates))/86400000<(item.windowDays??0))throw Error('Professional report history exceeds actual source dates');
    }
   }
   if(item.check){
    const xs=item.check.inputs;
    if(!Array.isArray(xs)||!xs.length||xs.some(v=>!Number.isFinite(v)))throw Error('Professional report invalid computation input');
    if(item.check.sourceInputs){
     const bound=[];
     for(const binding of item.check.sourceInputs){
      const source=evidence.sources.find(s=>s.id===binding.sourceId);
      if(!source||!declared.has(prefix+source.file))throw Error('Professional report source binding absent');
      let value=await read(source.file);
      for(const key of binding.path){if(!Object.hasOwn(value,key))throw Error('Professional report source cell absent');value=value[key];}
      bound.push(value);
     }
     if(JSON.stringify(bound)!==JSON.stringify(xs))throw Error('Professional report computation differs from source cells');
    }
    let expected;
    switch(item.check.op){
     case 'sum':expected=xs.reduce((a,b)=>a+b,0);break;
     case 'ratio':expected=xs[0]/xs[1];break;
     case 'return':if(xs.length<2||xs.some(x=>x<=0))throw Error('Professional report invalid price return');expected=xs.at(-1)/xs[0]-1;break;
     case 'rank':if(xs.some(v=>v<=0))throw Error('Professional report incomparable PE');expected=xs.map((_,i)=>i).sort((a,b)=>xs[a]-xs[b]);break;
     default:throw Error('Professional report unsupported computation check');
    }
    if(Array.isArray(expected)?JSON.stringify(expected)!==JSON.stringify(item.value):!Number.isFinite(expected)||!Number.isFinite(item.value)||Math.abs(expected-item.value)>1e-8*Math.max(1,Math.abs(expected)))throw Error('Professional report calculation/ranking mismatch');
    if(quality.status==='PASSED'&&!item.check.sourceInputs?.length)throw Error('Professional report computation inputs not bound to source cells');
   }
   if(quality.status==='PASSED'&&item.formula&&item.value!==null&&!item.check)throw Error('Professional report calculation not independently checked');
  }
  for(const s of evidence.sources??[]){
   if(!s.file||posix.isAbsolute(s.file)||s.file.split('/').some(x=>x==='..')||s.file.includes('\\'))throw Error('Professional report unsafe evidence path');
   const source=artifacts.find(a=>a.path===prefix+s.file);
   if(!source||source.sha256!==s.sha256)throw Error('Professional report source snapshot/hash missing');
  }
  for(const chartFile of artifacts.filter(a=>a.path.startsWith(prefix+'charts/')&&a.path.endsWith('.json'))){
   const chart=JSON.parse(await readFile(workspace+'/'+chartFile.path,'utf8'));
   if(!chart.purpose||!chart.period||!chart.unit||!chart.basis||!chart.sourceIds?.length||chart.sourceIds.some(id=>!sources.has(id)))throw Error('Professional report chart metadata incomplete');
   for(const series of chart.series??[]){
    if(['line','bar','combo'].includes(chart.type)&&series.values.length!==chart.labels?.length)throw Error('Professional report chart dimensions differ');
    if(!series.sourceInputs){if(quality.status==='PASSED')throw Error('Professional report chart values not bound to source cells');continue;}
    let expected=[];
    for(const binding of series.sourceInputs){
     if(binding===null){expected.push(null);continue;}
     const source=evidence.sources.find(s=>s.id===binding.sourceId);if(!source)throw Error('Professional report chart source absent');
     let value=await read(source.file);for(const key of binding.path)value=value[key];expected.push(value);
    }
    if(series.transform==='rebased-100'){
     const base=expected[0];if(!Number.isFinite(base)||base<=0)throw Error('Professional report invalid normalization base');
     expected=expected.map(v=>v===null?null:v/base*100);
    }
    if(expected.length!==series.values.length||expected.some((v,i)=>typeof v==='number'?typeof series.values[i]!=='number'||Math.abs(v-series.values[i])>1e-8*Math.max(1,Math.abs(v)):JSON.stringify(v)!==JSON.stringify(series.values[i])))throw Error('Professional report chart differs from source cells');
   }
  }
  for(const [path,hash] of Object.entries(manifest.resources?.files??{})){
   if(artifacts.find(a=>a.path===prefix+path)?.sha256!==hash)throw Error('Professional report asset hash mismatch');
  }
 }
}
