/** Acceptance-only crash window. Never imported by the product server. */
import {createCodingServer,httpGateway} from '../dist/server.js';
import {verifyClinePackages} from '../dist/sdk-packages.js';

if(process.env.RUNTIME_ACCEPTANCE_FAULT!=='AFTER_DURABLE_EDIT')throw Error('Explicit isolated fault mode required');
const chunks=[];
for await(const chunk of process.stdin)chunks.push(chunk);
const {secret}=JSON.parse(Buffer.concat(chunks).toString());chunks.length=0;
let armed=true;
const gatewayFactory=(...args)=>{
  const gateway=httpGateway(...args);
  return {...gateway,async commitCodeWorkspace(contract,proposal,signal){
    const result=await gateway.commitCodeWorkspace(contract,proposal,signal);
    if(armed&&proposal.kind==='EDIT'){
      armed=false;
      console.log(JSON.stringify({acceptanceFault:'DURABLE_EDIT_BEFORE_ACK',taskId:contract.taskId,
        manifestHash:proposal.manifest.sha256}));
      // The test controller must kill this Worker and observe its stopped container.
      // No fabricated response, code correction, model output or stop proof is returned.
      await new Promise(()=>{});
    }
    return result;
  }};
};
const server=createCodingServer({secret,callbackUrl:'http://127.0.0.1:8080',nativeCodingEnabled:true,
  contextOptimizationEnabled:true,sdkPackages:await verifyClinePackages(),gatewayFactory});
server.listen(3100,'0.0.0.0',()=>console.log('ACCEPTANCE_FAULT_WORKER_READY'));
for(const name of ['SIGTERM','SIGINT'])process.once(name,()=>void server.shutdown());
