import { readFileSync } from 'node:fs';
const secret = process.env.RUNTIME_CODING_SHARED_SECRET_FILE
  ? readFileSync(process.env.RUNTIME_CODING_SHARED_SECRET_FILE, 'utf8').trim()
  : process.env.RUNTIME_CODING_SHARED_SECRET;
try {
  const response = await fetch(`http://127.0.0.1:${process.env.RUNTIME_CODING_PORT ?? 3100}/readyz`, {
    headers: { authorization: `Bearer ${secret}` }, signal: AbortSignal.timeout(3000),
  });
  process.exitCode = response.ok ? 0 : 1;
} catch { process.exitCode = 1; }
