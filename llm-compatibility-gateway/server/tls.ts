import { createPrivateKey, X509Certificate, randomUUID } from 'node:crypto';
import { execFile, execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { isIP } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createSecureContext, type TlsOptions } from 'node:tls';
import { promisify } from 'node:util';
import { z } from 'zod';
import { GatewayError } from './config.js';
import { seal, unseal } from './credentials.js';

const pemPattern = /-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----/g;
const privatePattern = /-----BEGIN (?:ENCRYPTED |RSA |EC )?PRIVATE KEY-----[\s\S]+?-----END (?:ENCRYPTED |RSA |EC )?PRIVATE KEY-----/g;
const hostname = /^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)*[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$/;
export const namesSchema = z.array(z.string().refine(value => Boolean(isIP(value)) || hostname.test(value))).min(1).max(16);
const material = z.string().max(512_000);
export const importSchema = z.object({
  names: namesSchema, ca: material.optional(), certificate: material.optional(), privateKey: material.optional(),
  p12: material.optional(), passphrase: z.string().max(1024).optional(), csrId: z.uuid().optional(),
  apiPublicPort: z.number().int().min(1).max(65535).optional(),
  selfSigned: z.boolean().optional(),
});
type Input = z.infer<typeof importSchema>;
type Bundle = { names: string[]; certificate: string; privateKey: string; ca?: string; apiPublicPort?: number; selfSigned?: boolean };
type Pending = { id: string; privateKey: string; names: string[] };
const tlsError = (message: string) => new GatewayError(400, 'invalid_tls_configuration', message);
const runOpenSsl = promisify(execFile);
const optionsFor = (bundle: Bundle): TlsOptions => ({ cert: bundle.certificate, key: bundle.privateKey, minVersion: 'TLSv1.2', maxVersion: 'TLSv1.3' });

function openSsl(args: string[], message: string, input?: string) {
  try {
    return execFileSync('openssl', args, { encoding: 'utf8', input, timeout: 6000, maxBuffer: 2_000_000, stdio: ['pipe', 'pipe', 'pipe'] });
  } catch { throw tlsError(message); }
}

function temporary<T>(fn: (path: string) => T) {
  const dir = mkdtempSync(join(tmpdir(), 'gateway-tls-'));
  try { return fn(dir); }
  finally { rmSync(dir, { recursive: true, force: true }); }
}

function certificates(value: string) {
  try {
    const blocks = value.match(pemPattern);
    if (blocks) {
      if (blocks.length > 20 || value.replace(pemPattern, '').trim()) throw new Error('Invalid bundle');
      return blocks.map(block => new X509Certificate(block));
    }
    return [new X509Certificate(Buffer.from(value.replace(/\s+/g, ''), 'base64'))];
  } catch { throw tlsError('证书必须为有效 PEM/CRT 或 Base64 DER'); }
}

export function validateCa(value: string, checkValidity = true) {
  const chain = certificates(value);
  const now = Date.now();
  if (chain.some(cert => !cert.ca || checkValidity && (cert.validFromDate.getTime() > now || cert.validToDate.getTime() <= now))) {
    throw tlsError('CA 必须具备 CA 约束并处于有效期内');
  }
  return chain.map(cert => cert.toString()).join('');
}

export function validateUpstreamTrust(value: string, checkValidity = true) {
  const chain = certificates(value);
  const [leaf] = chain;
  if (chain.length === 1 && !leaf.ca && leaf.subject === leaf.issuer && leaf.verify(leaf.publicKey)) {
    const now = Date.now();
    if (checkValidity && (leaf.validFromDate.getTime() > now || leaf.validToDate.getTime() <= now)) {
      throw tlsError('上游自签证书不在有效期内');
    }
    return leaf.toString();
  }
  return validateCa(value, checkValidity);
}

function expiry(bundle: Bundle) {
  return Math.min(...certificates(bundle.certificate).concat(bundle.ca ? certificates(bundle.ca) : [])
    .map(cert => cert.validToDate.getTime()));
}

function verify(bundle: Bundle) {
  const [leaf, ...chain] = certificates(bundle.certificate);
  if (leaf.ca) throw tlsError('服务器证书必须为终端实体证书');
  const now = Date.now();
  if (leaf.validFromDate.getTime() > now || leaf.validToDate.getTime() <= now) throw tlsError('服务器证书不在有效期内');
  const selfSigned = leaf.subject === leaf.issuer && leaf.verify(leaf.publicKey);
  if (Boolean(bundle.selfSigned) !== selfSigned || selfSigned && (chain.length || bundle.ca)) {
    throw tlsError('自签名模式仅接受显式信任的单张自签服务器证书');
  }
  for (const name of bundle.names) {
    if (!(isIP(name) ? leaf.checkIP(name) : leaf.checkHost(name, { subject: 'never' }))) {
      throw tlsError('证书 SAN 未覆盖配置的全部域名或 IP');
    }
  }
  // Context creation also checks the private key against the leaf.
  try { createSecureContext(optionsFor(bundle)); }
  catch { throw tlsError('服务器证书与私钥不匹配或 TLS 材料无效'); }

  temporary(dir => {
    const leafPath = join(dir, 'leaf.pem');
    const chainPath = join(dir, 'chain.pem');
    const caPath = join(dir, 'ca.pem');
    writeFileSync(leafPath, leaf.toString(), { mode: 0o600 });
    if (chain.length) writeFileSync(chainPath, chain.map(cert => cert.toString()).join(''), { mode: 0o600 });
    if (bundle.ca) writeFileSync(caPath, bundle.ca, { mode: 0o600 });
    const anchors = bundle.selfSigned ? leafPath : bundle.ca ? caPath : undefined;
    openSsl(['verify', '-purpose', 'sslserver', ...(bundle.selfSigned ? ['-check_ss_sig'] : []),
      ...(anchors ? ['-CAfile', anchors, '-no-CApath', '-no-CAstore'] : []),
      ...(chain.length ? ['-untrusted', chainPath] : []), leafPath], '证书链验证失败，请检查签发链、有效期与信任 CA');
  });
}

function normalize(input: Input, pending?: Pending): Bundle {
  if (input.csrId && (!pending || pending.id !== input.csrId || pending.names.join(',') !== input.names.join(','))) {
    throw tlsError('CSR 标识或名称与待签名请求不一致');
  }
  if (input.p12 && (input.certificate || input.privateKey || input.csrId) || input.csrId && input.privateKey) {
    throw tlsError('请选择一种证书导入方式');
  }
  let certificate = input.certificate;
  let key = input.csrId ? pending?.privateKey : input.privateKey;
  if (input.p12) {
    if (input.passphrase?.includes('\n') || input.passphrase?.includes('\r')) throw tlsError('P12 密码不能包含换行');
    const decoded = temporary(dir => {
      const p12 = join(dir, 'material.p12');
      writeFileSync(p12, Buffer.from(input.p12!, 'base64'), { mode: 0o600 });
      return openSsl(['pkcs12', '-in', p12, '-noenc', '-passin', 'stdin'], 'P12/PFX 无法解密或格式无效', (input.passphrase ?? '') + '\n');
    });
    certificate = decoded.match(pemPattern)?.join('\n');
    const keys = decoded.match(privatePattern);
    if (keys?.length !== 1) throw tlsError('P12/PFX 必须包含一把私钥');
    key = keys[0];
  }
  if (!certificate || !key) throw tlsError('请提供证书和私钥，或上传 P12/PFX');
  let privateKey: string;
  try { privateKey = createPrivateKey({ key, passphrase: input.p12 ? undefined : input.passphrase }).export({ type: 'pkcs8', format: 'pem' }).toString(); }
  catch { throw tlsError('证书私钥格式或密码无效'); }
  const certs = certificates(certificate);
  const privateObject = createPrivateKey(privateKey);
  const leaf = certs.find(cert => !cert.ca && cert.checkPrivateKey(privateObject));
  if (!leaf) throw tlsError('证书与私钥不匹配');
  const chain: X509Certificate[] = [leaf];
  const remaining = certs.filter(cert => cert !== leaf);
  while (remaining.length) {
    const previous = chain.at(-1)!;
    const index = remaining.findIndex(cert => previous.checkIssued(cert) && previous.verify(cert.publicKey));
    if (index < 0) throw tlsError('证书包包含与所选叶证书无关的证书');
    const [next] = remaining.splice(index, 1);
    if (next.subject === next.issuer && next.verify(next.publicKey)) {
      if (remaining.length) throw tlsError('根证书之后仍存在无关证书');
      break;
    }
    chain.push(next);
  }
  const bundle = { names: input.names, certificate: chain.map(cert => cert.toString()).join(''), privateKey,
    ca: input.ca ? validateCa(input.ca) : undefined, apiPublicPort: input.apiPublicPort, selfSigned: input.selfSigned };
  verify(bundle);
  return bundle;
}

const readOptional = (path: string) => {
  try { return readFileSync(path, 'utf8'); }
  catch (error) { if ((error as NodeJS.ErrnoException).code === 'ENOENT') return undefined; throw error; }
};

function generateSelfSigned(names: string[]) {
  return temporary(dir => {
    const key = join(dir, 'server.key');
    const san = names.map(name => (isIP(name) ? 'IP:' : 'DNS:') + name).join(',');
    const certificate = openSsl(['req', '-x509', '-newkey', 'rsa:3072', '-noenc', '-days', '365',
      '-subj', '/CN=' + (names[0].length <= 64 ? names[0] : 'LLM Compatibility Gateway'),
      '-addext', 'subjectAltName=' + san, '-addext', 'basicConstraints=critical,CA:FALSE',
      '-addext', 'keyUsage=critical,digitalSignature,keyEncipherment', '-addext', 'extendedKeyUsage=serverAuth',
      '-keyout', key], '无法生成自签名证书');
    return { certificate, privateKey: readFileSync(key, 'utf8'), selfSigned: true };
  });
}

export class CertificateStore {
  private bundle: Bundle;
  private pending?: Pending;
  private readonly file: string;
  private readonly csrFile: string;

  constructor(dir: string, private readonly key: Buffer, allowBootstrap = true) {
    mkdirSync(dir, { recursive: true, mode: 0o700 });
    this.file = join(dir, 'active.enc');
    this.csrFile = join(dir, 'pending-csr.enc');
    const saved = readOptional(this.file);
    const pending = readOptional(this.csrFile);
    if (pending) this.pending = JSON.parse(unseal(pending, key)) as Pending;
    if (saved && (!allowBootstrap || process.env.TLS_FORCE_BOOTSTRAP !== 'true')) {
      this.bundle = JSON.parse(unseal(saved, key)) as Bundle;
      // An expired deployed certificate still starts the listeners so operators can recover via the mount.
      createSecureContext(optionsFor(this.bundle));
    } else {
      if (!allowBootstrap) throw new Error('TLS material has not been initialized');
      const bootstrap = process.env.TLS_BOOTSTRAP_DIR ?? '/run/gateway-tls';
      const domain = process.env.TLS_SERVER_NAMES?.split(',').map(name => name.trim()) ?? [];
      const cert = readOptional(join(bootstrap, 'server.crt'));
      const privateKey = readOptional(join(bootstrap, 'server.key'));
      const ca = readOptional(join(bootstrap, 'ca.crt'));
      const selfSigned = process.env.TLS_SELF_SIGNED === 'true';
      if ((!cert || !privateKey) && (cert || privateKey || !selfSigned)) {
        throw new Error('Mount server.crt and server.key, or explicitly enable TLS_SELF_SIGNED');
      }
      const material = selfSigned && !cert && !privateKey ? generateSelfSigned(namesSchema.parse(domain))
        : { certificate: cert, privateKey, ca, selfSigned };
      this.bundle = normalize(importSchema.parse({ names: domain, ...material }));
      this.writeEncrypted(this.file, this.bundle);
    }
  }

  get options() { return optionsFor(this.bundle); }
  get ca() { return this.bundle.selfSigned ? this.bundle.certificate : this.bundle.ca; }
  get primaryName() { return this.bundle.names[0]; }
  get publicPort() { return this.bundle.apiPublicPort; }

  setPublicPort(apiPublicPort: number) {
    const next = { ...this.bundle, apiPublicPort };
    this.writeEncrypted(this.file, next);
    this.bundle = next;
    return this.status();
  }

  status() {
    const leaf = certificates(this.bundle.certificate)[0];
    const expires = expiry(this.bundle);
    const daysLeft = Math.ceil((expires - Date.now()) / 86_400_000);
    return { names: this.bundle.names, fingerprint: leaf.fingerprint256, validFrom: leaf.validFromDate.toISOString(),
      validTo: new Date(expires).toISOString(), daysLeft, warning: daysLeft <= 30, expired: expires <= Date.now(),
      hasCa: Boolean(this.ca), selfSigned: Boolean(this.bundle.selfSigned), apiPublicPort: this.bundle.apiPublicPort };
  }

  async csr(names: string[]) {
    const dir = mkdtempSync(join(tmpdir(), 'gateway-csr-'));
    try {
      const keyPath = join(dir, 'request.key');
      const san = names.map(name => (isIP(name) ? 'IP:' : 'DNS:') + name).join(',');
      const { stdout: request } = await runOpenSsl('openssl', ['req', '-new', '-newkey', 'rsa:3072', '-noenc', '-subj',
        '/CN=' + (names[0].length <= 64 ? names[0] : 'LLM Compatibility Gateway'),
        '-addext', 'subjectAltName=' + san, '-keyout', keyPath], { encoding: 'utf8', timeout: 6000, maxBuffer: 2_000_000 })
        .catch(() => { throw tlsError('CSR 生成失败'); });
      const pending = { names, id: randomUUID(), privateKey: readFileSync(keyPath, 'utf8') };
      this.writeEncrypted(this.csrFile, pending);
      this.pending = pending;
      return { id: pending.id, request };
    } finally { rmSync(dir, { recursive: true, force: true }); }
  }

  update(input: Input, apply: (options: TlsOptions) => void) {
    const selfSigned = input.selfSigned ?? Boolean(this.bundle.selfSigned);
    const next = normalize({ ...input, selfSigned, apiPublicPort: input.apiPublicPort ?? this.bundle.apiPublicPort,
      ca: input.ca ?? (selfSigned ? undefined : this.bundle.ca) }, this.pending);
    const old = this.options;
    try {
      apply(optionsFor(next));
      this.writeEncrypted(this.file, next);
    } catch (error) {
      apply(old);
      throw error;
    }
    this.bundle = next;
    if (input.csrId) {
      this.pending = undefined;
      // Consume a successfully activated CSR.
      try { rmSync(this.csrFile, { force: true }); } catch { process.stderr.write('Failed to remove consumed encrypted CSR\n'); }
    }
    return this.status();
  }

  renewSelfSigned(names: string[], apply: (options: TlsOptions) => void) {
    const status = this.update({ names, ...generateSelfSigned(names) }, apply);
    return { ...status, certificate: this.ca };
  }

  private writeEncrypted(file: string, value: object) {
    const staged = file + '.next-' + randomUUID();
    try {
      writeFileSync(staged, seal(JSON.stringify(value), this.key), { mode: 0o600, flag: 'wx' });
      renameSync(staged, file);
    } finally { rmSync(staged, { force: true }); }
  }
}
