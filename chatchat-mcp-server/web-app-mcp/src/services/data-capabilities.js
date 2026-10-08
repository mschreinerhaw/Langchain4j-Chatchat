import { apiFetch } from './http';
import { API_BASE } from './config';

export function capabilityRequest(path = '', body, method = 'GET') {
  return apiFetch(`${API_BASE}/data-capabilities${path}`, {
    method, ...(body === undefined ? {} : { body: JSON.stringify(body) })
  });
}

export function downloadJson(value, filename) {
  const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2)], { type: 'application/json' }));
  const link = document.createElement('a');
  link.href = url; link.download = filename; link.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}
