import { chromium } from "playwright-core";
import { createServer } from "vite";
import { access, mkdir } from "node:fs/promises";
import path from "node:path";

const candidates = [process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH, "C:/Program Files/Google/Chrome/Application/chrome.exe", "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe", "/usr/bin/chromium"];
let executablePath;
for (const candidate of candidates.filter(Boolean)) { try { await access(candidate); executablePath=candidate; break; } catch {} }
if (!executablePath) throw new Error("Set PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH to an installed Chromium browser");
const assert = (condition, message) => { if (!condition) throw new Error(message); };
const user = { id:"browser-user", username:"browser-user", tenantId:"browser-tenant", displayName:"Test", permissionCodes:["workspace:chat","capability:data-science"] };
const pageDeclaration = (viewId, route=viewId) => ({pageId:viewId,viewId,title:viewId,menuCode:viewId,route});
const zhice = {workspaceId:"zhice",name:"灵动智策",description:"旧入口",layout:"default",entryRoute:"#/w/zhice/chat",pages:[pageDeclaration("chat"),pageDeclaration("dataScienceEnvironment"),pageDeclaration("dataScienceDevelop")]};
const science = {workspaceId:"data-science",name:"数据科学",description:"Python Studio",layout:"studio",entryRoute:"#/w/data-science/python/environment",pages:[pageDeclaration("dataScienceEnvironment","python/environment"),pageDeclaration("dataScienceDevelop","python/develop")]};
let preference = {defaultWorkspaceId:null,lastWorkspaceId:null,effectiveWorkspaceId:"zhice",revision:0};
let cancelled = 0, requests = [], browser;
const server = await createServer({server:{host:"127.0.0.1",port:0},logLevel:"error"});
try {
  await server.listen(); const port = server.httpServer.address().port;
  browser = await chromium.launch({executablePath,headless:true});
  const page = await browser.newPage({viewport:{width:1440,height:1000}});
  const errors=[]; page.on("pageerror", error => { errors.push(error.message); console.error(page.url(), error.stack); });
  await page.route("**/api/v1/**", async route => {
    const request = route.request(); const url=new URL(request.url()); const endpoint=url.pathname.replace("/api/v1","");
    requests.push({endpoint,method:request.method(),workspace:request.headers()["x-workspace-id"],authorization:request.headers().authorization});
    if (/cancel|\/kill/.test(endpoint)) cancelled++;
    let data=[]; let status=200; let code=200;
    if (endpoint==="/enterprise/auth/login") data={token:"browser-token",user};
    else if (endpoint==="/enterprise/auth/me") data=user;
    else if (endpoint==="/enterprise/menus") data=[{id:"workspace",title:"工作台",children:[{id:"chat",title:"智能对话",children:[]}]},{id:"capability",title:"能力管理",children:[{id:"dataScience",title:"数据科学",children:[{id:"dataScienceEnvironment",title:"Python 环境",children:[]},{id:"dataScienceDevelop",title:"Python 开发",children:[]}]}]}];
    else if (endpoint==="/workspaces") data=[{manifest:zhice,defaultWorkspace:true},{manifest:science,defaultWorkspace:false}];
    else if (endpoint==="/workspaces/preferences") {
      if (request.method()==="PUT") { const body=request.postDataJSON(); preference={...preference,...body,effectiveWorkspaceId:body.lastWorkspaceId,revision:preference.revision+1}; }
      data=preference;
    } else if (endpoint==="/workspaces/zhice/manifest") data=zhice;
    else if (endpoint==="/workspaces/data-science/manifest") data=science;
    else if (endpoint.startsWith("/workspaces/")) { status=403; code=403; }
    else if (endpoint==="/data-science/python/workbench") data={assets:[{id:"asset",name:"Browser Python",status:"READY"}],scripts:[{id:"script",assetId:"asset",fileName:"test.py",title:"Test",sourceCode:"print('original')",status:"DRAFT"}],folders:[],executions:[],dataFiles:[],systemExamples:[]};
    else if (endpoint==="/data-science/python/models") data=[{value:"fixture-model",label:"fixture-model",defaultModel:true}];
    else if (endpoint.endsWith("/todos")) data={items:[]};
    else if (endpoint==="/ui-display/trend-semantics") data={};
    await route.fulfill({status,contentType:"application/json",body:JSON.stringify({code,data,message:status===403?"Workspace unavailable or unauthorized":"ok"})});
  });
  await page.goto(`http://127.0.0.1:${port}/#/login`,{waitUntil:"networkidle"});
  await page.getByPlaceholder("请输入企业账号").fill("browser-user"); await page.getByPlaceholder("请输入登录密码").fill("fixture-password");
  const captcha=await page.evaluate(()=>document.querySelector("#app")._vnode.component.subTree.component.proxy.captchaCode);
  await page.getByPlaceholder("输入验证码").fill(captcha); await page.locator(".login-submit").click();
  await page.waitForFunction(()=>location.hash==="#/w/zhice/chat");
  await page.locator(".workspace-switcher select").waitFor();
  const token=await page.evaluate(()=>JSON.parse(localStorage.getItem("chatchat.auth.session")).token);
  assert(token==="browser-token","Login identity not restored");
  await page.locator(".workspace-switcher select").selectOption("data-science");
  await page.waitForFunction(()=>location.hash.startsWith("#/w/data-science/"));
  await page.evaluate(()=>location.hash="#/w/data-science/python/develop");
  await page.locator(".monaco-editor textarea.inputarea").first().waitFor({timeout:30000});
  await page.locator(".monaco-editor textarea.inputarea").first().focus();
  await page.keyboard.press("Control+A"); await page.keyboard.type("print('workspace draft')");
  await page.waitForTimeout(350);
  const source=await page.evaluate(()=>document.querySelector("#app")._vnode.component.proxy.activeViewInstance.form.sourceCode);
  assert(source.includes("workspace draft"),"Editor draft was not changed");
  await page.locator(".workspace-switcher select").selectOption("zhice");
  await page.waitForFunction(()=>location.hash==="#/w/zhice/chat");
  await page.locator(".workspace-switcher select").selectOption("data-science");
  await page.waitForFunction(()=>location.hash==="#/w/data-science/python/develop");
  await page.evaluate(()=>location.hash="#/w/data-science/python/develop");
  await page.locator(".monaco-editor textarea.inputarea").first().waitFor();
  const restored=await page.evaluate(()=>document.querySelector("#app")._vnode.component.proxy.activeViewInstance.form.sourceCode);
  assert(restored===source,"Workspace switch lost the editor draft");
  assert(await page.evaluate(()=>JSON.parse(localStorage.getItem("chatchat.auth.session")).token)===token,"Switch changed Token");
  await page.reload({waitUntil:"networkidle"}); await page.locator(".monaco-editor textarea.inputarea").first().waitFor();
  await page.waitForFunction(()=>document.querySelector("#app")._vnode.component.proxy.activeViewInstance?.form?.sourceCode?.includes("workspace draft"),null,{timeout:10000}).catch(async error=>{
    console.error(await page.evaluate(()=>{const app=document.querySelector("#app")._vnode.component.proxy; return {hash:location.hash,workspace:app.activeWorkspaceId,view:app.activeView,error:app.workspaceError,selection:app.workspaceSelection,form:app.activeViewInstance?.form,refKeys:Object.keys(app.activeViewInstance?.$data||{}),hostForm:document.querySelector('.monaco-host')?.__vueParentComponent?.proxy?.form,storage:{...sessionStorage}};})); throw error;
  });
  assert((await page.evaluate(()=>document.querySelector("#app")._vnode.component.proxy.activeViewInstance.form.sourceCode))===source,"Refresh lost draft");
  await page.getByRole("button",{name:"选择空间",exact:true}).click(); await page.getByRole("heading",{name:"选择工作空间"}).waitFor();
  await page.getByRole("button",{name:/灵动智策/}).click(); await page.waitForFunction(()=>location.hash==="#/w/zhice/chat");
  await page.evaluate(()=>location.hash="#/dataScienceDevelop"); await page.locator(".monaco-editor textarea.inputarea").first().waitFor();
  assert((await page.locator(".workspace-switcher select").inputValue())==="zhice","Legacy Python link changed workspace");
  await page.evaluate(()=>location.hash="#/w/forbidden/chat"); await page.locator(".workspace-selection [role=alert]").waitFor();
  assert((await page.locator(".workspace-selection [role=alert]").textContent()).includes("Workspace"),"Unauthorized workspace is not blocked");
  assert(cancelled===0,"Switch invoked backend cancellation");
  assert(errors.length===0,`Browser errors: ${errors.join("; ")}`);
  const output=path.resolve("../../target/workspace-audit"); await mkdir(output,{recursive:true}); await page.screenshot({path:path.join(output,"workspace-browser.png"),fullPage:true});
  console.log(JSON.stringify({result:"passed",checks:10,apiRequests:requests.length,cancelRequests:cancelled,tokenPreserved:true,fixture:"HTTP responses simulated; actual Vue/Monaco UI and Chromium"}));
} finally { await browser?.close(); await server.close(); }
