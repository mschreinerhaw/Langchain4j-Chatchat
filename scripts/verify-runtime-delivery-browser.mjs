// Read-only UI verification against an existing real scenario, never submits execute.
import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
const root=resolve(process.argv[2]),origin=process.argv[3]??'http://127.0.0.1:28180';
const auth=JSON.parse(await readFile(root+'/private/auth-private.json','utf8'));
const prepared=JSON.parse(await readFile(root+'/private/scenario-prepared-private.json','utf8'));
const chosen=process.argv[4]??prepared.agent.nodeId;
const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
const context=await browser.newContext({viewport:{width:1600,height:1000}});
await context.addInitScript(value=>localStorage.setItem('chatchat.auth.session',JSON.stringify(value)),auth);
const page=await context.newPage(),errors=[];let executes=0;
page.on('pageerror',error=>errors.push(error.message));
page.on('request',request=>{if(request.method()==='POST'&&request.url().endsWith('/execute'))executes++;});
const proof={readOnly:true,workspaceId:prepared.workspaceId,nodeId:chosen};
try{
 await page.goto(origin+'/index.html#/w/data-exploration/dataExploration?exploration='+prepared.workspaceId+'&node='+chosen);
 const panel=page.locator('[aria-label="分析交付状态"]');await panel.waitFor({timeout:60000});
 const text=await panel.textContent();for(const label of ['模型响应','数据供给','Python 执行','报告交付'])assert.ok(text.includes(label));
 await mkdir(root+'/browser',{recursive:true});await panel.screenshot({path:root+'/browser/delivery-stages.png'});
 assert.equal(executes,0);assert.deepEqual(errors,[]);
 Object.assign(proof,{status:'PASSED',labels:text,executeRequests:executes,browserErrors:errors});
}catch(error){Object.assign(proof,{status:'FAILED',error:error.message,executeRequests:executes,browserErrors:errors});process.exitCode=1;}
finally{await writeFile(root+'/browser-proof.json',JSON.stringify(proof,null,2));await browser.close();console.log(JSON.stringify(proof));}
