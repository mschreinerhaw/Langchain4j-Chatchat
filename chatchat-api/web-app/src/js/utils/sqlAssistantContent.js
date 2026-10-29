import MarkdownIt from 'markdown-it';

const markdown=new MarkdownIt({html:false});
const sqlLanguages=new Set(['sql','mysql','postgres','postgresql','hive','spark','trino','tsql','plsql','sqlite']);
export function sqlAssistantParts(value='') {
  const text=String(value ?? ''), lines=text.split('\n'), parts=[];
  let start=0;
  for(const token of markdown.parse(text,{})) {
    if(token.type!=='fence' || !token.map || !sqlLanguages.has(token.info.trim().split(/\s+/)[0].toLowerCase()))continue;
    if(token.map[0]>start)parts.push({type:'markdown',content:lines.slice(start,token.map[0]).join('\n')});
    parts.push({type:'sql',content:token.content.replace(/\n$/,'')});start=token.map[1];
  }
  if(start<lines.length)parts.push({type:'markdown',content:lines.slice(start).join('\n')});
  return parts;
}
export function highlightAssistantSql(sql, highlighter=globalThis.hljs) {
  if(highlighter?.getLanguage?.('sql')) {
    try{return highlighter.highlight(String(sql),{language:'sql',ignoreIllegals:true}).value;}catch{/* literal fallback */}
  }
  return markdown.utils.escapeHtml(String(sql));
}
