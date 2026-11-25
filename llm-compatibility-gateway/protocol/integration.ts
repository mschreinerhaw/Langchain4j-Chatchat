export function integrationBase(input: string, protocol: 'http' | 'https') {
  try {
    const url = new URL(input.trim());
    if (url.protocol !== protocol + ':' || url.username || url.password || url.search || url.hash) return '';
    const base = url.pathname.replace(/\/+$/, '').replace(/(?:\/v1)+$/, '');
    return url.origin + base + '/v1';
  } catch { return ''; }
}

export function integrationCurl(base: string, body: object, streaming: boolean, shell: 'bash' | 'powershell', ca?: string) {
  const ps = shell === 'powershell';
  const quote = (value: string) => "'" + value.replace(/'/g, ps ? "''" : "'\"'\"'") + "'";
  const args = [
    ...(ca ? ['--cacert ' + quote(ca)] : []),
    quote(base + '/chat/completions'),
    '-H "Authorization: Bearer ' + (ps ? '$env:GATEWAY_API_KEY' : '$GATEWAY_API_KEY') + '"',
    '-H "Content-Type: application/json"',
  ];
  const json = JSON.stringify(body, null, 2);
  if (ps) return "$body = @'\n" + json + "\n'@\n$OutputEncoding = [System.Text.UTF8Encoding]::new($false)\n"
    + '$body | curl.exe -i' + (streaming ? ' -N' : '') + ' ' + [...args, "--data-binary '@-'"].join(' `\n  ');
  return 'curl -i' + (streaming ? ' -N' : '') + ' ' + [...args, '--data-binary ' + quote(json)].join(' \\\n  ');
}
