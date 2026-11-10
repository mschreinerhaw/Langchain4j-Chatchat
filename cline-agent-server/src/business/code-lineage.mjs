// Describe the actual native workspace delta for Runtime's existing CodeHistory;
// never generate a repair or change the Agent's program.
function deltas(before,after,offset=0) {
 let start=0,end=0;
 while(start<before.length&&start<after.length&&before[start]===after[start])start++;
 if(start===before.length&&start===after.length)return [];
 while(end<before.length-start&&end<after.length-start&&before[before.length-1-end]===after[after.length-1-end])end++;
 const old=before.slice(start,before.length-end),next=after.slice(start,after.length-end);
 if(old.length<=16000&&next.length<=16000)return [{start:offset+start,end:offset+before.length-end,text:next}];
 // Independent native edits can surround a large unchanged body. Keep that body
 // out of the patch instead of treating the entire span as one replacement.
 const candidates=[];let position=0;
 for(const line of old.split(/(?<=\n)/)) {
  if(line.trim()&&old.indexOf(line)===old.lastIndexOf(line)) {
   const at=next.indexOf(line);
   if(at>=0&&at===next.lastIndexOf(line))candidates.push({line,position,at});
  }
  position+=line.length;
 }
 candidates.sort((a,b)=>Math.abs(a.position-old.length/2)-Math.abs(b.position-old.length/2));
 const anchor=candidates[0];
 if(!anchor)return [{start:offset+start,end:offset+before.length-end,text:next}];
 return [...deltas(old.slice(0,anchor.position),next.slice(0,anchor.at),offset+start),
  ...deltas(old.slice(anchor.position+anchor.line.length),next.slice(anchor.at+anchor.line.length),offset+start+anchor.position+anchor.line.length)];
}
export function nativeProposal(history,source) {
 if(!history?.versions.length)return {proposal:{source},unchanged:false};
 const before=history.currentSource,parent=history.versions.at(-1),repair={parentCodeVersion:parent.version,baseCodeSha256:parent.codeHash,edits:[]};
 if(before===source)return {proposal:{repair},unchanged:true};
 let current=before;
 const reject=()=>{
  const error=new TypeError('REPAIR_SEGMENT_LIMIT: Native changes cannot be represented by 1..32 unique exact edits of at most 16000 UTF-16 characters. No version or execution was accepted. Read the current submitted source with code_read, retain that version, and make a smaller statement/function change rather than rewriting unrelated code.');
  error.code='REPAIR_SEGMENT_LIMIT';throw error;
 };
 const apply=(start,end,text)=>{
  // Expand only with unchanged context. Each anchor is checked against the
  // intermediate source that Runtime will actually replay, never the base alone.
  while(!current.slice(start,end)||current.indexOf(current.slice(start,end))!==current.lastIndexOf(current.slice(start,end))){
   if(start>0){start--;text=current[start]+text;}
   else if(end<current.length){text+=current[end];end++;}
   else reject();
   if(end-start>16000||text.length>16000)reject();
  }
  if(end-start>16000||text.length>16000||repair.edits.length>=32)reject();
  repair.edits.push({oldText:current.slice(start,end),newText:text});
  current=current.slice(0,start)+text+current.slice(end);
 };
 const cut=(text,limit)=>{
  let n=Math.min(text.length,limit);
  if(n<text.length&&/[\uD800-\uDBFF]/.test(text[n-1])&&/[\uDC00-\uDFFF]/.test(text[n]))n--;
  return n;
 };
 for(const delta of deltas(before,source).reverse()) {
  const {start,end,text}=delta;
  if(end-start<=8000&&text.length<=8000){apply(start,end,text);continue;}
  // Delete the changed tail first, keeping a real source anchor for replacement.
  // Then insert the Agent's exact bytes in bounded chunks. This transports the
  // existing workspace delta; it never synthesizes or repairs Python code.
  let remaining=end-start;
  while(remaining>8000){
   let at=start+remaining-8000;
   if(/[\uDC00-\uDFFF]/.test(current[at])&&/[\uD800-\uDBFF]/.test(current[at-1]))at--;
   apply(at,start+remaining,'');remaining=at-start;
  }
  let consumed=cut(text,8000);
  apply(start,start+remaining,text.slice(0,consumed));
  while(consumed<text.length){
   const length=cut(text.slice(consumed),8000);
   apply(start+consumed,start+consumed,text.slice(consumed,consumed+length));consumed+=length;
  }
 }
 if(current!==source)throw Error('Native source delta reconstruction mismatch');
 return {proposal:{repair},unchanged:false};
}
