// Lifecycle utility invoked before/after Core, with task-only capabilities kept
// outside its workspace. It is not an Agent loop or an analysis program.
import {readFile,writeFile,chmod,readdir,lstat,mkdir,copyFile} from 'node:fs/promises';
import {join} from 'node:path';
import {downloadInput,uploadParts} from './silo-transfer.mjs';
import {readDeliverySelection,hashDeliveryArtifacts,fileDigest} from './delivery-manifest.mjs';
const [action,workspace,planPath]=process.argv.slice(2);
if(action==='selection'&&workspace){
 const artifacts=await hashDeliveryArtifacts(workspace,await readDeliverySelection(workspace));
 if(planPath){
  // This output directory is a fresh, control-owned mount outside the Core's
  // workspace. No model/provider/storage credentials are mounted in this phase.
  if((await readdir(planPath)).length)throw Error('Selection snapshot directory must be empty');
  for(const item of artifacts){
   const target=join(planPath,item.path);await mkdir(join(target,'..'),{recursive:true});await copyFile(join(workspace,item.path),target);
   const actual=await fileDigest(target);if(actual.size!==item.size||actual.sha256!==item.sha256)throw Error('Output changed while capturing delivery selection');
   await chmod(target,0o444);
  }
 }
 console.log(JSON.stringify(artifacts));process.exit(0);
}
if(!['download','upload'].includes(action)||!workspace||!planPath)throw Error('Usage: workspace-transfer.mjs selection workspace OR download|upload workspace private-plan.json');
const plan=JSON.parse(await readFile(planPath,'utf8')),signal=AbortSignal.timeout(300000);
if(action==='download'){
 if(!Array.isArray(plan.inputs)||plan.inputs.length>64)throw Error('Invalid input inventory');
 const inputs=[];for(const input of plan.inputs)inputs.push(await downloadInput(workspace,input,{signal}));
 await writeFile(join(workspace,'input','manifest.json'),JSON.stringify({schemaVersion:'coding_node_input.v1',...plan.binding,files:inputs},null,2),{flag:'wx',mode:0o444});
 async function protect(dir){for(const e of await readdir(dir,{withFileTypes:true})){const p=join(dir,e.name);if(e.isDirectory())await protect(p);else{if(!(await lstat(p)).isFile())throw Error('Non-regular input');await chmod(p,0o444);}}await chmod(dir,0o555);}
 await protect(join(workspace,'input'));
 console.log(JSON.stringify({inputs,inputSnapshotVersion:plan.binding.inputSnapshotVersion}));
}else{
 const manifest=JSON.parse(await readFile(join(workspace,'delivery_manifest.json'),'utf8'));
 if(!Array.isArray(plan)||plan.length!==manifest.artifacts.length)throw Error('Upload plan inventory mismatch');
 const receipts=[];for(const artifact of manifest.artifacts){const capability=plan.find(p=>p.path===artifact.path);if(!capability)throw Error('Missing output capability');receipts.push(await uploadParts(workspace,artifact,capability,{signal}));}
 console.log(JSON.stringify(receipts));
}
