/** Verify reviewed upstream bytes only; never install, rewrite or execute upstream resources. */
import fs from 'node:fs';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
const audit = JSON.parse(fs.readFileSync(new URL('../../docs/sql-chain-revalidation/microsoft-sql-audit.json', import.meta.url)));
assert.equal(audit.repository, 'https://github.com/microsoft/microsoft-sql');
assert.match(audit.commit, /^[a-f0-9]{40}$/);
for (const file of audit.files) {
  const url = 'https://api.github.com/repos/microsoft/microsoft-sql/contents/' + file.path + '?ref=' + audit.commit;
  const response = await fetch(url, { headers: { 'User-Agent': 'RuntimeOS-source-audit' }, signal: AbortSignal.timeout(15000) });
  assert.ok(response.ok, 'Upstream source unavailable: ' + response.status);
  const content = await response.json();
  assert.equal(content.encoding, 'base64');
  const bytes = Buffer.from(content.content, 'base64');
  assert.equal(bytes.length, file.bytes);
  assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'), file.sha256, file.path);
}
console.log(JSON.stringify({ status: 'PINNED_SOURCE_HASHES_MATCH', commit: audit.commit, files: audit.files.length, installed: false }));
