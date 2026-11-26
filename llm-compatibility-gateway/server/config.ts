import { DatabaseSync } from 'node:sqlite';
import { z } from 'zod';
import { ruleSchema, headerName } from '../protocol/rules.js';
import { profileNames, type Profile } from '../protocol/profiles.js';
import { seal, unseal } from './credentials.js';
import { validateUpstreamTrust } from './tls.js';
import { normalizeUpstreamUrl } from '../protocol/origin.js';
import { protocolSchema } from '../protocol/compat-schema.js';
import { summarizeTraffic, trafficStart, type TrafficRange } from './traffic.js';

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
  protocol: protocolSchema.optional(),
});
const capability = z.enum(['configured', 'supported', 'unsupported', 'unverified']);
export const modelSchema = z.object({
  alias: identifier, displayName: identifier.optional(), upstream: identifier, service: identifier, enabled: z.boolean().default(true),
  contextWindow: z.number().int().positive().optional(), maxOutput: z.number().int().positive().optional(),
  sseEnd: z.enum(['require_done', 'done_on_finished_eof']).default('require_done'),
  protocol: protocolSchema.optional(),
  capabilities: z.object({ tools: capability.default('unverified'), reasoning: capability.default('unverified'),
    vision: capability.default('unverified'), streaming: capability.default('unverified'),
    parallelTools: capability.optional(), streamUsage: capability.optional() }).default({
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
const clientsSchema = z.array(z.object({ id: identifier, key: z.string().min(16), models: z.array(z.string()).min(1),
  profile: profileSchema.default('generic'), mode: z.enum(['ordinary', 'runtime_managed']).default('ordinary') })).min(1);
type Client = z.infer<typeof clientsSchema>[number];
const envInt = (name: string, fallback: number, min: number, max: number) =>
  z.coerce.number().int().min(min).max(max).parse(process.env[name] ?? fallback);
export const limits = () => ({
  concurrency: envInt('GATEWAY_MAX_CONCURRENCY', 32, 1, 4096),
  requestBytes: envInt('GATEWAY_MAX_REQUEST_BYTES', 8_388_608, 1024, 134_217_728),
  responseBytes: envInt('GATEWAY_MAX_RESPONSE_BYTES', 8_000_000, 1024, 134_217_728),
  streamBytes: envInt('GATEWAY_MAX_STREAM_BYTES', 134_217_728, 1024, 1_073_741_824),
  eventBytes: envInt('GATEWAY_MAX_SSE_EVENT_BYTES', 2_000_000, 256, 16_777_216),
  drainMs: envInt('GATEWAY_SHUTDOWN_DRAIN_MS', 25_000, 100, 600_000),
});
export const diagnosticTimeouts = () => ({
  firstMs: envInt('GATEWAY_DIAGNOSTIC_FIRST_MS', 15_000, 100, 600_000),
  idleMs: envInt('GATEWAY_DIAGNOSTIC_IDLE_MS', 15_000, 100, 600_000),
});

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
    : [{ id: 'default', key: required('GATEWAY_API_KEY', 16), models: ['*'], profile: 'generic', mode: 'ordinary' }];
  if (new Set(clients.map(client => client.id)).size !== clients.length
    || new Set(clients.map(client => client.key)).size !== clients.length
    || clients.some(client => client.key === password)) throw new Error('Admin and client credentials must be distinct');
  return { origins, password, clients, key: Buffer.from(key, 'hex') };
}

export class Store {
  private readonly db: DatabaseSync;
  private readonly approvedOrigins: Set<string>;
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
    // Keep the base schema version compatible with builds that only read environment-approved origins.
    this.db.exec('CREATE TABLE IF NOT EXISTS upstream_origins (origin TEXT PRIMARY KEY)');
    this.approvedOrigins = new Set(this.db.prepare('SELECT origin FROM upstream_origins').all().map(row => String(row.origin)));
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
  get allowedOrigins() { return new Set([...this.origins, ...this.approvedOrigins]); }

  publicVersion(snapshot = this.current) {
    return { version: snapshot.version, config: {
      models: snapshot.config.models,
      services: snapshot.config.services.map(({ apiKey, ...service }) => ({ ...service, hasKey: Boolean(apiKey) })),
    } };
  }

  validate(input: unknown, checkCaValidity = true, allowNewOrigins = false): Config {
    const config = configSchema.parse(input);
    const services = new Map<string, Service>();
    const allowed = this.allowedOrigins;
    for (const service of config.services) {
      const base = normalizeUpstreamUrl(service.origin);
      if (!base) throw new GatewayError(400, 'invalid_upstream_url', '请输入有效的 HTTP(S) 上游地址');
      if (!allowNewOrigins && !allowed.has(new URL(base).origin)) {
        throw new GatewayError(400, 'upstream_not_allowed', '该上游地址尚未保存');
      }
      service.origin = base;
      if (services.has(service.id)) throw new GatewayError(409, 'duplicate_service', '服务 ID 重复');
      services.set(service.id, service);
      if (service.ca) service.ca = validateUpstreamTrust(service.ca, checkCaValidity);
    }
    const aliases = new Set<string>();
    for (const model of config.models) {
      if (aliases.has(model.alias)) throw new GatewayError(409, 'duplicate_model', '模型 ID 重复');
      aliases.add(model.alias);
      const service = services.get(model.service);
      if (!service) throw new GatewayError(400, 'unknown_service', '模型引用的服务不存在');
      const protocol = { ...service.protocol, ...model.protocol };
      if (protocol.doneEvent && protocol.doneEvent === protocol.usageEvent)
        throw new GatewayError(400, 'conflicting_sse_events', 'SSE 用量事件与结束事件不能相同');
    }
    return config;
  }

  publish(expected: number, input: unknown, options: { action?: string; retainSecrets?: boolean; allowNewOrigins?: boolean } = {}) {
    const { action = 'publish', retainSecrets = true, allowNewOrigins = false } = options;
    const config = this.validate(input, true, allowNewOrigins);
    const allowed = this.allowedOrigins;
    const newOrigins = new Set(config.services.map(service => new URL(service.origin).origin).filter(origin => !allowed.has(origin)));
    const previous = new Map(this.current.config.services.map(service => [service.id, service]));
    if (retainSecrets) for (const service of config.services) {
      if (service.apiKey === undefined) {
        const existing = previous.get(service.id);
        if (existing && normalizeUpstreamUrl(existing.origin) === service.origin) service.apiKey = existing.apiKey;
      }
    }
    this.db.exec('BEGIN IMMEDIATE');
    try {
      const { version } = this.db.prepare('SELECT max(version) AS version FROM revisions').get() as { version: number };
      if (version !== expected) throw new GatewayError(409, 'stale_revision', '配置已更新，请重新加载再发布');
      const encoded = { ...config, services: config.services.map(service => ({
        ...service, apiKey: service.apiKey ? seal(service.apiKey, this.key) : undefined,
      })) };
      const saveOrigin = this.db.prepare('INSERT OR IGNORE INTO upstream_origins VALUES (?)');
      for (const origin of newOrigins) saveOrigin.run(origin);
      this.db.prepare('INSERT INTO revisions VALUES (?,?,?,?)').run(version + 1, JSON.stringify(encoded), new Date().toISOString(),
        newOrigins.size ? action + ' · 新增上游地址 ' + [...newOrigins].join(', ') : action);
      this.db.exec('COMMIT');
      for (const origin of newOrigins) this.approvedOrigins.add(origin);
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
  traffic(range: TrafficRange, now = Date.now()) {
    const samples = this.db.prepare(`
      SELECT time, json_extract(metadata, '$.status') AS status,
        json_extract(metadata, '$.elapsedMs') AS elapsedMs,
        json_extract(metadata, '$.firstTokenMs') AS firstTokenMs
      FROM metrics WHERE time BETWEEN ? AND ?
    `).all(new Date(trafficStart(range, now)).toISOString(), new Date(now).toISOString());
    return summarizeTraffic(samples.map(row => ({
      time: String(row.time), status: Number(row.status),
      elapsedMs: typeof row.elapsedMs === 'number' ? row.elapsedMs : null,
      firstTokenMs: typeof row.firstTokenMs === 'number' ? row.firstTokenMs : null,
    })), range, now);
  }
  saveCheck(model: string, version: number, findings: object[], profile: Profile) {
    // Old releases continue to read the findings array after an image rollback.
    const recorded = findings.map((item, index) => index === 0 ? { ...item, profile } : item);
    this.db.prepare('INSERT OR REPLACE INTO checks VALUES (?,?,?,?)').run(model, version, new Date().toISOString(), JSON.stringify(recorded));
  }
  checks() {
    return this.db.prepare('SELECT model,version,time,findings FROM checks').all()
      .map(row => {
        const findings = JSON.parse(String(row.findings)) as Array<{ profile?: Profile }>;
        return { ...row, profile: findings[0]?.profile ?? 'generic', findings };
      });
  }
}
