const fs = require('fs');
const path = require('path');

const projectRoot = path.resolve(__dirname, '..');
const source = path.resolve(projectRoot, 'dist');
const target = path.resolve(projectRoot, '../src/main/resources/static/admin');
const expectedTarget = path.resolve(projectRoot, '../src/main/resources/static/admin');

if (target !== expectedTarget || !fs.existsSync(path.join(source, 'index.html'))) {
  throw new Error(`Refusing to sync MCP admin assets: source=${source}, target=${target}`);
}

fs.rmSync(target, { recursive: true, force: true });
fs.mkdirSync(path.dirname(target), { recursive: true });
fs.cpSync(source, target, { recursive: true });

console.log(`Synced MCP admin assets to ${target}`);
