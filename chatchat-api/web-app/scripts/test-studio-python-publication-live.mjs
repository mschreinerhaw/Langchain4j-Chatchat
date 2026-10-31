import {chromium} from 'playwright-core';import fs from 'node:fs';import path from 'node:path';import assert from 'node:assert/strict';
const origin=process.env.STUDIO_ORIGIN,authFile=process.env.STUDIO_AUTH_FILE,state=process.env.STUDIO_OUTPUT;
assert.ok(origin&&authFile&&state,'Set STUDIO_ORIGIN, STUDIO_AUTH_FILE and STUDIO_OUTPUT');
const auth=JSON.parse(fs.readFileSync(authFile)),saved=JSON.parse(fs.readFileSync(path.join(state,'script.json')));
const response=await fetch(origin+'/api/v1/data-science/python/workbench',{headers:{Authorization:'Bearer '+auth.token,'X-Workspace-Id':'data-science'}}),body=await response.json();assert.equal(response.status,200);assert.equal(body.code,200);
const script=body.data.scripts.find(s=>s.id===saved.id);assert.ok(script);assert.equal(script.status,'TESTED');
const schema=JSON.parse(fs.readFileSync(path.join(state,'schema.json'))),params=JSON.parse(fs.readFileSync(path.join(state,'trial-inputs.json')));
const out=process.env.STUDIO_SCREENSHOTS||'../../target/studio-python-screenshots';fs.mkdirSync(out,{recursive:true});
const browser=await chromium.launch({executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
try{
 const page=await browser.newPage({viewport:{width:1680,height:1100}});
 await page.addInitScript(({auth,script,schema,params})=>{
  localStorage.setItem('chatchat.auth.session',JSON.stringify(auth));const owner=[auth.user.tenantId,auth.user.id,'data-science'].map(encodeURIComponent).join(':');
  const key='script-'+script.id;localStorage.setItem('chatchat.pythonDraft.v1:'+owner,JSON.stringify({version:1,value:{tabs:[{key,form:script,parametersText:JSON.stringify(params),inputSchemaText:JSON.stringify(schema)}],activeKey:key}}));
  localStorage.setItem('chatchat.python-studio.layout.v1:'+owner,JSON.stringify({explorer:250,ai:340,bottom:230,explorerOpen:true,aiOpen:false,bottomOpen:true}));
 },{auth,script,schema,params});
 await page.goto(origin+'/index.html#/w/data-science/dataScienceDevelop');
 await page.locator('.editor-tab.active').filter({hasText:script.fileName}).waitFor({timeout:60000});await page.locator('.python-studio .monaco-editor').waitFor({timeout:60000});
 await page.screenshot({path:path.join(out,'python-studio-source-real.png'),fullPage:true});
 await page.getByRole('button',{name:'发布模板',exact:true}).click();await page.getByRole('heading',{name:'更新 Python 模板',exact:true}).waitFor();
 await page.screenshot({path:path.join(out,'python-studio-publication-real.png'),fullPage:true});
 console.log('Captured actual Studio source and current published template configuration; no save/run/publish requests submitted.');
}finally{await browser.close();}
