import { useState } from 'react';
import { Alert, Button, Card, Descriptions, Form, Input, InputNumber, Modal, Radio, Space, Tag, Typography } from 'antd';

export type TlsStatus = {
  enabled: boolean; listener: number; publicPort: number; adminPort: number; apiBaseUrl?: string; httpPublicPort?: number;
  certificate?: { names: string[]; fingerprint: string; validFrom: string; validTo: string;
    daysLeft: number; warning: boolean; expired: boolean; hasCa: boolean; selfSigned?: boolean };
  clients: { id: string; profile: string; models: string[] }[];
  profiles: Record<string, string>;
};

const fileContent = async (file?: File) => {
  if (!file) return undefined;
  if (file.size > 370_000) throw new Error('证书文件超过 370 KB');
  const bytes = new Uint8Array(await file.arrayBuffer());
  const txt = new TextDecoder().decode(bytes);
  if (txt.includes('-----BEGIN ')) return txt;
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
};
const names = (text: string) => text.split(/[\s,，]+/).filter(Boolean);
function download(name: string, content: string, type: string) {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const link = document.createElement('a');
  link.href = url; link.download = name; link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
type Call = <T>(path: string, method?: string, body?: unknown) => Promise<T>;

export function Certificates({ status, refresh, api }: { status?: TlsStatus; refresh: () => Promise<void>; api: Call }) {
  const [mode, setMode] = useState('pem');
  const [domains, setDomains] = useState(status?.certificate?.names.join(', ') ?? '');
  const [port, setPort] = useState(status?.publicPort);
  const [passphrase, setPassphrase] = useState('');
  const [csrId, setCsrId] = useState('');
  const [files, setFiles] = useState<Record<string, File | undefined>>({});
  const [clearCa, setClearCa] = useState(false);
  const [selfSigned, setSelfSigned] = useState(status?.certificate?.selfSigned ?? false);
  const [notice, setNotice] = useState<{ type: 'error' | 'success'; text: string }>();
  const [busy, setBusy] = useState(false);
  const [uploadVersion, setUploadVersion] = useState(0);
  const run = async (task: () => Promise<void>, text: string) => {
    setBusy(true); setNotice(undefined);
    try { await task(); setNotice({ type: 'success', text }); }
    catch (error) { setNotice({ type: 'error', text: (error as Error).message }); }
    finally { setBusy(false); }
  };
  const choose = (key: string, label: string, accept: string) => <Form.Item label={label} key={key + uploadVersion}>
    <input aria-label={label} type="file" accept={accept} onChange={e => setFiles({ ...files, [key]: e.target.files?.[0] })} />
  </Form.Item>;
  if (!status) return <Card loading />;
  if (!status.enabled) return <Alert message="当前实例在 HTTP 测试模式下运行" type="warning" showIcon />;
  const cert = status.certificate!;
  return <Space direction="vertical" size={16} className="full">
    <Card title="当前服务器证书" extra={<Tag color={cert.expired ? 'red' : cert.warning ? 'orange' : 'green'}>
      {cert.expired ? '已过期' : '剩余 ' + cert.daysLeft + ' 天'}
    </Tag>}>
      <Descriptions column={1} size="small" items={[
        { key: 'names', label: '证书 SAN', children: cert.names.join(' · ') },
        { key: 'url', label: '推理 Base URL', children: status.apiBaseUrl },
        { key: 'port', label: '容器监听端口', children: status.listener + ' / 管理 ' + status.adminPort },
        { key: 'expiry', label: '证书或链最早到期日', children: new Date(cert.validTo).toLocaleString() },
        { key: 'fingerprint', label: 'SHA-256 指纹', children: <Typography.Text copyable>{cert.fingerprint}</Typography.Text> },
        { key: 'ca', label: '客户端信任', children: cert.hasCa ? <Button href="/admin/tls/ca">
          {cert.selfSigned ? '下载自签服务器证书' : '下载 CA'}
        </Button> : '系统信任链' },
      ]} />
    </Card>
    {notice && <Alert type={notice.type} message={notice.text} showIcon />}
    <Card title="证书部署">
      <Form layout="vertical">
        <Form.Item label="服务域名和 IP"><Input aria-label="服务域名和 IP" value={domains} onChange={e => setDomains(e.target.value)} placeholder="llm.example.com, 10.2.25.10" /></Form.Item>
        <Form.Item label="客户端 HTTPS 端口" extra="需与 Docker 发布端口或外围 HTTPS 入口一致">
          <Space><InputNumber aria-label="客户端 HTTPS 端口" min={1} max={65535} value={port} onChange={value => setPort(value ?? undefined)} />
            <Button disabled={!port || busy} onClick={() => run(async () => {
              await api('tls/endpoint', 'PATCH', { apiPublicPort: port }); await refresh();
            }, '客户端接入端口已保存')}>保存端口</Button></Space>
        </Form.Item>
        <Form.Item label="证书格式">
          <Radio.Group value={mode} onChange={e => { setMode(e.target.value); setFiles({}); setUploadVersion(value => value + 1); setPassphrase(''); }}>
            <Radio.Button value="pem">PEM / CRT + KEY</Radio.Button><Radio.Button value="p12">P12 / PFX</Radio.Button>
            <Radio.Button value="csr">部署已签名 CSR</Radio.Button>
          </Radio.Group>
        </Form.Item>
        {mode === 'p12' ? choose('p12', 'P12 / PFX 文件', '.p12,.pfx') : choose('certificate', '服务器证书与中间链', '.pem,.crt,.cer')}
        {mode === 'pem' && choose('privateKey', '服务器私钥', '.key,.pem')}
        {mode === 'csr' ? <Form.Item label="CSR 标识"><Input aria-label="CSR 标识" value={csrId} onChange={e => setCsrId(e.target.value)} /></Form.Item>
          : <Form.Item label="私钥或 P12 密码"><Input.Password autoComplete="new-password" aria-label="私钥或 P12 密码" value={passphrase} onChange={e => setPassphrase(e.target.value)} /></Form.Item>}
        <Form.Item><label><input type="checkbox" checked={selfSigned} onChange={e => setSelfSigned(e.target.checked)} /> 使用自签服务器证书</label></Form.Item>
        {!selfSigned && choose('ca', '信任 CA 文件', '.pem,.crt,.cer')}
        {!selfSigned && <Form.Item><label><input type="checkbox" checked={clearCa} onChange={e => setClearCa(e.target.checked)} /> 改用系统信任链</label></Form.Item>}
        <Button type="primary" loading={busy} onClick={() => run(async () => {
          const body = { names: names(domains), apiPublicPort: port, selfSigned,
            certificate: mode !== 'p12' ? await fileContent(files.certificate) : undefined,
            privateKey: mode === 'pem' ? await fileContent(files.privateKey) : undefined,
            p12: mode === 'p12' ? await fileContent(files.p12) : undefined,
            csrId: mode === 'csr' ? csrId : undefined, passphrase: passphrase || undefined,
            ca: selfSigned ? undefined : clearCa ? '' : await fileContent(files.ca) };
          await api('tls', 'PUT', body);
          setFiles({}); setUploadVersion(value => value + 1); setPassphrase(''); setClearCa(false);
          if (!selfSigned) await refresh();
        }, '证书链验证通过，新的 TLS 握手已使用更新后的证书')}>验证并热更新</Button>
      </Form>
    </Card>
    <Card title="无 CA 环境">
      <Button disabled={busy || !names(domains).length} onClick={() => Modal.confirm({
        title: '生成并启用新的自签服务器证书',
        content: '更新后，所有客户端需要信任新的公开证书。确认后将下载该证书。',
        onOk: () => run(async () => {
          const result = await api<{ certificate: string }>('tls/self-signed', 'POST', { names: names(domains) });
          setSelfSigned(true);
          download('gateway-server.crt', result.certificate, 'application/x-pem-file');
        }, '新证书已启用。请在客户端导入下载的证书后重新连接。'),
      })}>生成并启用自签证书</Button>
    </Card>
    <Card title="生成 CSR">
      <Space wrap>
        <Button disabled={busy || !names(domains).length} onClick={() => run(async () => {
          const result = await api<{ id: string; request: string }>('tls/csr', 'POST', { names: names(domains) });
          setCsrId(result.id); setMode('csr'); setFiles({}); setUploadVersion(value => value + 1);
          download('gateway.csr', result.request, 'application/pkcs10');
        }, 'CSR 已下载。请将签发的证书与中间链上传。')}>生成并下载 CSR</Button>
        {csrId && <Typography.Text copyable>{csrId}</Typography.Text>}
      </Space>
    </Card>
    <Card title="客户端 Profile">
      <Descriptions column={1} size="small" items={status.clients.map(client => ({ key: client.id, label: client.id,
        children: status.profiles[client.profile] + ' · ' + client.models.join(', ') }))} />
    </Card>
  </Space>;
}
