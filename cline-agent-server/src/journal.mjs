import {openSync,writeSync,fsyncSync,closeSync,readFileSync,existsSync,mkdirSync} from 'node:fs';
import {dirname} from 'node:path';
export class Journal {
 constructor(file){mkdirSync(dirname(file),{recursive:true});this.file=file;this.events=existsSync(file)?readFileSync(file,'utf8').split('\n').filter(Boolean).map(JSON.parse):[];}
 append(fields){const event={...fields,eventId:String(this.events.length+1),timestamp:new Date().toISOString()};const fd=openSync(this.file,'a',0o600);try{writeSync(fd,JSON.stringify(event)+'\n');fsyncSync(fd);}finally{closeSync(fd);}this.events.push(event);return event;}
 after(id){if(!/^\d+$/.test(String(id)))throw Error('Invalid event cursor');return this.events.filter(e=>Number(e.eventId)>Number(id));}
}
