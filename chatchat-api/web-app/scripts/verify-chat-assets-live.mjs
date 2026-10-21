// Real deployed UI + authenticated Silo upload + one genuine Cline/Python analysis. No HTTP fixtures.
import {chromium} from 'playwright-core';
import {readFile,writeFile,mkdir,access} from 'node:fs/promises';
import assert from 'node:assert/strict';
import path from 'node:path';
import {createHash} from 'node:crypto';
const [authFile,base,evidenceDir]=process.argv.slice(2);
if(!authFile||!base||!evidenceDir)throw new Error('AUTH_FILE BASE_URL EVIDENCE_DIRECTORY required');
const auth=JSON.parse(await readFile(authFile,'utf8'));await mkdir(evidenceDir,{recursive:true});
let executablePath;
for(const file of ['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe']){try{await access(file);executablePath=file;break;}catch{}}
assert.ok(executablePath);const browser=await chromium.launch({executablePath,headless:true});
const uploaded=[],errors=[],apiFailures=[];let analysisRequest;
try{
  const page=await browser.newPage({viewport:{width:1560,height:1050}});
  page.on('pageerror',error=>errors.push(error.message));
  page.on('response',async response=>{
    if(response.url().includes('/user-assets')&&response.status()>=400)apiFailures.push({status:response.status(),path:new URL(response.url()).pathname});
    if(new URL(response.url()).pathname==='/api/v1/interactions/user-assets'&&response.request().method()==='POST'&&response.ok())uploaded.push((await response.json()).data);
  });
  await page.addInitScript(value=>localStorage.setItem('chatchat.auth.session',JSON.stringify(value)),auth);
  await page.goto(base+'/#/chat',{waitUntil:'domcontentloaded'});
  const composer=page.locator('.prompt-card');await composer.locator('textarea').waitFor({state:'visible',timeout:30000});
  for(const name of ['联网搜索','上传文件','上传图片'])assert.equal(await composer.getByRole('button',{name,exact:true}).count(),0);
  await composer.locator('.agent-picker select').selectOption('financial_data_analysis');
  const stamp=Date.now(),csvName=`pasted-csv-${stamp}.csv`,imageName=`pasted-image-${stamp}.png`;
  const csv=Buffer.from('category,amount,currency\nalpha,12.50,CNY\nbeta,30.25,CNY\n');
  const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aY5cAAAAASUVORK5CYII=','base64');
  async function paste(name,bytes,type){
    await composer.locator('textarea').evaluate((element,value)=>{const data=new DataTransfer();data.items.add(new File([new Uint8Array(value.bytes)],value.name,{type:value.type}));element.dispatchEvent(new ClipboardEvent('paste',{clipboardData:data,bubbles:true,cancelable:true}));},{name,bytes:[...bytes],type});
    const row=composer.getByRole('listitem').filter({hasText:name});await row.getByText(/已保存到用户空间/).waitFor({timeout:30000});return row;
  }
  const csvRow=await paste(csvName,csv,'text/csv');const imageRow=await paste(imageName,png,'image/png');
  const image=uploaded.find(file=>file.fileName===imageName);assert.ok(image);
  const original=await page.request.get(base+`/api/v1/interactions/user-assets/${image.id}/content`,{headers:{Authorization:`Bearer ${auth.token}`}});
  assert.deepEqual(await original.body(),png);await imageRow.getByRole('button',{name:'移除附件'}).click();
  const text='# 用户分析目标\n请在 Python 沙箱中读取本次附件 CSV，以 Decimal 按 currency 精确汇总 amount，报告行数和合计；不得将传输记录当业务数据，不得推测结果。\n\n# 参考说明\n'+('paddingmarker ordinary context notes\n'.repeat(250))+'\n';
  assert.ok(text.length>8000);await composer.locator('textarea').fill(text);
  await composer.getByText(/超过 8,000 字/).waitFor();
  await page.screenshot({path:path.join(evidenceDir,'chat-paste-before-send.png'),fullPage:true});
  const responsePromise=page.waitForResponse(response=>response.url().endsWith('/user-assets/analyses')&&response.request().method()==='POST',{timeout:900000});
  page.on('request',request=>{if(request.url().endsWith('/user-assets/analyses'))analysisRequest=request.postDataJSON();});
  await composer.getByRole('button',{name:'发送问题',exact:true}).click();
  const response=await responsePromise;assert.equal(response.status(),200);const receipt=(await response.json()).data;
  await writeFile(path.join(evidenceDir,'analysis-receipt-private.json'),JSON.stringify(receipt,null,2));
  assert.equal(receipt.resultAvailable,true);assert.equal(analysisRequest.fileIds.length,2);
  assert.equal(analysisRequest.instruction.includes('paddingmarker'),false);
  assert.equal(analysisRequest.modelName,'qwen3.7-plus');
  const markdown=uploaded.find(file=>file.fileName.endsWith('.md'));assert.ok(markdown);
  const saved=await page.request.get(base+`/api/v1/interactions/user-assets/${markdown.id}/content`,{headers:{Authorization:`Bearer ${auth.token}`}});
  assert.deepEqual(await saved.body(),Buffer.from(text));
  await page.getByText(/42\.75/).first().waitFor({state:'visible',timeout:30000});
  await page.waitForFunction(()=>!document.querySelector('.prompt-card textarea')?.disabled,{},{timeout:30000});
  assert.equal(await composer.getByRole('listitem').count(),0,'Successful attachments must leave the active draft');
  await page.locator('.thinking-message').waitFor({state:'hidden',timeout:30000});
  await page.screenshot({path:path.join(evidenceDir,'chat-paste-analysis-result.png'),fullPage:true});
  await page.reload({waitUntil:'domcontentloaded'});
  await page.locator('.recent-card').filter({hasText:'请阅读附件中的完整资料'}).first().click({timeout:30000});
  await page.getByText(/42\.75/).first().waitFor({state:'visible',timeout:30000});
  await page.waitForFunction(()=>!document.querySelector('.prompt-card textarea')?.disabled,{},{timeout:30000});
  await page.locator('.thinking-message').waitFor({state:'hidden',timeout:30000});
  await page.screenshot({path:path.join(evidenceDir,'chat-paste-analysis-restored.png'),fullPage:true});
  assert.deepEqual(errors,[]);assert.deepEqual(apiFailures,[]);
  const report={status:'PASSED',realDeployment:true,toolbarEntriesRemoved:true,pasteCsv:true,pasteImage:true,imageBytesMatch:true,
    longTextMarkdownBytesMatch:true,longTextCharacters:[...text].length,longTextSha256:createHash('sha256').update(text).digest('hex'),
    originalTextAbsentFromInstruction:true,actualAnalysis:true,reportVisible:true,composerReleased:true,historyRestored:true,taskId:receipt.taskId,result:'42.75 CNY',
    assetIds:uploaded.map(file=>file.id),browserErrors:errors,assetApiFailures:apiFailures};
  await writeFile(path.join(evidenceDir,'browser-acceptance.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));
}catch(error){await writeFile(path.join(evidenceDir,'browser-failure-private.json'),JSON.stringify({message:error.message,errors,apiFailures,uploaded,analysisRequest},null,2));throw error;}
finally{await browser.close();}
