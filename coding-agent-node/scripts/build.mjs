import { execFileSync } from 'node:child_process';
import { copyFileSync, mkdirSync, rmSync } from 'node:fs';
execFileSync(process.execPath,['scripts/generate-tool-routing.mjs','--check'],{stdio:'inherit'});
// Retired custom loaders must not survive an incremental TypeScript build.
for (const name of ['skills', 'skill-disclosure']) for (const extension of ['js', 'd.ts'])
  rmSync(`dist/${name}.${extension}`, { force: true });
for (const project of ['tsconfig.json', 'tsconfig.web.json'])
  execFileSync(process.execPath, ['node_modules/typescript/bin/tsc', '-p', project], { stdio: 'inherit' });
mkdirSync('dist/public', { recursive: true });
for (const file of ['index.html', 'style.css']) copyFileSync(`web/${file}`, `dist/public/${file}`);
copyFileSync('dist/client/web/main.js', 'dist/public/app.js');
copyFileSync('dist/client/web/python-diagnostics.js', 'dist/public/python-diagnostics.js');
