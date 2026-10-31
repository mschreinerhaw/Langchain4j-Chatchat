// Store directory files unchanged in one standard UTF-8 ZIP. No dependency or script execution.
export async function createSkillDirectoryArchive(files) {
  if (!files.length || files.length > 500) throw new Error('技能目录需包含 1–500 个文件');
  const encode = new TextEncoder(), seen = new Set(), entries = []; let offset = 0, total = 0;
  const crc32 = bytes => {
    let crc = 0xffffffff;
    for (const byte of bytes) { crc ^= byte; for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0); }
    return (crc ^ 0xffffffff) >>> 0;
  };
  for (const file of files) {
    const path = file.webkitRelativePath || file.name;
    if (!path || path.length > 512 || /[\\:\x00-\x1f\x7f]/.test(path) || path.split('/').some(p => !p || p === '.' || p === '..') || seen.has(path)) throw new Error('技能目录含无效或重复文件路径');
    seen.add(path); total += file.size;
    if (total > 5 * 1024 * 1024) throw new Error('技能目录最大 5MB');
    const bytes = new Uint8Array(await file.arrayBuffer());
    if (bytes.length !== file.size) throw new Error('技能文件大小已改变');
    const name = encode.encode(path), crc = crc32(bytes), header = new Uint8Array(30 + name.length), view = new DataView(header.buffer);
    view.setUint32(0, 0x04034b50, true); view.setUint16(4, 20, true); view.setUint16(6, 0x800, true); view.setUint16(12, 0x21, true);
    view.setUint32(14, crc, true); view.setUint32(18, bytes.length, true); view.setUint32(22, bytes.length, true); view.setUint16(26, name.length, true); header.set(name, 30);
    entries.push({ name, bytes, crc, offset, header }); offset += header.length + bytes.length;
  }
  const local = entries.flatMap(e => [e.header, e.bytes]), central = []; let centralSize = 0;
  for (const e of entries) {
    const header = new Uint8Array(46 + e.name.length), view = new DataView(header.buffer);
    view.setUint32(0, 0x02014b50, true); view.setUint16(4, 20, true); view.setUint16(6, 20, true); view.setUint16(8, 0x800, true); view.setUint16(14, 0x21, true);
    view.setUint32(16, e.crc, true); view.setUint32(20, e.bytes.length, true); view.setUint32(24, e.bytes.length, true); view.setUint16(28, e.name.length, true); view.setUint32(42, e.offset, true); header.set(e.name, 46);
    central.push(header); centralSize += header.length;
  }
  const end = new Uint8Array(22), view = new DataView(end.buffer);
  view.setUint32(0, 0x06054b50, true); view.setUint16(8, entries.length, true); view.setUint16(10, entries.length, true); view.setUint32(12, centralSize, true); view.setUint32(16, offset, true);
  if (offset + centralSize + end.length > 5 * 1024 * 1024) throw new Error('技能目录打包后超过 5MB');
  return new File([...local, ...central, end], 'original-directory.zip', { type: 'application/zip' });
}
