import { createHash, createHmac, randomUUID, timingSafeEqual } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { mkdir, writeFile, rename } from 'node:fs/promises';
import { join } from 'node:path';
import { arch, cpus, hostname, loadavg, platform } from 'node:os';
import type { IncomingMessage, ServerResponse } from 'node:http';
import type { CodeVersion, ModelCall, MonitorEvent, NodeSnapshot, TaskDetail, TaskState, TaskSummary } from './monitor-types.ts';

const VERSION = 'coding_agent.monitor.v1';
const SESSION_MS = 8 * 60 * 60 * 1000;
const COOKIE = 'coding_monitor_session';
const EVENT_KEYS = ['stage', 'status', 'operationId', 'proposedOperationId', 'modelCall', 'tool',
  'codeSha256', 'fileName', 'sourceBytes', 'skillFingerprint', 'validationStatus', 'outputStatus',
  'selectedModel', 'providerModel', 'protocol', 'startedAt', 'finishedAt', 'durationMs', 'message'];
type Contract = Record<string, any>;
interface Options {
  directory?: string; maxTasks?: number; maxEvents?: number; maxSourceBytes?: number;
  secrets?: string[];
}
function integer(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null;
}
function equal(a: string, b: string): boolean {
  const first = Buffer.from(a), second = Buffer.from(b);
  return first.length > 0 && first.length === second.length && timingSafeEqual(first, second);
}
function json(reply: ServerResponse, status: number, value: unknown, headers = {}) {
  reply.writeHead(status, { 'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store', 'x-content-type-options': 'nosniff', ...headers });
  reply.end(JSON.stringify(value));
}

/** Bounded operator observations. Runtime receipts remain the execution authority. */
export class MonitorStore {
  readonly instanceId = randomUUID();
  readonly startedAt = new Date().toISOString();
  readonly maxTasks: number;
  readonly maxEvents: number;
  readonly maxSourceBytes: number;
  private tasks = new Map<string, TaskDetail>();
  private directory?: string;
  private secrets: string[];
  private timer?: ReturnType<typeof setTimeout>;
  private writes: Promise<void> = Promise.resolve();
  private cursor = 0;
  private persistence: 'MEMORY' | 'HEALTHY' | 'DEGRADED';
  private persistenceError: string | null = null;

  constructor(options: Options = {}) {
    this.maxTasks = options.maxTasks ?? 256;
    this.maxEvents = options.maxEvents ?? 512;
    this.maxSourceBytes = options.maxSourceBytes ?? 8 * 1024 * 1024;
    for (const value of [this.maxTasks, this.maxEvents, this.maxSourceBytes])
      if (!Number.isSafeInteger(value) || value < 1) throw new Error('Invalid monitor retention limit');
    this.directory = options.directory;
    this.secrets = (options.secrets ?? []).filter(Boolean);
    this.persistence = this.directory ? 'HEALTHY' : 'MEMORY';
    if (this.directory) this.restore();
  }
  private text(value: unknown, max = 512): string {
    let text = typeof value === 'string' ? value : '';
    for (const secret of this.secrets) text = text.split(secret).join('[REDACTED]');
    return text.replace(/Bearer\s+[^\s"']+/gi, 'Bearer [REDACTED]').slice(0, max);
  }
  private restore() {
    const file = join(this.directory!, 'observations.json');
    if (!existsSync(file)) return;
    try {
      const bytes = readFileSync(file);
      if (bytes.length > 64 * 1024 * 1024) throw new Error('Monitor history exceeds recovery limit');
      const saved = JSON.parse(bytes.toString('utf8'));
      if (saved.schemaVersion !== VERSION || !Array.isArray(saved.tasks)) throw new Error('Unknown monitor history version');
      this.cursor = saved.cursor;
      for (const task of saved.tasks.slice(-this.maxTasks) as TaskDetail[]) {
        if (!task.id || !Array.isArray(task.events) || !Array.isArray(task.codes) || !Array.isArray(task.models))
          throw new Error('Invalid monitor history');
        if (task.status === 'RUNNING') {
          task.status = 'INTERRUPTED'; task.stage = 'NODE_RESTARTED';
          task.reconciliationRequired = true;
          for (const call of task.models) if (call.status === 'RUNNING') call.status = 'UNKNOWN';
        }
        task.events = task.events.slice(-this.maxEvents);
        this.tasks.set(task.id, task);
      }
      this.trimSources();
    } catch {
      this.persistence = 'DEGRADED'; this.persistenceError = 'Monitor history could not be restored';
      this.tasks.clear();
    }
  }
  private changed() {
    if (!this.directory || this.timer) return;
    this.timer = setTimeout(() => { this.timer = undefined; void this.persist(); }, 150);
    this.timer.unref();
  }
  private persist() {
    if (!this.directory) return this.writes;
    const data = JSON.stringify({ schemaVersion: VERSION, cursor: this.cursor, tasks: [...this.tasks.values()] });
    this.writes = this.writes.then(async () => {
      try {
        await mkdir(this.directory!, { recursive: true, mode: 0o700 });
        const temporary = join(this.directory!, 'observations.tmp');
        await writeFile(temporary, data, { mode: 0o600 });
        await rename(temporary, join(this.directory!, 'observations.json'));
        this.persistence = 'HEALTHY'; this.persistenceError = null;
      } catch {
        this.persistence = 'DEGRADED'; this.persistenceError = 'Monitor history write failed; observations remain in memory';
      }
    });
    return this.writes;
  }
  async flush() {
    clearTimeout(this.timer); this.timer = undefined;
    await this.persist();
  }
  start(contract: Contract, fencingToken: number, recoveryMode: string): string | null {
    // Never evict an active observation to make room for another active task.
    while (this.tasks.size >= this.maxTasks) {
      const victim = [...this.tasks.values()].find(task => task.status !== 'RUNNING');
      if (!victim) return null;
      this.tasks.delete(victim.id);
    }
    const id = createHash('sha256').update(JSON.stringify([
      contract.scope.tenantId, contract.scope.userId, contract.scope.runId, contract.taskId, fencingToken,
    ])).digest('hex');
    const now = new Date().toISOString();
    const skills = contract.runtimeContext?.skills;
    const model = contract.runtimeContext?.model;
    const configuredModel: TaskDetail['configuredModel'] = {};
    for (const key of ['selectedModel', 'providerModel', 'protocol'] as const)
      if (typeof model?.[key] === 'string') configuredModel[key] = this.text(model[key], 200);
    const task: TaskDetail = {
      id, taskId: this.text(contract.taskId, 256), runId: this.text(contract.scope.runId, 256),
      tenantId: this.text(contract.scope.tenantId, 256), userId: this.text(contract.scope.userId, 256), fencingToken,
      status: 'RUNNING', stage: 'ACCEPTED', objective: this.text(contract.objective?.instruction, 1000),
      createdAt: now, updatedAt: now, finishedAt: null, deadlineAt: contract.budget?.deadlineAt ?? null,
      elapsedMs: 0, recoveryMode, reconciliationRequired: false, error: null,
      eventCount: 0, retainedEvents: 0, codeCount: 0, modelCalls: 0, inputTokens: 0,
      outputTokens: 0, usageKnownCalls: 0, configuredModel,
      skills: { status: this.text(skills?.status) || 'NOT_CONFIGURED', fingerprint: this.text(skills?.fingerprint) || null,
        configured: (Array.isArray(skills?.skills) ? skills.skills : []).slice(0, 128).map((skill: Contract) => ({
          skillId: this.text(skill.skillId, 200), name: this.text(skill.name, 200), version: this.text(skill.version, 80),
        })), stages: Object.keys(skills?.stages ?? {}).slice(0, 32),
        loadedAt: null, applications: 0, lastAppliedAt: null },
      budget: { maxModelCalls: integer(contract.budget?.maxModelCalls), deadlineAt: contract.budget?.deadlineAt ?? null },
      events: [], models: [], codes: [], eventsTruncated: false, codesTruncated: false,
    };
    this.tasks.set(id, task); this.event(id, { type: 'SESSION_ACCEPTED', timestamp: now });
    return id;
  }
  event(id: string | null, value: Contract) {
    const task = id ? this.tasks.get(id) : null;
    if (!task) return;
    const now = new Date().toISOString();
    const event: MonitorEvent = { cursor: ++this.cursor, timestamp: now, type: this.text(value.type, 80) };
    for (const key of EVENT_KEYS) {
      if (typeof value[key] === 'string') event[key] = this.text(value[key], 512);
      else if (integer(value[key]) !== null) event[key] = value[key];
    }
    if (value.usage) event.usage = { inputTokens: integer(value.usage.inputTokens),
      outputTokens: integer(value.usage.outputTokens), totalCost: integer(value.usage.totalCost) };
    task.events.push(event); task.eventCount++; task.updatedAt = now;
    if (task.events.length > this.maxEvents) { task.events.shift(); task.eventsTruncated = true; }
    task.retainedEvents = task.events.length; task.stage = this.text(value.stage) || event.type;
    if (event.type === 'SKILLS_LOADED') task.skills.loadedAt = now;
    if (event.type === 'SKILLS_APPLIED') { task.skills.applications++; task.skills.lastAppliedAt = now; }
    if (Number.isSafeInteger(value.modelCall) && value.modelCall > 0) {
      let call = task.models.find(call => call.ordinal === value.modelCall);
      if (!call && task.models.length < 128) {
        call = { ordinal: value.modelCall, status: 'RUNNING', startedAt: now, finishedAt: null, durationMs: null,
          usage: { inputTokens: null, outputTokens: null, totalCost: null } };
        task.models.push(call);
      }
      if (call) {
        if (event.type === 'MODEL_RESOLVED') {
          for (const key of ['selectedModel', 'providerModel', 'protocol'] as const)
            if (typeof event[key] === 'string') call[key] = event[key];
          if (typeof event.startedAt === 'string') call.startedAt = event.startedAt;
          if (typeof event.finishedAt === 'string') call.finishedAt = event.finishedAt;
          call.durationMs = integer(event.durationMs);
        }
        if (['MODEL_USAGE', 'MODEL_FAILED'].includes(event.type)) {
          call.status = event.type === 'MODEL_USAGE' ? 'SUCCEEDED' : 'FAILED';
          call.finishedAt ??= now;
          call.durationMs ??= Math.max(0, Date.parse(call.finishedAt) - Date.parse(call.startedAt));
          if (event.type === 'MODEL_USAGE') call.usage = event.usage as ModelCall['usage'];
        }
      }
    }
    this.changed();
  }
  code(id: string | null, value: { operationId: string; fileName: string; source: string }) {
    const task = id ? this.tasks.get(id) : null;
    if (!task || typeof value.source !== 'string' || Buffer.byteLength(value.source) > 256000) return;
    const hash = createHash('sha256').update(value.source).digest('hex');
    const codeId = createHash('sha256').update(`${value.operationId}\0${value.fileName}\0${hash}`).digest('hex');
    if (task.codes.some(code => code.id === codeId)) return;
    if (task.codes.length >= 64) { task.codes.shift(); task.codesTruncated = true; }
    task.codes.push({ id: codeId, operationId: this.text(value.operationId), fileName: this.text(value.fileName, 256),
      sha256: hash, sourceBytes: Buffer.byteLength(value.source), createdAt: new Date().toISOString(),
      source: value.source, sourceRetained: true });
    task.codeCount++; this.trimSources(); this.changed();
  }
  private trimSources() {
    const codes = [...this.tasks.values()].flatMap(task => task.codes);
    let total = codes.reduce((sum, code) => sum + (code.sourceRetained ? code.sourceBytes : 0), 0);
    for (const code of codes) {
      if (total <= this.maxSourceBytes) break;
      if (code.sourceRetained) { total -= code.sourceBytes; delete code.source; code.sourceRetained = false; }
    }
  }
  finish(id: string | null, status: TaskState, error?: unknown) {
    const task = id ? this.tasks.get(id) : null;
    if (!task) return;
    task.status = status; task.finishedAt = new Date().toISOString();
    task.error = this.text(error) || null;
    for (const call of task.models) if (call.status === 'RUNNING') call.status = 'UNKNOWN';
    this.event(id, { type: 'SESSION_FINISHED', status, stage: status });
  }
  private summary(task: TaskDetail): TaskSummary {
    const { events, models, codes, skills, configuredModel, budget, eventsTruncated, codesTruncated, ...summary } = task;
    return { ...summary, elapsedMs: Math.max(0, Date.parse(task.finishedAt ?? task.updatedAt) - Date.parse(task.createdAt))
      + (task.status === 'RUNNING' ? Math.max(0, Date.now() - Date.parse(task.updatedAt)) : 0),
      modelCalls: models.length, inputTokens: models.reduce((sum, call) => sum + (call.usage.inputTokens ?? 0), 0),
      outputTokens: models.reduce((sum, call) => sum + (call.usage.outputTokens ?? 0), 0),
      usageKnownCalls: models.filter(call => call.usage.inputTokens !== null).length };
  }
  list(status: string, search: string, offset: number, limit: number) {
    const tasks = [...this.tasks.values()].reverse().filter(task => (!status || task.status === status)
      && (!search || [task.taskId, task.runId, task.objective, task.tenantId].some(value => value.toLowerCase().includes(search.toLowerCase()))));
    return { schemaVersion: VERSION, observedAt: new Date().toISOString(), total: tasks.length,
      offset, limit, items: tasks.slice(offset, offset + limit).map(task => this.summary(task)) };
  }
  detail(id: string): TaskDetail | null {
    const task = this.tasks.get(id);
    return task ? { ...structuredClone(task), ...this.summary(task), codes: task.codes.map(({ source, ...code }) => code) } : null;
  }
  source(id: string, codeId: string): CodeVersion | null {
    const code = this.tasks.get(id)?.codes.find(code => code.id === codeId);
    return code ? structuredClone(code) : null;
  }
  node(draining: boolean, active: number, capacity: number, callbackTimeoutMs: number): NodeSnapshot {
    const memory = process.memoryUsage(), cpu = process.cpuUsage();
    const counts: NodeSnapshot['tasks'] = { RUNNING: 0, COMPLETED: 0, FAILED: 0, CANCELLED: 0, INTERRUPTED: 0 };
    for (const task of this.tasks.values()) counts[task.status]++;
    return { schemaVersion: VERSION, instanceId: this.instanceId, observedAt: new Date().toISOString(),
      status: draining ? 'DRAINING' : 'READY', sdk: '@cline/agents@0.0.93', build: process.env.RUNTIME_CODING_BUILD ?? 'development',
      nodeVersion: process.version, platform: platform(), arch: arch(), hostname: hostname(),
      uptimeSeconds: Math.floor(process.uptime()), startedAt: this.startedAt,
      memory: { rssBytes: memory.rss, heapUsedBytes: memory.heapUsed, heapTotalBytes: memory.heapTotal },
      cpu: { userMicros: cpu.user, systemMicros: cpu.system, cores: cpus().length, loadAverage: loadavg() },
      sessions: { active, capacity, retained: this.tasks.size }, tasks: counts, callbackTimeoutMs,
      history: { persistence: this.persistence, error: this.persistenceError, maxTasks: this.maxTasks,
        maxEventsPerTask: this.maxEvents, maxSourceBytes: this.maxSourceBytes,
        retainedSourceBytes: [...this.tasks.values()].flatMap(task => task.codes).reduce((sum, code) => sum + (code.sourceRetained ? code.sourceBytes : 0), 0) },
      authority: 'NODE_OBSERVATION_ONLY' };
  }
}

export function createMonitorHandler({ token, store, snapshot, secureCookie = false }: {
  token?: string; store: MonitorStore; snapshot: () => NodeSnapshot; secureCookie?: boolean;
}) {
  if (token && token.length < 32) throw new Error('Monitor token must contain at least 32 characters');
  const bootKey = randomUUID(); // Browser sessions expire on process restart; never a dispatch credential.
  const signature = (expiry: string) => createHmac('sha256', `${token}:${bootKey}`).update(expiry).digest('hex');
  const cookie = (value: string, maxAge: number) => `${COOKIE}=${value}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${maxAge}${secureCookie ? '; Secure' : ''}`;
  function authorized(request: IncomingMessage) {
    if (!token) return false;
    if (equal(request.headers.authorization?.replace(/^Bearer /, '') ?? '', token)) return true;
    const session = request.headers.cookie?.split(';').map(value => value.trim()).find(value => value.startsWith(`${COOKIE}=`))?.slice(COOKIE.length + 1);
    if (!session) return false;
    const [expiry, hash] = session.split('.');
    return Number(expiry) > Date.now() && Number(expiry) <= Date.now() + SESSION_MS && equal(hash ?? '', signature(expiry));
  }
  const assetRoot = existsSync(new URL('./public/index.html', import.meta.url))
    ? new URL('./public/', import.meta.url) : new URL('../dist/public/', import.meta.url);
  const assets = new Map([['/monitor/', ['index.html', 'text/html; charset=utf-8']],
    ['/monitor/app.js', ['app.js', 'text/javascript; charset=utf-8']], ['/monitor/style.css', ['style.css', 'text/css; charset=utf-8']]]);
  return async (request: IncomingMessage, reply: ServerResponse): Promise<boolean> => {
    const url = new URL(request.url ?? '/', 'http://localhost');
    if (!url.pathname.startsWith('/monitor') && !url.pathname.startsWith('/v1/monitor')) return false;
    const security = { 'cache-control': 'no-store', 'x-content-type-options': 'nosniff',
      'referrer-policy': 'no-referrer', 'content-security-policy': "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'" };
    if (request.method === 'GET' && url.pathname === '/monitor') {
      reply.writeHead(302, { location: '/monitor/', ...security }); reply.end(); return true;
    }
    if (request.method === 'GET' && assets.has(url.pathname)) {
      const [file, type] = assets.get(url.pathname)!;
      try { reply.writeHead(200, { ...security, 'content-type': type }); reply.end(readFileSync(new URL(file, assetRoot))); }
      catch { json(reply, 503, { message: 'Monitor assets unavailable; run npm run build' }); }
      return true;
    }
    if (!token) { json(reply, 503, { code: 'MONITOR_DISABLED', message: 'Configure a separate read-only monitor token' }); return true; }
    if (request.method === 'POST' && ['/monitor/session', '/monitor/logout'].includes(url.pathname)) {
      const origin = request.headers.origin;
      if ((origin && origin !== `http://${request.headers.host}` && origin !== `https://${request.headers.host}`)
          || request.headers['sec-fetch-site'] === 'cross-site'
          || !request.headers['content-type']?.startsWith('application/json')) {
        json(reply, 403, { message: 'Same-origin JSON request required' }); return true;
      }
      if (url.pathname === '/monitor/logout') {
        json(reply, 200, { status: 'SIGNED_OUT' }, { 'set-cookie': cookie('', 0) }); return true;
      }
      let bytes = '';
      for await (const chunk of request) {
        bytes += chunk;
        if (Buffer.byteLength(bytes) > 4096) { json(reply, 413, { message: 'Session request too large' }); return true; }
      }
      let value;
      try { value = JSON.parse(bytes); } catch { json(reply, 400, { message: 'Invalid JSON' }); return true; }
      if (typeof value?.token !== 'string' || !equal(value.token, token)) { json(reply, 401, { message: 'Invalid monitor token' }); return true; }
      const expiry = String(Date.now() + SESSION_MS);
      json(reply, 200, { status: 'SIGNED_IN', expiresAt: new Date(Number(expiry)).toISOString() },
        { 'set-cookie': cookie(`${expiry}.${signature(expiry)}`, SESSION_MS / 1000) }); return true;
    }
    if (!authorized(request)) { json(reply, 401, { code: 'MONITOR_UNAUTHORIZED', message: 'Read-only monitor authentication required' }); return true; }
    if (request.method !== 'GET') { json(reply, 405, { message: 'Monitor API is read-only' }, { allow: 'GET' }); return true; }
    const path = url.pathname.slice('/v1/monitor'.length);
    if (path === '/node') { json(reply, 200, snapshot()); return true; }
    if (path === '/openapi.json') { json(reply, 200, openapi); return true; }
    if (path === '/tasks') {
      const offset = Number(url.searchParams.get('offset') ?? 0), limit = Number(url.searchParams.get('limit') ?? 30);
      const state = url.searchParams.get('status') ?? '';
      if (!Number.isSafeInteger(offset) || offset < 0 || !Number.isSafeInteger(limit) || limit < 1 || limit > 100
          || state && !['RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED'].includes(state)) {
        json(reply, 400, { message: 'Invalid pagination or status' }); return true;
      }
      json(reply, 200, store.list(state, (url.searchParams.get('q') ?? '').slice(0, 256), offset, limit)); return true;
    }
    const route = /^\/tasks\/([a-f0-9]{64})(?:\/(events|models|skills|codes)(?:\/([a-f0-9]{64}))?)?$/.exec(path);
    if (route) {
      const task = store.detail(route[1]);
      if (!task) { json(reply, 404, { message: 'Observation not retained on this node' }); return true; }
      const section = route[2];
      if (!section) { json(reply, 200, { schemaVersion: VERSION, observedAt: new Date().toISOString(), task }); return true; }
      if (section === 'codes' && route[3]) {
        const code = store.source(route[1], route[3]);
        json(reply, code ? code.sourceRetained ? 200 : 410 : 404, code ?? { message: 'Code version not retained' }); return true;
      }
      if (route[3]) { json(reply, 404, { message: 'Unknown monitor route' }); return true; }
      if (section === 'events') {
        const after = Number(url.searchParams.get('after') ?? 0), limit = Number(url.searchParams.get('limit') ?? 100);
        if (!Number.isSafeInteger(after) || after < 0 || !Number.isSafeInteger(limit) || limit < 1 || limit > 200) {
          json(reply, 400, { message: 'Invalid event cursor or limit' }); return true;
        }
        const events = task.events.filter(event => event.cursor > after).slice(0, limit);
        json(reply, 200, { schemaVersion: VERSION, items: events,
          nextCursor: events.at(-1)?.cursor ?? after, hasMore: task.events.some(event => event.cursor > (events.at(-1)?.cursor ?? after)),
          truncated: task.eventsTruncated, earliestCursor: task.events[0]?.cursor ?? null }); return true;
      }
      json(reply, 200, { schemaVersion: VERSION, [section]: task[section as 'models' | 'skills' | 'codes'] }); return true;
    }
    json(reply, 404, { message: 'Unknown monitor route' }); return true;
  };
}

// Stable discovery document for applications embedding or polling this component.
const openapi = {
  openapi: '3.1.0', info: { title: 'Coding Agent Node Monitor', version: '1.0.0',
    description: 'Read-only node observations. Runtime OS remains authoritative. Retained history is bounded.' },
  servers: [{ url: '/v1/monitor' }], security: [{ monitorToken: [] }],
  components: { securitySchemes: { monitorToken: { type: 'http', scheme: 'bearer' } } },
  paths: Object.fromEntries(['/node', '/tasks', '/tasks/{id}', '/tasks/{id}/events', '/tasks/{id}/models',
    '/tasks/{id}/skills', '/tasks/{id}/codes', '/tasks/{id}/codes/{codeId}'].map(path => [path, {
    get: { summary: path, parameters: [...path.matchAll(/\{(\w+)\}/g)].map(match => ({
      name: match[1], in: 'path', required: true, schema: { type: 'string', pattern: '^[a-f0-9]{64}$' },
    })), responses: { '200': { description: 'Versioned observation' }, '401': { description: 'Authentication required' },
      '404': { description: 'Observation not retained' }, '410': { description: 'Source expired; hash metadata retained' } } },
  }])),
};
