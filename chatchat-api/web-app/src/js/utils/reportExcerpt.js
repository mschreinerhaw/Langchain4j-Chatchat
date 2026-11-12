import MarkdownIt from 'markdown-it';
const markdown=new MarkdownIt({html:false});

/** A prose excerpt, never a table header, code block or report heading. */
export function reportExcerpt(...sources){
  for(const source of sources){
    const tokens=markdown.parse(String(source||''),{}),paragraphs=[];
    let tableDepth=0;
    for(let i=0;i<tokens.length;i++){
      const token=tokens[i];
      if(token.type==='table_open')tableDepth++;
      if(token.type==='table_close')tableDepth--;
      if(tableDepth||token.type!=='inline'||tokens[i-1]?.type!=='paragraph_open')continue;
      // A previously flattened/malformed table may be parsed as a paragraph.
      if(/^\s*\|/.test(token.content))continue;
      const text=(token.children||[]).map(child=>['text','code_inline'].includes(child.type)?child.content:['softbreak','hardbreak'].includes(child.type)?' ':'').join('').replace(/\s+/g,' ').trim();
      if(text)paragraphs.push(text);
      if(paragraphs.length===2)break;
    }
    const text=paragraphs.join(' ');
    if(text)return text.length>190?text.slice(0,189)+'…':text;
  }
  return '';
}
