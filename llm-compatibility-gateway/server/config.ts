import { DatabaseSync } from 'node:sqlite';
import { z } from 'zod';
import { ruleSchema, headerName } from '../protocol/rules.js';
import { profileNames, type Profile } from '../protocol/profiles.js';
import { seal, unseal } from './credentials.js';
import { validateUpstreamTrust } from './tls.js';

export class GatewayError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

const identifier = z.string().min(1).max(240).refine(value => !/[\u0000-\u001f\u007f]/.test(value));
const path = z.string().regex(/^\/(?!\/)[^\\?#\s]*$/);
const timeout = z.number().int().min(100).max(600_000);
export const profileSchema = z.enum(Object.keys(profileNames) as Profile[]);
const secret = z.string().max(8192).regex(/^[\x20-\x7e]*$/);
export const serviceSchema = z.object({
  id: identifier, name: identifier, origin: z.url(), chatPath: path.default('/v1/chat/completions'),
  modelsPath: path.default('/v1/models'),
  framework: z.enum(['openai', 'vllm', 'sglang', 'ollama', 'xinference', 'lm-studio', 'llama.cpp', 'localai', 'custom']).default('openai'),
  enabled: z.boolean().default(true), authHeader: headerName.default('authorization'),
  authPrefix: secret.default('Bearer '), apiKey: secret.optional(),
  ca: z.string().max(65_536).optional(), connectMs: timeout.default(5_000),
  firstMs: timeout.default(60_000), idleMs: timeout.default(60_000),
  concurrency: z.number().int().min(1).max(512).default(16),
});
const capability = z.enum(['configured', 'supported', 'unsupported', 'unverified']);
export const modelSchema = z.object({
  alias: identifier, upstream: identifier, service: identifier, enabled: z.boolean().default(true),
  contextWindow: z.number().int().positive().optional(), maxOutput: z.number().int().positive().optional(),
  sseEnd: z.enum(['require_done', 'done_on_finished_eof']).default('require_done'),
  capabilities: z.object({ tools: capability.default('unverified'), reasoning: capability.default('unverified'),
    vision: capability.default('unverified'), streaming: capability.default('unverified') }).default({
    tools: 'unverified', reasoning: 'unverified', vision: 'unverified', streaming: 'unverified',
  }),
  rules: z.array(ruleSchema).max(100).default([]),
  profileRules: z.partialRecord(profileSchema, z.array(ruleSchema).max(100)).default({}),
});
export const configSchema = z.object({
  services: z.array(serviceSchema).max(200), models: z.array(modelSchema).max(1000),
});
export type Service = z.infer<typeof serviceSchema>;
export type Model = z.infer<typeof modelSchema>;
export type Config = z.infer<typeof configSchema>;
export type Snapshot = { version: number; config: Config };
type Row = { version: number; payload: string };
type Client = { id: string; key: string; models: string[]; profile: Profile };
const clientsSchema = z.array(z.object({ id: identifier, key: z.string().min(16), models: z.array(z.string()).min(1),
  profile: profileSchema.default('generic') })).min(1);

export function withProfile(model: Model, profile: Profile): Model {
  return { ...model, rules: [...model.rules, ...(model.profileRules[profile] ?? [])] };
}

function required(name: string, min: number) {
  const value = process.env[name];
  if (!value || value.length < min) throw new Error(`Set ${name} with at least ${min} characters`);
  return value;
}

export function settings() {
  const origins = new Set((process.env.UPSTREAM_ALLOWED_ORIGINS ?? '').split(',').map(item => item.trim()).filter(Boolean).map(item => {
    const url = new URL(item);
    if (!['http:', 'https:'].includes(url.protocol) || url.href !== url.origin + '/') {
      throw new Error('UPSTREAM_ALLOWED_ORIGINS requires exact HTTP(S) origins');
    }
    return url.origin;
  }));
  const key = required('CONFIG_ENCRYPTION_KEY', 64);
  if (!/^[a-f0-9]{64}$/i.test(key)) throw new Error('CONFIG_ENCRYPTION_KEY requires 64 hex characters');
  const password = required('ADMIN_PASSWORD', 16);
  const clients: Client[] = process.env.GATEWAY_CLIENTS
    ? clientsSchema.parse(JSON.parse(process.env.GATEWAY_CLIENTS))
    : [{ id: 'default', key: required('GATEWAY_API_KEY', 16), models: ['*'], profile: 'generic' }];
  if (new Set(clients.map(client => client.id)).size !== clients.length
    || new Set(clients.map(client => client.key)).size !== clients.length
    || clients.some(client => client.key === password)) throw new Error('Admin and client credentials must be distinct');
  return { origins, password, clients, key: Buffer.from(key, 'hex') };
}

export class Store {
  private readonly db: DatabaseSync;
  private current: Snapshot;

  constructor(file: string, private readonly key: Buffer, private readonly origins: Set<string>) {
    this.db = new DatabaseSync(file);
    this.db.exec('PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000');
    const { user_version } = this.db.prepare('PRAGMA user_version').get() as { user_version: number };
    if (user_version > 1) throw new Error('Database schema is newer than this gateway');
    if (user_version === 0) this.db.exec(`
      BEGIN IMMEDIATE;
      CREATE TABLE IF NOT EXISTS revisions (version INTEGER PRIMARY KEY, payload TEXT NOT NULL, time TEXT NOT NULL, action TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS metrics (id TEXT PRIMARY KEY, time TEXT NOT NULL, metadata TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS checks (model TEXT PRIMARY KEY, version INTEGER NOT NULL, time TEXT NOT NULL, findings TEXT NOT NULL);
      INSERT OR IGNORE INTO revisions VALUES (1, '{"services":[],"models":[]}', datetime('now'), 'initialize');
      PRAGMA user_version=1;
      COMMIT;
    `);
    this.current = this.getRevision();
    this.validate(this.current.config, false);
  }

  getRevision(version?: number): Snapshot {
    const row = (version === undefined
      ? this.db.prepare('SELECT version,payload FROM revisions ORDER BY version DESC LIMIT 1').get()
      : this.db.prepare('SELECT version,payload FROM revisions WHERE version=?').get(version)) as Row | undefined;
    if (!row) throw new GatewayError(404, 'revision_not_found', '配置版本不存在');
    const payload = JSON.parse(row.payload) as Config;
    for (const service of payload.services) if (service.apiKey) service.apiKey = unseal(service.apiKey, this.key);
    const config = configSchema.parse(payload);
    return { version: row.version, config };
  }

  get snapshot() { return this.current; }

  publicVersion(snapshot = this.current) {
    return { version: snapshot.version, config: {
      models: snapshot.config.models,
      services: snapshot.config.services.map(({ apiKey, ...service }) => ({ ...service, hasKey: Boolean(apiKey) })),
    } };
  }

  validate(input: unknown, checkCaValidity = true): Config {
    const config = configSchema.parse(input);
    const services = new Set<string>();
    for (const service of config.services) {
      const url = new URL(service.origin);
      if (url.href !== url.origin + '/' || !this.origins.has(url.origin)) {
        throw new GatewayError(400, 'upstream_not_allowed', '服务地址必须是白名单中的 HTTP(S) Origin，接口路径单独配置');
      }
      if (services.has(service.id)) throw new GatewayError(409, 'duplicate_service', '服务 ID 重复');
      services.add(service.id);
      if (service.ca) service.ca = validateUpstreamTrust(service.ca, checkCaValidity);
    }
    const aliases = new Set<string>();
    for (const model of config.models) {
      if (aliases.has(model.alias)) throw new GatewayError(409, 'duplicate_model', '模型 ID 重复');
      aliases.add(model.alias);
      if (!services.has(model.service)) throw new GatewayError(400, 'unknown_service', '模型引用的服务不存在');
    }
    return config;
  }

  publish(expected: number, input: unknown, action = 'publish', retainSecrets = true) {
    const config = this.validate(input);
    const previous = new Map(this.current.config.services.map(service => [service.id, service]));
    if (retainSecrets) for (const service of config.services) {
      if (service.apiKey === undefined) {
        const existing = previous.get(service.id);
        if (existing && new URL(existing.origin).origin === new URL(service.origin).origin) service.apiKey = existing.apiKey;
      }
    }
    this.db.exec('BEGIN IMMEDIATE');
    try {
      const { version } = this.db.prepare('SELECT max(version) AS version FROM revisions').get() as { version: number };
      if (version !== expected) throw new GatewayError(409, 'stale_revision', '配置已更新，请重新加载再发布');
      const encoded = { ...config, services: config.services.map(service => ({
        ...service, apiKey: service.apiKey ? seal(service.apiKey, this.key) : undefined,
      })) };
      this.db.prepare('INSERT INTO revisions VALUES (?,?,?,?)').run(version + 1, JSON.stringify(encoded), new Date().toISOString(), action);
      this.db.exec('COMMIT');
      this.current = { version: version + 1, config };
      return this.publicVersion();
    } catch (error) {
      this.db.exec('ROLLBACK');
      throw error;
    }
  }

  history() { return this.db.prepare('SELECT version,time,action FROM revisions ORDER BY version DESC LIMIT 100').all(); }
  record(id: string, metadata: object) {
    this.db.prepare('INSERT INTO metrics VALUES (?,?,?)').run(id, new Date().toISOString(), JSON.stringify(metadata));
    this.db.prepare('DELETE FROM metrics WHERE rowid <= (SELECT rowid FROM metrics ORDER BY rowid DESC LIMIT 1 OFFSET 4999) - 1').run();
  }
  metrics() {
    return this.db.prepare('SELECT id,time,metadata FROM metrics ORDER BY rowid DESC LIMIT 1000').all()
      .map(row => ({ id: row.id, time: row.time, ...JSON.parse(String(row.metadata)) }));
  }
  saveCheck(model: string, version: number, findings: object) {
    this.db.prepare('INSERT OR REPLACE INTO checks VALUES (?,?,?,?)').run(model, version, new Date().toISOString(), JSON.stringify(findings));
  }
  checks() {
    return this.db.prepare('SELECT model,version,time,findings FROM checks').all()
      .map(row => ({ ...row, findings: JSON.parse(String(row.findings)) }));
  }
}
