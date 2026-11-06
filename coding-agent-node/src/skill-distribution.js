import { readFileSync, realpathSync, lstatSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

// Runtime owns this delivery manifest, not Cline's discovery or Markdown loader.
const root = new URL('../skills/', import.meta.url);
const definitions = JSON.parse(readFileSync(new URL('catalog.json', root), 'utf8'));
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
export function safeResourcePath(path) {
  if (typeof path !== 'string' || path.length > 512
      || !path.split('/').every(part => /^[A-Za-z0-9_][A-Za-z0-9_.-]*$/.test(part)))
    throw new TypeError('Skill resource is outside the delivery manifest');
  return path;
}
export function taskSkillPackages(context = {}) {
  const packages = definitions.filter(c => c.enabled !== false).map(card => ({ card: structuredClone(card), builtin: true,
    resources: structuredClone(card.resources ?? (card.references ?? []).map(path => ({ path }))),
    filePath: fileURLToPath(new URL(`${card.name}/SKILL.md`, root)) }));
  const ids = new Set(packages.map(p => p.card.id));
  for (const supplied of context.skills?.skills ?? []) {
    const card = { ...supplied, id: supplied.id ?? supplied.skillId };
    if (!card.id || ids.has(card.id)) throw new TypeError('Duplicate authorized Skill identity');
    ids.add(card.id); packages.push({ card, builtin: false });
  }
  return packages;
}
export function imageSkillBytes(pkg, path = 'SKILL.md') {
  safeResourcePath(path);
  const folder = new URL(`${pkg.card.name}/`, root);
  if (lstatSync(folder).isSymbolicLink()) throw new TypeError('Skill package symlinks are forbidden');
  const manifest = path === 'SKILL.md' ? pkg.card.nativeOriginal ? pkg.card.provenance : undefined
    : pkg.resources.find(r => r.path === path);
  if (path !== 'SKILL.md' && !manifest) throw new TypeError('Skill resource is outside the delivery manifest');
  let current = folder;
  for (const part of path.split('/')) {
    current = new URL(part + '/', current);
    // A URL's trailing slash is irrelevant to lstat on files on supported hosts.
    if (lstatSync(fileURLToPath(current).replace(/[\\/]$/, '')).isSymbolicLink())
      throw new TypeError('Skill delivery symlinks are forbidden');
  }
  const target = new URL(path, folder), base = realpathSync(folder);
  if (!realpathSync(target).startsWith(base + (process.platform === 'win32' ? '\\' : '/')))
    throw new TypeError('Skill resource escapes its package');
  const bytes = readFileSync(target);
  if (bytes.length > 2 * 1024 * 1024 || manifest?.sha256 && sha256(bytes) !== manifest.sha256
      || Number.isSafeInteger(manifest?.size) && bytes.length !== manifest.size)
    throw new TypeError('Skill resource integrity mismatch');
  return bytes;
}
export const bundledSkillFiles = () => taskSkillPackages().map(p => ({ ...p.card, filePath: p.filePath }));
