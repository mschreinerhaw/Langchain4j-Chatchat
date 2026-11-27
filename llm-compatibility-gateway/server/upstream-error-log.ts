import { appendFile, rename, stat } from 'node:fs/promises';
import { isObject, type JsonObject } from '../protocol/rules.js';

export function modelErrorFields(value: unknown): JsonObject {
  if (!isObject(value)) return {};
  const error = isObject(value.error) ? value.error : value;
  const fields: JsonObject = {};
  for (const key of ['code', 'type', 'param', 'message']) {
    const v = error[key] ?? (key === 'message' && typeof value.error === 'string' ? value.error : undefined);
    if (typeof v === 'number' && Number.isSafeInteger(v) && key === 'code') fields[key] = v;
    if (typeof v !== 'string') continue;
    if (key !== 'message') {
      if (/^[A-Za-z0-9_.:/\[\]-]{1,128}$/.test(v)) fields[key] = v;
      continue;
    }
    fields.message = v
      .replace(/\b(?:authorization|api[-_ ]?key|access[-_ ]?token|password|secret|cookie)\b["']?\s*[:=][^\r\n]*/gi, '[REDACTED]')
      .replace(/\bbearer\s+\S+|\bsk-[A-Za-z0-9_-]{8,}/gi, '[REDACTED]')
      .replace(/https?:\/\/[^\s"'<>]+/g, '[URL]')
      .split(/\r?\n/, 1)[0].slice(0, 300);
  }
  return fields;
}

export class UpstreamErrorLog {
  private tail: Promise<void> = Promise.resolve();
  private pending = 0;

  constructor(private readonly file: string, private readonly maxBytes = 8 * 1024 * 1024) {}

  write(record: JsonObject): Promise<void> {
    if (this.pending >= 256) return Promise.resolve();
    this.pending++;
    const line = JSON.stringify({ time: new Date().toISOString(), ...record }) + '\n';
    this.tail = this.tail.then(async () => {
      const size = await stat(this.file).then(info => info.size).catch((error: NodeJS.ErrnoException) => {
        if (error.code === 'ENOENT') return 0;
        throw error;
      });
      if (size && size + Buffer.byteLength(line) > this.maxBytes) await rename(this.file, this.file + '.1');
      await appendFile(this.file, line, { mode: 0o600 });
    }).catch(() => {
      process.stderr.write('Gateway could not write model error log\n');
    }).finally(() => { this.pending--; });
    return this.tail;
  }
}
