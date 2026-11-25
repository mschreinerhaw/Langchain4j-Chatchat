const fs = require('node:fs');
const runtime = process.argv[2] ?? '/opt/cline/runtime';
const manifest = JSON.parse(fs.readFileSync(runtime + '/runtime-manifest.json', 'utf8'));
const source = fs.readFileSync(runtime + '/cline-core.js', 'utf8');
const blocks = source.split('\n').filter(line => /version: \d+, providers: \{/.test(line) && line.includes('id: "qwen3.6-35b-a3b"'));
if (blocks.length !== 1) throw new Error('Cannot identify the embedded Cline catalog');
const modelIds = [...new Set(Array.from(blocks[0].matchAll(/\bid: "([^"\\]+)", name:/g), match => match[1]))].sort();
if (modelIds.length < 100) throw new Error('Incomplete Cline catalog');
process.stdout.write(JSON.stringify({ version: manifest.upstream, sourceCommit: manifest.sourceCommit, modelIds }, null, 2) + '\n');
