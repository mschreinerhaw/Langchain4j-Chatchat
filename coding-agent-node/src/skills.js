import { readFileSync, realpathSync, lstatSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { parseSkillConfigFromMarkdown } from '@cline/core';

// Deliberately scoped to the image package: never scan a shared HOME or another
// Agent's workspace. Bare @cline/agents does not discover filesystem Skills.
const root = new URL('../skills/', import.meta.url);
const digest = text => createHash('sha256').update(text).digest('hex');
const definitions = JSON.parse(readFileSync(new URL('catalog.json', root), 'utf8'));
const packageCache = new Map();
const disclosureCache = new WeakMap();
function readPackageFile(folder, path, expected) {
  // Manifest paths are opaque, relative POSIX names. Never follow symlinks or
  // interpret a resource path as a URL (queries, escaping and schemes included).
  if (typeof path !== 'string' || !path.split('/').every(part => /^[A-Za-z0-9_][A-Za-z0-9_.-]*$/.test(part)))
    throw new TypeError('Invalid bundled resource path');
  let current = folder;
  const segments=path.split('/');
  for (const [index,segment] of segments.entries()) {
    current = new URL(segment + (index === segments.length-1 ? '' : '/'), current);
    if (lstatSync(current).isSymbolicLink()) throw new TypeError('Bundled Skill symlinks are forbidden');
  }
  const target = new URL(path, folder);
  if (!realpathSync(target).startsWith(realpathSync(folder) + (process.platform === 'win32' ? '\\' : '/')))
    throw new TypeError('Bundled resource escapes package');
  const stat=lstatSync(target),key=target.href;
  const signature=`${stat.size}:${stat.mtimeMs}:${stat.ctimeMs}:${expected?.sha256??''}`;
  const cached=packageCache.get(key);
  if(cached?.signature===signature)return cached.bytes;
  const bytes=readFileSync(target);
  if(bytes.length>2*1024*1024 || expected && (Number.isSafeInteger(expected.size)&&bytes.length!==expected.size
      || expected.sha256&&digest(bytes)!==expected.sha256))
    throw new TypeError('Bundled Skill resource integrity mismatch');
  packageCache.set(key,{signature,bytes});
  return bytes;
}
const builtin = definitions.map(definition => {
  if(!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(definition.name))throw new Error('Invalid bundled Skill name');
  const folder = new URL(`${definition.name}/`, root);
  const resources=(definition.resources??definition.references.map(path=>({path}))).map(resource=>({...resource}));
  return {card:Object.freeze({...definition,references:undefined,resources:undefined,
    ...(definition.provenance?.sha256?{contentSha256:definition.provenance.sha256}:{}),
    selectionOwner:'CLINE',contentDelivery:'ON_DEMAND'}),resources,folder,
    filePath:fileURLToPath(new URL('SKILL.md',folder))};
});
function disclose(item) {
  const definition=item.card;
  const bytes=readPackageFile(item.folder, 'SKILL.md',definition.nativeOriginal?definition.provenance:undefined);
  const cached=disclosureCache.get(bytes);
  if(cached)return {...cached};
  const source = bytes.toString('utf8');
  const parsed = parseSkillConfigFromMarkdown(source, definition.name);
  if (parsed.name !== definition.name || parsed.description !== definition.description
      || (!definition.nativeOriginal && parsed.frontmatter.version !== definition.version) || !parsed.instructions.trim()
      || definition.nativeOriginal && digest(source)!==definition.provenance?.sha256)
    throw new Error(`Invalid bundled Skill: ${definition.name}`);
  const result={instructions:definition.nativeOriginal?source:parsed.instructions,contentSha256:digest(source)};
  disclosureCache.set(bytes,result);
  return {...result};
}

export function taskSkills(context = {}) {
  // Historical Agent/snapshot disable fields are intentionally ignored. Only the
  // platform image catalog owns availability; Cline still chooses content on demand.
  const dynamic = structuredClone(context.skills?.skills ?? []).map(card => ({ ...card, id: card.id ?? card.skillId }));
  const reserved = new Set(builtin.flatMap(item => [item.card.id.toLowerCase(), item.card.name.toLowerCase()]));
  const conflicts = dynamic.filter(card => [card.id, card.name].some(value => reserved.has(String(value).trim().toLowerCase())))
    .map(card => ({ card, reason: 'BUILTIN_NAME_RESERVED' }));
  const excluded = new Set(conflicts.map(conflict => conflict.card));
  const dynamicNames = new Map(), dynamicIds = new Set(), uniqueDynamic = [];
  for (const card of dynamic) {
    if (!card.id || dynamicIds.has(card.id)) throw new TypeError('Duplicate task Skill ID');
    dynamicIds.add(card.id);
    if (excluded.has(card)) continue;
    const name = String(card.name ?? '').trim().toLowerCase();
    const retained = name && dynamicNames.get(name);
    if (retained) conflicts.push({ card, reason: 'DYNAMIC_NAME_DUPLICATE',
      retainedSkillId: retained.id, retainedVersion: retained.version });
    else { uniqueDynamic.push(card); if (name) dynamicNames.set(name, card); }
  }
  // Image defaults win for every version; business bindings cannot replace them.
  // Report collisions explicitly; never let a dynamic package replace a default.
  const cards = [...builtin.filter(item => item.card.enabled !== false).map(item => item.card),
    ...uniqueDynamic.map(card => ({ ...card, origin: 'AGENT_DYNAMIC' }))];
  const ids = new Set();
  for (const card of cards) {
    if (!card.id || ids.has(card.id)) throw new TypeError('Duplicate task Skill ID');
    ids.add(card.id);
  }
  const catalog = { status: cards.length ? 'AVAILABLE' : 'NOT_CONFIGURED', skills: cards,
    conflicts: conflicts.map(({ card, reason, ...retained }) => ({ id: card.id, name: card.name, version: card.version, reason, ...retained })),
    fingerprint: digest(JSON.stringify(cards)) };
  return { catalog,
    readBuiltin(id, path = '') {
      const item = builtin.find(item => item.card.id === id && cards.some(card => card.id === id));
      if (!item) return null;
      if (!path || path === 'SKILL.md') return { skillId: id, version: item.card.version,
        ...(item.card.nativeOriginal ? {nativeOriginal:true} : {}),
        ...disclose(item),
        resources: item.resources.map(({ path, sha256 }) => ({ path, sha256 })), authority: 'METHODOLOGY_ONLY' };
      const resource = item.resources.find(resource => resource.path === path);
      if (!resource) throw new TypeError('Skill reference is outside the task manifest');
      const bytes=readPackageFile(item.folder,path,item.card.nativeOriginal?resource:undefined);
      const text=bytes.toString('utf8'),binary=!Buffer.from(text,'utf8').equals(bytes)||text.includes('\0');
      return { skillId: id, version: item.card.version, ...resource,sha256:digest(bytes),size:bytes.length,
        content:binary?bytes.toString('base64'):text,encoding:binary?'base64':'utf-8',authority: 'METHODOLOGY_ONLY' };
    } };
}

export const bundledSkillFiles = () => builtin.map(item => ({ ...item.card, filePath: item.filePath }));

/** Validate with the installed Cline parser; preserve the original disclosure byte for byte. */
export function validateNativeDisclosure(result, card) {
  if (!result?.nativeOriginal || typeof result.instructions !== 'string') return result;
  if (digest(result.instructions) !== result.contentSha256) throw new TypeError('Native Skill content hash mismatch');
  const parsed = parseSkillConfigFromMarkdown(result.instructions, card.name);
  if (parsed.name !== card.name || !parsed.description?.trim() || !parsed.instructions?.trim())
    throw new TypeError('Native Skill cannot be loaded by this Cline version');
  return result;
}
