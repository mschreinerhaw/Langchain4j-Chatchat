export const CHAT_TEXT_FILE_THRESHOLD = 8000;
export const CHAT_ASSET_MAX_BYTES = 16 * 1024 * 1024;
export const CHAT_ASSET_MAX_FILES = 8;
export const CHAT_FILE_ANALYSIS_OBJECTIVE = '请阅读附件中的完整资料，识别其中的用户问题并完成分析；区分原始事实、推断与无法验证的内容。';
export function exceedsChatTextLimit(text) {
  let length = 0;
  for (const _character of String(text ?? '')) if (++length > CHAT_TEXT_FILE_THRESHOLD) return true;
  return false;
}
export function chatTextFile(text) {
  // Preserve exact original UTF-8 text, including surrounding whitespace.
  return new File([String(text ?? '')], `对话输入-${Date.now()}.md`, { type: 'text/markdown;charset=utf-8' });
}
export function pastedFiles(clipboard) {
  const items = Array.from(clipboard?.items || []).filter(item => item.kind === 'file');
  const files = items.length ? items.map(item => item.getAsFile()).filter(Boolean) : Array.from(clipboard?.files || []);
  return files.map(file => file.name ? file : new File([file], `粘贴图片-${Date.now()}.${file.type === 'image/jpeg' ? 'jpg' : 'png'}`, {type:file.type}));
}
export function validateChatFiles(files, existing = []) {
  if (files.length + existing.length > CHAT_ASSET_MAX_FILES) throw new Error('每次最多添加 8 个文件。');
  if ([...existing, ...files].reduce((sum,file) => sum + (file.size || 0), 0) > CHAT_ASSET_MAX_BYTES)
    throw new Error('本次分析的附件总大小不能超过 16 MB。');
  for (const file of files) {
    if (!file.size) throw new Error('不能上传空文件。');
    if (/\.(png|jpg|jpeg|webp|gif)$/i.test(file.name||'') && file.size>10*1024*1024)throw new Error('图片不能超过 10 MB。');
    if (!/\.(pdf|docx|xlsx|csv|parquet|txt|md|json|jsonl|png|jpg|jpeg|webp|gif)$/i.test(file.name || ''))
      throw new Error('暂不支持该文件格式，请粘贴文档、数据文件或 PNG/JPG/WebP/GIF 图片。');
  }
}
