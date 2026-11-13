import {mkdir,cp,readFile,writeFile,readdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const specs=[['bootstrap','bootstrap','dist'],['tabler','@tabler/core','dist'],['echarts','echarts','dist'],['icons','lucide-static','icons'],['alpine','alpinejs','dist'],['datatables','datatables.net-dt',''],['jquery','jquery','dist'],['mermaid','mermaid','dist']];
const files={bootstrap:['css/bootstrap.min.css','js/bootstrap.bundle.min.js'],tabler:['css/tabler.min.css','js/tabler.min.js'],echarts:['echarts.min.js'],icons:['activity.svg'],alpine:['cdn.min.js'],datatables:['css/dataTables.dataTables.min.css','js/dataTables.min.js','js/dataTables.dataTables.min.js'],jquery:['jquery.min.js'],mermaid:['mermaid.min.js']};
const resources=[];
await mkdir('public/assets',{recursive:true});
for(const [id,pkg,dir] of specs){
 const root=`node_modules/${pkg}`,meta=JSON.parse(await readFile(`${root}/package.json`));
 await cp(`${root}/${dir}`,`public/assets/${id}`,{recursive:true});
 if(id==='datatables'){
  const core=JSON.parse(await readFile('node_modules/datatables.net/package.json'));
  if(core.version!==meta.version)throw Error('DataTables core/style version mismatch');
  await cp('node_modules/datatables.net/js/dataTables.min.js','public/assets/datatables/js/dataTables.min.js');
 }
 const license=(await readdir(root)).find(f=>/^licen[sc]e/i.test(f));
 await cp(license?`${root}/${license}`:`licenses/${id}-LICENSE`,`public/assets/${id}/LICENSE`);
 resources.push({id,package:pkg,version:meta.version,license:meta.license,source:meta.repository,dependencies:id==='datatables'?['jquery']:[],conflicts:id==='tabler'?['bootstrap']:id==='bootstrap'?['tabler']:[],files:await Promise.all(files[id].map(async f=>({path:`/assets/${id}/${f}`,sha256:createHash('sha256').update(await readFile(`public/assets/${id}/${f}`)).digest('hex')})))});
}
await writeFile('public/resources.json',JSON.stringify(resources,null,2));
