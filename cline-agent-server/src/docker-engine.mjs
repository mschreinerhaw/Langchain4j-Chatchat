import http from 'node:http';
export class DockerEngine {
 constructor(socketPath='/var/run/docker.sock'){this.socketPath=socketPath;}
 call(method,path,body){return new Promise((resolve,reject)=>{
  const req=http.request({socketPath:this.socketPath,path:'/v1.45'+path,method,headers:body?{'content-type':'application/json'}:{}},res=>{
   const chunks=[];res.on('data',b=>chunks.push(b));res.on('end',()=>{
    const text=Buffer.concat(chunks).toString();let value;try{value=JSON.parse(text);}catch{value=text;}
    if(res.statusCode>=400)reject(Object.assign(Error('Docker '+method+' '+path+' failed ('+res.statusCode+')'),{dockerStatus:res.statusCode}));else resolve(value);
   });res.on('error',reject);
  });req.setTimeout(30000,()=>req.destroy(Error('Docker control deadline')));req.on('error',reject);req.end(body?JSON.stringify(body):undefined);
 });}
 async create(name,options){return (await this.call('POST','/containers/create?name='+encodeURIComponent(name),options)).Id;}
 inspect(id){return this.call('GET','/containers/'+id+'/json');}
 async stop(id){try{await this.call('POST','/containers/'+id+'/stop?t=8');}catch(e){if(e.dockerStatus!==304)throw e;}const info=await this.inspect(id);if(info.State.Running)throw Error('Container stop not confirmed');return {containerId:id,running:false,exitCode:info.State.ExitCode,finishedAt:info.State.FinishedAt};}
}
export function workerContainerSpec({image,root,workerId,sessionId,tenantId,memoryBytes,cpuSet}){
 return {Image:image,User:'1000:1000',WorkingDir:'/poc/service',Cmd:['node','/poc/service/src/isolated-worker.mjs'],Env:['CLINE_SERVER_CONFIG=/run/cline-poc.json','HOME=/home/cline','PATH=/usr/local/bin:/usr/bin:/bin'],Labels:{'cline.p3':'stage1','cline.workerId':workerId,'cline.sessionId':sessionId,'cline.tenantId':tenantId},Healthcheck:{Test:['NONE']},
  HostConfig:{NetworkMode:'none',ReadonlyRootfs:true,CapDrop:['ALL'],SecurityOpt:['no-new-privileges'],Memory:memoryBytes,NanoCpus:2000000000,CpusetCpus:cpuSet,PidsLimit:256,RestartPolicy:{Name:'no'},Tmpfs:{'/tmp':'rw,nosuid,nodev,size=128m,mode=1777'},Binds:[root+'/workspace:/poc/project:rw',root+'/state:/poc/state:rw',root+'/bridge:/poc/bridge:rw',root+'/config.json:/run/cline-poc.json:ro']}};
}
