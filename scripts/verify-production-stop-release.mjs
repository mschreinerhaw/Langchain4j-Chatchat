import assert from 'node:assert/strict';
import {createHash, randomUUID} from 'node:crypto';
import {mkdir, writeFile, readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';

const origin=process.env.VERIFY_ORIGIN||'http://192.168.195.221:18100';
const output=resolve(process.env.VERIFY_OUTPUT||'../target/production-stop-reconcile');
const terminal=new Set(['COMPLETED','FAILED','CANCELLED','PARTIAL','BUDGET_EXCEEDED']);
await mkdir(output,{recursive:true});
let token;
async function api(path,body){
    const options={method:body===undefined?'GET':'POST',headers:{...(token?{Authorization:'Bearer '+token}:{}),
        ...(body===undefined||body instanceof FormData?{}:{'Content-Type':'application/json'})},signal:AbortSignal.timeout(30000)};
    if(body!==undefined)options.body=body instanceof FormData?body:JSON.stringify(body);
    const response=await fetch(origin+'/api/v1'+path,options);
    const value=await response.json();
    assert.ok(response.ok&&value.code===200,path+': '+response.status+' '+value.message);
    return value.data;
}
const pathOf=task=>'/coding-monitor/tasks/'+task.runId+'/'+task.taskId;
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function wait(task,predicate,seconds){
    const until=Date.now()+seconds*1000;
    do{
        const latest=await api(pathOf(task));
        if(predicate(latest.outcome,latest))return latest;
        await sleep(1500);
    }while(Date.now()<until);
    throw Error('Task state deadline exceeded '+task.taskId);
}
async function submit(mode){
    const form=new FormData();
    form.append('file',new Blob(['category,amount\ncash,20\nstock,30\nfund,50\n'],{type:'text/csv'}),'stop-release-SYNTHETIC.csv');
    const input=await api('/interactions/user-assets',form);
    const instruction=mode==='cancel'
        ? 'This is a synthetic cancellation verification. Read the supplied CSV and run a Python computation after a brief 20 second pause. Produce a result only if the task remains active.'
        : 'Synthetic deployment check. Use Python to calculate the sum of amount from the supplied CSV. Generate an offline self-contained HTML report that labels the data SYNTHETIC and shows the 3 category amounts and the computed total. Generate RESULT JSON with exactly one integer field total. Select the HTML as REPORT and the JSON as RESULT in delivery_manifest.json. Use the actual computed values.';
    const request={requestId:'stop-release-'+mode+'-'+randomUUID(),fileIds:[input.id],instruction,modelName:'qwen3.7-plus',
        artifactTypes:mode==='cancel'?['RESULT']:['REPORT','RESULT'],...(mode==='cancel'?{}:{
            reportMode:'visual',resultSchema:{type:'object',properties:{total:{type:'integer'}},required:['total'],additionalProperties:false},
        })};
    await writeFile(resolve(output,mode+'-request.json'),JSON.stringify(request,null,2));
    const task=await api('/coding/tasks',request);
    const identity={taskId:task.taskId,runId:task.runId};
    await writeFile(resolve(output,mode+'-task.json'),JSON.stringify(identity,null,2));
    return identity;
}
assert.ok(process.env.VERIFY_PASSWORD,'Provide authorized login credentials');
token=(await api('/enterprise/auth/login',{username:process.env.VERIFY_USER||'admin',password:process.env.VERIFY_PASSWORD})).token;
const mode=process.argv[2];
if(mode==='history'){
    const historical={runId:process.env.VERIFY_HISTORY_RUN_ID,taskId:process.env.VERIFY_HISTORY_TASK_ID};
    assert.ok(historical.runId&&historical.taskId,'Provide VERIFY_HISTORY_RUN_ID and VERIFY_HISTORY_TASK_ID');
    const detail=await wait(historical,o=>terminal.has(o.status),120);
    assert.equal(detail.outcome.status,'CANCELLED');
    assert.equal(detail.outcome.executionControl.executorStopped,true);
    assert.equal(detail.outcome.executionControl.reconciliationRequired,false);
    assert.equal(detail.canStop,false);
    const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
    try{
        const page=await browser.newPage({viewport:{width:1500,height:950}});
        await page.addInitScript(value=>sessionStorage.setItem('runtime-coding-monitor-session',value),token);
        await page.goto(origin+'/monitor/',{waitUntil:'domcontentloaded'});
        await page.locator('#filters input[name=name]').fill(detail.name.slice(0,48));
        await page.locator('#filters button').click();
        const row=page.locator('tr').filter({hasText:historical.taskId});
        await row.locator('.badge.CANCELLED').waitFor();
        assert.equal(await row.getByRole('button',{name:'重试停止'}).count(),0);
        await page.screenshot({path:resolve(output,'historical-cancelled.png')});
    }finally{await browser.close();}
    const saved={...historical,status:detail.outcome.status,revision:detail.outcome.revision,control:detail.outcome.executionControl,browserVerified:true};
    await writeFile(resolve(output,'historical-verification.json'),JSON.stringify(saved,null,2));
    console.log(JSON.stringify(saved));
}else if(mode==='cancel'){
    const task=await submit('cancel');
    await wait(task,(o,detail)=>o.status==='RUNNING'&&detail.modelReceipts?.length>0,120);
    await api(pathOf(task)+'/cancel',{});
    const detail=await wait(task,o=>terminal.has(o.status),120);
    assert.equal(detail.outcome.status,'CANCELLED');
    assert.equal(detail.outcome.executionControl.executorStopped,true);
    assert.equal(detail.outcome.executionControl.reconciliationRequired,false);
    const saved={...task,status:detail.outcome.status,control:detail.outcome.executionControl};
    await writeFile(resolve(output,'cancel-verification.json'),JSON.stringify(saved,null,2));
    console.log(JSON.stringify(saved));
}else if(mode==='report-submit'){
    console.log(JSON.stringify(await submit('report')));
}else if(mode==='report-check'){
    const task=JSON.parse(await readFile(resolve(output,'report-task.json'),'utf8'));
    const detail=await api(pathOf(task));
    const result={...task,status:detail.outcome.status,reason:detail.outcome.reasonCode,
        modelReceipts:detail.modelReceipts?.length,executions:detail.executions?.length};
    console.log(JSON.stringify(result));
    if(result.status==='COMPLETED'){
        assert.ok(result.modelReceipts>0&&result.executions>0);
        const delivery=await api('/coding/tasks/runs/'+task.runId+'/'+task.taskId+'/delivery');
        const report=delivery.visualReport.htmlReport;
        const bundle=await api('/interactions/user-assets/'+report.entryFileId+'/report-bundle');
        const files=bundle.files.map(({content,...file})=>{
            const bytes=Buffer.from(content,'base64');
            assert.equal(createHash('sha256').update(bytes).digest('hex'),file.sha256);
            if(file.path.endsWith('.json'))try{if(JSON.parse(bytes).total===100)result.total=100;}catch{}
            return file;
        });
        if(result.total!==100&&delivery.results?.some(row=>row.total===100))result.total=100;
        assert.equal(result.total,100);
        await writeFile(resolve(output,'client-assets-bundle.json'),JSON.stringify(bundle,null,2));
        await writeFile(resolve(output,'report-verification.json'),JSON.stringify({...result,files},null,2));
    }else if(terminal.has(result.status))throw Error('Synthetic report did not complete');
}else throw Error('Expected history, cancel, report-submit or report-check');
