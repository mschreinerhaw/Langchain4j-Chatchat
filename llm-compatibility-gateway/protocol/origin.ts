export function normalizeOrigin(input: string): string | undefined {
  const value = input.trim();
  if (!/^https?:\/\/[^/?#@\\\s]+\/?$/i.test(value)) return undefined;
  try {
    const url = new URL(value);
    return url.href === url.origin + '/' ? url.origin : undefined;
  } catch { return undefined; }
}
