// Dedicated security acceptance service. Reads actual private tenant files through
// the official MCP transport. It does not simulate any Agent or cancellation result.
import http from 'node:http';
import {readFileSync} from 'node:fs';
import {createRequire} from 'node:module';
import {Journal} from '../src/journal.mjs';
const config=JSON.parse(readFileSync(process.env.TENANT_MCP_CONFIG,'utf8')),require=createRequire(process.env.MCP_SDK_PACKAGE??'/poc/runtime/package.json');
const {McpServer}=require('@modelcontextprotocol/sdk/server/mcp.js'),{StreamableHTTPServerTransport}=require('@modelcontextprotocol/sdk/server/streamableHttp.js'),{z}=require('zod');
const journal=new Journal(config.journal);
http.createServer(async(req,res)=>{
 const tenant=Object.entries(config.tenants).find(([_id,t])=>req.headers.authorization==='Bearer '+t.token);
 if(!tenant){res.writeHead(401);res.end();return;}
 if(req.method!=='POST'){res.writeHead(405);res.end();return;}
 const [tenantId,scope]=tenant;const mcp=new McpServer({name:'tenant-boundary-files',version:'1.0.0'});
 mcp.registerTool('read_dataset',{description:'Read the authorized tenant dataset. The datasetId must be granted to this task.',inputSchema:{datasetId:z.string()}},async({datasetId})=>{
  const path=scope.datasets[datasetId];journal.append({type:'mcp.dataset.read',tenantId,datasetId,allowed:!!path});
  if(!path)return {isError:true,content:[{type:'text',text:'DATASET_ACCESS_DENIED'}]};
  return {content:[{type:'text',text:readFileSync(path,'utf8')}]};
 });
 const transport=new StreamableHTTPServerTransport({sessionIdGenerator:undefined,enableJsonResponse:true});await mcp.connect(transport);
 res.on('close',()=>{void transport.close();void mcp.close();});
 try{const chunks=[];for await(const b of req)chunks.push(b);await transport.handleRequest(req,res,JSON.parse(Buffer.concat(chunks)));}
 catch{if(!res.headersSent)res.writeHead(500);res.end();}
}).listen(config.port??18401,'0.0.0.0');
