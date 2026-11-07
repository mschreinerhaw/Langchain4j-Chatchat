/** Isolated acceptance observer. Product server never imports this controller. */
import {createHash} from 'node:crypto';
import {createCodingServer,httpGateway} from '../dist/server.js';
import {verifyClinePackages} from '../dist/sdk-packages.js';
const input=[];for await(const chunk of process.stdin)input.push(chunk);
const {secret}=JSON.parse(Buffer.concat(input).toString());input.length=0;
const hash=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
const emit=value=>console.log(JSON.stringify({acceptanceObservation:'P1',...value}));
let armed=Boolean(process.env.RUNTIME_P1_FAULT);
const gatewayFactory=(...args)=>{
 const gateway=httpGateway(...args);
 return{...gateway,
  async listCodeMcpTools(contract,serverName,signal){
   const tools=await gateway.listCodeMcpTools(contract,serverName,signal);
   emit({type:'MCP_DISCOVERED',taskId:contract.taskId,serverName,tools:tools.map(t=>t.agentToolName),schemaSha256:hash(tools)});
   return tools;
  },
  async callCodeMcpTool(contract,request,signal){
   const result=await gateway.callCodeMcpTool(contract,request,signal);
   emit({type:'MCP_RETURNED',taskId:contract.taskId,serverName:request.serverName,toolName:request.toolName,
    toolCallId:request.context?.toolCallId,inputSha256:hash(request.arguments),resultSha256:hash(result)});
   if(armed&&process.env.RUNTIME_P1_FAULT==='AFTER_MCP_RESULT'){
    armed=false;emit({type:'FAULT_GATE',taskId:contract.taskId,toolCallId:request.context?.toolCallId});
    await new Promise(()=>{}); // Controller stops this Worker; no model response/result is fabricated.
   }
   return result;
  },
  async model(contract,request,signal){
   emit({type:'MODEL_REQUEST',taskId:contract.taskId,operationId:request.operationId,
    requestSha256:hash(request),tools:request.tools.map(t=>t.name),schemaSha256:hash(request.tools)});
   return gateway.model(contract,request,signal);
  }
 };
};
const server=createCodingServer({secret,callbackUrl:'http://127.0.0.1:8080',nativeCodingEnabled:true,
 contextOptimizationEnabled:true,sdkPackages:await verifyClinePackages(),gatewayFactory});
server.listen(3100,'0.0.0.0',()=>emit({type:'READY'}));
for(const name of ['SIGTERM','SIGINT'])process.once(name,()=>void server.shutdown());
