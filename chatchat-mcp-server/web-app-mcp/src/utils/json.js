export function parseJsonObject(text, fallback = {}) {
  if (text == null || String(text).trim() === '') {
    return fallback;
  }
  const value = JSON.parse(text);
  if (!value || Array.isArray(value) || typeof value !== 'object') {
    throw new Error('JSON 内容必须是对象');
  }
  return value;
}

export function prettyJson(value, fallback = {}) {
  return JSON.stringify(value ?? fallback, null, 2);
}

export function formatDateTime(value) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleString();
}

export function formatBytes(bytes) {
  const value = Number(bytes || 0);
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}
