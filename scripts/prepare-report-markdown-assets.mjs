// Preserve the deployed runtime and views; update only the visual report renderer.
import {readFileSync,writeFileSync,readdirSync,mkdirSync} from 'node:fs';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';

const [baseline,build,output]=process.argv.slice(2);
assert(baseline&&build&&output);
const find=(dir,pattern)=>{const names=readdirSync(dir).filter(n=>pattern.test(n));assert.equal(names.length,1);return names[0];};
const oldJs=find(baseline,/^PythonVisualReport-.*\.js$/),oldCss=find(baseline,/^PythonVisualReport-.*\.css$/);
const newJs=find(build,/^PythonVisualReport-.*\.js$/),newCss=find(build,/^PythonVisualReport-.*\.css$/);
const oldCode=readFileSync(join(baseline,oldJs),'utf8');let code=readFileSync(join(build,newJs),'utf8');
const imports=s=>[...s.matchAll(/import\{([^}]+)\}from"\.\/([^"]+)";/g)];
const previous=imports(oldCode),current=imports(code);
assert.equal(previous.length,1);assert.equal(current.length,2);
const slots=s=>s.split(',').map(name=>name.split(' as ')[0]);
assert.deepEqual(slots(previous[0][1]),slots(current[0][1]));
code=code.replace(current[0][0],current[0][0].replace(current[0][2],previous[0][2]));
assert.deepEqual(slots(current[1][1]),['M']);
const markdown=find(baseline,/^markdown-it-.*\.js$/);
assert.match(readFileSync(join(baseline,markdown),'utf8'),/export\{\w+ as M\}/);
code=code.replace(current[1][0],current[1][0].replace(current[1][2],markdown));
const nextJs='PythonVisualReport-markdown-20261024.js',nextCss='PythonVisualReport-markdown-20261024.css';
const assets=join(output,'assets');mkdirSync(assets,{recursive:true});
writeFileSync(join(assets,nextJs),code);writeFileSync(join(assets,nextCss),readFileSync(join(build,newCss)));
const sha=s=>createHash('sha256').update(s).digest('hex'),referrers={};
for(const name of readdirSync(baseline).filter(n=>n.endsWith('.js'))){
 const before=readFileSync(join(baseline,name),'utf8');const after=before.replaceAll(oldJs,nextJs).replaceAll(oldCss,nextCss);
 if(before!==after){writeFileSync(join(assets,name),after);referrers[name]=sha(before);}
}
assert.ok(Object.keys(referrers).length);
const hashes=Object.fromEntries(readdirSync(assets).map(n=>[n,sha(readFileSync(join(assets,n)))]));
writeFileSync(join(output,'asset-manifest.json'),JSON.stringify({oldJs,oldCss,nextJs,nextCss,referrers,hashes,runtimeSlotsVerified:true,preservedOtherViews:true},null,2));
console.log(JSON.stringify({nextJs,nextCss,referrers:Object.keys(referrers),assetCount:Object.keys(hashes).length}));
