import { execFileSync } from 'node:child_process';
import { copyFileSync, mkdirSync } from 'node:fs';
for (const project of ['tsconfig.json', 'tsconfig.web.json'])
  execFileSync(process.execPath, ['node_modules/typescript/bin/tsc', '-p', project], { stdio: 'inherit' });
mkdirSync('dist/public', { recursive: true });
for (const file of ['index.html', 'style.css']) copyFileSync(`web/${file}`, `dist/public/${file}`);
copyFileSync('dist/client/web/main.js', 'dist/public/app.js');
