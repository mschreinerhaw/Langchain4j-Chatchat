import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const CLINE_VERSION = '0.0.93';
export const CLINE_PACKAGES = Object.freeze(['@cline/sdk', '@cline/core', '@cline/agents', '@cline/llms', '@cline/shared']);

/** Verify deployed modules without creating sessions, registering tools or starting the hub. */
export async function verifyClinePackages() {
  const versions = {};
  for (const name of CLINE_PACKAGES) {
    const entry = import.meta.resolve(name);
    let directory = dirname(fileURLToPath(entry)), metadata;
    for (let depth = 0; depth < 8; depth++) {
      try {
        const candidate = JSON.parse(readFileSync(join(directory, 'package.json'), 'utf8'));
        if (candidate.name === name) { metadata = candidate; break; }
      } catch (error) { if (error.code !== 'ENOENT') throw error; }
      const parent = dirname(directory); if (parent === directory) break; directory = parent;
    }
    if (metadata?.version !== CLINE_VERSION) throw new Error(`CLINE_PACKAGE_VERSION_MISMATCH: ${name}`);
    await import(name);
    versions[name] = metadata.version;
  }
  return Object.freeze(versions);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href)
  console.log(JSON.stringify({ status: 'VERIFIED', packages: await verifyClinePackages() }));
