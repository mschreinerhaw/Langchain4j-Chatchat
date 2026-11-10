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
 for(const delta of deltas(before,source).reverse()) {
  let {start,end,text}=delta;
  for(;;){
   const oldText=current.slice(start,end);
   if(oldText&&current.indexOf(oldText)===current.lastIndexOf(oldText))break;
   if(start>0){start--;text=current[start]+text;}
   else if(end<current.length){text+=current[end];end++;}
   else throw Error('Cannot bind native source delta');
  }
  repair.edits.push({oldText:current.slice(start,end),newText:text});
  current=current.slice(0,start)+text+current.slice(end);
 }
 if(current!==source)throw Error('Native source delta reconstruction mismatch');
 return {proposal:{repair},unchanged:false};
}
