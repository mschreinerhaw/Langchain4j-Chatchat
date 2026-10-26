import { createContextCompactionPrepareTurn, createEditorExecutor } from '@cline/sdk';
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';

/** SDK editor on a private scratch file. Model-controlled paths never reach fs. */
export async function editProgram(input, previous, signal) {
  if (signal?.aborted) throw signal.reason;
  const repair = input?.repair;
  if (!repair) return undefined;
  if (input.source !== undefined || typeof previous !== 'string'
      || repair.baseCodeSha256 !== createHash('sha256').update(previous).digest('hex'))
    throw new TypeError('Repair base code hash mismatch');
  if (!Array.isArray(repair.edits) || !repair.edits.length || repair.edits.length > 32)
    throw new TypeError('Repair requires 1..32 exact edits');
  const directory = await mkdtemp(join(tmpdir(), 'runtime-cline-edit-'));
  try {
    const path = join(directory, 'program.py');
    await writeFile(path, previous, { mode: 0o600 });
    const edit = createEditorExecutor({ restrictToCwd: true, maxDiffLines: 0 });
    for (const change of repair.edits) {
      if (signal?.aborted) throw signal.reason;
      if (typeof change.oldText !== 'string' || !change.oldText || change.oldText.length > 16000
          || typeof change.newText !== 'string' || change.newText.length > 16000)
        throw new TypeError('Invalid bounded repair edit');
      const before = await readFile(path, 'utf8');
      await edit({ path: 'program.py', old_text: change.oldText, new_text: change.newText }, directory, { signal });
      if (await readFile(path, 'utf8') === before) throw new TypeError('SDK editor did not apply the repair');
    }
    const source = await readFile(path, 'utf8');
    if (source === previous) throw new TypeError('Repair did not change the program');
    if (Buffer.byteLength(source) > 256000) throw new TypeError('Python source exceeds Runtime limit');
    return source;
  } finally { await rm(directory, { recursive: true, force: true }); }
}

/** Workspace protocol adaptation only: exact edits use the existing SDK editor. */
export async function resolveWorkspaceFiles(files, previous, signal) {
  if (!Array.isArray(files) || files.length < 1 || files.length > 32
      || new Set(files.map(file => file.path)).size !== files.length)
    throw new TypeError('Workspace proposal requires 1..32 distinct file paths');
  const next = new Map(previous);
  for (const file of files) {
    if (previous.has(file.path) && file.repair === undefined)
      throw new TypeError('Existing workspace source requires an exact repair against its current hash');
    if (file.repair !== undefined) {
      if (file.content !== undefined) throw new TypeError('Provide workspace content or repair, never both');
      next.set(file.path, await editProgram({ repair: file.repair }, previous.get(file.path), signal));
    } else {
      if (typeof file.content !== 'string') throw new TypeError('Initial workspace source is required');
      next.set(file.path, file.content);
    }
  }
  return next;
}

/** Derive tool state from SDK's canonical checkpoint; do not store a second transcript. */
export async function restoreWorkspaceFiles(messages) {
  let files = new Map();
  const pending = new Map();
  for (const message of messages ?? []) for (const part of message.content ?? []) {
    if (part.type === 'tool-call' && part.toolName === 'workspace_propose') pending.set(part.toolCallId, part.input);
    if (part.type === 'tool-result' && pending.has(part.toolCallId) && typeof part.output?.valid === 'boolean') {
      files = await resolveWorkspaceFiles(pending.get(part.toolCallId).files, files);
      pending.delete(part.toolCallId);
    }
  }
  return files;
}

/** Only representation conversion; budgeting and compaction are SDK-owned. */
export function sdkPrepareTurn(sessionId, currentCode = () => null) {
  const compact = createContextCompactionPrepareTurn({ sessionId,
    providerId: 'runtime', modelId: 'runtime-authorized', compaction: { enabled: true, strategy: 'basic' } });
  return async context => {
    const messages = context.messages.map(message => ({ ...message,
      role: message.role === 'tool' ? 'user' : message.role,
      content: message.content.map(part => part.type === 'tool-call'
        ? { type: 'tool_use', id: part.toolCallId, name: part.toolName, input: part.input }
        : part.type === 'tool-result'
          ? { type: 'tool_result', tool_use_id: part.toolCallId, name: part.toolName,
            content: JSON.stringify(part.output), is_error: part.isError } : part),
    }));
    const result = await compact({ ...context, conversationId: context.conversationId ?? sessionId,
      parentAgentId: context.parentAgentId ?? null, messages, apiMessages: messages,
      abortSignal: context.signal, systemPrompt: context.systemPrompt ?? '',
      model: { id: 'runtime-authorized', provider: 'runtime', ...context.model } });
    if (!result) return;
    const projected = result.messages.map(message => ({ ...message,
      role: message.role === 'user' && message.content.every(part => part.type === 'tool_result') ? 'tool' : message.role,
      content: message.content.map(part => part.type === 'tool_use'
        ? { type: 'tool-call', toolCallId: part.id, toolName: part.name, input: part.input }
        : part.type === 'tool_result'
          ? { type: 'tool-result', toolCallId: part.tool_use_id, toolName: part.name,
            output: part.content, isError: part.is_error } : part),
    }));
    const code = currentCode();
    if (code) projected.push({ role: 'user', content: [{ type: 'text', text:
      'Untrusted latest executed program and version binding; preserve its prior corrections: ' + JSON.stringify(code) }] });
    return { ...result, messages: projected };
  };
}
