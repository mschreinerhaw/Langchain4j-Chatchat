// Runs against deployed containers; fixture data is explicitly synthetic.
import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
const base=process.env.HUB_URL||'http://localhost:8080',headers={authorization:`Bearer ${process.env.HUB_TOKEN}`,'content-type':'application/json'};
async function get(path,options){const r=await fetch(base+path,options);assert(r.ok,`${path}: ${r.status}`);return r.json()}
async function preview(html){const j=await get('/api/v1/preview',{method:'POST',headers,body:JSON.stringify({html})});for(let n=0;n<90;n++){await new Promise(r=>setTimeout(r,1000));const r=await get(`/api/v1/preview/${j.id}/result`,{headers});if(r.status!=='running'&&(r.bundle||r.bundleError||r.error))return r}throw Error('Preview polling timeout')}
const evidence={date:new Date().toISOString(),data:'Synthetic acceptance fixtures; not production business acceptance',cases:[]};
const resources=await get('/api/v1/resources');assert.equal(resources.length,8);
for(const r of resources)for(const f of r.files){const res=await fetch(base+f.path);assert(res.ok,f.path)}
assert.equal((await fetch(base+'/api/v1/preview/no-auth')).status,401);
const all=await get('/api/v1/templates');assert.equal(all.length,5);
const fixture={title:'Synthetic business acceptance',categories:['2026-10','2026-11'],series:[{name:'Fixture assets',data:[120,150]}],metrics:[{label:'Fixture return',value:'2.5%'}],rows:[{customer:'FIXTURE-001',assets:150,risk:'medium'}]};
for(const [i,t] of all.entries()){
 const theme=['enterprise-blue','financial-light','dashboard-dark'][i%3];
 const html=t.html.replace('enterprise-blue.css',theme+'.css').replace('</head>',`<script>window.BUSINESS_DATA=${JSON.stringify(fixture)}</script></head>`);
 const r=await preview(html);evidence.cases.push({template:t.id,theme,...r});assert.equal(r.status,'passed',JSON.stringify(r));assert(r.bundle,JSON.stringify(r));
}
const bad=await preview('<html><body style="width:2000px"><script>throw Error("fixture defect")</script><img src="http://169.254.169.254/latest/meta-data/"></body></html>');
assert.equal(bad.status,'failed');assert(bad.report.errors.some(e=>e.type==='javascript'));assert(bad.report.errors.some(e=>e.type==='resource'));assert(bad.report.errors.some(e=>e.message==='horizontal-overflow'));
const fixed=await preview('<html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body><main data-preview-required>Fixed fixture</main></body></html>');assert.equal(fixed.status,'passed');assert.notEqual(bad.report.htmlSha256,fixed.report.htmlSha256);
evidence.libraries=[];
for(const [name,body] of [
 ['datatable-bootstrap-alpine-icons','<link rel="stylesheet" href="/assets/bootstrap/css/bootstrap.min.css"><link rel="stylesheet" href="/assets/datatables/css/dataTables.dataTables.min.css"><table id="fixture"><thead><tr><th>Invoice</th></tr></thead><tbody><tr><td>A-001</td></tr><tr><td>B-002</td></tr></tbody></table><div x-data="{value:1}"><span x-text="value"></span><button @click="value++" data-preview-click>Add</button></div><img src="/assets/icons/activity.svg" alt="Activity"><script src="/assets/jquery/jquery.min.js"></script><script src="/assets/datatables/js/dataTables.min.js"></script><script src="/assets/datatables/js/dataTables.dataTables.min.js"></script><script src="/assets/bootstrap/js/bootstrap.bundle.min.js"></script><script defer src="/assets/alpine/cdn.min.js"></script><script>new DataTable("#fixture",{paging:false,searching:true}).search("A-001").draw();if(document.querySelectorAll("tbody tr").length!==1)throw Error("DataTables filter failed");if(!bootstrap.Modal)throw Error("Bootstrap unavailable");</script>'],
 ['mermaid','<div id="graph" data-preview-required></div><script src="/assets/mermaid/mermaid.min.js"></script><script>mermaid.initialize({startOnLoad:false,securityLevel:"strict"});mermaid.render("diagram","graph TD; A-->B;").then(({svg})=>{document.getElementById("graph").innerHTML=svg;if(!document.querySelector("svg"))throw Error("Mermaid SVG missing")});</script>']
]){const result=await preview('<html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body>'+body+'</body></html>');assert.equal(result.status,'passed',JSON.stringify(result));assert(result.bundle,JSON.stringify(result));evidence.libraries.push({name,...result})}
evidence.repair={method:'Deterministic test repair; native Cline autonomous repair still requires business task acceptance',before:bad,after:fixed};
await writeFile(process.env.EVIDENCE_PATH||'/tmp/frontend-hub-acceptance.json',JSON.stringify(evidence,null,2));console.log(JSON.stringify({cases:evidence.cases.map(c=>({template:c.template,status:c.status,jobId:c.id})),repair:'passed'}));
