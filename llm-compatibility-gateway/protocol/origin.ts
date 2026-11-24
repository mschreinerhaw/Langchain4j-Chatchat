export function normalizeUpstreamUrl(input: string): string | undefined {
  const value = input.trim();
  if (!/^https?:\/\/[^/?#@\\\s]+(?:\/[^?#\\\s]*)?$/i.test(value)) return undefined;
  try {
    const url = new URL(value);
    return url.origin + url.pathname.replace(/\/+$/, '');
  } catch { return undefined; }
}

export function upstreamEndpoint(base: string, path: string): URL {
  const url = new URL(base);
  if (!/^\/(?!\/)[^\\?#\s]*$/.test(path)) throw new Error('请填写有效的接口路径');
  const endpoint = new URL(path, url.origin);
  const prefix = url.pathname.replace(/\/+$/, '').split('/').slice(1);
  const segments = endpoint.pathname.split('/').slice(1);
  // A provider may put the API version in both its Base URL and the default endpoint path.
  let overlap = Math.min(prefix.length, segments.length);
  while (overlap && !prefix.slice(-overlap).every((segment, index) => segment === segments[index])) overlap--;
  url.pathname = '/' + [...prefix, ...segments.slice(overlap)].join('/');
  return url;
}
