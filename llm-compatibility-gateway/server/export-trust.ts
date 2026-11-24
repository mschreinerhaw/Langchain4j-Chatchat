import { CertificateStore } from './tls.js';

const store = new CertificateStore(process.env.TLS_DATA_DIR ?? '/app/certs',
  Buffer.from(process.env.CONFIG_ENCRYPTION_KEY ?? '', 'hex'), false);
if (!store.ca) throw new Error('No gateway trust certificate is configured');
process.stdout.write(store.ca);
