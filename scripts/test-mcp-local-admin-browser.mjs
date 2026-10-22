import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import assert from 'node:assert/strict';
import {randomBytes} from 'node:crypto';
const admin=JSON.parse(readFileSync(process.env.CHATCHAT_STAGE_SESSION_FILE,'utf8'));
const base=process.env.CHATCHAT_MCP_STAGE_URL || 'http://192.168.195.221:18101';
const output=process.env.CHATCHAT_BROWSER_OUTPUT;mkdirSync(output,{recursive:true});
const username='mcp_ui_'+Date.now(),password=randomBytes(16).toString('hex'),resetPassword=randomBytes(16).toString('hex');
const checks={},errors=[];
const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true,args:['--no-proxy-server']});
let created=false,context,page;
async function api(ctx,path,body,token,method){const response=await ctx.request.fetch(base+'/api/v1'+path,{method:method||(body?'POST':'GET'),headers:token?{Authorization:'Bearer '+token}:{},...(body?{data:body}:{})});return {status:response.status(),value:await response.json()};}
async function save(page){const pending=page.waitForResponse(r=>r.url().includes('/api/v1/admin/users/')&&r.request().method()==='PUT');await page.getByRole('button',{name:'保存用户与授权',exact:true}).click();const result=(await (await pending).json());assert.equal(result.code,200);await page.locator('.admin-users .el-loading-mask').waitFor({state:'hidden'});}
async function login(page,secret=password){await page.goto(base+'/admin/');await page.getByPlaceholder('请输入用户名').fill(username);await page.getByPlaceholder('请输入密码').fill(secret);await page.getByRole('button',{name:'登录',exact:true}).click();await page.locator('.sidebar-account-name').waitFor();}
async function token(page){return page.evaluate(()=>sessionStorage.getItem('chatchat.mcp.admin.token'));}
async function setGrant(page,label,value){const input=page.getByRole('checkbox',{name:'授权 '+label,exact:true});if(await input.isChecked()!==value)await input.locator('xpath=ancestor::label[1]').click();assert.equal(await input.isChecked(),value);}
try{
 context=await browser.newContext({viewport:{width:1440,height:1000},locale:'zh-CN'});
 await context.addInitScript(session=>{sessionStorage.setItem('chatchat.mcp.admin.token',session.token);sessionStorage.setItem('chatchat.mcp.admin.user',session.username);sessionStorage.setItem('chatchat.mcp.admin.expiresAt',String(session.expiresAt));sessionStorage.setItem('chatchat.mcp.admin.lastActivityAt',String(Date.now()));},admin);
 page=await context.newPage();page.setDefaultTimeout(60000);page.on('pageerror',e=>errors.push(e.message));
 await page.goto(base+'/admin/');await page.getByRole('menuitem',{name:'用户管理',exact:true}).click();await page.locator('.admin-user-item').first().waitFor();
 await page.getByRole('button',{name:'创建用户',exact:true}).click();await page.getByLabel('新用户名称',{exact:true}).fill(username);await page.getByLabel('初始密码',{exact:true}).fill(password);
 const createResponse=page.waitForResponse(r=>r.url().endsWith('/api/v1/admin/users')&&r.request().method()==='POST');await page.getByRole('button',{name:'确认创建',exact:true}).click();assert.equal((await (await createResponse).json()).code,200);created=true;
 writeFileSync(output+'/fixture-private.json',JSON.stringify({username}));
 await page.locator('.admin-user-item.selected').filter({hasText:username}).waitFor();checks.createUserThroughUi=true;
 const ordinary=await browser.newContext({viewport:{width:1440,height:1000},locale:'zh-CN'});const userPage=await ordinary.newPage();userPage.setDefaultTimeout(60000);userPage.on('pageerror',e=>errors.push(e.message));
 await login(userPage);await userPage.getByText('当前账号尚未获得管理菜单权限，请联系管理员授权。',{exact:true}).waitFor();checks.noImplicitMenuAccess=true;
 await setGrant(page,'MCP 服务',true);await setGrant(page,'登录审计',true);await save(page);checks.saveMenuAuthorizationThroughUi=true;
 await userPage.reload();await userPage.getByRole('menuitem',{name:'MCP 服务',exact:true}).waitFor();assert.equal(await userPage.getByRole('menuitem',{name:'用户管理',exact:true}).count(),0);assert.equal(await userPage.getByRole('menuitem',{name:'License 授权',exact:true}).count(),0);checks.navigationMatchesUserGrants=true;
 let userToken=await token(userPage);
 assert.equal((await api(ordinary,'/mcp-services',null,userToken)).status,200);
 assert.equal((await api(ordinary,'/admin/users',null,userToken)).status,403);
 assert.equal((await api(ordinary,'/api-services',{name:'blocked'},userToken)).status,403);checks.directUnauthorizedReadAndWriteDenied=true;
 for(const width of [1366,1440,1920]){await page.setViewportSize({width,height:1000});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);await page.evaluate(()=>window.scrollTo(0,0));await page.screenshot({path:output+'/menu-permissions-'+width+'.png',fullPage:true});}checks.threeDesktopWidths=true;
 await setGrant(page,'MCP 服务',false);await save(page);assert.equal((await api(ordinary,'/mcp-services',null,userToken)).status,403);checks.revokeAffectsExistingSession=true;
 await api(ordinary,'/admin/auth/login',{username,password:'deliberately-wrong-password'});
 await userPage.getByRole('menuitem',{name:'登录审计',exact:true}).click();await userPage.getByLabel('搜索登录日志',{exact:true}).fill(username);await userPage.getByRole('button',{name:'查询',exact:true}).click();await userPage.getByRole('cell',{name:'失败',exact:true}).first().waitFor();await userPage.getByRole('cell',{name:'成功',exact:true}).first().waitFor();await userPage.locator('.el-loading-mask').waitFor({state:'hidden'});await userPage.evaluate(()=>window.scrollTo(0,0));await userPage.screenshot({path:output+'/login-audits.png',fullPage:true});checks.successAndFailureLoginAuditVisible=true;
 await page.getByLabel('重置密码',{exact:true}).fill(resetPassword);const confirmation=page.getByRole('button',{name:'继续保存',exact:true});const resetPending=page.waitForResponse(r=>r.url().includes('/admin/users/')&&r.request().method()==='PUT');await page.getByRole('button',{name:'保存用户与授权',exact:true}).click();await confirmation.click();assert.equal((await (await resetPending).json()).code,200);await page.locator('.admin-users .el-loading-mask').waitFor({state:'hidden'});
 assert.equal((await api(ordinary,'/admin/auth/me',null,userToken)).status,401);checks.resetPasswordInvalidatesSession=true;
 const newLogin=await api(ordinary,'/admin/auth/login',{username,password:resetPassword});assert.equal(newLogin.value.code,200);userToken=newLogin.value.data.token;
 await page.locator('.admin-account-form .el-switch').click();const disablePending=page.waitForResponse(r=>r.url().includes('/admin/users/')&&r.request().method()==='PUT');await page.getByRole('button',{name:'保存用户与授权',exact:true}).click();await confirmation.click();assert.equal((await (await disablePending).json()).code,200);assert.equal((await api(ordinary,'/admin/auth/me',null,userToken)).status,401);const disabledLogin=await api(ordinary,'/admin/auth/login',{username,password:resetPassword});assert.notEqual(disabledLogin.value.code,200);checks.disableBlocksExistingAndNewSessions=true;
 await page.getByLabel('搜索用户',{exact:true}).fill('admin');await page.getByRole('button',{name:'查询',exact:true}).click();await page.locator('.admin-user-item').filter({hasText:'内置管理员'}).click();await page.getByText('内置管理员拥有所有管理菜单权限，不能停用或修改授权。',{exact:true}).waitFor();assert.equal(await page.getByRole('button',{name:'保存用户与授权',exact:true}).isDisabled(),true);checks.builtinAdministratorProtected=true;
 const rootState=await api(context,'/admin/users?keyword='+username,null,admin.token);assert.equal(rootState.value.data.items[0].enabled,false);assert.deepEqual(errors,[]);checks.noPageErrors=true;
} catch(error) { if(page)await page.screenshot({path:output+'/failure.png'}).catch(()=>{}); throw error;
} finally {
 if(created && context)await api(context,'/admin/users/'+username,{enabled:false,password:'',menuKeys:[]},admin.token,'PUT');
 writeFileSync(output+'/report.json',JSON.stringify({checks,errors,fixtureUsername:username},null,2));await browser.close();
}
console.log(JSON.stringify(checks));
