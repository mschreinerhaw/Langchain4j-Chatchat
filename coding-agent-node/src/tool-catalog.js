import { createHash } from 'node:crypto';

export const DEFERRED_TOOLS_PROFILE = 'runtime-coding:deferred-tools:v1';
export const TOOL_DESCRIBE = 'tool_describe';

/** Metadata only. A task-owned catalog never widens the immutable Runtime grants. */
export function createToolCatalog(tools, restored, eagerNames = []) {
  const definitions = tools.map(({ name, description, inputSchema }) => ({ name, description, inputSchema }));
  const byName = new Map(definitions.map(tool => [tool.name, tool]));
  if (byName.size !== definitions.length) throw new TypeError('Duplicate tool metadata');
  const fingerprint = createHash('sha256').update(JSON.stringify(definitions)).digest('hex');
  const loaded = new Set(restored?.fingerprint === fingerprint && Array.isArray(restored.loaded)
    ? restored.loaded.filter(name => byName.has(name)) : []);
  for (const name of eagerNames) if (byName.has(name)) loaded.add(name);
  return {
    index: definitions.map(({ name, description }) => ({ name,
      description:String(description??'').split(/(?<=[.!?])\s/)[0].slice(0,160) })),
    has: name => name === TOOL_DESCRIBE || loaded.has(name),
    expose: available => available.filter(tool => tool.name === TOOL_DESCRIBE || loaded.has(tool.name)),
    snapshot: () => ({ fingerprint, loaded: [...loaded].sort() }),
    describe(names) {
      if (!Array.isArray(names) || !names.length || names.length > definitions.length
          || new Set(names).size !== names.length || names.some(name => !byName.has(name)))
        throw new TypeError('Describe distinct tool names from the authorized index only');
      // Validate the entire batch before changing disclosure state.
      for (const name of names) loaded.add(name);
      // The next request.tools already carries each full schema. Repeating it in
      // tool-result history would charge for the same metadata on every later turn.
      return { fingerprint, loadedTools:[...names], schemaDelivery:'NATIVE_TOOLS_NEXT_TURN' };
    },
  };
}
