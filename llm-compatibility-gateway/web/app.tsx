import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Alert, Button, Card, Checkbox, Col, ConfigProvider, Descriptions, Form, Input, InputNumber, Layout,
  Menu, Modal, Row, Select, Space, Statistic, Table, Tabs, Tag, Typography, message, theme } from 'antd';
import 'antd/dist/reset.css';
import './style.css';
import type { Config, Model, Service } from '../server/config.js';
import type { Rule } from '../protocol/rules.js';
import { profileNames, type Profile } from '../protocol/profiles.js';
import { normalizeOrigin } from '../protocol/origin.js';
import { Certificates, type TlsStatus } from './tls.js';
import clineCatalog from './cline-models.json' with { type: 'json' };

type Revision = { version: number; config: Config; allowedOrigins?: string[]; apiBaseUrl?: string };
type Overview = { overview: Record<string, string | number | null>; metrics: Record<string, unknown>[]; checks: Check[] };
type Check = { model: string; time: string; version: number; findings: Finding[] };
type Finding = { test: string; status: string; reason: string; httpStatus?: number; suggestion?: string; clineImpact?: string; evidence?: unknown[] };
type Trace = { stage: string; ms: number; value: any };
const json = (data: unknown) => JSON.stringify(data, null, 2);
const stages = { client: '标准客户端请求', upstream_request: '发送给上游的请求',
  upstream_response: '上游原始响应', gateway: '网关标准响应' };
const profileOptions = Object.entries(profileNames).map(([value, label]) => ({ value, label }));
const headers = { 'content-type': 'application/json' };
const title = { overview: '服务概览', services: '上游模型服务', models: '模型映射', rules: '兼容规则',
  lab: '请求实验室', checks: '兼容性检测', logs: '请求监控', tls: 'HTTPS 证书', integration: '接入配置', history: '配置与审计' };
type Page = keyof typeof title;
const sample = (model = '', stream = false, mode = 'chat') => ({
  model, stream, messages: [{ role: 'user', content: mode === 'reasoning' ? '解释 17 为什么是质数。' : '请回复：网关正常。' },
    ...(mode === 'multi' ? [{ role: 'assistant', content: '网关正常。' }, { role: 'user', content: '重复你之前的答复。' }] : [])],
  ...(mode === 'tool' ? { tools: [{ type: 'function', function: { name: 'gateway_echo', description: 'Echo a value',
    parameters: { type: 'object', properties: { value: { type: 'string' } } } } }], tool_choice: 'required' } : {}),
  ...(mode === 'params' ? { max_completion_tokens: 10, temperature: 0.7, parallel_tool_calls: false } : {}),
});
const emptyService = { id: '', name: '', origin: '', chatPath: '/v1/chat/completions', modelsPath: '/v1/models',
  authHeader: 'authorization', authPrefix: 'Bearer ', framework: 'openai', enabled: true, connectMs: 5000, firstMs: 60000,
  idleMs: 60000, concurrency: 16 };
const emptyModel = { alias: '', upstream: '', service: '', enabled: true, sseEnd: 'require_done', rules: [],
  capabilities: { tools: 'unverified', reasoning: 'unverified', vision: 'unverified', streaming: 'unverified' } };
const capLabels = { configured: '已配置', supported: '已实测支持', unsupported: '已实测不支持', unverified: '未验证' };
const capNames = { tools: '工具调用', reasoning: '推理内容', vision: '视觉输入', streaming: '流式' };

async function api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await fetch('/admin/' + path, { method, headers: body === undefined ? undefined : headers,
    body: body === undefined ? undefined : JSON.stringify(body) });
  if (response.status === 401 && path !== 'login') window.dispatchEvent(new Event('gateway-logout'));
  const data = await response.json();
  if (!response.ok) throw new Error(data.error?.message ?? '请求失败');
  return data;
}

async function stream(path: string, body: unknown, signal: AbortSignal, receive: (record: Trace) => void) {
  const response = await fetch('/admin/' + path, { method: 'POST', headers, body: JSON.stringify(body), signal });
  if (!response.ok) {
    if (response.status === 401) window.dispatchEvent(new Event('gateway-logout'));
    throw new Error((await response.json()).error?.message ?? '请求失败');
  }
  const reader = response.body!.getReader();
  const decoder = new TextDecoder();
  let pending = '';
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      pending += decoder.decode(value, { stream: true });
      const records = pending.split('\n');
      pending = records.pop()!;
      for (const line of records) if (line) receive(JSON.parse(line));
    }
  } finally { reader.releaseLock(); }
}

function download(filename: string, data: unknown) {
  const url = URL.createObjectURL(new Blob([json(data)], { type: 'application/json' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function Code({ value }: { value: string }) {
  return <pre className="code">{value}</pre>;
}
function Diff({ before, after }: { before: unknown; after: unknown }) {
  const fields = (value: unknown, path = '', entries = new Map<string, string>()) => {
    if (value && typeof value === 'object' && Object.keys(value).length) {
      for (const [key, item] of Object.entries(value)) fields(item, path ? path + '.' + key : key, entries);
    } else entries.set(path, json(value) ?? '—');
    return entries;
  };
  const left = fields(before);
  const right = fields(after);
  const changes = [...new Set([...left.keys(), ...right.keys()])].filter(key => left.get(key) !== right.get(key)).slice(0, 150);
  return <div className="diff">{changes.length ? changes.map(key => <div key={key}><strong>{key}</strong>
    {left.has(key) && <span className="removed">{left.get(key)}</span>}
    {right.has(key) && <span className="added">{right.get(key)}</span>}</div>) : <Typography.Text type="secondary">字段一致</Typography.Text>}</div>;
}
function Login({ onLogin }: { onLogin: () => Promise<void> }) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  return <main className="login">
    <Card className="login-card">
      <div className="brand-mark">G</div>
      <Typography.Title level={2}>LLM 兼容网关</Typography.Title>
      <Typography.Paragraph type="secondary">登录管理平台</Typography.Paragraph>
      <Form layout="vertical" onFinish={async ({ password }) => {
        setPending(true); setError('');
        try { await api('login', 'POST', { password }); await onLogin(); }
        catch (error) { setError(String((error as Error).message)); }
        finally { setPending(false); }
      }}>
        {error && <Alert type="error" message={error} showIcon />}
        <Form.Item name="password" label="管理员密码" rules={[{ required: true }]}>
          <Input.Password autoComplete="current-password" autoFocus />
        </Form.Item>
        <Button htmlType="submit" block type="primary" loading={pending}>登录</Button>
      </Form>
    </Card>
  </main>;
}

function App() {
  const [revision, setRevision] = useState<Revision>();
  const [ready, setReady] = useState(false);
  const [page, setPage] = useState<Page>('overview');
  const [status, setStatus] = useState<Overview>();
  const [tls, setTls] = useState<TlsStatus>();
  const [error, setError] = useState('');
  const [editor, setEditor] = useState<{ kind: 'model' | 'service'; key?: string; value: any }>();
  const [toast, context] = message.useMessage();
  const refresh = async () => setStatus(await api<Overview>('overview'));
  const refreshTls = async () => setTls(await api<TlsStatus>('tls'));
  const load = async () => { setRevision(await api<Revision>('config')); await Promise.all([refresh(), refreshTls()]); };
  const attempt = async (operation: () => Promise<void>) => {
    setError('');
    try { await operation(); } catch (error) { setError((error as Error).message); }
  };
  useEffect(() => {
    const logout = () => { setRevision(undefined); setStatus(undefined); setTls(undefined); setEditor(undefined); };
    window.addEventListener('gateway-logout', logout);
    void load().catch(() => {}).finally(() => setReady(true));
    return () => window.removeEventListener('gateway-logout', logout);
  }, []);
  useEffect(() => {
    if (!revision || !['overview', 'logs', 'models'].includes(page)) return;
    const timer = setInterval(() => { void refresh().catch(() => {}); }, 6000);
    return () => clearInterval(timer);
  }, [page, revision]);
  useEffect(() => {
    if (!revision) return;
    const timer = setInterval(() => { void refreshTls().catch(() => {}); }, 60_000);
    return () => clearInterval(timer);
  }, [revision]);
  if (!ready) return null;
  if (!revision) return <Login onLogin={load} />;
  const { config, version } = revision;
  const trustFile = tls?.certificate?.hasCa ? tls.certificate.selfSigned ? 'gateway-server.crt' : 'gateway-ca.crt' : undefined;
  const publish = async (next: Config, approveOrigin?: string) => {
    const saved = await api<Revision>('config', 'PUT', { version, config: next, approveOrigin });
    setRevision(saved); setEditor(undefined);
    void refresh().catch(() => {});
    void toast.success('已发布配置 v' + saved.version);
  };
  const remove = (kind: 'service' | 'model', key: string) => Modal.confirm({
    title: '删除 ' + key, content: '此操作将发布新的配置版本。',
    onOk: () => attempt(() => publish(kind === 'model' ? { ...config, models: config.models.filter(model => model.alias !== key) }
      : { ...config, services: config.services.filter(service => service.id !== key) })),
  });
  const go = (next: Page) => { setError(''); setPage(next); };
  const latest = (id: string) => status?.checks.find(check => check.model === id);
  let content: React.ReactNode;
  if (page === 'overview') {
    const overview = status?.overview ?? {};
    const cards = { configured: '模型服务', published: '已发布模型', online: '在线服务', unhealthy: '异常服务', requests: '近期推理请求',
      success: '近期成功率', latency: '平均耗时', firstToken: '首 Token 延迟', active: '当前并发' };
    content = <><div className="stat-grid">{Object.entries(cards).map(([key, label]) => <Card key={key}>
      <Statistic title={label} value={key === 'success' ? (Number(overview.requests) ? Math.round(100 * Number(overview.success) / Number(overview.requests)) + '%' : '—')
        : overview[key] ?? '—'} suffix={['latency', 'firstToken'].includes(key) && overview[key] != null ? 'ms' : undefined} />
    </Card>)}</div><Card title="最近错误" className="after"><Metrics rows={status?.metrics.filter(row => row.error).slice(0, 8)} /></Card></>;
  } else if (page === 'services') {
    content = <Card extra={<Button type="primary" onClick={() => setEditor({ kind: 'service', value: emptyService })}>新增服务</Button>}>
      <Table rowKey="id" dataSource={config.services} scroll={{ x: 760 }} columns={[
        { title: '服务', dataIndex: 'name', render: (name, row) => <><strong>{name}</strong><div className="muted">{row.id}</div></> },
        { title: 'Origin / 框架', dataIndex: 'origin', render: (origin, row) => <>{origin}<div className="muted">{row.framework}</div></> },
        { title: '认证', render: (_, row) => (row as Service & { hasKey?: boolean }).hasKey ? '已配置' : '无密钥' },
        { title: '状态', render: (_, row) => <Tag color={row.enabled ? 'green' : 'default'}>{row.enabled ? '启用' : '停用'}</Tag> },
        { title: '操作', render: (_, row) => <Space wrap>
          <Button size="small" onClick={() => setEditor({ kind: 'service', key: row.id, value: row })}>编辑</Button>
          <Button size="small" onClick={() => attempt(async () => {
            const result = await api('upstream/models', 'POST', { id: row.id });
            Modal.info({ width: 780, title: '模型列表与认证响应', content: <Code value={json(result)} /> });
          })}>检测 / 模型列表</Button>
          <Button size="small" onClick={() => go('lab')}>对话测试</Button>
          <Button size="small" danger onClick={() => remove('service', row.id)}>删除</Button>
        </Space> },
      ]} />
    </Card>;
  } else if (page === 'models') {
    content = <Card extra={<Button type="primary" disabled={!config.services.length}
      onClick={() => setEditor({ kind: 'model', value: { ...emptyModel, service: config.services[0]?.id } })}>新增映射</Button>}>
      <Table rowKey="alias" dataSource={config.models} scroll={{ x: 960 }} columns={[
        { title: '对外 ID / 上游 ID', render: (_, row) => <><strong>{row.alias}</strong><div className="muted">{row.upstream}</div></> },
        { title: '服务', dataIndex: 'service' },
        { title: '上下文 / 输出', render: (_, row) => (row.contextWindow ?? '—') + ' / ' + (row.maxOutput ?? '—') },
        { title: '能力配置', render: (_, row) => <Space wrap>{Object.entries(row.capabilities).map(([key, value]) =>
          <Tag key={key}>{capNames[key as keyof typeof capNames]} · {capLabels[value]}</Tag>)}</Space> },
        { title: '规则 / 检测', render: (_, row) => <>{row.rules.length} 条<div className="muted">
          {latest(row.alias) ? new Date(latest(row.alias)!.time).toLocaleString() + ' · v' + latest(row.alias)!.version : '未检测'}</div></> },
        { title: '状态', render: (_, row) => <Tag color={row.enabled ? 'green' : 'default'}>{row.enabled ? '启用' : '停用'}</Tag> },
        { title: '操作', render: (_, row) => <Space>
          <Button size="small" onClick={() => setEditor({ kind: 'model', key: row.alias, value: row })}>编辑</Button>
          <Button size="small" danger onClick={() => remove('model', row.alias)}>删除</Button>
        </Space> },
      ]} />
    </Card>;
  } else if (page === 'rules') {
    content = <Rules key={version} revision={revision} publish={next => attempt(() => publish(next))} attempt={attempt} />;
  } else if (page === 'lab') {
    content = <Lab config={config} apiBaseUrl={tls?.apiBaseUrl ?? baseUrl()} trustFile={trustFile} />;
  } else if (page === 'checks') {
    content = <Checks config={config} previous={status?.checks ?? []} refresh={refresh} />;
  } else if (page === 'logs') {
    content = <Card extra={<Button onClick={() => attempt(refresh)}>刷新</Button>}><Metrics rows={status?.metrics} /></Card>;
  } else if (page === 'tls') {
    content = <Certificates status={tls} refresh={refreshTls} api={api} />;
  } else if (page === 'integration') {
    content = <Integration key={tls?.apiBaseUrl} config={config} apiBaseUrl={tls?.apiBaseUrl ?? baseUrl()} trustFile={trustFile} />;
  } else {
    content = <History key={version} revision={revision} attempt={attempt} restored={async () => { await load(); void toast.success('配置已恢复'); }} />;
  }
  return <Layout className="shell">
    {context}
    <Layout.Sider width={220} breakpoint="lg" collapsedWidth={60} className="sidebar">
      <div className="brand"><div className="brand-mark">G</div><strong>LLM 兼容网关</strong></div>
      <Menu theme="dark" mode="inline" selectedKeys={[page]} onClick={({ key }) => go(key as Page)}
        items={Object.entries(title).map(([key, label]) => ({ key, label }))} />
    </Layout.Sider>
    <Layout>
      <Layout.Header className="top"><span>管理控制台</span><Space wrap>
        <Tag color="green">运行正常</Tag><span className="muted">v1.1.1 · 配置 v{version}</span>
        <Button size="small" onClick={() => attempt(async () => { await api('logout', 'POST'); window.dispatchEvent(new Event('gateway-logout')); })}>退出</Button>
      </Space></Layout.Header>
      <Layout.Content className="main">
        <Typography.Title level={2}>{title[page]}</Typography.Title>
        {tls?.certificate?.warning && <Alert showIcon type={tls.certificate.expired ? 'error' : 'warning'} className="after"
          message={tls.certificate.expired ? 'TLS 证书链已过期' : 'TLS 证书链将在 ' + tls.certificate.daysLeft + ' 天内到期'}
          action={<Button size="small" onClick={() => go('tls')}>查看证书</Button>} />}
        {error && <Alert showIcon type="error" message={error} closable onClose={() => setError('')} className="after" />}
        {content}
      </Layout.Content>
    </Layout>
    {editor && <Edit key={editor.kind + (editor.key ?? '')} editor={editor} config={config} allowedOrigins={revision.allowedOrigins ?? []}
      close={() => setEditor(undefined)} save={(value, approveOrigin) => publish(editor.kind === 'service'
        ? { ...config, services: editor.key ? config.services.map(row => row.id === editor.key ? value : row) : [...config.services, value] }
        : { ...config, models: editor.key ? config.models.map(row => row.alias === editor.key ? value : row) : [...config.models, value] }, approveOrigin)} />}
  </Layout>;
}

function Edit({ editor, config, allowedOrigins, close, save }: {
  editor: { kind: 'model' | 'service'; key?: string; value: any }; config: Config; allowedOrigins: string[];
  close: () => void; save: (value: any, approveOrigin?: string) => Promise<void>;
}) {
  const [form] = Form.useForm();
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [confirmedOrigin, setConfirmedOrigin] = useState<string>();
  const origin = normalizeOrigin(Form.useWatch('origin', form) ?? '');
  const pendingOrigin = origin && !allowedOrigins.includes(origin) ? origin : undefined;
  const service = editor.kind === 'service';
  const initial = { ...editor.value, apiKey: undefined };
  const used = new Set(config.models.filter(model => model.alias !== editor.key).map(model => model.alias));
  const modelOptions = clineCatalog.modelIds.filter(id => !used.has(id)).map(id => ({ value: id, label: id }));
  if (!service && editor.key && !clineCatalog.modelIds.includes(editor.key)) {
    modelOptions.unshift({ value: editor.key, label: editor.key + '（现有映射）' });
  }
  const field = (name: string, label: string, required = true) => <Form.Item name={name} label={label} rules={[{ required }]}><Input /></Form.Item>;
  const count = (name: string, label: string) => <Form.Item name={name} label={label}><InputNumber min={1} style={{ width: '100%' }} /></Form.Item>;
  return <Modal open title={(editor.key ? '编辑' : '新增') + (service ? '上游服务' : '模型映射')} width={700} onCancel={close}
    onOk={() => form.submit()} okText="验证并发布" cancelText="取消" confirmLoading={busy}>
    {error && <Alert type="error" message={error} showIcon />}
    <Form form={form} layout="vertical" initialValues={initial} onFinish={async values => {
      setBusy(true); setError('');
      try {
        const key = values.clearKey ? '' : values.apiKey || undefined;
        const requested = service ? normalizeOrigin(values.origin) : undefined;
        const needsApproval = requested && !allowedOrigins.includes(requested);
        if (needsApproval && confirmedOrigin !== requested) throw new Error('请确认将该地址加入白名单');
        await save(service ? { ...initial, ...values, origin: requested, apiKey: key } : { ...initial, ...values },
          needsApproval ? requested : undefined);
      } catch (error) { setError((error as Error).message); }
      finally { setBusy(false); }
    }}>
      <Row gutter={16}><Col span={12}>{service ? field('id', '服务 ID') :
        <Form.Item name="alias" label="Cline 对外模型" rules={[{ required: true }]} extra={'Cline ' + clineCatalog.version + ' 离线模型目录'}>
          <Select aria-label="Cline 对外模型" showSearch options={modelOptions} placeholder="搜索并选择模型" />
        </Form.Item>}</Col>
        <Col span={12}>{field(service ? 'name' : 'upstream', service ? '服务名称' : '上游真实模型 ID')}</Col></Row>
      {service ? <>
        <Form.Item name="origin" label="上游 Origin" extra="填写协议、主机与端口，接口路径在下方填写。"
          rules={[{ required: true }, { validator: (_, value) => !value || normalizeOrigin(value)
            ? Promise.resolve() : Promise.reject(new Error('请输入 HTTP(S) 地址，例如 http://10.2.25.111:32004')) }]}>
          <Input placeholder="http://10.2.25.111:32004" maxLength={2048} onChange={() => setConfirmedOrigin(undefined)} />
        </Form.Item>
        {pendingOrigin && <Form.Item><Checkbox checked={confirmedOrigin === pendingOrigin}
          onChange={event => setConfirmedOrigin(event.target.checked ? pendingOrigin : undefined)}>将 {pendingOrigin} 加入上游白名单</Checkbox></Form.Item>}
        <Form.Item name="framework" label="推理框架"><Select options={['openai', 'vllm', 'sglang', 'ollama', 'xinference', 'lm-studio', 'llama.cpp', 'localai', 'custom'].map(value => ({ value }))} /></Form.Item>
        <Row gutter={16}><Col span={12}>{field('chatPath', '对话路径')}</Col><Col span={12}>{field('modelsPath', '模型列表路径')}</Col>
          <Col span={12}>{field('authHeader', '认证请求头')}</Col><Col span={12}>{field('authPrefix', '认证前缀', false)}</Col></Row>
        <Form.Item name="apiKey" label="上游 API Key"><Input.Password autoComplete="new-password" placeholder="留空保留同一服务地址的已有密钥" /></Form.Item>
        <Form.Item name="clearKey" valuePropName="checked"><Checkbox>清除已保存的密钥</Checkbox></Form.Item>
        <Form.Item name="ca" label="上游 CA 或自签服务器证书（PEM）"><Input.TextArea rows={2} /></Form.Item>
        <Row gutter={12}>{Object.entries({ connectMs: '连接超时 ms', firstMs: '首包超时 ms', idleMs: '流空闲超时 ms', concurrency: '并发上限' })
          .map(([name, label]) => <Col span={6} key={name}>{count(name, label)}</Col>)}</Row>
      </> : <>
        <Form.Item name="service" label="关联服务" rules={[{ required: true }]}><Select options={config.services.map(item => ({ value: item.id, label: item.name }))} /></Form.Item>
        <Row gutter={16}><Col span={12}>{count('contextWindow', '上下文窗口')}</Col><Col span={12}>{count('maxOutput', '最大输出 Token')}</Col></Row>
        <Form.Item name="sseEnd" label="SSE 结束条件"><Select options={[{ value: 'require_done', label: '要求上游 [DONE]' },
          { value: 'done_on_finished_eof', label: '所有 choice 已结束且正常 EOF 时补齐 [DONE]' }]} /></Form.Item>
        <Row gutter={12}>{Object.entries(capNames).map(([name, label]) => <Col span={6} key={name}>
          <Form.Item name={['capabilities', name]} label={label}><Select options={Object.entries(capLabels).map(([value, label]) => ({ value, label }))} /></Form.Item>
        </Col>)}</Row>
      </>}
      <Form.Item name="enabled" valuePropName="checked"><Checkbox>启用</Checkbox></Form.Item>
    </Form>
  </Modal>;
}

function ModelSelect({ config, value, change }: { config: Config; value: string; change: (value: string) => void }) {
  return <Select aria-label="模型映射" value={value || undefined} placeholder="选择模型映射" showSearch onChange={change} style={{ minWidth: 220 }}
    options={config.models.map(model => ({ value: model.alias }))} />;
}
function Rules({ revision, publish, attempt }: { revision: Revision; publish: (next: Config) => Promise<void>;
  attempt: (fn: () => Promise<void>) => Promise<void> }) {
  const { config, version } = revision;
  const [alias, setAlias] = useState(config.models[0]?.alias ?? '');
  const model = config.models.find(item => item.alias === alias);
  const [profile, setProfile] = useState<Profile | 'shared'>('shared');
  const [raw, setRaw] = useState(json(model?.rules ?? []));
  const [input, setInput] = useState(json(sample(alias)));
  const [stage, setStage] = useState<Rule['stage']>('request');
  const [result, setResult] = useState<unknown>();
  const rules = (() => { try { const data = JSON.parse(raw); return Array.isArray(data) ? data : null; } catch { return null; } })();
  const listedRules = (item?: Model, selected = profile) => selected === 'shared' ? item?.rules ?? [] : item?.profileRules[selected] ?? [];
  const next = () => ({ ...config, models: config.models.map(item => item.alias === alias
    ? profile === 'shared' ? { ...item, rules: JSON.parse(raw) }
      : { ...item, profileRules: { ...item.profileRules, [profile]: JSON.parse(raw) } }
    : item) });
  const changeRule = (index: number, value: Record<string, unknown>) => setRaw(json(rules!.map((item, pos) => pos === index ? value : item)));
  return <Space direction="vertical" size={16} className="full">
    <Card><Space wrap><ModelSelect config={config} value={alias} change={value => {
      setAlias(value); setRaw(json(listedRules(config.models.find(item => item.alias === value)))); setInput(json(sample(value))); setResult(undefined);
    }} />
      <Select aria-label="规则 Profile" value={profile} options={[{ value: 'shared', label: '所有客户端共用' }, ...profileOptions]}
        onChange={value => { setProfile(value); setRaw(json(listedRules(model, value))); setResult(undefined); }} />
      <Button disabled={!model} onClick={() => attempt(async () => { await api('validate', 'POST', { version, config: next() });
        Modal.success({ title: '规则结构验证通过' }); })}>验证草稿</Button>
      <Button type="primary" disabled={!model} onClick={() => attempt(async () => publish(next()))}>发布规则</Button>
    </Space></Card>
    <Card><Tabs items={[
      { key: 'visual', label: '表单编辑', children: rules && rules.every(item => item && typeof item === 'object' && !Array.isArray(item)) ? <>
        {rules.map((rule: Record<string, unknown>, index: number) => <div className="rule-row" key={index}>
          <Select aria-label="规则阶段" value={rule.stage} options={['request', 'response', 'headers', 'sse'].map(value => ({ value }))}
            onChange={stage => changeRule(index, { ...rule, stage })} />
          <Select aria-label="规则操作" value={rule.op} options={['set', 'default', 'delete', 'rename', 'cast', 'map', 'clamp'].map(value => ({ value }))}
            onChange={op => changeRule(index, { stage: rule.stage, path: rule.path, op,
              ...({ set: { value: null }, default: { value: null }, rename: { to: '' }, cast: { type: 'string' },
                map: { values: {} }, clamp: { min: 0, max: 1 } } as Record<string, object>)[String(op)] })} />
          <Input aria-label="字段路径" placeholder="字段路径" value={String(rule.path ?? '')} onChange={e => changeRule(index, { ...rule, path: e.target.value })} />
          <Input key={String(rule.op)} aria-label="规则参数 JSON" placeholder="参数 JSON" defaultValue={JSON.stringify(Object.fromEntries(Object.entries(rule).filter(([key]) => !['stage', 'op', 'path'].includes(key))))}
            onBlur={e => { try {
              const extra = JSON.parse(e.target.value);
              if (!extra || typeof extra !== 'object' || Array.isArray(extra)) throw new Error();
              changeRule(index, { ...extra, stage: rule.stage, op: rule.op, path: rule.path });
            } catch { Modal.error({ title: '规则参数需要一个 JSON 对象' }); } }} />
          <Button aria-label="上移规则" disabled={!index} onClick={() => {
            const copy = [...rules]; [copy[index - 1], copy[index]] = [copy[index], copy[index - 1]]; setRaw(json(copy));
          }}>↑</Button>
          <Button danger onClick={() => setRaw(json(rules.filter((_, pos) => pos !== index)))}>删除</Button>
        </div>)}
        <Button disabled={!model} onClick={() => setRaw(json([...rules, { stage: 'request', op: 'delete', path: '' }]))}>添加规则</Button>
      </> : <Alert type="warning" message="JSON 编辑中的规则必须是对象数组" /> },
      { key: 'json', label: 'JSON 编辑', children: <Input.TextArea aria-label="规则 JSON" className="mono" rows={12} value={raw} onChange={event => setRaw(event.target.value)} /> },
      { key: 'diff', label: '发布差异', children: <Diff before={listedRules(model)} after={rules} /> },
    ]} /></Card>
    <Card title="用样本测试规则" extra={<Space>
      <Select aria-label="预览阶段" value={stage} options={['request', 'response', 'headers', 'sse'].map(value => ({ value }))}
        onChange={value => { setStage(value); setInput(json(value === 'request' ? sample(alias) : {})); }} />
      <Button disabled={!model} onClick={() => attempt(async () => setResult(await api('preview', 'POST', {
        alias, profile: profile === 'shared' ? 'generic' : profile, body: JSON.parse(input), stage, config: next(),
      })))}>执行预览</Button>
    </Space>}>
      <Row gutter={16}><Col xs={24} xl={12}><Input.TextArea aria-label="预览样本 JSON" className="mono" rows={8} value={input} onChange={e => setInput(e.target.value)} /></Col>
        <Col xs={24} xl={12}>{result !== undefined && <Code value={json(result)} />}</Col></Row>
    </Card>
  </Space>;
}

function Lab({ config, apiBaseUrl, trustFile }: { config: Config; apiBaseUrl: string; trustFile?: string }) {
  const [mode, setMode] = useState('gateway');
  const [profile, setProfile] = useState<Profile>('generic');
  const [alias, setAlias] = useState(config.models[0]?.alias ?? '');
  const [service, setService] = useState(config.models[0]?.service ?? config.services[0]?.id ?? '');
  const [raw, setRaw] = useState(json(sample(alias)));
  const [requestHeaders, setHeaders] = useState('{}');
  const [path, setPath] = useState('/v1/chat/completions');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [records, setRecords] = useState<{ gateway: Trace[]; direct: Trace[] }>({ gateway: [], direct: [] });
  const abort = useRef<AbortController | null>(null);
  useEffect(() => () => abort.current?.abort(), []);
  const direct = mode === 'direct';
  const model = config.models.find(item => item.alias === alias);
  const selected = config.services.find(item => item.id === service);
  const current = direct ? records.direct : records.gateway;
  const state = current.findLast(record => record.stage === 'result');
  const transport = current.find(record => record.stage === 'transport');
  const compatibility = current.findLast(record => record.stage === 'compatibility');
  const first = current.find(record => record.stage === 'upstream_response');
  const other = (direct ? records.gateway : records.direct).find(row => row.stage === 'upstream_response');
  const bodyAt = (key: string) => current.find(row => row.stage === key)?.value;
  const format = (record: Trace) => json(record.value);
  const run = async () => {
    setError(''); setBusy(true); setRecords(old => ({ ...old, [mode]: [] }));
    const controller = new AbortController();
    abort.current = controller;
    try {
      await stream('lab', { alias, service, direct, path, profile, headers: JSON.parse(requestHeaders), body: JSON.parse(raw) },
        controller.signal, trace => setRecords(old => {
          const rows: Trace[] = old[direct ? 'direct' : 'gateway'];
          if (rows.length >= 500 && !['result', 'compatibility'].includes(trace.stage)) return old;
          return { ...old, [mode]: [...rows, trace] };
        }));
    } catch (error) { setError(controller.signal.aborted ? '请求已取消' : (error as Error).message); }
    finally { if (abort.current === controller) abort.current = null; setBusy(false); }
  };
  const setModel = (value: string) => {
    const next = config.models.find(item => item.alias === value)!;
    setAlias(value); setService(next.service);
    if (direct) setPath(config.services.find(item => item.id === next.service)?.chatPath ?? '/v1/chat/completions');
    setRaw(json(sample(direct ? next.upstream : next.alias)));
  };
  const curl = () => {
    try {
      const origin = direct ? selected?.origin : apiBaseUrl.replace(/\/v1$/, '');
      const auth = direct ? selected?.authHeader ?? 'authorization' : 'Authorization';
      const prefix = direct ? selected?.authPrefix ?? '' : 'Bearer ';
      const quote = (str: string) => "'" + str.replace(/'/g, "'\"'\"'") + "'";
      const custom = Object.entries(JSON.parse(requestHeaders) as Record<string, string>).map(([key, value]) => {
        return ' -H ' + quote(key + ': ' + (/auth|key|cookie|secret|token/i.test(key) ? '<HEADER_VALUE>' : value));
      }).join('');
      Modal.info({ title: 'curl 命令', width: 850, content: <Code value={
        'curl -N ' + (direct && selected?.ca ? '--cacert upstream-trust.crt ' : !direct && trustFile ? '--cacert ' + trustFile + ' ' : '')
        + quote((origin ?? '') + path) + ' -H "Content-Type: application/json" -H "'
        + auth + ': ' + prefix.replace(/[^a-z0-9 _-]/gi, '') + (direct ? '$UPSTREAM_API_KEY' : '$GATEWAY_API_KEY')
        + '"' + custom + ' --data-binary @request.json\n\n# request.json\n' + json(JSON.parse(raw))} /> });
    } catch (error) { setError((error as Error).message); }
  };
  const importReplay = async (file: File) => {
    try {
      if (file.size > 8_388_608) throw new Error('请求样本超过 8 MiB');
      const capture = JSON.parse(await file.text());
      const body = typeof capture.body === 'string' ? JSON.parse(capture.body) : capture.body ?? capture;
      if (!Array.isArray(body?.messages)) throw new Error('请求样本缺少 messages 数组');
      const mapped = config.models.find(item => item.alias === body.model);
      if (!direct && mapped) setModel(mapped.alias);
      setRaw(json({ ...body, model: direct ? body.model : mapped?.alias ?? alias }));
      const forbidden = /auth|api[-_]?key|secret|token|cookie|password|^host$|^content-|^accept$|^connection$|^x-gateway-/i;
      setHeaders(json(Object.fromEntries(Object.entries(capture.headers ?? {}).filter(([key, value]) => !forbidden.test(key) && typeof value === 'string'))));
      if (capture.profile && Object.hasOwn(profileNames, capture.profile)) setProfile(capture.profile);
      setError('');
    } catch (error) { setError((error as Error).message); }
  };
  return <Space direction="vertical" size={16} className="full">
    <Card>
      <Space wrap>
        <Select aria-label="调用方式" value={mode} disabled={busy} options={[{ value: 'gateway', label: '通过网关' }, { value: 'direct', label: '直连上游' }]} onChange={value => {
          setMode(value); setPath(value === 'direct' ? selected?.chatPath ?? '/v1/chat/completions' : '/v1/chat/completions');
          try { const data = JSON.parse(raw); setRaw(json({ ...data, model: value === 'direct' ? model?.upstream ?? data.model : alias })); } catch {}
        }} />
        <Tag>OpenAI Chat Completions</Tag>
        <Select aria-label="客户端 Profile" value={profile} options={profileOptions} onChange={setProfile} disabled={direct || busy} />
        <ModelSelect config={config} value={alias} change={setModel} />
        {direct && <Select aria-label="上游服务" value={service || undefined} placeholder="选择上游" onChange={value => {
          setService(value); setPath(config.services.find(item => item.id === value)?.chatPath ?? '/v1/chat/completions');
        }} options={config.services.map(item => ({ value: item.id, label: item.name }))} />}
        <Select aria-label="测试模板" placeholder="加载测试模板" options={[
          ['chat', '普通对话'], ['stream', 'SSE 流式'], ['tool', '工具调用'], ['multi', '多轮对话'], ['reasoning', 'Reasoning'], ['params', '参数兼容性'],
        ].map(([value, label]) => ({ value, label }))} onChange={value => setRaw(json(sample(direct ? model?.upstream : alias, value === 'stream', value)))} />
      </Space>
      <Row gutter={16} className="after"><Col xs={24} lg={12}><label htmlFor="labpath">请求地址</label><Input id="labpath" addonBefore={direct ? selected?.origin : apiBaseUrl.replace(/\/v1$/, '')}
        value={path} onChange={e => setPath(e.target.value)} /></Col>
        <Col xs={24} lg={12}><label htmlFor="labheaders">附加请求头 JSON</label><Input.TextArea id="labheaders" className="mono" autoSize value={requestHeaders} onChange={e => setHeaders(e.target.value)} /></Col></Row>
      <div className="after"><label htmlFor="labbody">请求 JSON</label><Input.TextArea id="labbody" className="mono" rows={9} value={raw} onChange={e => setRaw(e.target.value)} /></div>
      <Space wrap className="after"><Button type="primary" onClick={() => void run()} disabled={busy || (!direct && !alias) || (direct && !service)}>发送请求</Button>
        <Button disabled={!busy} onClick={() => abort.current?.abort()}>取消</Button><Button onClick={curl}>生成 curl</Button>
        <label className="file"><span>导入脱敏请求</span><input type="file" aria-label="导入脱敏请求" accept=".json,application/json"
          onChange={event => { const file = event.target.files?.[0]; if (file) void importReplay(file); event.target.value = ''; }} /></label>
        <Button onClick={() => { try { setRaw(json(JSON.parse(raw))); } catch { setError('请求 JSON 无效'); } }}>格式化</Button>
      </Space>
    </Card>
    {error && <Alert type="warning" message={error} showIcon />}
    {state && <Alert showIcon type={state.value.status >= 400 ? 'error' : 'success'}
      message={'HTTP ' + state.value.status + ' · 总耗时 ' + state.ms + ' ms' + (first ? ' · 首个响应 ' + first.ms + ' ms' : '')}
      description={state.value.error?.message} />}
    {(transport || compatibility) && <Row gutter={[16, 16]}>
      {transport && <Col xs={24} xl={12}><Card title="HTTPS 连接及认证"><Code value={json(transport.value)} /></Card></Col>}
      {compatibility && <Col xs={24} xl={12}><Card title="客户端协议兼容性"><Code value={json(compatibility.value)} /></Card></Col>}
    </Row>}
    <Row gutter={[16, 16]}>{Object.entries(stages).map(([key, label]) => <Col xs={24} xl={12} key={key}><Card title={label}>
      {current.filter(record => record.stage === key).length ? <Code value={current.filter(record => record.stage === key)
        .map(record => '[' + record.ms + ' ms] ' + format(record)).join('\n\n')} /> : <Typography.Text type="secondary">等待数据</Typography.Text>}
    </Card></Col>)}</Row>
    {bodyAt('upstream_request') && <Card title="请求字段差异"><Diff before={bodyAt('client')} after={bodyAt('upstream_request').body} /></Card>}
    {bodyAt('upstream_response') && bodyAt('gateway') && <Card title="响应字段差异">
      <Diff before={payload(bodyAt('upstream_response'))} after={payload(bodyAt('gateway'))} />
    </Card>}
    {first && other && <Card title="直连与网关响应对照"><Diff before={payload(direct ? first.value : other.value)} after={payload(direct ? other.value : first.value)} /></Card>}
  </Space>;
}

function payload(value: any) {
  const raw = value?.body ?? value?.event;
  if (typeof raw === 'string') { try { return JSON.parse(raw); } catch {} }
  return raw;
}

function Checks({ config, previous, refresh }: { config: Config; previous: Check[]; refresh: () => Promise<void> }) {
  const [alias, setAlias] = useState(config.models[0]?.alias ?? '');
  const [results, setResults] = useState<Finding[]>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);
  const report = results ?? previous.find(check => check.model === alias)?.findings ?? [];
  return <Space direction="vertical" size={16} className="full">
    <Card><Space wrap><ModelSelect config={config} value={alias} change={value => { controller.current?.abort(); setAlias(value); setResults(undefined); }} />
      <Button type="primary" disabled={!alias || busy} onClick={async () => {
        setBusy(true); setError(''); setResults([]);
        controller.current = new AbortController();
        try { await stream('check', { alias }, controller.current.signal, row => setResults(items => [...(items ?? []), row.value])); await refresh(); }
        catch (error) { setError(controller.current.signal.aborted ? '检测已取消' : (error as Error).message); }
        finally { setBusy(false); }
      }}>{busy ? '检测中' : '运行体检'}</Button>
      <Button disabled={!busy} onClick={() => controller.current?.abort()}>取消</Button>
      <Button disabled={!report.length} onClick={() => download('gateway-check.json', { alias, results: report.map(({ evidence: _evidence, ...item }) => item) })}>导出报告</Button>
    </Space></Card>
    {error && <Alert message={error} type="warning" showIcon />}
    <Card title="检测明细"><Table rowKey="test" dataSource={report} pagination={false} expandable={{
      rowExpandable: item => Boolean(item.evidence?.length), expandedRowRender: item => <Code value={json(item.evidence)} />,
    }} columns={[
      { title: '检测项', dataIndex: 'test' }, { title: '结果', dataIndex: 'status', render: status =>
        <Tag color={{ PASS: 'green', FAIL: 'red', WARNING: 'orange', UNSUPPORTED: 'gold' }[String(status)]}>{status}</Tag> },
      { title: 'HTTP', dataIndex: 'httpStatus' }, { title: '原因', dataIndex: 'reason' },
      { title: '建议', dataIndex: 'suggestion' }, { title: 'Cline 影响', dataIndex: 'clineImpact' },
    ]} /></Card>
    {previous.length > 0 && <Card title="模型检测对比"><Table rowKey="model" dataSource={previous} columns={[
      { title: '模型', dataIndex: 'model' }, { title: '配置版本', dataIndex: 'version' },
      ...['PASS', 'FAIL', 'WARNING', 'UNSUPPORTED', 'UNVERIFIED'].map(status => ({
        title: status, render: (_: unknown, row: Check) => row.findings.filter(item => item.status === status).length,
      })),
    ]} /></Card>}
  </Space>;
}
function Metrics({ rows }: { rows?: Record<string, unknown>[] }) {
  return <Table dataSource={rows ?? []} rowKey={row => String(row.id)} size="small" scroll={{ x: 860 }} columns={[
    { title: '时间', dataIndex: 'time', render: value => new Date(value).toLocaleString() },
    { title: '客户端', dataIndex: 'client' }, { title: '模型', dataIndex: 'model' },
    { title: 'HTTP', dataIndex: 'status' }, { title: '耗时 ms', dataIndex: 'elapsedMs' },
    { title: '首 Token ms', dataIndex: 'firstTokenMs' }, { title: '错误', dataIndex: 'error' }, { title: '配置', dataIndex: 'revision' },
  ]} expandable={{ expandedRowRender: row => <Code value={json(row)} /> }} />;
}
function baseUrl() { return window.location.protocol + '//' + window.location.hostname + ':18080/v1'; }
function Integration({ config, apiBaseUrl, trustFile }: { config: Config; apiBaseUrl: string; trustFile?: string }) {
  const [alias, setAlias] = useState(config.models[0]?.alias ?? '');
  const [url, setUrl] = useState(apiBaseUrl);
  return <Card><Space wrap><ModelSelect config={config} value={alias} change={setAlias} />
    <Input aria-label="客户端 Base URL" value={url} onChange={event => setUrl(event.target.value)} style={{ width: 300 }} />
    {trustFile && <Button href="/admin/tls/ca">下载客户端信任证书</Button>}
  </Space><Tabs className="after" items={[
    { key: 'config', label: 'OpenAI Compatible / Cline / Runtime OS',
      children: <Descriptions column={1} bordered items={[
        { key: 'p', label: 'Provider', children: 'OpenAI Compatible' }, { key: 'b', label: 'Base URL', children: url },
        { key: 'm', label: 'Model ID', children: alias }, { key: 'k', label: 'API Key', children: '<GATEWAY_API_KEY>' },
      ]} /> },
    { key: 'java', label: 'LangChain4j / JVM', children: <Code value={'// dev.langchain4j:langchain4j-open-ai:1.20.0\nvar model = OpenAiChatModel.builder()\n  .baseUrl('
      + json(url) + ')\n  .modelName(' + json(alias) + ')\n  .apiKey(System.getenv("GATEWAY_API_KEY"))\n  .build();'
      + (trustFile ? '\n\n# 导入信任证书\nsh import-java-ca.sh ' + trustFile + ' gateway-trust.p12\njava -Djavax.net.ssl.trustStore=gateway-trust.p12 -Djavax.net.ssl.trustStoreType=PKCS12 ...' : '')} /> },
    { key: 'py', label: 'Python SDK', children: <Code value={(trustFile ? '# export SSL_CERT_FILE=/path/to/' + trustFile + '\n' : '') + 'import os\nfrom openai import OpenAI\n\nclient = OpenAI(base_url=' + json(url) +
      ', api_key=os.environ["GATEWAY_API_KEY"])\nprint(client.chat.completions.create(model=' + json(alias) + ', messages=[{"role":"user","content":"Hello"}]))'} /> },
    { key: 'node', label: 'Node.js SDK', children: <Code value={(trustFile ? '// 启动前设置 NODE_EXTRA_CA_CERTS=/path/to/' + trustFile + '\n' : '') + 'import OpenAI from "openai";\n\nconst client = new OpenAI({ baseURL: ' + json(url) +
      ', apiKey: process.env.GATEWAY_API_KEY });\nconsole.log(await client.chat.completions.create(' + json(sample(alias)) + '));'} /> },
    { key: 'curl', label: 'curl', children: <Code value={'curl -N ' + (trustFile ? '--cacert ' + trustFile + ' ' : '') + "'" + (url.replace(/\/$/, '') + '/chat/completions').replace(/'/g, "'\"'\"'") + "'"
      + ' -H "Authorization: Bearer $GATEWAY_API_KEY" -H "Content-Type: application/json" --data-binary @request.json\n\n# request.json\n'
      + json(sample(alias))} /> },
  ]} /></Card>;
}
function History({ revision, attempt, restored }: { revision: Revision; attempt: (fn: () => Promise<void>) => Promise<void>; restored: () => Promise<void> }) {
  const [rows, setRows] = useState<{ version: number; time: string; action: string }[]>([]);
  useEffect(() => { void attempt(async () => setRows(await api('history'))); }, [revision.version]);
  const restore = async (file: File) => {
    const backup = JSON.parse(await file.text());
    Modal.confirm({ title: '发布导入的配置', onOk: () => attempt(async () => {
      await api('restore', 'POST', { version: revision.version, config: backup.config }); await restored();
    }) });
  };
  return <Card extra={<Space wrap><Button onClick={() => attempt(async () => download('gateway-config.json', await api('backup')))}>导出配置</Button>
    <label className="file"><span>导入配置</span><input type="file" aria-label="导入配置" accept=".json,application/json" onChange={event => {
      const file = event.target.files?.[0]; if (file) void attempt(() => restore(file)); event.target.value = '';
    }} /></label></Space>}>
    <Table rowKey="version" dataSource={rows} columns={[
      { title: '版本', dataIndex: 'version' }, { title: '时间', dataIndex: 'time' }, { title: '操作', dataIndex: 'action' },
      { title: '管理', render: (_, row) => <Space>
        <Button size="small" onClick={() => attempt(async () => {
          const before = await api<Revision>('history/' + row.version);
          Modal.info({ title: '与当前配置的字段差异', width: 850, content: <Diff before={before.config} after={revision.config} /> });
        })}>差异</Button>
        <Button size="small" disabled={row.version === revision.version} onClick={() => Modal.confirm({
          title: '将配置 v' + row.version + ' 作为新版本发布',
          onOk: () => attempt(async () => { await api('rollback', 'POST', { version: revision.version, target: row.version }); await restored(); }),
        })}>回滚</Button>
      </Space> },
    ]} />
  </Card>;
}
createRoot(document.getElementById('root')!).render(<ConfigProvider button={{ autoInsertSpace: false }} theme={{
  algorithm: theme.defaultAlgorithm, token: { colorPrimary: '#087c71', colorText: '#17283b',
    colorBgLayout: '#f3f6fa', borderRadius: 8, fontFamily: 'system-ui, "Microsoft YaHei", sans-serif' },
}}><App /></ConfigProvider>);
