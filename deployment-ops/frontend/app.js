const $ = s => document.querySelector(s);
const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const pages = {overview:'Overview', applications:'Applications', containers:'Containers', images:'Images', deployments:'Deployments', registry:'Registry', nodes:'Nodes', entries:'Application links', users:'Users and roles', audit:'Audit log'};
let page = 'overview', nodes = [], generation = 0;
function session(active) {
  $('#login').hidden = !active; $('#content').hidden = active;
  for (const id of ['nav','logout','refresh']) $('#'+id).hidden = active;
  if (active) { $('#title').textContent = 'Sign in'; $('#identity').textContent = ''; }
}
async function api(path, options = {}) {
  const response = await fetch('/api'+path, {...options, headers:{'X-Ops-Request':'1', ...options.headers}});
  const result = await response.json();
  if (!response.ok) {
    if (response.status === 401) { session(true); throw Error('Please sign in.'); }
    throw Error(result.error || `Request failed (${response.status})`);
  }
  return result;
}
const post = (path, data) => api(path, {method:'POST',body:JSON.stringify(data)});
function notice(error) { $('#notice').textContent = error?.message || error || ''; }
function row(cells) { return '<tr>'+cells.map(c=>`<td>${c}</td>`).join('')+'</tr>'; }
function table(headers, rows) { return `<table><thead><tr>${headers.map(h=>`<th>${esc(h)}</th>`).join('')}</tr></thead><tbody>${rows.join('') || `<tr><td colspan="${headers.length}">No records</td></tr>`}</tbody></table>`; }
function show(value) { $('#output').textContent = typeof value === 'string' ? value : JSON.stringify(value,null,2); $('#detail').showModal(); }
function options() { return nodes.map(n=>`<option value="${n.id}">${esc(n.name)}</option>`).join(''); }
function form(id, handler) { $('#'+id).onsubmit = async event => { event.preventDefault(); try { await handler(event.target); } catch (error) { notice(error); } }; }
$('#nav').innerHTML = Object.entries(pages).map(([key,label])=>`<button data-page="${key}">${label}</button>`).join('');
$('#nav').onclick = event => { if (event.target.dataset.page) { page=event.target.dataset.page; load().catch(notice); } };
$('#refresh').onclick = () => load().catch(notice);
$('#close').onclick = () => $('#detail').close();
$('#logout').onclick = async () => { try { await post('/logout',{}); session(true); notice(''); } catch(error) { notice(error); } };
form('loginForm', async element => { await post('/login',Object.fromEntries(new FormData(element))); $('#identity').textContent=element.username.value; element.reset(); await load(); });
async function load() {
  const current = ++generation, selected = page;
  notice(''); nodes = await api('/nodes'); if (current !== generation) return;
  session(false); $('#title').textContent = pages[selected];
  document.querySelectorAll('[data-page]').forEach(b=>b.classList.toggle('active',b.dataset.page===selected));
  const el = $('#content'); el.innerHTML = '<p>Loading...</p>';
  if (selected === 'audit') {
    const records=await api('/audit'); if(current!==generation)return;
    el.innerHTML='<h2>Recent operations</h2>'+table(['Time','Actor','Action','Target','Result'],records.map(a=>row([a.created_at,a.actor,a.action,a.target,a.outcome].map(esc)))); return;
  }
  if (selected === 'users') {
    const users=await api('/users'); if(current!==generation)return;
    el.innerHTML='<h2>Local users and permissions</h2><form id="user"><input name="username" placeholder="Username" required><input name="password" type="password" minlength="8" placeholder="Password (8+ characters)" required><select name="role"><option value="viewer">Viewer (read only)</option><option value="admin">Administrator</option></select><button>Save user</button></form>'+table(['Username','Role'],users.map(u=>row([esc(u.username),esc(u.role)])));
    form('user', async f => { await post('/users',Object.fromEntries(new FormData(f))); await load(); }); return;
  }
  if (selected === 'nodes') {
    el.innerHTML='<h2>Managed Docker nodes</h2><form id="node"><input name="name" placeholder="Node name" required><input name="url" type="url" placeholder="https://node:8091" required><input name="token" type="password" placeholder="Executor token" required><button>Register node</button></form>'+table(['ID','Node','Executor URL'],nodes.map(n=>row([n.id,esc(n.name),esc(n.url)])));
    form('node',async f=>{ await post('/nodes',Object.fromEntries(new FormData(f))); await load(); }); return;
  }
  if (selected === 'entries') {
    const entries=await api('/entries'); if(current!==generation)return;
    el.innerHTML='<h2>Application directory</h2><form id="entry"><input name="name" placeholder="Application name" required><input name="project" placeholder="Project"><input name="url" type="url" placeholder="http://private-address" required><button>Add link</button></form>'+table(['Application','Project','Address'],entries.map(a=>row([esc(a.name),esc(a.project),`<a href="${esc(a.url)}" target="_blank" rel="noopener noreferrer">${esc(a.url)}</a>`])));
    form('entry',async f=>{ await post('/entries',Object.fromEntries(new FormData(f))); await load(); }); return;
  }
  if (selected === 'registry') {
    const rows=[];
    for (const node of nodes) { try { const result=await api(`/nodes/${node.id}/registry`); for (const [repo,tags] of Object.entries(result.tags)) rows.push(row([esc(node.name),esc(repo),esc((tags||[]).join(', '))])); } catch(error) { notice('Registry is unavailable: '+error.message); } }
    if(current!==generation)return;
    el.innerHTML='<h2>Private Registry</h2><p>Requires the optional offline registry:2.8.3 image. Image deletion is disabled.</p>'+table(['Node','Repository','Tags'],rows); return;
  }
  if (selected === 'deployments') {
    const rows=[];
    for (const node of nodes) { try { for (const release of await api(`/nodes/${node.id}/releases`)) rows.push(row([esc(node.name),esc(release.application),esc(release.image),esc(release.status),esc(release.id)+(release.previous?`<button data-rollback="${release.id}" data-node="${node.id}">Roll back</button>`:'')])); } catch(error) { notice(error); } }
    if(current!==generation)return;
    el.innerHTML='<h2>Offline deployments</h2><p>Import the image first, then upload a JSON manifest. Failed health checks restore the previous container.</p><form id="release"><select name="node">'+options()+'</select><input type="file" name="file" accept=".json" required><button>Validate and deploy</button></form>'+table(['Node','Application','Image','Status','Release ID'],rows);
    form('release',async f=>{ const manifest=JSON.parse(await f.file.files[0].text()); if(!confirm(`Deploy ${manifest.application}?`))return; notice('Deploying and checking health...'); show(await post(`/nodes/${f.node.value}/releases`,manifest)); await load(); });
    el.querySelectorAll('[data-rollback]').forEach(b=>b.onclick=async()=>{ if(!confirm('Restore the container retained before this release?'))return; try { show(await post(`/nodes/${b.dataset.node}/rollback`,{release:b.dataset.rollback})); await load(); } catch(error) { notice(error); } }); return;
  }
  const inventory=await api('/inventory'); if(current!==generation)return;
  const containers=[], images=[];
  for(const node of inventory) { for(const c of node.data?.containers||[])containers.push({...c,node}); for(const i of node.data?.images||[])images.push({...i,node}); }
  const failures=inventory.filter(n=>n.error); if(failures.length)notice(failures.map(n=>`${n.name}: offline (${n.error})`).join('; '));
  if(selected==='overview') {
    const projects=new Set(containers.map(c=>c.Labels?.['com.docker.compose.project']||c.Names?.[0]));
    el.innerHTML='<div class="cards">'+[['Discovered applications',projects.size],['Running containers',containers.filter(c=>c.State==='running').length],['Local images',images.length],['Online nodes',`${inventory.filter(n=>n.data).length}/${nodes.length}`]].map(([label,value])=>`<div class="card"><span>${label}</span><strong>${value}</strong></div>`).join('')+'</div><h2>Live node status</h2>'+table(['Node','Docker','CPU cores','Physical memory','Running containers','Status'],inventory.map(n=>row([esc(n.name),esc(n.data?.node.ServerVersion||'-'),esc(n.data?.node.NCPU??'-'),n.data?((n.data.node.MemTotal||0)/1024**3).toFixed(1)+' GiB':'-',esc(n.data?.node.ContainersRunning??'-'),n.data?'Online':'Offline']))); return;
  }
  if(selected==='images') {
    el.innerHTML='<h2>Local images and verified TAR import</h2><form id="import"><select name="node">'+options()+'</select><input name="file" type="file" accept=".tar" required><input name="digest" placeholder="SHA-256 (required over HTTP)" pattern="[a-f0-9]{64}"><button>Import TAR (max 256 MiB)</button></form>'+table(['Node','Tags','Image ID','Size'],images.map(i=>row([esc(i.node.name),esc((i.RepoTags||[]).join(', ')),esc(i.Id),(i.Size/1024**2).toFixed(1)+' MiB'])));
    form('import',async f=>{ const file=f.file.files[0]; if(file.size>256*1024**2)throw Error('The Web upload limit is 256 MiB.'); notice('Verifying and importing...'); const bytes=await file.arrayBuffer(); let digest=f.digest.value; if(!digest){ if(!crypto.subtle)throw Error('Enter the offline SHA-256 checksum, or use HTTPS.'); digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))).map(x=>x.toString(16).padStart(2,'0')).join(''); } await api(`/nodes/${f.node.value}/images/import`,{method:'POST',headers:{'X-SHA256':digest},body:bytes}); await load(); }); return;
  }
  const labels={inspect:'Details',logs:'Logs',stats:'Metrics',start:'Start',stop:'Stop',restart:'Restart'};
  el.innerHTML=`<h2>${selected==='applications'?'Discovered applications':'Container inventory'}</h2><input id="filter" placeholder="Filter by project, name, node or status">`+table(['Node','Application / container','Image','Status','Actions'],containers.map(c=>row([esc(c.node.name),esc(c.Names?.join(', '))+'<br><small>'+esc(c.Labels?.['com.docker.compose.project']||'Standalone container')+'</small>',esc(c.Image),esc(c.Status),Object.entries(labels).map(([action,label])=>`<button data-node="${c.node.id}" data-id="${c.Id}" data-action="${action}">${label}</button>`).join('')])));
  $('#filter').oninput=e=>el.querySelectorAll('tbody tr').forEach(r=>r.hidden=!r.textContent.toLowerCase().includes(e.target.value.toLowerCase()));
  el.querySelectorAll('[data-action]').forEach(b=>b.onclick=async()=>{ const action=b.dataset.action; try { const path=`/nodes/${b.dataset.node}/containers/${b.dataset.id}/${action}`; if(['start','stop','restart'].includes(action)){ if(!confirm(`${labels[action]} container ${b.dataset.id.slice(0,12)}?`))return; await post(path,{}); await load(); } else show(await api(path)); } catch(error) { notice(error); } });
}
load().catch(error=>{ if(error.message!=='Please sign in.')notice(error); });
