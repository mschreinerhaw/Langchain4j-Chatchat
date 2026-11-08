import {createRequire} from 'node:module';
import {join,resolve,relative,isAbsolute} from 'node:path';
import {realpathSync,existsSync} from 'node:fs';
export function loadNative(source,dist){
 const require=createRequire(dist?join(dist,'package.json'):join(source,'apps/vscode/package.json'));
 const grpc=require('@grpc/grpc-js'),loader=require('@grpc/proto-loader'),health=require('grpc-health-check');
 const dir=dist?join(dist,'proto'):join(source,'apps/vscode/proto');
 const definition=loader.loadSync(['host/workspace.proto','host/env.proto','host/window.proto','host/diff.proto','cline/task.proto','cline/state.proto'].map(p=>join(dir,p)),{includeDirs:[dir],longs:Number,enums:Number,defaults:true,oneofs:true});
 return {grpc,health,proto:grpc.loadPackageDefinition(definition)};
}
export function workspacePath(workspace,file){
 const root=realpathSync(workspace),candidate=resolve(root,file),actual=existsSync(candidate)?realpathSync(candidate):candidate;
 const rel=relative(root,actual);if(rel==='..'||rel.startsWith('..'+(process.platform==='win32'?'\\':'/'))||isAbsolute(rel))throw Error('Workspace path denied');return actual;
}
export async function startHostBridge(native,workspace,emit){
 const {grpc,health,proto}=native,server=new grpc.Server();
 const unsupported=(name)=>(_call,callback)=>{emit('host.unsupported',{method:name,source:'apps/vscode/proto/host'});callback({code:grpc.status.UNIMPLEMENTED,message:'Server host capability unavailable: '+name});};
 const bind=(service,handlers)=>{const impl={};for(const [key,d]of Object.entries(service.service)){impl[key]=handlers[key]??unsupported(d.path);}server.addService(service.service,impl);};
 bind(proto.host.WorkspaceService,{
  getWorkspacePaths:(_c,cb)=>cb(null,{paths:[workspace]}),
  saveOpenDocumentIfDirty:(_c,cb)=>cb(null,{wasSaved:false}),
 });
 bind(proto.host.WindowService,{
  showMessage:(c,cb)=>{emit('host.notification',c.request);if(c.request.options?.items?.length)return unsupported('window.showMessage.interactive')(c,cb);cb(null,{});},
  getOpenTabs:(_c,cb)=>cb(null,{tabs:[]}),getVisibleTabs:(_c,cb)=>cb(null,{tabs:[]}),
 });
 bind(proto.host.EnvService,{
  getHostVersion:(_c,cb)=>cb(null,{platform:'Linux Server',version:process.version,clineType:'Runtime Server POC',clineVersion:'4.1.26'}),
  getIdeRedirectUri:(_c,cb)=>cb(null,{value:''}),
  getTelemetrySettings:(_c,cb)=>cb(null,{isEnabled:2,errorLevel:'off'}),
  subscribeToTelemetrySettings:(call)=>{call.write({isEnabled:2,errorLevel:'off'});call.on('cancelled',()=>call.end());},
  debugLog:(_c,cb)=>cb(null,{}),
  shutdown:(_c,cb)=>{emit('host.shutdown.requested',{});cb(null,{});},
 });
 bind(proto.host.DiffService,{});
 new health.HealthImplementation({'':'SERVING'}).addToServer(server);
 const port=await new Promise((res,rej)=>server.bindAsync('127.0.0.1:0',grpc.ServerCredentials.createInsecure(),(e,p)=>e?rej(e):res(p)));
 return {port,close:()=>server.forceShutdown()};
}
