import {getStoredAuthSession} from '../../services/api';
import {workspaceHeaders} from './workspaceContext';
import {sha256} from '@noble/hashes/sha256';

const mime=path=>({'js':'text/javascript','css':'text/css','json':'application/json','csv':'text/csv','txt':'text/plain','png':'image/png','jpg':'image/jpeg','jpeg':'image/jpeg','svg':'image/svg+xml','webp':'image/webp','woff':'font/woff','woff2':'font/woff2','html':'text/html'}[path.split('.').pop().toLowerCase()]||'application/octet-stream');
const policy="default-src 'none'; script-src 'unsafe-inline' data:; style-src 'unsafe-inline'; img-src data:; font-src data:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'";
export async function loadHtmlReport(descriptor,signal){
  if(!/^[A-Za-z0-9_-]{1,100}$/.test(descriptor?.entryFileId||''))throw new Error('报告入口无效');
  const auth=getStoredAuthSession();
  const response=await fetch(`/api/v1/interactions/user-assets/${encodeURIComponent(descriptor.entryFileId)}/report-bundle`,{signal,headers:{...(auth?.token?{Authorization:`Bearer ${auth.token}`} : {}),...workspaceHeaders()}});
  if(!response.ok)throw new Error(response.status===401?'登录已过期，请重新登录':'无法读取已授权的报告资源');
  const envelope=await response.json();if(envelope.code!==200)throw new Error('报告资源读取失败');
  const bundle=envelope.data;
  if(['entryFileId','taskId','runId','entryPath'].some(key=>bundle.report?.[key]!==descriptor[key]))throw new Error('报告任务绑定不一致');
  return assembleHtmlReport(bundle);
}
export async function assembleHtmlReport(bundle){
  if(!Array.isArray(bundle.files)||bundle.files.length>64)throw new Error('报告资源过多');
  const files=new Map();let total=0;
  for(const file of bundle.files){
    if(!file.path||file.path.startsWith('/')||file.path.includes('\\')||file.path.split('/').some(p=>!p||p==='.'||p==='..')||files.has(file.path))throw new Error('报告资源路径无效');
    const bytes=Uint8Array.from(atob(file.content),c=>c.charCodeAt(0));total+=bytes.length;
    if(bytes.length!==file.size||bytes.length>8*1024*1024||total>24*1024*1024)throw new Error('报告资源大小校验失败');
    const digest=globalThis.crypto?.subtle?new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)):sha256(bytes);
    const hash=Array.from(digest,x=>x.toString(16).padStart(2,'0')).join('');
    if(hash!==file.sha256)throw new Error('报告资源完整性校验失败');
    files.set(file.path,{...file,text:new TextDecoder().decode(bytes),type:mime(file.path)});
  }
  const entry=files.get(bundle.report.entryPath);if(!entry||entry.type!=='text/html')throw new Error('缺少 HTML 报告入口');
  const base='https://report.invalid/'+bundle.report.entryPath;
  function resource(ref,from=base){const url=new URL(ref,from);if(url.origin!=='https://report.invalid'||url.search||url.hash)throw new Error('报告包含未交付的外部资源');const file=files.get(decodeURIComponent(url.pathname.slice(1)));if(!file)throw new Error('报告依赖文件缺失：'+ref);return file;}
  const data=file=>`data:${file.type};base64,${file.content}`;
  // These bytes are already covered by the delivered HTML's verified SHA-256.
  // Treat supported embedded images as local content, never as network URLs.
  function embeddedImage(ref){
    if(!/^data:/i.test(ref))return null;
    const match=/^data:(image\/(?:png|jpeg|webp|svg\+xml));base64,([A-Za-z0-9+/]*={0,2})$/i.exec(ref);
    if(!match)throw new Error('报告内嵌图片格式无效');
    let bytes;try{bytes=atob(match[2]);}catch{throw new Error('报告内嵌图片编码无效');}
    if(!bytes.length||bytes.length>8*1024*1024)throw new Error('报告内嵌图片大小校验失败');
    return `data:${match[1].toLowerCase()};base64,${match[2]}`;
  }
  function css(text,from){if(/@import\b/i.test(text))throw new Error('请将样式依赖打包后交付');return text.replace(/url\(\s*(['"]?)(.*?)\1\s*\)/gi,(_,quote,ref)=>(/^(?:#|data:(?:image\/|font\/))/i.test(ref))?`url(${ref})`:`url("${data(resource(ref,from))}")`);}
  const doc=new DOMParser().parseFromString(entry.text,'text/html');
  doc.querySelectorAll('base,meta[http-equiv],iframe,frame,object,embed,form').forEach(el=>el.remove());
  for(const el of doc.querySelectorAll('link')){
    if(el.rel!=='stylesheet'){el.remove();continue;}
    const file=resource(el.getAttribute('href'));if(file.type!=='text/css')throw new Error('样式资源类型不匹配');
    const style=doc.createElement('style');style.textContent=css(file.text,'https://report.invalid/'+file.path);el.replaceWith(style);
  }
  for(const el of doc.querySelectorAll('style'))el.textContent=css(el.textContent,base);
  for(const el of doc.querySelectorAll('[style]'))el.setAttribute('style',css(el.getAttribute('style'),base));
  for(const el of doc.querySelectorAll('script')){
    if(el.type==='module')throw new Error('请将模块脚本打包为普通 JS 后交付');
    if(el.hasAttribute('src')){const file=resource(el.getAttribute('src'));if(file.type!=='text/javascript')throw new Error('脚本资源类型不匹配');el.setAttribute('src',data(file));el.removeAttribute('integrity');}
  }
  for(const el of doc.querySelectorAll('[src]:not(script)')){const ref=el.getAttribute('src');el.setAttribute('src',(el.tagName==='IMG'&&embeddedImage(ref))||data(resource(ref)));el.removeAttribute('srcset');}
  doc.querySelectorAll('[srcset]').forEach(el=>el.removeAttribute('srcset'));
  doc.querySelectorAll('a[href],area[href]').forEach(el=>{if(!el.getAttribute('href').startsWith('#'))el.removeAttribute('href');el.removeAttribute('target');el.removeAttribute('ping');});
  const virtual=Object.fromEntries([...files].map(([path,file])=>[path,{content:file.content,type:file.type}]));
  const encoded=JSON.stringify(virtual).replace(/</g,'\\u003c');
  const bootstrap=`(()=>{const files=${encoded},base=${JSON.stringify(base)};window.fetch=async(input,options={})=>{const url=new URL(typeof input==='string'?input:input.url,base);const file=files[decodeURIComponent(url.pathname.slice(1))];if(url.origin!=='https://report.invalid'||url.search||!file||(options.method&&options.method!=='GET'))throw Error('Resource is not part of this report');return new Response(Uint8Array.from(atob(file.content),c=>c.charCodeAt(0)),{headers:{'Content-Type':file.type}});};window.addEventListener('error',()=>{document.documentElement.dataset.reportError='true';parent.postMessage({type:'runtime-html-report-error'},'*');});window.addEventListener('unhandledrejection',()=>{document.documentElement.dataset.reportError='true';parent.postMessage({type:'runtime-html-report-error'},'*');});})();`;
  const meta=doc.createElement('meta');meta.httpEquiv='Content-Security-Policy';meta.content=policy;
  const script=doc.createElement('script');script.textContent=bootstrap;
  doc.head.prepend(meta,script);
  const sizing=doc.createElement('script');
  sizing.textContent=`(()=>{let last=0,pending=false;const measure=()=>{pending=false;const body=document.body;if(!body)return;const style=getComputedStyle(body);const height=Math.ceil(Math.max(body.scrollHeight,body.getBoundingClientRect().height)+(parseFloat(style.marginTop)||0)+(parseFloat(style.marginBottom)||0));if(height>0&&height!==last){last=height;parent.postMessage({type:'runtime-html-report-size',height},'*');}};const queue=()=>{if(!pending){pending=true;requestAnimationFrame(measure);}};const start=()=>{if(typeof ResizeObserver!=='undefined')new ResizeObserver(queue).observe(document.body);queue();document.fonts?.ready.then(queue);};if(document.readyState==='loading')addEventListener('DOMContentLoaded',start,{once:true});else start();addEventListener('load',queue);addEventListener('resize',queue);})();`;
  doc.body.append(sizing);
  return '<!doctype html>\n'+doc.documentElement.outerHTML;
}
