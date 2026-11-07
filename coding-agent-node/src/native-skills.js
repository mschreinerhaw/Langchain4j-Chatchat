import { createUserInstructionConfigService, loadAgentPluginPackages, AGENT_PLUGINS_V1_MANIFEST_SCHEMA,
  createDefaultExecutors, createDefaultTools } from '@cline/core';
import { mkdir, writeFile, readFile, realpath, lstat, rm } from 'node:fs/promises';
import { resolve, join, dirname, relative, isAbsolute } from 'node:path';
import { tmpdir } from 'node:os';
import { taskSkillPackages, imageSkillBytes, safeResourcePath, sha256 } from './skill-distribution.js';

/** Delivery and access control only. Discovery, parsing, invocation and reads are SDK-owned. */
export async function prepareNativeSkills(contract, gateway, { sessionId, signal, observe } = {}) {
  const base = resolve(tmpdir(), 'runtime-cline-authorized-skills');
  const root = join(base, sha256(JSON.stringify([contract.scope.tenantId, contract.scope.userId,
    contract.scope.runId, contract.taskId, sessionId])));
  const files = new Map(), packages = [], loaded = new Set();
  let service;
  const within = path => { const r = relative(root, path); return r && !r.startsWith('..') && !isAbsolute(r); };
  const removeWorkspace = async () => {
    if (dirname(root) !== base || await realpath(base) !== base) throw new Error('Invalid Skill cleanup root');
    let stat;try { stat = await lstat(root); } catch (error) { if (error.code === 'ENOENT') return;throw error; }
    if (stat.isSymbolicLink()) { await rm(root, { force: true });return; }
    if (await realpath(root) !== root) throw new Error('Skill cleanup path was redirected');
    await rm(root, { recursive: true, force: true });
  };
  const checkSignal = () => { if (signal?.aborted) throw signal.reason; };
  const stage = async (pkg, folder, path, bytes, expected) => {
    safeResourcePath(path);checkSignal();
    if (expected && sha256(bytes) !== expected) throw new TypeError('Skill delivery hash mismatch');
    const target = join(folder, ...path.split('/'));
    if (!within(target)) throw new TypeError('Skill delivery escapes task workspace');
    if (files.has(resolve(target))) throw new TypeError('Duplicate Skill delivery path');
    // Existing workspace paths must not redirect a resumed task through a symlink.
    for (let p = dirname(target); p !== base; p = dirname(p)) {
      try { if ((await lstat(p)).isSymbolicLink()) throw new TypeError('Skill workspace symlinks are forbidden'); }
      catch (error) { if (error.code !== 'ENOENT') throw error; }
    }
    await mkdir(dirname(target), { recursive: true, mode: 0o700 });
    try { if ((await lstat(target)).isSymbolicLink()) throw new TypeError('Skill resource symlink is forbidden'); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    await writeFile(target, bytes, { mode: 0o600 });
    files.set(resolve(target), { pkg, path, sha256: sha256(bytes), size: bytes.length });
  };
  const dynamicRead = async (pkg, path = '') => {
    checkSignal();
    const result = await gateway.readSkill(contract, pkg.card.id, path, signal);
    if (result.skillId !== pkg.card.id || result.version !== pkg.card.version)
      throw new TypeError('Skill version differs from the authorized delivery');
    return result;
  };
  const authorize = async entry => {
    checkSignal();
    if (!entry || !within(entry.target) || await realpath(entry.target) !== entry.target)
      throw new TypeError('File is outside the authorized Skill resource manifest');
    if (!entry.pkg.builtin) {
      const current = await dynamicRead(entry.pkg, entry.path === 'SKILL.md' ? '' : entry.path);
      const digest = entry.path === 'SKILL.md' ? current.contentSha256 : current.sha256;
      if (digest !== entry.pkg.sourceHashes.get(entry.path)) throw new TypeError('Skill source changed or was revoked');
    }
    const bytes = await readFile(entry.target);
    if (sha256(bytes) !== entry.sha256) throw new TypeError('Skill workspace integrity mismatch');
  };
  const reportRead = async (entry, reference, output) => {
    const encoded = typeof output === 'string' ? output : JSON.stringify(output);
    const key = sha256(entry.target + '\0' + encoded);
    await observe?.({ type: 'SKILLS_LOADED', stage: 'CODING', skillId: entry.pkg.card.id,
      skillVersion: entry.pkg.card.version, skillPath: reference ? entry.path : 'SKILL.md',
      skillContentHash: entry.sha256, skillReference: reference, skillSourceBytes: entry.size,
      skillContentBytes: Buffer.byteLength(encoded), skillEstimatedTokens: Math.ceil(Buffer.byteLength(encoded) / 4),
      duplicateRead: loaded.has(key), contentReused: false, nativeSkills: true });
    if (loaded.size < 4096) loaded.add(key);
  };
  try {
    await mkdir(base, { recursive: true, mode: 0o700 });
    if (await realpath(base) !== base || (await lstat(base)).isSymbolicLink())
      throw new TypeError('Skill workspace base is not an isolated directory');
    await mkdir(root, { recursive: true, mode: 0o700 });
    if (await realpath(root) !== root || (await lstat(root)).isSymbolicLink())
      throw new TypeError('Skill workspace root is not an isolated directory');
    for (const pkg of taskSkillPackages(contract.runtimeContext)) {
      checkSignal();
      const pluginName = pkg.builtin ? 'runtime-image' : 'runtime-agent-' + sha256(pkg.card.id).slice(0, 16);
      const pluginRoot = join(root, pluginName);
      await mkdir(pluginRoot, { recursive: true, mode: 0o700 });
      let source, resources;
      pkg.sourceHashes = new Map();
      if (pkg.builtin) { source = imageSkillBytes(pkg);resources = pkg.resources; }
      else {
        const delivered = await dynamicRead(pkg);
        if (sha256(delivered.instructions) !== delivered.contentSha256) throw new TypeError('Skill source hash mismatch');
        pkg.sourceHashes.set('SKILL.md', delivered.contentSha256);
        pkg.nativeOriginal = delivered.nativeOriginal === true;
        source = Buffer.from(delivered.instructions);
        resources = delivered.resources ?? [];
      }
      const nativeOriginal = pkg.builtin || pkg.nativeOriginal;
      const name = nativeOriginal ? pkg.card.name : 'method-' + sha256(pkg.card.id).slice(0, 16);
      if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(name)) throw new TypeError('Invalid authorized Agent Skill name');
      const folder = join(pluginRoot, 'skills', name);
      // Legacy Runtime methodologies have no SKILL.md file. The distribution envelope
      // supplies standard metadata; the legacy instruction body remains byte-exact.
      const skill = nativeOriginal ? source : Buffer.concat([Buffer.from('---\nname: ' + name
        + '\ndescription: ' + JSON.stringify(pkg.card.description || pkg.card.name || name)
        + '\nmetadata:\n  version: ' + JSON.stringify(pkg.card.version) + '\n---\n'), source]);
      await stage(pkg, folder, 'SKILL.md', skill);
      for (const resource of resources) {
        let bytes;
        if (pkg.builtin) bytes = imageSkillBytes(pkg, resource.path);
        else {
          const item = await dynamicRead(pkg, safeResourcePath(resource.path));
          bytes = Buffer.from(item.content, item.encoding === 'base64' ? 'base64' : 'utf8');
          pkg.sourceHashes.set(resource.path, resource.sha256);
        }
        await stage(pkg, folder, resource.path, bytes, resource.sha256);
      }
      await writeFile(join(pluginRoot, 'plugin.json'), JSON.stringify({ $schema: AGENT_PLUGINS_V1_MANIFEST_SCHEMA,
        name: pluginName, version: '1.0.0' }), { mode: 0o600 });
      packages.push(pluginRoot);
    }
    const discovery = await loadAgentPluginPackages({ pluginPaths: [...new Set(packages)], searchPaths: [], cwd: root });
    if (discovery.diagnostics.some(d => d.level === 'error') || discovery.mcpServers.length)
      throw new TypeError('Native Skill discovery rejected authorized package: ' + discovery.diagnostics.map(d => d.message).join('; '));
    service = createUserInstructionConfigService({ skills: { directories: [], includePluginSkills: false,
      pluginPaths: [], agentPluginSkills: discovery.skills }, rules: { directories: [] }, workflows: { directories: [] } });
    await service.start();
    const records = service.listRecords('skill');
    if (records.length !== taskSkillPackages(contract.runtimeContext).length) throw new TypeError('Native Skill discovery is incomplete');
    for (const [target, entry] of files) entry.target = target;
    const nativeSkills = service.createSkillsExecutor();
    const skills = async (name, args, context) => {
      // Authorization uses SDK-discovered names and file paths, never guessed aliases.
      const matches = records.filter(r => r.id === name || r.item.name === name);
      const record = matches.find(r => r.id === name) ?? (matches.length === 1 ? matches[0] : undefined);
      if (!record) throw new TypeError('Skill is outside this task workspace or its name is ambiguous');
      const entry = files.get(resolve(record.filePath));await authorize(entry);
      const output = await nativeSkills(name, args, context);await reportRead(entry, false, output);return output;
    };
    Object.defineProperty(skills, 'configuredSkills', { get: () => nativeSkills.configuredSkills });
    const nativeRead = createDefaultExecutors({ fileRead: { maxFileSizeBytes: 2 * 1024 * 1024 } }).readFile;
    const readFileExecutor = async (request, context) => {
      checkSignal();
      const target = resolve(root, request.path), entry = files.get(target);
      if (!entry || !within(target) || await realpath(target) !== target || (await lstat(target)).isSymbolicLink()) {
        await observe?.({ type:'SKILL_FILE_REJECTED', stage:'CODING', tool:'read_files',
          diagnosticCode:'SKILL_FILE_SCOPE_DENIED', nativeSkills:true });
        throw new TypeError('SKILL_FILE_SCOPE_DENIED: File is outside the authorized Skill resource manifest. read_files reads task-authorized Skill files only; use the authorized input_read or Python Sandbox tool for delivered data.');
      }
      await authorize(entry);
      const output = await nativeRead({ ...request, path: target }, context);
      await reportRead(entry, entry.path !== 'SKILL.md', output);return output;
    };
    const tools = createDefaultTools({ cwd: root, executors: { skills, readFile: readFileExecutor },
      enableSkills: true, enableReadFiles: true, enableBash: false, enableWebFetch: false,
      enableSearch: false, enableEditor: false, enableApplyPatch: false, enableAskQuestion: false, enableSubmitAndExit: false });
    // These published SDK versions list names only in the tool description.
    // Expose SDK-owned discovery metadata through the existing tool parameter,
    // without adding instructions, selecting Skills, or loading their bodies.
    const skillsTool = tools.find(tool => tool.name === 'skills');
    // SDK descriptions already list names; the qualified id also carries that name.
    // Keep every purpose and disabled restriction without repeating names/false flags each turn.
    const purposes = nativeSkills.configuredSkills.map(({ id, description, disabled }) =>
      ({ id, description, ...(disabled ? { disabled: true } : {}) }));
    skillsTool.inputSchema.properties.skill.description += '\n' + JSON.stringify(purposes);
    const resourceTool = tools.find(tool => tool.name === 'read_files');
    resourceTool.description = 'Read authorized Skill files and references. /data/input paths belong to the Python Sandbox and are outside this tool. '
      + resourceTool.description;
    resourceTool.inputSchema.properties.files.items.properties.path.description = 'Absolute path of a task-authorized Skill file or reference.';
    await observe?.({ type: 'SKILLS_DISCOVERED', stage: 'CONTEXT_READY', nativeSkills: true,
      discovery: 'loadAgentPluginPackages', loader: 'createUserInstructionConfigService', nativeSkillCount: records.length,
      skills: records.map(r => ({ id: r.id, name: r.item.name, description: r.item.description,
        skillId: files.get(resolve(r.filePath)).pkg.card.id })) });
    return { root, tools, records, readFileExecutor, async dispose() { service.stop();await removeWorkspace(); } };
  } catch (error) { service?.stop();await removeWorkspace().catch(() => {});throw error; }
}
