export const CONTROL_BODY_LIMIT=1024*1024;
export const NATIVE_MODEL_BODY_LIMIT=8*1024*1024;

export async function readJsonBody(req,limit=CONTROL_BODY_LIMIT){
 const tooLarge=bytes=>Object.assign(Error('Request too large'),{status:413,bytes,limit});
 const declared=Number(req.headers['content-length']);
 if(Number.isSafeInteger(declared)&&declared>limit)throw tooLarge(declared);
 let bytes=0;
 const chunks=[];
 // Keep the HTTP connection alive long enough to return a structured 413.
 for await(const chunk of req.iterator({destroyOnReturn:false})){
  bytes+=chunk.length;
  if(bytes>limit)throw tooLarge(bytes);
  chunks.push(chunk);
 }
 try{return JSON.parse(Buffer.concat(chunks).toString()||'{}');}
 catch{throw Object.assign(Error('Invalid JSON'),{status:400});}
}
