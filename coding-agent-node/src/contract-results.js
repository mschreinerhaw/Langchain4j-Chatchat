export function primaryJsonlResult(files) {
 if(!Array.isArray(files)||!files.length)throw new TypeError('Result files are required');
 if(files.includes('rows.jsonl'))return 'rows.jsonl';
 const primary=files.filter(f=>typeof f==='string'&&/^[A-Za-z0-9_-]+\.jsonl$/.test(f));
 if(primary.length!==1)throw new TypeError('A unique primary JSONL result is required');
 return primary[0];
}
export function requirePythonResults(files,analysis) {
 const primary=primaryJsonlResult(files);
 if(!analysis&&files.length!==1)throw new TypeError('Legacy Python result profile supports one file');
 if(analysis&&(files.length>9||files.some(f=>typeof f!=='string'||!/^[A-Za-z0-9_-]+\.(md|png|xlsx|json|jsonl|py)$/.test(f))))
  throw new TypeError('Analysis requires safe supported result basenames');
 return primary;
}
