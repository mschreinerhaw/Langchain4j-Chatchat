import {createHash} from 'node:crypto';
import {chromium} from 'playwright';

const origin = 'https://report.invalid';
const types = {
  html: 'text/html; charset=utf-8', css: 'text/css', js: 'application/javascript',
  json: 'application/json', csv: 'text/csv', txt: 'text/plain', png: 'image/png',
  jpg: 'image/jpeg', jpeg: 'image/jpeg', svg: 'image/svg+xml', webp: 'image/webp',
  gif: 'image/gif', avif: 'image/avif', woff: 'font/woff', woff2: 'font/woff2',
};

export async function printReport(spec) {
  if (!Array.isArray(spec?.files) || spec.files.length > 64) throw Error('Invalid report inventory');
  const files = new Map();
  let total = 0;
  for (const file of spec.files) {
    if (typeof file.path !== 'string' || file.path.length > 512 ||
        file.path.split('/').some(part => !part || part === '.' || part === '..') ||
        /[\\?#\x00-\x1f]/.test(file.path) || typeof file.content !== 'string') throw Error('Invalid resource path');
    const path = '/' + file.path.split('/').map(encodeURIComponent).join('/');
    const type = types[file.path.split('.').at(-1).toLowerCase()];
    const bytes = Buffer.from(file.content, 'base64');
    total += bytes.length;
    if (typeof type !== 'string' || files.has(path) || bytes.length > 8 * 1024 ** 2 || total > 24 * 1024 ** 2 ||
        bytes.length !== file.size || createHash('sha256').update(bytes).digest('hex') !== file.sha256)
      throw Error('Invalid resource bytes');
    files.set(path, {type, bytes});
  }
  if (typeof spec.entry !== 'string') throw Error('Missing report entry');
  const entry = '/' + spec.entry.split('/').map(encodeURIComponent).join('/');
  if (!files.get(entry)?.type.startsWith('text/html')) throw Error('Missing report entry');

  const browser = await chromium.launch({
    headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE, chromiumSandbox: process.platform !== 'win32',
    env: Object.fromEntries(['PATH', 'HOME', 'LANG'].filter(key => process.env[key]).map(key => [key, process.env[key]])),
    args: ['--disable-dev-shm-usage', '--disable-background-networking', '--dns-prefetch-disable',
      '--force-webrtc-ip-handling-policy=disable_non_proxied_udp'],
    proxy: {server: 'http://127.0.0.1:9', bypass: '<-loopback>'}, timeout: 10000,
  });
  const timer = setTimeout(() => void browser.close().catch(() => {}), 25000);
  try {
    const context = await browser.newContext({viewport: {width: 1200, height: 900},
      serviceWorkers: 'block', acceptDownloads: false});
    await context.routeWebSocket('**/*', socket => socket.close());
    await context.route('**/*', async route => {
      const url = new URL(route.request().url());
      const file = url.origin === origin && files.get(url.pathname);
      if (!file || route.request().method() !== 'GET') return route.abort();
      return route.fulfill({contentType: file.type, body: file.bytes, headers: {
        'content-security-policy': "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; form-action 'none'; base-uri 'none'",
        'x-content-type-options': 'nosniff',
      }});
    });
    const page = await context.newPage();
    await page.goto(origin + entry, {waitUntil: 'networkidle', timeout: 15000});
    await page.evaluate(() => document.fonts.ready);
    if (new URL(page.url()).pathname !== entry) throw Error('Report changed its entry page');
    await page.addStyleTag({content: 'img,svg,canvas{max-width:100%;height:auto} @media print{pre{white-space:pre-wrap;overflow-wrap:anywhere}}'});
    const pdf = await page.pdf({format: 'A4', printBackground: true,
      margin: {top: '9mm', right: '9mm', bottom: '9mm', left: '9mm'}});
    if (pdf.length > 12 * 1024 ** 2) throw Error('Report exceeds PDF budget');
    return pdf;
  } finally {
    clearTimeout(timer);
    await browser.close();
  }
}
