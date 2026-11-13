// @vitest-environment jsdom
import {it,expect} from 'vitest';
import {webcrypto} from 'node:crypto';
import {assembleHtmlReport} from './htmlReport';
Object.defineProperty(globalThis,'crypto',{value:webcrypto,configurable:true});
async function bundle(html,extras={}){
  const sources={'output/site/report.html':html,...extras};const files=[];
  for(const [path,text] of Object.entries(sources)){const data=new TextEncoder().encode(text);const hash=Buffer.from(await webcrypto.subtle.digest('SHA-256',data)).toString('hex');files.push({path,size:data.length,sha256:hash,content:Buffer.from(data).toString('base64')});}
  return {report:{entryPath:'output/site/report.html'},files};
}
it('embeds actual local script, stylesheet, image and fetch resources with restrictive CSP',async()=>{
 const value=await assembleHtmlReport(await bundle('<link rel="stylesheet" href="css/app.css"><script defer src="app.js"></script><img src="plot.svg">',{'output/site/css/app.css':'body{background:url(../plot.svg)}','output/site/app.js':'fetch("data.json")','output/site/data.json':'{"value":12}','output/site/plot.svg':'<svg xmlns="http://www.w3.org/2000/svg"/>'}));
 expect(value).toContain("connect-src 'none'");expect(value).toContain('data:text/javascript;base64,');expect(value).toContain('data:image/svg+xml;base64,');expect(value).toContain('window.fetch=');expect(value).not.toContain('Bearer');
});
it('rejects missing resources, external URLs, modules and corrupted bytes',async()=>{
 for(const html of ['<script src="missing.js"></script>','<img src="https://example.org/x.png">','<script type="module">import x from "x"</script>'])await expect(assembleHtmlReport(await bundle(html))).rejects.toThrow();
 const bad=await bundle('<h1>Report</h1>');bad.files[0].sha256='0'.repeat(64);await expect(assembleHtmlReport(bad)).rejects.toThrow();
});
it('removes redirects, embedded browsing contexts and navigational links',async()=>{
 const html=await assembleHtmlReport(await bundle('<meta http-equiv="refresh" content="0;url=https://example.org"><iframe src="https://example.org"></iframe><a href="https://example.org" ping="https://example.org">Source</a>'));
 const doc=new DOMParser().parseFromString(html,'text/html');expect(doc.querySelector('iframe')).toBeNull();expect(doc.querySelector('a').hasAttribute('href')).toBe(false);expect(doc.querySelector('meta[http-equiv="refresh"]')).toBeNull();
});
it('still verifies hashes on HTTP where Web Crypto is unavailable',async()=>{
 Object.defineProperty(globalThis,'crypto',{value:{},configurable:true});
 try{const value=await bundle('<h1>HTTP report</h1>');expect(await assembleHtmlReport(value)).toContain('HTTP report');value.files[0].sha256='0'.repeat(64);await expect(assembleHtmlReport(value)).rejects.toThrow();}
 finally{Object.defineProperty(globalThis,'crypto',{value:webcrypto,configurable:true});}
});
it('renders embedded PNGs covered by the delivered HTML hash without separate files',async()=>{
 const png='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6PQAAAABJRU5ErkJggg==';
 const result=await assembleHtmlReport(await bundle(`<h1>Delivered report</h1><img src="${png}">`));
 const doc=new DOMParser().parseFromString(result,'text/html');expect(doc.querySelector('img').getAttribute('src')).toBe(png);
 expect(result).toContain("connect-src 'none'");
 const changed=await bundle(`<img src="${png}">`);changed.files[0].sha256='0'.repeat(64);await expect(assembleHtmlReport(changed)).rejects.toThrow('完整性');
});
it('rejects non-image data URLs and malformed embedded images',async()=>{
 for(const ref of ['data:text/html;base64,PHNjcmlwdD4=','data:image/png;base64,@@@@','data:image/png;base64,'])await expect(assembleHtmlReport(await bundle(`<img src="${ref}">`))).rejects.toThrow();
 await expect(assembleHtmlReport(await bundle('<script src="data:text/javascript;base64,YWxlcnQoMSk="></script>'))).rejects.toThrow();
});
