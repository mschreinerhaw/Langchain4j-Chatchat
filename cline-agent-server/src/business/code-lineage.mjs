// Describe the actual native workspace delta for Runtime's existing CodeHistory;
// never generate a repair or change the Agent's program.
export function nativeProposal(history,source) {
 if(!history?.versions.length)return {proposal:{source},unchanged:false};
 const before=history.currentSource,parent=history.versions.at(-1),repair={parentCodeVersion:parent.version,baseCodeSha256:parent.codeHash,edits:[]};
 if(before===source)return {proposal:{repair},unchanged:true};
 let start=0,end=0;
 while(start<before.length&&start<source.length&&before[start]===source[start])start++;
 while(end<before.length-start&&end<source.length-start&&before[before.length-1-end]===source[source.length-1-end])end++;
 for(;;){
  const oldText=before.slice(start,before.length-end);
  if(oldText&&before.indexOf(oldText)===before.lastIndexOf(oldText))break;
  if(start>0)start--;else if(end>0)end--;else throw Error('Cannot bind native source delta');
 }
 repair.edits.push({oldText:before.slice(start,before.length-end),newText:source.slice(start,source.length-end)});
 return {proposal:{repair},unchanged:false};
}
