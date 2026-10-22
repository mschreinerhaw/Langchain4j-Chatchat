import { chromium } from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import { readFileSync,writeFileSync,mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';
const data=JSON.parse(readFileSync(process.env.CHATCHAT_STAGE_SESSION_FILE,'utf8'));
const base=process.env.CHATCHAT_STAGE_URL || 'http://192.168.195.221:18100',output=process.env.CHATCHAT_BROWSER_OUTPUT;
mkdirSync(output,{recursive:true});
const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true,args:['--no-proxy-server']});
const checks={},errors=[],created={roles:[],users:[],denies:[]};let context,page;
async function api(path,body,method,session=data.admin){
 const response=await context.request.fetch(base+'/api/v1'+path,{method:method || (body?'POST':'GET'),headers:{Authorization:'Bearer '+session.token},...(body?{data:body}:{})});
 const result=await response.json();if(response.status()!==200 || result.code!==200)throw new Error(path+' HTTP '+response.status());return result.data;
}
async function open(session){
 const ctx=await browser.newContext({viewport:{width:1440,height:1000},locale:'zh-CN'});await ctx.addInitScript(value=>localStorage.setItem('chatchat.auth.session',JSON.stringify(value)),session);
 const view=await ctx.newPage();view.setDefaultTimeout(60000);view.on('pageerror',e=>errors.push(e.message));view.on('dialog',dialog=>dialog.accept());return {ctx,view};
}
try{
 ({ctx:context,view:page}=await open(data.ordinary));
 assert.ok(!data.ordinary.user.permissionCodes.includes('system:api:all'));
 await page.goto(base+'/#/w/permissions/systemRoles');await page.locator('.permission-console').waitFor();await page.getByRole('button',{name:'新增角色',exact:true}).waitFor();
 await page.locator('.permission-master-item').first().waitFor();
 assert.equal(await page.locator('.assistant-right,.history-section').count(),0);
 const sidebar=page.locator('.assistant-sidebar');
 for(const title of ['用户管理','组织管理','角色权限','资源授权','安全审计'])assert.ok(await sidebar.getByText(title,{exact:true}).count()>0,title);
 assert.equal(await sidebar.getByText('技能权限',{exact:true}).count(),0);assert.equal(await sidebar.getByText('工具与数据授权',{exact:true}).count(),0);
 checks.fiveFlatNavigationWithoutChatChrome=true;checks.ordinaryAdministratorWithoutApiAll=true;
 const roleName='业务授权验收角色 '+Date.now(),roleCode='UX_BROWSER_'+Date.now();
 await page.getByRole('button',{name:'新增角色',exact:true}).click();const profile=page.locator('.permission-profile-dialog');await profile.getByLabel('角色名称',{exact:true}).fill(roleName);await profile.getByLabel('角色编码',{exact:true}).fill(roleCode);
 const made=page.waitForResponse(r=>r.url().endsWith('/api/v1/enterprise/roles')&&r.request().method()==='POST');
 await profile.getByRole('button',{name:'保存角色',exact:true}).click();const role=(await (await made).json()).data;created.roles.push(role.id);await profile.waitFor({state:'hidden'});
 await page.locator('.permission-master-item').filter({hasText:roleName}).click();
 await page.getByRole('checkbox',{name:'授权 '+data.fixture.workspacePermissionName,exact:true}).check();const saved=page.waitForResponse(r=>r.url().endsWith('/enterprise/roles/'+role.id+'/authorization')&&r.request().method()==='PUT');await page.getByRole('button',{name:'保存权限变更',exact:true}).click();assert.equal((await saved).status(),200);
 assert.ok((await api('/enterprise/roles/'+role.id+'/authorization')).permissionIds.includes(data.fixture.workspacePermissionId));checks.rolePermissionSavedThroughUi=true;
 for(const width of [1366,1440,1920]){await page.setViewportSize({width,height:1000});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);assert.ok(await page.locator('.permission-detail').evaluate(el=>el.clientWidth)>500);await page.locator('.permission-console').screenshot({path:output+'/roles-'+width+'.png'});}
 checks.threeDesktopWidthsWithoutHorizontalOverflow=true;
 await page.setViewportSize({width:1440,height:1000});await page.screenshot({path:output+'/permission-workspace-navigation.png'});
 await page.goto(base+'/#/w/permissions/systemUsers');await page.getByRole('button',{name:'新增账户',exact:true}).waitFor();await page.getByRole('button',{name:'新增账户',exact:true}).click();const userDialog=page.locator('.entity-modal');
 await userDialog.getByLabel('账号',{exact:true}).fill('ux_browser_'+Date.now());await userDialog.getByLabel('姓名',{exact:true}).fill('业务成员（验收）');await userDialog.getByLabel('密码',{exact:true}).fill('Synthetic!'+Date.now()+'x');
 await userDialog.locator('select').last().selectOption(role.id);
 const madeUser=page.waitForResponse(r=>r.url().endsWith('/api/v1/enterprise/users')&&r.request().method()==='POST');await userDialog.getByRole('button',{name:'新增账户',exact:true}).click();const newUser=(await (await madeUser).json()).data;created.users.push(newUser.id);await userDialog.waitFor({state:'hidden'});checks.userCreationAndRoleAssignmentThroughUi=true;
 await page.goto(base+'/#/w/permissions/systemResources');await page.getByLabel('搜索资源',{exact:true}).fill(data.fixture.workspacePermissionName);await page.locator('.permission-master-item').filter({hasText:data.fixture.workspacePermissionName}).first().click();await page.locator('tr').filter({hasText:roleName}).waitFor();await page.getByText(/项用户与角色的授权继承关系/).waitFor();checks.roleResourceAndUserInheritanceQueryable=true;
 await page.getByLabel('搜索资源',{exact:true}).fill(data.fixture.toolName);await page.locator('.permission-master-item').first().click();const editor=page.locator('.permission-resource-editor');await editor.getByLabel('对象类型',{exact:true}).selectOption('USER');await editor.getByLabel('授权对象',{exact:true}).selectOption(data.admin.user.id);await editor.getByLabel('授权动作',{exact:true}).selectOption('deny');
 assert.equal((await api('/mcp/runtime/invoke',data.fixture.invocation)).status,'SUCCESS');
 const denySaved=page.waitForResponse(r=>r.url().endsWith('/permission-center/tool-grants')&&r.request().method()==='POST');await editor.getByRole('button',{name:'保存授权',exact:true}).click();const deny=(await (await denySaved).json()).data;created.denies.push(deny.id);await editor.locator('tr').filter({hasText:'拒绝'}).waitFor();
 const deniedResponse=await context.request.post(base+'/api/v1/mcp/runtime/invoke',{headers:{Authorization:'Bearer '+data.admin.token},data:data.fixture.invocation});const denied=await deniedResponse.json();assert.ok(deniedResponse.status()===403 || deniedResponse.status()===200 && !['SUCCESS','REPAIRED','PARTIAL'].includes(denied.data?.status));checks.uiDenyImmediatelyBlocksRuntimeAndMcp=true;
 await page.locator('.permission-console').screenshot({path:output+'/resource-denial.png'});
 const removed=page.waitForResponse(r=>r.url().endsWith('/permission-center/tool-grants/'+deny.id)&&r.request().method()==='DELETE');await editor.locator('tr').filter({hasText:'拒绝'}).getByRole('button',{name:'撤销',exact:true}).click();assert.equal((await removed).status(),200);created.denies=[];assert.equal((await api('/mcp/runtime/invoke',data.fixture.invocation)).status,'SUCCESS');checks.uiRevokeRestoresRuntimeAndMcp=true;
 for(const route of ['permissionTools','systemSkillRoles']){await page.goto(base+'/#/w/permissions/'+route);await page.locator('.permission-console').waitFor();await page.waitForURL('**/#/w/permissions/systemResources');}
 checks.oldRoutesConvergeToOneResourcePage=true;
 await page.goto(base+'/#/w/permissions/systemLogins');await page.getByRole('heading',{name:'安全审计',exact:true}).waitFor();await page.getByRole('button',{name:'授权变更',exact:true}).click();await page.locator('.permission-table tbody tr').filter({hasText:'保存工具授权'}).first().waitFor();await page.locator('.permission-console').screenshot({path:output+'/security-audit.png'});checks.authorizationAuditVisible=true;
 await page.goto(base+'/#/w/permissions/systemOrganizations');await page.getByRole('button',{name:'新增组织',exact:true}).waitFor();await page.locator('.organization-table-row').nth(1).waitFor();checks.organizationsRemainUsable=true;
 const root=await open(data.admin);page=root.view;await page.goto(base+'/#/w/zhice/workspaces');await page.getByRole('heading',{name:'工作空间设置',exact:true}).waitFor();await page.getByRole('button',{name:'配置',exact:true}).first().click();await page.getByRole('heading',{name:'配置工作空间',exact:true}).waitFor();await page.locator('.permission-profile-dialog').getByRole('button',{name:'取消',exact:true}).click();await page.locator('.workspace-settings').screenshot({path:output+'/workspace-settings.png'});checks.workspaceSettingsRestored=true;
 assert.deepEqual(errors,[]);checks.noPageErrors=true;
 writeFileSync(output+'/acceptance.json',JSON.stringify({status:'PASSED',checks},null,2));console.log(JSON.stringify({status:'PASSED',checks}));
}catch(error){
 if(page)await page.screenshot({path:output+'/failure.png'}).catch(()=>{});
 writeFileSync(output+'/failure.json',JSON.stringify({status:'FAILED',checks,error:String(error),pageErrors:errors},null,2));throw error;
}finally{
 const cleanupErrors=[];
 if(context){for(const id of created.denies)try{await api('/permission-center/tool-grants/'+id,null,'DELETE');}catch(e){cleanupErrors.push(e.message);}for(const id of created.users)try{await api('/enterprise/users/'+id,null,'DELETE');}catch(e){cleanupErrors.push(e.message);}for(const id of created.roles)try{await api('/enterprise/roles/'+id,null,'DELETE');}catch(e){cleanupErrors.push(e.message);}}
 writeFileSync(output+'/cleanup.json',JSON.stringify({status:cleanupErrors.length?'FAILED':'PASSED',created,errors:cleanupErrors},null,2));await browser.close();if(cleanupErrors.length)throw new Error('Browser fixture cleanup incomplete');
}
