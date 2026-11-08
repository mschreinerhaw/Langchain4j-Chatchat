import {readFileSync} from 'node:fs';
import {resolve,join,sep} from 'node:path';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';

// Standalone utils.ts reads this relative to its runtime working directory.
export function validateRuntime(dist){
 const root=resolve(dist),manifest=JSON.parse(readFileSync(join(root,'runtime-manifest.json'),'utf8'));
 if(manifest.sourceCommit!=='a459f1d04dea030833b558be645011ab739c463b')throw Error('Unexpected official runtime source commit');
 const required=['cline-core.js','package.json','proto/descriptor_set.pb','proto/host/workspace.proto','proto/cline/task.proto'];
 const entries=new Map(manifest.files.map(e=>[e.path,e]));
 for(const name of required)if(!entries.has(name))throw Error('Runtime manifest missing required asset: '+name);
 for(const entry of entries.values()){
  const file=resolve(root,entry.path);if(!file.startsWith(root+sep))throw Error('Runtime manifest path escapes distribution');
  let bytes;try{bytes=readFileSync(file);}catch{throw Error('Runtime resource missing: '+entry.path+' (ProtoBus loader: apps/vscode/src/standalone/utils.ts:23)');}
  if(bytes.length!==entry.bytes||createHash('sha256').update(bytes).digest('hex')!==entry.sha256)throw Error('Runtime resource integrity mismatch: '+entry.path);
 }
 const require=createRequire(join(root,'package.json')),loader=require('@grpc/proto-loader');
 const defs=loader.loadFileDescriptorSetFromBuffer(readFileSync(join(root,'proto/descriptor_set.pb')),{longs:Number,enums:Number,defaults:true,oneofs:true});
 for(const name of ['cline.TaskService','host.WorkspaceService'])if(!defs[name])throw Error('ProtoBus descriptor missing service: '+name);
 return {sourceCommit:manifest.sourceCommit,verifiedFiles:entries.size,descriptorSha256:entries.get('proto/descriptor_set.pb').sha256,descriptorServices:Object.keys(defs).filter(k=>defs[k]&&!defs[k].format).length};
}
