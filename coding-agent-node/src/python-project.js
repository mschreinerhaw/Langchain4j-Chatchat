import { createHash } from 'node:crypto';

const VERSION = 'runtime_os.python.code-project.v1';
const TOKEN = '__RUNTIME_PROJECT_PAYLOAD__';
const hash = text => createHash('sha256').update(text).digest('hex');
export function projectPath(path) {
  if (typeof path !== 'string' || path.length > 240 || !path.endsWith('.py') || path.includes('\\')
      || path.includes(':') || path.split('/').some(p => !/^[\p{L}\p{N}_][\p{L}\p{N}_.-]{0,100}$/u.test(p) || p === '.' || p === '..'))
    throw new TypeError('Invalid Python project path');
  return path;
}
export function validateProject(value) {
  if (!value || !Array.isArray(value.files) || !value.files.length || value.files.length > 32) throw new TypeError('Bounded source project required');
  projectPath(value.entryPath);
  let bytes = 0; const names = new Set();
  const files = value.files.map(file => {
    projectPath(file.path);
    if (typeof file.content !== 'string' || names.has(file.path.toLowerCase())) throw new TypeError('Duplicate or missing source file');
    names.add(file.path.toLowerCase()); bytes += Buffer.byteLength(file.content);
    return { path: file.path, content: file.content };
  }).sort((a, b) => a.path < b.path ? -1 : a.path > b.path ? 1 : 0);
  if (bytes > 128000 || !files.some(f => f.path === value.entryPath)) throw new TypeError('Project source budget or entry invalid');
  return { entryPath: value.entryPath, files };
}
export function initialProject(config) {
  const payload = config?.payloadChunks?.join('');
  if (config?.schemaVersion !== VERSION || typeof payload !== 'string' || hash(payload) !== config.sha256
      || typeof config.adapterTemplate !== 'string' || config.adapterTemplate.split(TOKEN).length !== 2)
    throw new TypeError('Invalid Runtime source project');
  return validateProject(JSON.parse(payload));
}
export function encodeProject(project, config) {
  return config.adapterTemplate.replace(TOKEN, Buffer.from(JSON.stringify(validateProject(project))).toString('base64'));
}
export function decodeProject(source, config) {
  const match = /^__project_b64__ = "([A-Za-z0-9+/=]+)"$/m.exec(source ?? '');
  if (!match || config.adapterTemplate.replace(TOKEN, match[1]) !== source) throw new TypeError('Runtime project adapter was modified');
  return validateProject(JSON.parse(Buffer.from(match[1], 'base64').toString('utf8')));
}
/** Only source files are editable. The execution adapter is owned by Runtime. */
export function proposeProject(input, previous, config) {
  const change = input?.project;
  if (!change || input.source !== undefined || input.repair !== undefined || input.rollback !== undefined)
    throw new TypeError('Project execution requires project only');
  if (previous && (change.baseCodeSha256 !== hash(previous) || !Number.isInteger(change.parentCodeVersion))) throw new TypeError('Project edits require latest code version and digest');
  let project = previous ? decodeProject(previous, config) : initialProject(config);
  const files = new Map(project.files.map(file => [file.path, file.content]));
  const edits = change.edits ?? [], additions = change.additions ?? [];
  if (!Array.isArray(edits) || edits.length > 32 || !Array.isArray(additions) || additions.length > 32
      || change.reuse !== true && !edits.length && !additions.length) throw new TypeError('Explicit reuse or bounded source changes required');
  for (const edit of edits) {
    projectPath(edit.path); const old = files.get(edit.path);
    if (old === undefined || typeof edit.oldText !== 'string' || (!edit.oldText && old !== '') || edit.oldText.length > 16000
        || typeof edit.newText !== 'string' || edit.newText.length > 16000 || edit.oldText && old.split(edit.oldText).length !== 2)
      throw new TypeError('Source edit requires a unique exact anchor in the named file');
    files.set(edit.path, edit.oldText === '' ? edit.newText : old.replace(edit.oldText, () => edit.newText));
  }
  for (const file of additions) {
    projectPath(file.path);
    if (files.has(file.path) || [...files.keys()].some(p => p.toLowerCase() === file.path.toLowerCase())) throw new TypeError('Additions cannot replace existing files');
    files.set(file.path, file.content);
  }
  project = validateProject({ entryPath: change.entryPath ?? project.entryPath, files: [...files].map(([path, content]) => ({ path, content })) });
  const source = encodeProject(project, config);
  return { source, proposal: previous ? { repair: { baseCodeSha256: change.baseCodeSha256, parentCodeVersion: change.parentCodeVersion, edits } } : { source } };
}

export const projectSchema = { type: 'object', description: 'Reuse authorized existing files. First call may use reuse:true without changes. Later calls require latest execution codeHash/baseCodeSha256 and parentCodeVersion. Edits preserve unrelated code; additions cannot overwrite existing paths. Use a new entry adapter only when needed to write the contracted result.',
  properties: { reuse: { type: 'boolean' }, baseCodeSha256: { type: 'string' }, parentCodeVersion: { type: 'integer' }, entryPath: { type: 'string' },
    edits: { type: 'array', items: { type: 'object', properties: { path: { type: 'string' }, oldText: { type: 'string' }, newText: { type: 'string' } }, required: ['path','oldText','newText'], additionalProperties: false } },
    additions: { type: 'array', items: { type: 'object', properties: { path: { type: 'string' }, content: { type: 'string' } }, required: ['path','content'], additionalProperties: false } } }, additionalProperties: false };
