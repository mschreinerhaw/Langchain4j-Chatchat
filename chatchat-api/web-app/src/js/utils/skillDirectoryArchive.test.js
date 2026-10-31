import { it, expect } from 'vitest';
import { createSkillDirectoryArchive } from './skillDirectoryArchive.js';
const file = (path, content) => Object.assign(new File([content], path.split('/').at(-1)), { webkitRelativePath: path });
it('writes the standard CRC-32 test vector and preserves binary bytes and UTF-8 paths', async () => {
  const archive = await createSkillDirectoryArchive([file('sample/SKILL.md', '123456789'), file('sample/assets/中文.bin', new Uint8Array([0, 255, 13, 10]))]);
  const bytes = new Uint8Array(await archive.arrayBuffer()), view = new DataView(bytes.buffer);
  expect(view.getUint32(14, true)).toBe(0xcbf43926);
  const firstLength = 30 + view.getUint16(26, true), second = firstLength + 9;
  const secondStart = second + 30 + view.getUint16(second + 26, true);
  expect(new TextDecoder().decode(bytes.slice(second + 30, secondStart))).toBe('sample/assets/中文.bin');
  expect([...bytes.slice(secondStart, secondStart + 4)]).toEqual([0, 255, 13, 10]);
  expect(view.getUint16(bytes.length - 12, true)).toBe(2);
});
it('rejects traversal, duplicate paths and excessive directory files before upload', async () => {
  for (const path of ['../secret', '/root/x', 'a\\b', 'a//b', 'C:/secret']) await expect(createSkillDirectoryArchive([file(path, 'x')])).rejects.toThrow();
  await expect(createSkillDirectoryArchive([file('same', 'a'), file('same', 'b')])).rejects.toThrow('重复');
  await expect(createSkillDirectoryArchive(Array.from({ length: 501 }, (_, i) => file(String(i), 'x')))).rejects.toThrow('500');
});
it('rejects an oversized directory and reports a changed file size instead of truncation', async () => {
  await expect(createSkillDirectoryArchive([file('huge', new Uint8Array(5 * 1024 * 1024 + 1))])).rejects.toThrow('5MB');
  await expect(createSkillDirectoryArchive([{ name: 'changed', size: 1, arrayBuffer: async () => new Uint8Array([1, 2]).buffer }])).rejects.toThrow('大小已改变');
});
